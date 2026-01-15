package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class FurnaceHeatBoostUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.furnace_heat_boost");

    private static final long HEAT_CACHE_MS = 1500L;

    private static final Map<Long, HeatCache> HEAT_CACHE = new HashMap<>();

    private record HeatCache(long untilMs, int d) {}

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().industrial().furnaceHeat();
        return cfg.enabled() && cfg.maxPct() > 0.0 && cfg.radius() > 0;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFurnaceStart(FurnaceStartSmeltEvent e) {
        var cfg = C().industrial().furnaceHeat();

        Block block = e.getBlock();
        Location loc = block.getLocation();

        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/FurnaceHeat] country=" + country
                    + " lvl=" + countryMaxLevel(country, cfg.permBase(), 1)
                    + " permBase=" + cfg.permBase());
        }

        final int R = Math.max(1, cfg.radius());
        int d = nearestHeatDistance(block, R);
        double pct = lerpHeatBoostPercent(d, R, cfg.maxPct());

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/FurnaceHeat] R=" + R + " d=" + d
                    + " maxPct=" + cfg.maxPct()
                    + " pct=" + String.format(java.util.Locale.ROOT, "%.2f", pct));
        }

        if (pct <= 0.0) return;

        int base = e.getTotalCookTime();
        if (base <= 1) return;

        int reduced = Math.max(1, (int) Math.round(base * (1.0 - pct / 100.0)));
        if (reduced >= base) return;

        e.setTotalCookTime(reduced);

        if (cfg.sfx()) {
            Location fx = loc.clone().add(0.5, 1.0, 0.5);
            block.getWorld().spawnParticle(Particle.SMALL_FLAME, fx, 3, 0.08, 0.08, 0.08, 0.0);
            block.getWorld().playSound(fx, Sound.BLOCK_LAVA_POP, 0.15f, 1.6f);
        }

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/FurnaceHeat] boost "
                    + String.format(java.util.Locale.ROOT, "%.1f", pct) + "% (d=" + d + ", R=" + R + ") at " + loc
                    + " country=" + country + " base=" + base + " -> " + reduced);
        }
    }

    private static int nearestHeatDistance(Block origin, int r) {
        if (r <= 0) return Integer.MAX_VALUE;

        final var w = origin.getWorld();

        long now = System.currentTimeMillis();
        long key = heatCacheKey(w.getUID(), origin.getX(), origin.getY(), origin.getZ());

        HeatCache cached = HEAT_CACHE.get(key);
        if (cached != null && cached.untilMs() >= now) {
            return cached.d();
        }

        final int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();

        // Chebyshev-distance "radius": d = max(|dx|,|dy|,|dz|)
        // Диагонали считаются как d=1 (например (1,1,0) => 1).
        for (int d = 1; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != d) continue;

                        Material t = w.getBlockAt(ox + dx, oy + dy, oz + dz).getType();
                        if (t == Material.LAVA || t == Material.MAGMA_BLOCK) {
                            HEAT_CACHE.put(key, new HeatCache(now + HEAT_CACHE_MS, d));
                            return d;
                        }
                    }
                }
            }
        }

        int res = Integer.MAX_VALUE;
        HEAT_CACHE.put(key, new HeatCache(now + HEAT_CACHE_MS, res));
        return res;
    }

    private static long heatCacheKey(UUID world, int x, int y, int z) {
        long h = world.getMostSignificantBits() ^ world.getLeastSignificantBits();
        h ^= (long) x * 73428767L;
        h ^= (long) y * 912367L;
        h ^= (long) z * 19349663L;
        return h;
    }

    private static double lerpHeatBoostPercent(int d, int radius, double maxPct) {
        if (d > radius) return 0.0;
        if (d <= 1) return maxPct;

        double t = (double) (radius - d) / (double) (Math.max(1, radius - 1));
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return t * maxPct;
    }
}
