package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.ParkCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class PondBedsUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("park.pond_beds");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }

    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        ParkCfg.PondBedsCfg cfg = ctx.config().park().pondBeds();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().park().pondBeds();
        long period = Math.max(20L, cfg.periodTicks());

        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period));
    }

    @Override
    protected void onDisable() {
        cooldown.clear();
    }

    private void tick(ParkCfg.PondBedsCfg cfg) {
        float bonus = (float) cfg.saturationBonus();
        if (bonus <= 0f) return;

        long now = System.currentTimeMillis();
        long cdMs = Math.max(0L, cfg.cooldownMs());

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline() || p.isDead()) continue;

            ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
            if (z == null || z.getType() != ZoneType.PARK) continue;

            String country = UpgradeCondition.zoneCountryCanonical(z);
            if (country == null || country.isBlank()) continue;

            if (countryMaxLevel(country, cfg.permBase(), 1) < 1) continue;

            UUID uuid = p.getUniqueId();
            Long last = cooldown.get(uuid);
            if (last != null && (now - last) < cdMs) continue;

            int food = p.getFoodLevel();
            if (food <= 0) continue;

            float sat = p.getSaturation();
            p.setSaturation(Math.min(food, sat + bonus));
            cooldown.put(uuid, now);

            if (C().core().debug()) {
                plugin().getLogger().info("[Park/PondBeds] +" + bonus + " sat for " + p.getName());
            }
        }
    }
}
