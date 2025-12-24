package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * Park upgrades:
 * 1) Gardener's Hut — bonus plant growth chance
 * 2) Quiet Guard — reduces loud game-events in park radius (simple: cancel some events)
 * 3) Pond & Flowerbeds — restores saturation over time
 * 4) Quiet Hour — blocks hostile natural spawns inside park
 * 5) Benches — regeneration while "sitting" (sneak) in park
 */
public class ParkUpgradesManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final UpgradesConfig config;

    private BukkitTask periodicTask;

    // cooldown for saturation bonus
    private final Map<UUID, Long> saturationCooldown = new ConcurrentHashMap<>();

    // cooldown for quiet guard spam
    private final Map<UUID, Long> quietGuardCooldown = new ConcurrentHashMap<>();

    public ParkUpgradesManager(UnityLauncher plugin, ZoneManager zoneManager, UpgradesConfig config) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.config = config;
    }

    public void start() {
        if (periodicTask != null) periodicTask.cancel();

        periodicTask = new BukkitRunnable() {
            @Override public void run() {
                processPondAndBedsSaturation();
                processBenchRegeneration();
            }
        }.runTaskTimer(plugin, 100L, 100L);

        if (config.debug) plugin.getLogger().info("[ParkUpgrades] Started");
    }

    public void stop() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        saturationCooldown.clear();
        quietGuardCooldown.clear();
    }

    // =====================================================================
    //  1) Gardener's Hut — bonus growth
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlantGrow(BlockGrowEvent e) {
        Block block = e.getBlock();
        Location loc = block.getLocation();

        ZoneInfo zone = zoneManager.getZoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.PARK) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.parkGardenerPerm, 1) < 1) return;

        double chance = config.parkGrowthChanceBonus;
        if (chance <= 0) return;
        if (Math.random() >= chance) return;

        BlockData data = e.getNewState().getBlockData();
        if (data instanceof Ageable ageable) {
            int currentAge = ageable.getAge();
            int maxAge = ageable.getMaximumAge();
            if (currentAge < maxAge) {
                ageable.setAge(Math.min(maxAge, currentAge + 1));
                e.getNewState().setBlockData(ageable);

                if (config.debug) {
                    plugin.getLogger().info("[ParkUpgrades] Gardener bonus at " + loc);
                }
            }
        }
    }

    // =====================================================================
    //  2) Quiet Guard — cancel some loud game events inside park
    //  NOTE: GenericGameEvent location is non-null in Paper; IDE warning fixed.
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onGameEvent(GenericGameEvent e) {
        Location loc = e.getLocation(); // IDE may warn but keep safe usage anyway

        ZoneInfo zone = zoneManager.getZoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.PARK) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.parkQuietGuardPerm, 1) < 1) return;

        // Instead of a config list of muted events (which you removed),
        // we apply a simple anti-spam throttle: cancel repeated events near players.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(loc.getWorld())) continue;
            if (p.getLocation().distanceSquared(loc) > (config.parkQuietGuardRadius * config.parkQuietGuardRadius)) continue;

            UUID uuid = p.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = quietGuardCooldown.get(uuid);
            if (last != null && (now - last) < 1200) return; // allow some events

            // cancel the event for the park (global cancel)
            e.setCancelled(true);
            quietGuardCooldown.put(uuid, now);

            if (config.debug) {
                plugin.getLogger().info("[ParkUpgrades] Quiet Guard cancelled event: " +
                        e.getEvent().getKey().getKey() + " at " + loc);
            }
            return;
        }
    }

    // =====================================================================
    //  3) Pond & Flowerbeds — saturation bonus over time
    // =====================================================================
    private void processPondAndBedsSaturation() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) continue;

            Location loc = player.getLocation();
            ZoneInfo zone = zoneManager.getZoneAt(loc);
            if (zone == null || zone.getType() != ZoneType.PARK) continue;

            String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
            if (zoneCountry == null) continue;

            if (countryMaxLevel(zoneCountry, config.parkPondBedsPerm, 1) < 1) continue;

            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            Long last = saturationCooldown.get(uuid);
            if (last != null && (now - last) < config.parkSaturationCooldownMs) continue;

            float currentFood = player.getFoodLevel();
            if (currentFood <= 0) continue;

            float currentSat = player.getSaturation();
            float bonus = (float) config.parkSaturationBonus;
            if (bonus <= 0f) continue;

            // saturation can't exceed food level
            player.setSaturation(Math.min(currentFood, currentSat + bonus));
            saturationCooldown.put(uuid, now);

            if (config.debug) {
                plugin.getLogger().info("[ParkUpgrades] Pond sat +" + bonus + " for " + player.getName());
            }
        }
    }

    // =====================================================================
    //  4) Quiet Hour — block natural hostile spawns inside park zone
    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL &&
                e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS &&
                e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.PATROL) {
            return;
        }

        Entity entity = e.getEntity();
        if (!(entity instanceof Monster)) return;

        Location loc = e.getLocation();
        ZoneInfo zone = zoneManager.getZoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.PARK) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.parkQuietHourPerm, 1) < 1) return;

        e.setCancelled(true);

        if (config.debug) {
            plugin.getLogger().info("[ParkUpgrades] Quiet Hour blocked " + entity.getType() + " at " + loc);
        }
    }

    // =====================================================================
    //  5) Benches — Regen while "sitting" (sneaking) in park
    // =====================================================================
    public void processBenchRegeneration() {
        int radius = config.parkBenchRadius; // теперь используется

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isSneaking()) continue;

            ZoneInfo zone = zoneManager.getZoneAt(player.getLocation());
            if (zone == null || zone.getType() != ZoneType.PARK) continue;

            String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
            if (zoneCountry == null) continue;

            if (countryMaxLevel(zoneCountry, config.parkBenchesPerm, 1) < 1) continue;

            if (!isNearBench(player.getLocation(), radius)) continue;

            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.REGENERATION,
                    config.parkBenchRegenSeconds * 20,
                    0,
                    true,
                    false,
                    false
            ));
        }
    }

    private boolean isNearBench(Location loc, int radius) {
        if (radius <= 0) return true; // на всякий: радиус 0 = всегда в зоне

        World w = loc.getWorld();
        if (w == null) return false;

        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();

        int r = Math.min(radius, 16); // защита от безумных значений (чтобы не лагало)
        int r2 = r * r;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r2) continue;

                // чуть-чуть по высоте тоже смотрим
                for (int dy = -1; dy <= 1; dy++) {
                    Material m = w.getBlockAt(cx + dx, cy + dy, cz + dz).getType();
                    if (Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m)) return true;
                }
            }
        }
        return false;
    }

    // =====================================================================
    //  Optional API (keep for integrations, suppress warnings)
    // =====================================================================
    @SuppressWarnings("unused")
    public boolean isParkWithUpgrade(Location location, String upgradePerm) {
        ZoneInfo zone = zoneManager.getZoneAt(location);
        if (zone == null || zone.getType() != ZoneType.PARK) return false;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return false;

        return countryMaxLevel(zoneCountry, upgradePerm, 1) >= 1;
    }

    @SuppressWarnings("unused")
    public List<ZoneInfo> getParksWithUpgrade(String upgradePerm) {
        List<ZoneInfo> parks = new ArrayList<>();
        for (ZoneInfo zone : zoneManager.getZones()) {
            if (zone.getType() != ZoneType.PARK) continue;

            String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
            if (zoneCountry == null) continue;

            if (countryMaxLevel(zoneCountry, upgradePerm, 1) >= 1) parks.add(zone);
        }
        return parks;
    }

}
