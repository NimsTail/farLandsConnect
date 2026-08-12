package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.MilitaryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

// infra/military-diplomacy-design.md §3.3/§14.2/§13 Фаза 4 "Логистика" —
// примета-only в этой версии (зацикленный звук генератора у военного
// объекта). Реальный экономический эффект (снижение стоимости содержания)
// и BREAK_ANCHOR-нейтрализация через сундук-склад — см. MilitaryCfg.LogisticsCfg,
// не реализованы: требуют инфраструктуры, которой пока нет ни у одного
// апгрейда на сайте.
public final class LogisticsUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.logistics");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.LogisticsCfg cfg = ctx.config().military().logistics();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().logistics();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> {
            for (ZoneInfo z : zones().getAllZonesSnapshot()) {
                if (z.getType() != ZoneType.MILITARY) continue;
                String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
                if (canonicalCountry == null) continue;
                if (UpgradeCondition.countryMaxLevel(canonicalCountry, cfg.permBase(), 1) < 1) continue;

                Location center = z.getCenter();
                if (center == null || center.getWorld() == null) continue;
                center.getWorld().playSound(center, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.6f, 0.8f);
            }
        }, period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/Logistics] started period=" + period);
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
