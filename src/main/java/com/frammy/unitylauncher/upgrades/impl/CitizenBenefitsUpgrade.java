package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * "Льготы внутри страны" (4_benefitsWithinTheCountry, empty.benefitsWithinTheCountry в старом каталоге).
 * Общее гражданское благо: пока гражданин находится на территории своей страны, ему
 * периодически немного восполняется сытость (страна "заботится" о жителях).
 */
public final class CitizenBenefitsUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("country.citizen_benefits");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().country().citizenBenefits();
        return cfg.enabled() && cfg.saturationAmount() > 0f;
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().citizenBenefits();
        long period = Math.max(20L, cfg.periodTicks());

        tasks.add(new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin(), period, period));

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/CitizenBenefits] task started period=" + period);
    }

    private void tick() {
        var cfg = C().country().citizenBenefits();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (p.getFoodLevel() >= 20) continue;

            String pc = UpgradeCondition.playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) continue;
            String owner = UpgradeCondition.locationCountryOwner(p.getLocation());
            if (owner == null || !pc.equalsIgnoreCase(owner)) continue;

            if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) continue;

            p.setSaturation(Math.min(20f, p.getSaturation() + cfg.saturationAmount()));

            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/CitizenBenefits] +" + cfg.saturationAmount()
                        + " saturation for " + p.getName() + " owner=" + owner);
            }
        }
    }
}
