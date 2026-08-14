package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.military.MilitaryAnchorService;
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
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// infra/military-diplomacy-design.md GH#24 идея 3 "Жгучий" — поджигает
// одного врага в радиусе объекта на пару секунд, с кулдауном на цель.
// На максимальном уровне (2) может вместо/вместе с поджогом ударить
// молнией, если цель не под крышей — переиспользует
// MilitaryAnchorService.isExposed (та же "открыт небу" проверка, что и у
// анкера разведки).
public final class ScorchUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.scorch");

    // Фидбек 2026-08-14 — общий узел "усиление" убран, длительность поджога
    // и шанс молнии теперь два независимых параметрических апгрейда (тот же
    // приём, что GH#29 ввёл для Арбалета).
    private static final String DURATION_PERM_BASE = "unity.military.scorch_duration";
    private static final String LIGHTNING_PERM_BASE = "unity.military.scorch_lightning";
    private static final double[] DURATION_MULTIPLIER = {1.0, 1.5, 2.0};
    private static final double[] LIGHTNING_CHANCE = {0.0, 0.15, 0.25}; // черновые числа

    // playerName -> когда последний раз горел от этого эффекта (мс).
    private final Map<String, Long> lastScorchedByPlayer = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.ScorchCfg cfg = ctx.config().military().scorch();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().scorch();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/Scorch] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.ScorchCfg cfg) {
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;
        long cooldownMs = cfg.cooldownTicks() * 50L;
        long now = System.currentTimeMillis();

        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            // GH#24 (фидбек 2026-08-14 п.1/4) — только на объекте, реально вкачанном в SCORCH.
            if (!subtypeService.isActiveAs(z, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.SCORCH)) continue;

            // Фидбек 2026-08-14 — длительность поджога и шанс молнии читаются
            // независимо; isActiveAs выше уже подтверждает базовую покупку
            // типа, ни один из параметров не гейтит само срабатывание.
            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            int durationLevel = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(canonicalCountry, DURATION_PERM_BASE, 2);
            int lightningLevel = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(canonicalCountry, LIGHTNING_PERM_BASE, 2);

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            Player target = null;
            double bestDist = cfg.radius();
            for (Player p : center.getWorld().getPlayers()) {
                String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry != null && playerCountry.equalsIgnoreCase(z.getCountryName())) continue; // не враг
                double d = p.getLocation().distance(center);
                if (d > bestDist) continue;

                Long last = lastScorchedByPlayer.get(p.getName());
                if (last != null && now - last < cooldownMs) continue;

                bestDist = d;
                target = p;
            }
            if (target == null) continue;

            lastScorchedByPlayer.put(target.getName(), now);
            int fireTicks = (int) Math.round(cfg.fireTicks() * DURATION_MULTIPLIER[durationLevel]);
            target.setFireTicks(Math.max(target.getFireTicks(), fireTicks));

            // "не под крышей" = открыт небу — переиспользует то же правило,
            // что и якорь разведки (MilitaryAnchorService.isExposed).
            boolean notUnderRoof = MilitaryAnchorService.isExposed(target.getLocation());
            double lightningChance = LIGHTNING_CHANCE[lightningLevel];
            if (lightningChance > 0 && notUnderRoof && Math.random() < lightningChance) {
                target.getWorld().strikeLightning(target.getLocation());
            }
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastScorchedByPlayer.clear();
    }
}
