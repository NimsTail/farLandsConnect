package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.ParkCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Location;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;

import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class GardenerUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("park.gardener");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        ParkCfg.GardenerCfg cfg = ctx.config().park().gardener();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onGrow(BlockGrowEvent e) {
        Location loc = e.getBlock().getLocation();

        ZoneInfo z = UpgradeCondition.zoneAt(loc);
        if (z == null || z.getType() != ZoneType.PARK) return;

        String country = UpgradeCondition.zoneCountryCanonical(z);
        if (country == null || country.isBlank()) return;

        var cfg = C().park().gardener();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        double chance = cfg.extraGrowChance();
        if (chance <= 0.0) return;
        if (chance < 1.0 && ThreadLocalRandom.current().nextDouble() >= chance) return;

        BlockData data = e.getNewState().getBlockData();
        if (!(data instanceof Ageable age)) return;

        int cur = age.getAge();
        int max = age.getMaximumAge();
        if (cur >= max) return;

        age.setAge(Math.min(max, cur + 1));
        e.getNewState().setBlockData(age);

        if (C().core().debug()) {
            plugin().getLogger().info("[Park/Gardener] bonus grow at " + loc);
        }
    }
}
