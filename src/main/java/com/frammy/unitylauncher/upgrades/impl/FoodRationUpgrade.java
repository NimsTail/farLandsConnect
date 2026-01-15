package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class FoodRationUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("country.food_ration");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().country().foodRation();
        return cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().foodRation();

        long period = Math.max(20L, cfg.periodTicks());

        tasks.add(new BukkitRunnable() {
            @Override public void run() {
                runFoodRationTick();
            }
        }.runTaskTimer(plugin(), period, period));

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/FoodRation] task started period=" + period);
    }

    private void runFoodRationTick() {
        var cfg = C().country().foodRation();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;

            Location loc = p.getLocation();

            String pc = UpgradeCondition.playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) continue;

            String owner = UpgradeCondition.locationCountryOwner(loc);
            if (owner == null || owner.isBlank()) continue;

            if (!pc.equalsIgnoreCase(owner)) continue;

            if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) continue;

            int food = p.getFoodLevel();
            int minFood = (int) Math.max(0, cfg.minFoodLevel());
            if (food > minFood) continue;

            int addFood = Math.max(0, cfg.addFoodAmount());
            float addSat = Math.max(0f, cfg.addSaturation());

            int newFood = Math.min(20, food + addFood);
            p.setFoodLevel(newFood);
            p.setSaturation(Math.min(20f, p.getSaturation() + addSat));

            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/FoodRation] +" + addFood + " food (+" + addSat + " sat) for "
                        + p.getName() + " " + food + " -> " + newFood + " owner=" + owner);

            }
        }
    }
}
