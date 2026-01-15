package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class EcoFuelUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.ecofuel_bamboo");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().industrial().ecoFuel();
        return cfg.enabled() && cfg.bambooMultiplier() > 1.0;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFurnaceBurn(FurnaceBurnEvent e) {
        var cfg = C().industrial().ecoFuel();

        Block block = e.getBlock();
        Location loc = block.getLocation();

        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        var fuel = e.getFuel();
        if (fuel == null) return;

        double mult;
        Material type = fuel.getType();

        if (type == Material.BAMBOO) {
            mult = cfg.bambooMultiplier();
        } else if (type == Material.BAMBOO_BLOCK) {
            // если добавишь отдельный множитель в конфиг — замени на cfg.bambooBlockMultiplier()
            mult = cfg.bambooMultiplier();
        } else {
            return;
        }

        if (mult <= 1.0) return;

        int base = e.getBurnTime();
        if (base <= 0) return;

        int boosted = (int) Math.round(base * mult);

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/EcoFuel] country=" + country
                    + " lvl=" + countryMaxLevel(country, cfg.permBase(), 1)
                    + " permBase=" + cfg.permBase()
                    + " fuel=" + type
                    + " base=" + base
                    + " mult=" + mult
                    + " boosted=" + boosted);
        }

        if (boosted <= base) return;

        e.setBurnTime(boosted);

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/EcoFuel] boosted fuel burn at " + loc
                    + " from " + base + " to " + boosted
                    + " (fuel=" + type + " mult=" + mult + ") country=" + country);
        }

    }
}
