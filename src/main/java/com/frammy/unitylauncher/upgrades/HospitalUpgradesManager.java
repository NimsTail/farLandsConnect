package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.*;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public class HospitalUpgradesManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final UpgradesConfig config;

    private BukkitTask regenTask;

    // ===== Sanitary index =====
    private final Map<UUID, Map<Long, List<HospitalCenter>>> sanitaryByWorldChunk = new ConcurrentHashMap<>();
    private volatile int sanitaryChunkRadius = 0; // сколько чанков вокруг проверять

    private record HospitalCenter(int x, int z, String countryCanon) {
    }

    // Вызывать при старте и когда зоны меняются (создали/удалили/переместили).
    public void rebuildSanitaryIndex() {
        sanitaryByWorldChunk.clear();

        int r = Math.max(0, config.hospitalSanitaryRadius);
        sanitaryChunkRadius = (r + 15) >> 4; // ceil(radius/16)

        for (ZoneInfo z : zoneManager.getAllZonesSnapshot()) { // <-- твой метод
            if (z.getType() != ZoneType.HOSPITAL) continue;

            String countryCanon = zoneManager.getCountryCanonicalOfZone(z);
            if (countryCanon == null || countryCanon.isBlank()) continue;

            World w = z.getWorld(); // <-- твой метод
            if (w == null) continue;

            Location center = z.getCenter(); // <-- твой метод
            if (center == null || center.getWorld() == null) continue;

            int cx = center.getBlockX();
            int cz = center.getBlockZ();

            int baseChunkX = cx >> 4;
            int baseChunkZ = cz >> 4;

            int cr = sanitaryChunkRadius;

            Map<Long, List<HospitalCenter>> byChunk =
                    sanitaryByWorldChunk.computeIfAbsent(w.getUID(), k -> new ConcurrentHashMap<>());

            HospitalCenter hc = new HospitalCenter(cx, cz, countryCanon);

            for (int dx = -cr; dx <= cr; dx++) {
                for (int dz = -cr; dz <= cr; dz++) {
                    long key = chunkKey(baseChunkX + dx, baseChunkZ + dz);
                    byChunk.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(hc);
                }
            }
        }
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    // ===== Call from your spawn listener =====
    public boolean shouldCancelSpawnBySanitaryZone(CreatureSpawnEvent e) {
        if (config.hospitalSanitaryRadius <= 0) return false;
        if (config.hospitalSanitarySpawnMultiplier >= 1.0) return false;

        // только натуральные/рейдовые/подкрепления? оставь как было у тебя
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return false;
        }

        Location loc = e.getLocation();
        World w = loc.getWorld();
        if (w == null) return false;

        Map<Long, List<HospitalCenter>> byChunk = sanitaryByWorldChunk.get(w.getUID());
        if (byChunk == null) return false;

        Chunk c = loc.getChunk();
        long key = chunkKey(c.getX(), c.getZ());
        List<HospitalCenter> candidates = byChunk.get(key);
        if (candidates == null || candidates.isEmpty()) return false;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int r = config.hospitalSanitaryRadius;
        int r2 = r * r;

        for (HospitalCenter hc : candidates) {
            int dx = x - hc.x;
            int dz = z - hc.z;
            if (dx * dx + dz * dz <= r2) {
                // можно дополнительно проверять апгрейд страны владельца госпиталя:
                if (countryMaxLevel(hc.countryCanon, config.hospitalSanitaryPerm, 1) >= 1) {
                    // шанс отмены: 1 - multiplier
                    double cancelChance = 1.0 - config.hospitalSanitarySpawnMultiplier;
                    if (Math.random() < cancelChance) return true;
                }
            }
        }
        return false;
    }

    // BloodGift: храним expiry и флаг "бонус уже выдан"
    private static final class BloodGiftState {
        long expiryMs;
        boolean applied;
        BloodGiftState(long expiryMs, boolean applied) {
            this.expiryMs = expiryMs;
            this.applied = applied;
        }
    }
    private final Map<UUID, BloodGiftState> bloodGift = new ConcurrentHashMap<>();

    public HospitalUpgradesManager(UnityLauncher plugin, ZoneManager zoneManager, UpgradesConfig config) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.config = config;
    }

    public void start() {
        rebuildSanitaryIndex();
        if (regenTask != null) regenTask.cancel();

        long period = Math.max(20L, config.hospitalRegenPulsePeriodTicks);
        regenTask = new BukkitRunnable() {
            @Override public void run() {
                processRegenPulse();
                cleanupExpiredBloodGifts();
            }
        }.runTaskTimer(plugin, period, period);

        if (config.debug) plugin.getLogger().info("[HospitalUpgrades] Started (period=" + period + ")");
    }

    public void stop() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
        bloodGift.clear();
    }

    // =====================================================================
    //  1) Psych Support — Luck I after death
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player player = e.getPlayer();
        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
        if (playerCountry == null) return;

        if (countryMaxLevel(playerCountry, config.hospitalPsychSupportPerm, 1) < 1) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.LUCK,
                    Math.max(20, config.hospitalPsychSupportLuckDurationTicks),
                    0,
                    true,
                    false,
                    true
            ));

            if (config.debug) {
                plugin.getLogger().info("[HospitalUpgrades] Psych Support: " + player.getName() + " got Luck");
            }
        }, 20L);
    }

    // =====================================================================
    //  2) Diet — saturation bonus in hospital zone
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerEat(PlayerItemConsumeEvent e) {
        Player player = e.getPlayer();

        ZoneInfo zone = zoneManager.getZoneAt(player.getLocation());
        if (zone == null || zone.getType() != ZoneType.HOSPITAL) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.hospitalDietPerm, 1) < 1) return;

        final float bonus = (float) config.hospitalDietSaturationBonus;
        if (bonus <= 0f) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            float currentSat = player.getSaturation();
            float currentFood = player.getFoodLevel();
            player.setSaturation(Math.min(currentFood, currentSat + bonus));

            if (config.debug) {
                plugin.getLogger().info("[HospitalUpgrades] Diet: " + player.getName() + " +" + bonus + " saturation");
            }
        }, 1L);
    }

    // =====================================================================
    //  3) Regen Pulse — periodic regeneration in hospital zone
    // =====================================================================
    private void processRegenPulse() {
        int dur = Math.max(20, config.hospitalRegenPulseDurationTicks);
        int amp = Math.max(0, config.hospitalRegenPulseAmplifier);

        for (Player player : Bukkit.getOnlinePlayers()) {
            ZoneInfo zone = zoneManager.getZoneAt(player.getLocation());
            if (zone == null || zone.getType() != ZoneType.HOSPITAL) continue;

            String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
            if (zoneCountry == null) continue;

            if (countryMaxLevel(zoneCountry, config.hospitalRegenPulsePerm, 1) < 1) continue;

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION,
                    dur,
                    amp,
                    true,
                    false,
                    true
            ));
        }
    }

    // =====================================================================
    //  4) Sanitary Zone — reduce hostile spawns near hospitals
    //     config.hospitalSanitarySpawnMultiplier: 0..1 (e.g. 0.5 = half spawns)
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMobSpawn(CreatureSpawnEvent e) {
        // фильтры можно оставить здесь, чтобы shouldCancel... был проще и дешевле
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL &&
                e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        if (!(e.getEntity() instanceof Monster)) return;

        if (shouldCancelSpawnBySanitaryZone(e)) {
            e.setCancelled(true);
        }
    }

    // =====================================================================
    //  5) Blood Gift — temporary +maxHP after sleeping in hospital
    //     FIXED: idempotent (no infinite stacking)
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerLeaveBed(PlayerBedLeaveEvent e) {
        Player player = e.getPlayer();
        Location bedLoc = e.getBed().getLocation();

        ZoneInfo zone = zoneManager.getZoneAt(bedLoc);
        if (zone == null || zone.getType() != ZoneType.HOSPITAL) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.hospitalBloodGiftPerm, 1) < 1) return;

        applyOrExtendBloodGift(player);
    }

    private void applyOrExtendBloodGift(Player player) {
        UUID uuid = player.getUniqueId();

        long addMs = Math.max(1, config.hospitalBloodGiftDurationMinutes) * 60_000L;
        long now = System.currentTimeMillis();
        long newExpiry = now + addMs;

        BloodGiftState st = bloodGift.get(uuid);
        if (st == null) {
            st = new BloodGiftState(newExpiry, false);
            bloodGift.put(uuid, st);
        } else {
            st.expiryMs = Math.max(st.expiryMs, newExpiry);
        }

        // Сколько осталось действовать (в тиках)
        long remainingMs = Math.max(0L, st.expiryMs - now);
        int remainingTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(20L, remainingMs / 50L));

        // 1) Absorption (сердечки-щит)
        if (config.hospitalBloodGiftAbsorptionEnabled) {
            int amp = Math.max(0, config.hospitalBloodGiftAbsorptionAmplifier);
            // защита от читерства: максимум 2 сердца (= amplifier 0)
            if (amp > 0) amp = 0;

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.ABSORPTION,
                    remainingTicks,
                    amp,
                    true,   // ambient
                    false,  // particles
                    true    // icon
            ));
        }

        // 2) Опционально лёгкий regen, чтобы было “лечебно”, а не только “щит”
        if (config.hospitalBloodGiftRegenEnabled) {
            int regenTicks = Math.max(20, config.hospitalBloodGiftRegenTicks);
            int regenAmp = Math.max(0, config.hospitalBloodGiftRegenAmplifier);

            // Regen даём коротко, не на всё время — иначе будет слишком жирно
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION,
                    regenTicks,
                    regenAmp,
                    true,
                    false,
                    true
            ));
        }

        // Сообщение — только один раз за активный период
        if (!st.applied) {
            st.applied = true;

            // сколько сердец даёт absorption (amp 0 = 2 сердца)
            double hearts = config.hospitalBloodGiftAbsorptionEnabled ? 2.0 : 0.0;

            player.sendMessage(ChatColor.GREEN + "✚ Дар крови: "
                    + (hearts > 0 ? ("+" + hearts + " ♥ ") : "")
                    + "на " + config.hospitalBloodGiftDurationMinutes + " минут");
        } else if (config.debug) {
            plugin.getLogger().info("[HospitalUpgrades] Blood Gift extended for " + player.getName());
        }
    }

    private void cleanupExpiredBloodGifts() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, BloodGiftState>> it = bloodGift.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<UUID, BloodGiftState> e = it.next();
            BloodGiftState st = e.getValue();
            if (st.expiryMs > now) continue;

            UUID uuid = e.getKey();
            it.remove();

            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            // Снимаем эффект сразу (чтобы не висел лишние секунды из-за тиков)
            if (config.hospitalBloodGiftAbsorptionEnabled) {
                player.removePotionEffect(PotionEffectType.ABSORPTION);
            }

            player.sendMessage(ChatColor.YELLOW + "Эффект 'Дар крови' закончился");
        }
    }

    // =====================================================================
    //  6) Safe Zone — damage reduction inside hospital zone
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamageInHospital(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        ZoneInfo z = zoneManager.getZoneAt(p.getLocation());
        if (z == null || z.getType() != ZoneType.HOSPITAL) return;

        String countryCanon = zoneManager.getCountryCanonicalOfZone(z);
        if (countryCanon == null || countryCanon.isBlank()) return;

        if (countryMaxLevel(countryCanon, config.hospitalSafeZonePerm, 1) < 1) return;

        double k = config.hospitalSafeZoneDamageMultiplier;
        if (k <= 0 || k >= 1) return;

        e.setDamage(e.getDamage() * k);
    }

    // =====================================================================
    //  7) Triage — reduce duration of negative effects for citizens with upgrade
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPotionEffect(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;

        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED &&
                e.getAction() != EntityPotionEffectEvent.Action.CHANGED) {
            return;
        }

        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
        if (playerCountry == null) return;

        if (countryMaxLevel(playerCountry, config.hospitalTriagePerm, 1) < 1) return;

        PotionEffect newEffect = e.getNewEffect();
        if (newEffect == null) return;

        PotionEffectType type = newEffect.getType();
        if (type != PotionEffectType.POISON &&
                type != PotionEffectType.WITHER &&
                type != PotionEffectType.NAUSEA) {
            return;
        }

        int original = newEffect.getDuration();
        double reduction = Math.min(100, Math.max(0, config.hospitalTriageReducePercent)) / 100.0;
        int reduced = (int) (original * (1.0 - reduction));

        if (reduced < original) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> player.addPotionEffect(new PotionEffect(
                    type,
                    Math.max(1, reduced),
                    newEffect.getAmplifier(),
                    newEffect.isAmbient(),
                    newEffect.hasParticles(),
                    newEffect.hasIcon()
            )));
        }
    }
}
