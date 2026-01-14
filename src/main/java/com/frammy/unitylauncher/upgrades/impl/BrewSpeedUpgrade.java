package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class BrewSpeedUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.brew_speed");
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().industrial().brewSpeed();
        return cfg.enabled() && cfg.speedPercent() > 0.0;
    }

    private final Map<Block, Double> brewAccel = new ConcurrentHashMap<>();

    @Override public UpgradeKey key() { return KEY; }
    @Override public Listener listener() { return this; }

    @Override
    protected void onEnable() {
        tasks.add(org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin(), this::tick, 1L, 1L));
    }

    @Override
    protected void onDisable() {
        brewAccel.clear();
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.BREWING) return;
        var h = e.getInventory().getHolder();
        if (!(h instanceof BrewingStand bs)) return;
        brewAccel.putIfAbsent(bs.getBlock(), 0.0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBrew(BrewEvent e) {
        brewAccel.putIfAbsent(e.getBlock(), 0.0);
    }

    private void tick() {
        var cfg = C().industrial().brewSpeed();
        if (cfg.speedPercent() <= 0.0) return;

        final double s = cfg.speedPercent() / 100.0;
        final double extraPerTick = (s >= 0.999) ? 1000.0 : (s / Math.max(1e-6, (1.0 - s)));

        Iterator<Map.Entry<Block, Double>> it = brewAccel.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Double> en = it.next();
            Block b = en.getKey();

            if (!b.getChunk().isLoaded()) { it.remove(); continue; }

            BlockState st = b.getState();
            if (!(st instanceof BrewingStand bs)) { it.remove(); continue; }

            Location loc = b.getLocation();

            String country = UpgradeCondition.locationCountryOwner(loc);
            if (country == null || country.isBlank()) { it.remove(); continue; }

            if (countryMaxLevel(country, cfg.permBase(), 1) < 1) { it.remove(); continue; }

            int time = bs.getBrewingTime();
            if (time <= 1) continue;

            double acc = en.getValue() + extraPerTick;
            int extra = (int) Math.floor(acc);
            if (extra > 0) {
                acc -= extra;
                int newTime = Math.max(1, time - extra);
                bs.setBrewingTime(newTime);
                bs.update(false, false);
                en.setValue(acc);
            } else {
                en.setValue(acc);
            }
        }
    }
}
