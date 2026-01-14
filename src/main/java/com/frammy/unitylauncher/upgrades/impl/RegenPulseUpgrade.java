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
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class RegenPulseUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.regen_pulse");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.RegenPulseCfg cfg = ctx.config().hospital().regenPulse();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().hospital().regenPulse();

        long period = Math.max(20L, cfg.periodTicks());
        int dur = Math.max(20, cfg.durationTicks());
        int amp = Math.max(0, cfg.amplifier());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!UpgradeCondition.isInsideZoneTypeRaw(p.getLocation(), ZoneType.HOSPITAL)) continue;

                String country = UpgradeCondition.locationCountryOwner(p.getLocation());
                if (country == null || country.isBlank()) continue;

                if (countryMaxLevel(country, cfg.permBase(), 1) < 1) continue;

                UpgradeCondition.applyPotionSmart(
                        p,
                        PotionEffectType.REGENERATION,
                        dur,
                        amp,
                        true,
                        false,
                        true
                );

            }
        }, period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Hospital/RegenPulse] started period=" + period + " dur=" + dur + " amp=" + amp);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
