package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.HospitalCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class SafeZoneUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.safe_zone");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.SafeZoneCfg cfg = ctx.config().hospital().safeZone();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        if (!UpgradeCondition.isInsideZoneTypeRaw(p.getLocation(), ZoneType.HOSPITAL)) return;

        String country = UpgradeCondition.locationCountryOwner(p.getLocation());
        if (country == null || country.isBlank()) return;

        var cfg = C().hospital().safeZone();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        double k = cfg.damageMultiplier();
        if (k <= 0.0 || k >= 1.0) return;

        e.setDamage(e.getDamage() * k);
    }
}
