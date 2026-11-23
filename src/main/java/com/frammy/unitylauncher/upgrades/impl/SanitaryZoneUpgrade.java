package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.HospitalCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class SanitaryZoneUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.sanitary_zone");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private BukkitTask rebuildTask;

    private final Map<UUID, Map<Long, List<Center>>> byWorldChunk = new ConcurrentHashMap<>();
    private volatile int chunkRadius = 0;
    private volatile int radiusBlocks = 0;

    private record Center(int x, int z, String countryCanon) {}

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.SanitaryZoneCfg cfg = ctx.config().hospital().sanitaryZone();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        rebuildIndex();

        var cfg = C().hospital().sanitaryZone();
        long period = Math.max(20L, cfg.rebuildPeriodTicks());
        rebuildTask = plugin().getServer().getScheduler().runTaskTimer(plugin(), this::rebuildIndex, period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Hospital/SanitaryZone] index started rebuildPeriod=" + period);
        }
    }

    @Override
    protected void onDisable() {
        if (rebuildTask != null) {
            rebuildTask.cancel();
            rebuildTask = null;
        }
        byWorldChunk.clear();
    }

    private void rebuildIndex() {
        var cfg = C().hospital().sanitaryZone();

        int r = Math.max(0, cfg.radiusBlocks());
        this.radiusBlocks = r;
        this.chunkRadius = (r + 15) >> 4;

        byWorldChunk.clear();

        for (ZoneInfo z : plugin().getZoneManager().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.HOSPITAL) continue;

            World w = z.getWorld();
            if (w == null) continue;

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            String countryCanon = UpgradeCondition.locationCountryOwner(center);
            if (countryCanon == null || countryCanon.isBlank()) continue;

            int cx = center.getBlockX();
            int cz = center.getBlockZ();

            int baseChunkX = cx >> 4;
            int baseChunkZ = cz >> 4;

            Center c = new Center(cx, cz, countryCanon);
            int cr = this.chunkRadius;

            Map<Long, List<Center>> map = byWorldChunk.computeIfAbsent(w.getUID(), k -> new ConcurrentHashMap<>());
            for (int dx = -cr; dx <= cr; dx++) {
                for (int dz = -cr; dz <= cr; dz++) {
                    long key = chunkKey(baseChunkX + dx, baseChunkZ + dz);
                    map.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                            .add(c);
                }
            }
        }
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        var cfg = C().hospital().sanitaryZone();

        if (radiusBlocks <= 0) return;
        double mult = cfg.spawnMultiplier();
        if (mult >= 1.0) return;

        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        if (!(e.getEntity() instanceof Monster)) return;

        Location loc = e.getLocation();
        World w = loc.getWorld();
        if (w == null) return;

        Map<Long, List<Center>> map = byWorldChunk.get(w.getUID());
        if (map == null) return;

        Chunk ch = loc.getChunk();
        List<Center> candidates = map.get(chunkKey(ch.getX(), ch.getZ()));
        if (candidates == null || candidates.isEmpty()) return;

        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        int r2 = radiusBlocks * radiusBlocks;

        for (Center c : candidates) {
            int dx = x - c.x;
            int dz = z - c.z;
            if (dx * dx + dz * dz > r2) continue;

            if (countryMaxLevel(c.countryCanon, cfg.permBase(), 1) < 1) continue;

            double cancelChance = 1.0 - mult;
            if (ThreadLocalRandom.current().nextDouble() < cancelChance) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
