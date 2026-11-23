package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.HospitalCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class DietUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.diet");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.DietCfg cfg = ctx.config().hospital().diet();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();

        if (!UpgradeCondition.isInsideZoneTypeRaw(p.getLocation(), ZoneType.HOSPITAL)) return;

        String country = UpgradeCondition.locationCountryOwner(p.getLocation());
        if (country == null || country.isBlank()) return;

        var cfg = C().hospital().diet();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        float bonus = (float) cfg.saturationBonus();
        if (bonus <= 0f) return;

        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            if (!p.isOnline()) return;

            float sat = p.getSaturation();
            int food = p.getFoodLevel();
            p.setSaturation(Math.min(food, sat + bonus));

            if (C().core().debug()) {
                plugin().getLogger().info("[Hospital/Diet] " + p.getName() + " +" + bonus + " saturation");
            }
        }, 1L);
    }
}
