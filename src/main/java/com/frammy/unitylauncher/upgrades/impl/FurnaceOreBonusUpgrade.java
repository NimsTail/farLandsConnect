package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class FurnaceOreBonusUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.furnace_ore_bonus");

    private volatile Set<org.bukkit.Material> cachedOutputs = EnumSet.noneOf(org.bukkit.Material.class);

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().industrial().furnaceOreBonus();
        return cfg.enabled() && cfg.chance() > 0.0;
    }

    @Override
    protected void onEnable() {
        rebuildOutputsCache();
    }

    @Override
    protected void onDisable() {
        cachedOutputs = EnumSet.noneOf(org.bukkit.Material.class);
    }

    private void rebuildOutputsCache() {
        var cfg = C().industrial().furnaceOreBonus();
        EnumSet<org.bukkit.Material> set = EnumSet.noneOf(org.bukkit.Material.class);

        for (String s : cfg.outputs()) {
            if (s == null || s.isBlank()) continue;
            try {
                set.add(org.bukkit.Material.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                if (C().core().debug()) plugin().getLogger().warning("[Upgrades/FurnaceOreBonus] unknown material in outputs: " + s);
            }
        }
        cachedOutputs = set;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFurnaceSmelt(FurnaceSmeltEvent e) {
        var cfg = C().industrial().furnaceOreBonus();

        ItemStack result = e.getResult();
        if (result == null || result.getType().isAir()) return;
        if (!cachedOutputs.contains(result.getType())) return;

        if (ThreadLocalRandom.current().nextDouble() >= cfg.chance()) return;

        Location loc = e.getBlock().getLocation();

        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        ItemStack bonus = result.clone();
        bonus.setAmount(Math.min(result.getAmount() + 1, bonus.getMaxStackSize()));
        e.setResult(bonus);

        if (cfg.sfx()) {
            World w = e.getBlock().getWorld();
            Location fxLoc = loc.clone().add(0.5, 1.0, 0.5);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, fxLoc, 2);
            w.playSound(fxLoc, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.3f, 1.2f);
        }

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/FurnaceOreBonus] +1 " + result.getType() + " at " + loc + " country=" + country);
        }
    }
}
