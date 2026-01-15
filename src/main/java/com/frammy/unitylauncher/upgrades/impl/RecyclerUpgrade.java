package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.IndustrialCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class RecyclerUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.recycler");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private volatile Set<Material> inputs = Set.of();
    private volatile Map<Material, Double> extraDrops = Map.of();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        IndustrialCfg.RecyclerCfg cfg = ctx.config().industrial().recycler();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().industrial().recycler();
        inputs = UpgradeCondition.parseMaterialSet(cfg.inputs());
        extraDrops = parseChanceMap(cfg.extraDrops());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreakRecycler(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material type = b.getType();
        if (!inputs.contains(type)) return;

        Location loc = b.getLocation();

        // Только внутри INDUSTRIAL, даже если поверх/внутри есть другие зоны
        if (!UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL)) return;

        // Владелец территории (сквозь дочерние зоны)
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        var cfg = C().industrial().recycler();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        var rnd = ThreadLocalRandom.current();

        for (var en : extraDrops.entrySet()) {
            Material dropMat = en.getKey();
            double prob = en.getValue();
            if (prob <= 0.0) continue;

            if (rnd.nextDouble() < prob) {
                b.getWorld().dropItemNaturally(loc, new ItemStack(dropMat, 1));
                if (C().core().debug()) {
                    plugin().getLogger().info("[Upgrades/Recycler] extra " + dropMat + " at " + loc + " from " + type + " country=" + country);
                }
            }
        }
    }

    private static Map<Material, Double> parseChanceMap(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();

        Map<Material, Double> out = new EnumMap<>(Material.class);

        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;

            // формат: MATERIAL=0.05
            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length() - 1) continue;

            String ms = t.substring(0, eq).trim();
            String ps = t.substring(eq + 1).trim();

            Material m;
            try { m = Material.valueOf(ms); }
            catch (Throwable ignored) { continue; }

            double p;
            try { p = Double.parseDouble(ps); }
            catch (Throwable ignored) { continue; }

            if (Double.isNaN(p) || Double.isInfinite(p)) continue;
            if (p <= 0.0) continue;

            // ограничим 0..1 (как вероятность)
            p = Math.max(0.0, Math.min(1.0, p));
            out.put(m, p);
        }

        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }
}
