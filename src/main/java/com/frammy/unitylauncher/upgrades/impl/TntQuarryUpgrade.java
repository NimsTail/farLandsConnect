package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.CountryCfg;
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
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class TntQuarryUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("tnt.quarry_and_license");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private volatile Map<Material, Double> dupWhitelist = Map.of();
    private volatile Set<Material> licenseOres = Set.of();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        CountryCfg.TntQuarryCfg cfg = ctx.config().country().tntQuarry();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().tntQuarry();
        dupWhitelist = Collections.unmodifiableMap(parseMaterialChanceMap(cfg.dupWhitelist()));
        licenseOres = Collections.unmodifiableSet(UpgradeCondition.parseMaterialSet(cfg.licenseOres()));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) {
        var blocks = e.blockList();
        if (blocks == null || blocks.isEmpty()) return;

        var cfg = C().country().tntQuarry();
        var rnd = ThreadLocalRandom.current();

        // кэш по стране в рамках одного взрыва (пермы/флаги)
        Map<String, Flags> flagsCache = new HashMap<>(8);

        for (Block b : blocks) {
            if (b == null) continue;

            Location bl = b.getLocation();

            // страна именно по блоку (границы/разные зоны)
            String country = UpgradeCondition.locationCountryOwner(bl);
            if (country == null || country.isBlank()) continue;

            Flags f = flagsCache.computeIfAbsent(country, c -> {
                int lvl = countryMaxLevel(c, cfg.permBase(), 2);
                boolean hasQuarry  = lvl >= 1;
                boolean hasLicense = lvl >= 2; // license = 2 уровень
                return new Flags(hasQuarry, hasLicense);
            });

            if (!f.hasQuarry && !f.hasLicense) continue;

            boolean inIndustrial = UpgradeCondition.isInsideZoneTypeRaw(bl, ZoneType.INDUSTRIAL);
            if (!inIndustrial) continue; // теперь и quarry и license работают ТОЛЬКО в industrial

            Material type = b.getType();

            // === INDUSTRIAL TNT-Quarry: дюп whitelist-блоков в INDUSTRIAL ===
            if (f.hasQuarry) {
                Double prob = dupWhitelist.get(type);
                if (prob != null && prob > 0.0 && rnd.nextDouble() <= prob) {
                    for (ItemStack dIt : b.getDrops()) {
                        if (dIt == null || dIt.getType().isAir() || dIt.getAmount() <= 0) continue;
                        bl.getWorld().dropItemNaturally(bl, dIt.clone());
                    }

                    if (C().core().debug()) {
                        plugin().getLogger().info("[Upgrades/TntQuarry] extra drops for " + type + " at " + bl + " country=" + country);
                    }
                }
                continue;
            }

            // === INDUSTRIAL TNT-License (lvl2): доп. дроп руды в INDUSTRIAL ===
            if (licenseOres.contains(type)) {
                Collection<ItemStack> drops = b.getDrops();
                if (drops.isEmpty()) continue;

                for (ItemStack baseDrop : drops) {
                    if (baseDrop == null || baseDrop.getType().isAir() || baseDrop.getAmount() <= 0) continue;

                    int extraMult = 0;
                    for (int i = 0; i < cfg.licenseMaxExtra(); i++) {
                        if (rnd.nextDouble() < cfg.licenseChancePerExtra()) extraMult++;
                    }
                    if (extraMult <= 0) continue;

                    ItemStack extra = baseDrop.clone();
                    int totalExtra = baseDrop.getAmount() * extraMult;
                    extra.setAmount(Math.min(extra.getMaxStackSize(), totalExtra));

                    bl.getWorld().dropItemNaturally(bl, extra);

                    if (C().core().debug()) {
                        plugin().getLogger().info("[Upgrades/TntLicense] extra " + extra.getAmount() + "x " + extra.getType()
                                + " at " + bl + " (mult=" + extraMult + ") country=" + country);
                    }
                }
            }

        }
    }

    private record Flags(boolean hasQuarry, boolean hasLicense) {}

    /**
     * Формат списка:
     *   - "STONE=0.25"
     *   - "DEEPSLATE=0.10"
     * Неверные строки игнорируются.
     */
    private static Map<Material, Double> parseMaterialChanceMap(List<String> lines) {
        if (lines == null || lines.isEmpty()) return Map.of();

        Map<Material, Double> out = new EnumMap<>(Material.class);
        for (String raw : lines) {
            if (raw == null) continue;
            String s = raw.trim();
            if (s.isEmpty()) continue;

            int eq = s.indexOf('=');
            if (eq <= 0 || eq >= s.length() - 1) continue;

            String ms = s.substring(0, eq).trim();
            String ps = s.substring(eq + 1).trim();

            Material m;
            try { m = Material.valueOf(ms); }
            catch (Throwable ignored) { continue; }

            double p;
            try { p = Double.parseDouble(ps); }
            catch (Throwable ignored) { continue; }

            if (p <= 0.0) continue;
            out.put(m, Math.min(1.0, p));
        }
        return out;
    }
}
