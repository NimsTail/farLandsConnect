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
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 2, §14.2 "Военный
// госпиталь". Прямая копия RegenPulseUpgrade (hospital.regen_pulse) под
// ZoneType.MILITARY — не завязана на войну, работает как обычный апгрейд.
public final class MilitaryHospitalRegenUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.hospital_regen");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.HospitalRegenCfg cfg = ctx.config().military().hospitalRegen();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().hospitalRegen();

        long period = Math.max(20L, cfg.periodTicks());
        int dur = Math.max(20, cfg.durationTicks());
        int amp = Math.max(0, cfg.amplifier());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> {
            var specService = UnityLauncher.getInstance().militarySpecializationService;
            for (Player p : Bukkit.getOnlinePlayers()) {
                // GH#24 п.2: раньше проверялось только "стоит ли игрок внутри
                // КАКОГО-ТО военного объекта страны с купленным апгрейдом" —
                // теперь нужен конкретный объект (не просто булево "внутри
                // военной зоны"), чтобы спросить его специализацию (§4.1).
                ZoneInfo zone = specService.militaryZoneAt(p.getLocation());
                if (zone == null) continue;
                if (!specService.isActiveAs(zone, MilitarySpecialization.HOSPITAL)) continue;

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
            plugin().getLogger().info("[Military/HospitalRegen] started period=" + period + " dur=" + dur + " amp=" + amp);
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
