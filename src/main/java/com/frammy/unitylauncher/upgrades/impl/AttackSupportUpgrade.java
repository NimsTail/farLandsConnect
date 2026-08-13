package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.military.MilitarySpecialization;
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

import java.util.HashSet;
import java.util.Set;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 4 "Поддержка атаки" —
// Strength I/Speed I гражданам СВОЕЙ страны, пока они физически находятся в
// зоне СТРАНЫ-ЦЕЛИ, с которой объявлена WAR (не в своей — это баф для рейда
// вражеской территории, не для обороны своей). См. MilitaryCfg.AttackSupportCfg
// про BREAK_ANCHOR-нейтрализацию (не реализована в этой версии).
public final class AttackSupportUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.attack_support");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.AttackSupportCfg cfg = ctx.config().military().attackSupport();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().attackSupport();
        long period = Math.max(20L, cfg.periodTicks());
        int dur = Math.max(20, cfg.durationTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> {
            var ul = UnityLauncher.getInstance();
            // GH#24 п.2: раньше баф действовал для любой страны с купленным
            // апгрейдом — теперь только если у страны есть хотя бы один
            // военный объект, реально специализированный под Поддержку
            // атаки (§4.1). У этой способности нет "радиуса действия
            // объекта" (баф триггерится позицией игрока в ЧУЖОЙ стране, не
            // рядом со своим объектом) — гейт поэтому не геометрический, а
            // "команда вообще имеет активный такой объект прямо сейчас",
            // тем же принципом, что специализация уже маскируется на время
            // переключения (MilitarySpecializationService.current()).
            // Считается один раз за тик, не на каждого игрока.
            Set<String> countriesWithAttackSupport = countriesWithActiveSpecialization(ul, MilitarySpecialization.ATTACK_SUPPORT);

            for (Player p : Bukkit.getOnlinePlayers()) {
                String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry == null || playerCountry.isBlank()) continue;
                if (countryMaxLevel(UpgradeCondition.resolveCountryGroupId(playerCountry), cfg.permBase(), 1) < 1) continue;
                if (!countriesWithAttackSupport.contains(playerCountry)) continue;

                String hereCountry = UpgradeCondition.locationCountryOwner(p.getLocation());
                if (hereCountry == null || hereCountry.equalsIgnoreCase(playerCountry)) continue; // only in enemy territory, not your own
                if (!ul.warStatusCache.isAtWar(playerCountry, hereCountry)) continue;

                UpgradeCondition.applyPotionSmart(p, PotionEffectType.STRENGTH, dur, cfg.strengthAmplifier(), true, false, true);
                UpgradeCondition.applyPotionSmart(p, PotionEffectType.SPEED, dur, cfg.speedAmplifier(), true, false, true);
            }
        }, period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/AttackSupport] started period=" + period + " dur=" + dur);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Raw (non-canonical) country names — matches what countryRegistryJdbc.getCountryOfPlayer returns, since that's what we compare against. */
    private static Set<String> countriesWithActiveSpecialization(UnityLauncher ul, MilitarySpecialization type) {
        Set<String> out = new HashSet<>();
        for (ZoneInfo z : ul.getZoneManager().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            String country = z.getCountryName();
            if (country == null || country.isBlank()) continue;
            if (ul.militarySpecializationService.isActiveAs(z, type)) out.add(country);
        }
        return out;
    }
}
