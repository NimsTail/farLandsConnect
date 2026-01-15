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
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class QuietHourUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("park.quiet_hour");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        ParkCfg.QuietHourCfg cfg = ctx.config().park().quietHour();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason r = e.getSpawnReason();
        if (r != CreatureSpawnEvent.SpawnReason.NATURAL
                && r != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS
                && r != CreatureSpawnEvent.SpawnReason.PATROL) return;

        if (!(e.getEntity() instanceof Monster)) return;

        Location loc = e.getLocation();
        ZoneInfo z = UpgradeCondition.zoneAt(loc);
        if (z == null || z.getType() != ZoneType.PARK) return;

        String country = UpgradeCondition.zoneCountryCanonical(z);
        if (country == null || country.isBlank()) return;

        var cfg = C().park().quietHour();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        e.setCancelled(true);

        if (C().core().debug()) {
            plugin().getLogger().info("[Park/QuietHour] blocked " + e.getEntityType() + " at " + loc);
        }
    }
}
