package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.MilitaryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 2+4, §14.2/§14.4/§14.5
// "Оборона". Примета (патруль) — Фаза 2. Нейтрализация (PVE_WAVES, §14.4) —
// Фаза 4: 5 волн подряд отбитых без паузы >3 мин репортится на сайт
// (routes/plugin.ts "/plugin/military/neutralize"), который сам решает War
// Score/CONTESTED/HQ-уязвимость (lib/militaryZones.ts) — этот класс только
// считает волны и шлёт репорт, ничего не решает сам.
public final class DefensePatrolUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.defense");
    private static final String META_KEY = "unityMilitaryGuardCountry";

    // §14.5 черновые числа.
    private static final int WAVES_TO_NEUTRALIZE = 5;
    private static final long WAVE_GAP_RESET_MS = 3 * 60 * 1000;

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    // markerId зоны -> живые мобы патруля этой зоны (в памяти — переживать
    // рестарт плагина не обязано, как и остальные in-memory кеши апгрейдов).
    private final Map<String, List<UUID>> guardsByZone = new ConcurrentHashMap<>();
    private final Map<String, Integer> waveStreakByZone = new ConcurrentHashMap<>();
    private final Map<String, Long> lastWaveClearedByZone = new ConcurrentHashMap<>();
    // Последний игрок (страна врага), нанёсший урон патрулю этой зоны — тот,
    // кому в итоге засчитается нейтрализация, если стрик дойдёт до 5.
    private final Map<String, String> lastAttackerCountryByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.DefensePatrolCfg cfg = ctx.config().military().defensePatrol();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().defensePatrol();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/DefensePatrol] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.DefensePatrolCfg cfg) {
        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;

            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            if (canonicalCountry == null) continue;
            if (UpgradeCondition.countryMaxLevel(canonicalCountry, cfg.permBase(), 1) < 1) continue;

            String markerId = z.getMarkerID();
            List<UUID> alive = guardsByZone.computeIfAbsent(markerId, k -> new ArrayList<>());
            boolean hadGuardsBefore = !alive.isEmpty();
            alive.removeIf(id -> {
                Entity e = Bukkit.getEntity(id);
                return e == null || !e.isValid();
            });
            boolean waveFullyCleared = hadGuardsBefore && alive.isEmpty();

            handleWaveOutcome(z, markerId, waveFullyCleared);

            if (alive.size() < cfg.maxAlive()) {
                Location center = z.getCenter();
                if (center != null && center.getWorld() != null) {
                    int toSpawn = Math.min(cfg.mobsPerWave(), cfg.maxAlive() - alive.size());
                    for (int i = 0; i < toSpawn; i++) {
                        LivingEntity mob = (LivingEntity) center.getWorld().spawnEntity(center, patrolTypeFor(center));
                        mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), markerId));
                        mob.setRemoveWhenFarAway(true);
                        alive.add(mob.getUniqueId());
                    }
                }
            }
        }
    }

    /** §14.4/§14.5 — a fully-cleared wave (with no >3min gap since the last one) advances the streak; anything else resets it. */
    private void handleWaveOutcome(ZoneInfo z, String markerId, boolean waveFullyCleared) {
        if (!waveFullyCleared) return; // no wave existed yet, or it wasn't cleared — streak stays as-is either way (next spawn already tops it back up)

        long now = System.currentTimeMillis();
        Long lastClear = lastWaveClearedByZone.get(markerId);
        int streak = (lastClear != null && now - lastClear <= WAVE_GAP_RESET_MS) ? waveStreakByZone.getOrDefault(markerId, 0) + 1 : 1;
        lastWaveClearedByZone.put(markerId, now);
        waveStreakByZone.put(markerId, streak);

        if (streak < WAVES_TO_NEUTRALIZE) return;

        String attackerCountry = lastAttackerCountryByZone.get(markerId);
        waveStreakByZone.put(markerId, 0);
        lastAttackerCountryByZone.remove(markerId);
        if (attackerCountry == null) return; // no credited attacker (e.g. mobs infighting) — no report

        var api = UnityLauncher.getInstance().getFarLandsApi();
        if (api != null) api.reportMilitaryNeutralize(markerId, attackerCountry);
    }

    /** Скелет/зомби по биому — только ванильные мобы, ничего нового (§14.2). */
    private static EntityType patrolTypeFor(Location loc) {
        Biome biome = loc.getBlock().getBiome();
        String name = biome.name();
        if (name.contains("DESERT")) return EntityType.HUSK;
        if (name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE")) return EntityType.STRAY;
        return Math.random() < 0.5 ? EntityType.ZOMBIE : EntityType.SKELETON;
    }

    // Враждебны к чужакам, не к своим (§14.2: "не из страны/союза" — союзники
    // сюда пока не входят, см. CountryRegistryJdbc.getPlayerViewMilitaryPermission).
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        List<org.bukkit.metadata.MetadataValue> meta = e.getEntity().getMetadata(META_KEY);
        if (meta.isEmpty()) return;

        // meta value here is the zone markerId (not the country) — resolve
        // via the zone to compare against the target player's own country.
        String markerId = meta.get(0).asString();
        String guardCountry = zones().getAllZonesSnapshot().stream()
                .filter(z -> markerId.equals(z.getMarkerID()))
                .findFirst()
                .map(UpgradeCondition::zoneCountryCanonical)
                .orElse(null);
        String playerCountry = UpgradeCondition.playerCountryCanonical(p.getName());
        if (guardCountry != null && guardCountry.equals(playerCountry)) {
            e.setCancelled(true);
        }
    }

    /** §14.4 attribution — remembers which enemy country most recently damaged this zone's patrol, credited if the streak completes. */
    @EventHandler(ignoreCancelled = true)
    public void onGuardDamaged(EntityDamageByEntityEvent e) {
        List<org.bukkit.metadata.MetadataValue> meta = e.getEntity().getMetadata(META_KEY);
        if (meta.isEmpty()) return;
        if (!(e.getDamager() instanceof Player p)) return;

        String markerId = meta.get(0).asString();
        String zoneCountryName = zones().getAllZonesSnapshot().stream()
                .filter(z -> markerId.equals(z.getMarkerID()))
                .findFirst()
                .map(ZoneInfo::getCountryName)
                .orElse(null);
        String attackerCountryName = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (attackerCountryName == null || zoneCountryName == null || attackerCountryName.equalsIgnoreCase(zoneCountryName)) return;
        if (!UnityLauncher.getInstance().warStatusCache.isAtWar(attackerCountryName, zoneCountryName)) return;

        lastAttackerCountryByZone.put(markerId, attackerCountryName);
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (List<UUID> alive : guardsByZone.values()) {
            for (UUID id : alive) {
                Entity e = Bukkit.getEntity(id);
                if (e != null && e.isValid()) e.remove();
            }
        }
        guardsByZone.clear();
        waveStreakByZone.clear();
        lastWaveClearedByZone.clear();
        lastAttackerCountryByZone.clear();
    }
}
