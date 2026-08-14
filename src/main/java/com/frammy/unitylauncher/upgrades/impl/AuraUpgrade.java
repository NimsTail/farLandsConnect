package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.MilitaryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// infra/military-diplomacy-design.md GH#24 идея 2 "Ореол чужеродности" —
// зеркало AttackSupportUpgrade (тот же per-tick-по-игрокам паттерн), только
// негативный эффект вражескому игроку, стоящему в НАШЕЙ территории (не мы
// в чужой). Длительность и промежуток неактивности примерно равны — пульс,
// не постоянный дебафф. Уровни сокращают промежуток и усиливают эффект.
public final class AuraUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.aura");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    // playerName -> когда последний раз получил эффект (мс).
    private final Map<String, Long> lastAppliedByPlayer = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.AuraCfg cfg = ctx.config().military().aura();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().aura();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> {
            var ul = UnityLauncher.getInstance();
            var subtypeService = ul.militaryDefenseSubtypeService;
            long pulseMs = (cfg.durationTicks() + cfg.inactivityTicks()) * 50L;

            for (Player p : Bukkit.getOnlinePlayers()) {
                String hereCountry = UpgradeCondition.locationCountryOwner(p.getLocation());
                if (hereCountry == null || hereCountry.isBlank()) continue;

                String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry != null && playerCountry.equalsIgnoreCase(hereCountry)) continue; // свои — не трогаем

                // GH#26 (фидбек — "должен уже работать на самом простом
                // уровне, улучшения должны прокачивать, не включать") —
                // level (сила, отдельный узел от квоты) больше не гейтит
                // срабатывание вообще (level<1 → continue убран) — база уже
                // подтверждена hasActiveAuraZone ниже; level только влияет
                // на амплификатор эффекта, клампится к минимум 1 (не
                // купленный уровень — это базовая сила, не "минус сила").
                int level = Math.max(1, UpgradeCondition.countryMaxLevel(
                        UpgradeCondition.resolveCountryGroupId(hereCountry),
                        com.frammy.unitylauncher.military.MilitaryDefenseSubtype.AURA.levelPermBase(), 2));
                // GH#24 (фидбек 2026-08-14 п.1/4) — раньше срабатывал на ЛЮБОЙ
                // DEFENSE-зоне страны; теперь только если хоть одна реально вкачана в AURA.
                if (!hasActiveAuraZone(subtypeService, hereCountry)) continue;

                long now = System.currentTimeMillis();
                Long last = lastAppliedByPlayer.get(p.getName());
                if (last != null && now - last < pulseMs) continue;

                UpgradeCondition.applyPotionSmart(p, PotionEffectType.WEAKNESS, cfg.durationTicks(), cfg.amplifier() + (level - 1), true, false, true);
                lastAppliedByPlayer.put(p.getName(), now);
            }
        }, period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/Aura] started period=" + period);
        }
    }

    /** Считается один раз в тик было бы дешевле, но страна тут не известна заранее (перебираем всех онлайн) — при малом числе военных зон разница не важна. */
    private boolean hasActiveAuraZone(com.frammy.unitylauncher.military.MilitaryDefenseSubtypeService subtypeService, String countryName) {
        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            if (!countryName.equalsIgnoreCase(z.getCountryName())) continue;
            if (subtypeService.isActiveAs(z, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.AURA)) return true;
        }
        return false;
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastAppliedByPlayer.clear();
    }
}
