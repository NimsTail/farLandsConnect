package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.ColonyCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class OutpostUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("colony.outpost_raid_cull");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        ColonyCfg.OutpostRaidCullCfg cfg = ctx.config().colony().outpostRaidCull();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        // no tasks
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onRaidMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.RAID) return;

        Location loc = e.getLocation();

        // Работает на всей территории страны/колонии и любых зон внутри (deep owner)
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        var cfg = C().colony().outpostRaidCull();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        double pct = Math.max(0.0, Math.min(100.0, cfg.cullPercent()));
        if (ThreadLocalRandom.current().nextDouble(100.0) < pct) {
            e.setCancelled(true);
            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/Outpost] cancelled raid mob spawn at " + loc + " country=" + country);
            }
        }
    }
}
