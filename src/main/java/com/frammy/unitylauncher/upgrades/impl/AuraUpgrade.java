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

    // Фидбек 2026-08-14 — общий узел "усиление" убран, интервал и сила
    // эффекта теперь два независимых параметрических апгрейда (тот же
    // приём, что GH#29 ввёл для Арбалета: скорострельность/эффекты).
    private static final String INTERVAL_PERM_BASE = "unity.military.aura_interval";
    private static final String STRENGTH_PERM_BASE = "unity.military.aura_strength";
    private static final double[] INTERVAL_MULTIPLIER = {1.0, 0.7, 0.5};

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

            for (Player p : Bukkit.getOnlinePlayers()) {
                String hereCountry = UpgradeCondition.locationCountryOwner(p.getLocation());
                if (hereCountry == null || hereCountry.isBlank()) continue;

                String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry != null && playerCountry.equalsIgnoreCase(hereCountry)) continue; // свои — не трогаем

                // GH#24 (фидбек 2026-08-14 п.1/4) — раньше срабатывал на ЛЮБОЙ
                // DEFENSE-зоне страны; теперь только если хоть одна реально вкачана в AURA.
                if (!hasActiveAuraZone(subtypeService, hereCountry)) continue;
                // farlandsconnect GH#32 (раунд 10) — захваченные секторы
                // линии фронта снижают эффективность: если хоть одна активная
                // зона AURA страны сейчас ослаблена, пульс с вероятностным
                // шансом пропускается целиком (Ореол безусловно одна зона на
                // страну по геймдизайну — activeAuraZoneMarkerId ищет её же).
                String activeAuraMarkerId = activeAuraZoneMarkerId(subtypeService, hereCountry);
                if (!UnityLauncher.getInstance().militaryEffectivenessCache.rollActive(activeAuraMarkerId)) continue;

                // Фидбек 2026-08-14 — интервал и сила эффекта читаются
                // независимо друг от друга (см. класс-javadoc поля выше);
                // ни один из них не гейтит базовое срабатывание — оба
                // клампятся к минимум 0 (0 = ещё не куплено = база).
                String canonicalCountry = UpgradeCondition.resolveCountryGroupId(hereCountry);
                int intervalLevel = UpgradeCondition.countryMaxLevel(canonicalCountry, INTERVAL_PERM_BASE, 2);
                int strengthLevel = UpgradeCondition.countryMaxLevel(canonicalCountry, STRENGTH_PERM_BASE, 2);
                long pulseMs = Math.round((cfg.durationTicks() + cfg.inactivityTicks()) * 50L * INTERVAL_MULTIPLIER[intervalLevel]);

                long now = System.currentTimeMillis();
                Long last = lastAppliedByPlayer.get(p.getName());
                if (last != null && now - last < pulseMs) continue;

                UpgradeCondition.applyPotionSmart(p, PotionEffectType.WEAKNESS, cfg.durationTicks(), cfg.amplifier() + strengthLevel, true, false, true);
                lastAppliedByPlayer.put(p.getName(), now);
            }
        }, period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/Aura] started period=" + period);
        }
    }

    /** Считается один раз в тик было бы дешевле, но страна тут не известна заранее (перебираем всех онлайн) — при малом числе военных зон разница не важна. */
    private boolean hasActiveAuraZone(com.frammy.unitylauncher.military.MilitaryDefenseSubtypeService subtypeService, String countryName) {
        return activeAuraZoneMarkerId(subtypeService, countryName) != null;
    }

    /** farlandsconnect GH#32 (раунд 10) — markerId of the country's active AURA zone, for the effectiveness lookup above. Same scan as hasActiveAuraZone, just returning the id instead of a boolean. */
    private String activeAuraZoneMarkerId(com.frammy.unitylauncher.military.MilitaryDefenseSubtypeService subtypeService, String countryName) {
        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            if (!countryName.equalsIgnoreCase(z.getCountryName())) continue;
            if (subtypeService.isActiveAs(z, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.AURA)) return z.getMarkerID();
        }
        return null;
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
