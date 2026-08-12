package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 2, §14.2/§14.5 — только
// Оборона и Военный госпиталь тут: они не завязаны на войну (§13). Разведка/
// Радиоразведка/Поддержка атаки/Логистика ждут Фазы 3-4 (нужен статус WAR).
public record MilitaryCfg(
        DefensePatrolCfg defensePatrol,
        HospitalRegenCfg hospitalRegen
) {
    public static MilitaryCfg defaults() {
        return new MilitaryCfg(DefensePatrolCfg.defaults(), HospitalRegenCfg.defaults());
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        var dp = DefensePatrolCfg.defaults();
        dirty |= def(c, "military.defensePatrol.enabled", dp.enabled());
        dirty |= def(c, "military.defensePatrol.permBase", dp.permBase());
        dirty |= def(c, "military.defensePatrol.periodTicks", dp.periodTicks());
        dirty |= def(c, "military.defensePatrol.mobsPerWave", dp.mobsPerWave());
        dirty |= def(c, "military.defensePatrol.maxAlive", dp.maxAlive());

        var hr = HospitalRegenCfg.defaults();
        dirty |= def(c, "military.hospitalRegen.enabled", hr.enabled());
        dirty |= def(c, "military.hospitalRegen.permBase", hr.permBase());
        dirty |= def(c, "military.hospitalRegen.periodTicks", hr.periodTicks());
        dirty |= def(c, "military.hospitalRegen.durationTicks", hr.durationTicks());
        dirty |= def(c, "military.hospitalRegen.amplifier", hr.amplifier());

        return dirty;
    }

    public static MilitaryCfg read(FileConfiguration c) {
        var dpD = DefensePatrolCfg.defaults();
        var dp = new DefensePatrolCfg(
                c.getBoolean("military.defensePatrol.enabled", dpD.enabled()),
                str(c, "military.defensePatrol.permBase", dpD.permBase()),
                c.getLong("military.defensePatrol.periodTicks", dpD.periodTicks()),
                c.getInt("military.defensePatrol.mobsPerWave", dpD.mobsPerWave()),
                c.getInt("military.defensePatrol.maxAlive", dpD.maxAlive())
        );

        var hrD = HospitalRegenCfg.defaults();
        var hr = new HospitalRegenCfg(
                c.getBoolean("military.hospitalRegen.enabled", hrD.enabled()),
                str(c, "military.hospitalRegen.permBase", hrD.permBase()),
                c.getLong("military.hospitalRegen.periodTicks", hrD.periodTicks()),
                c.getInt("military.hospitalRegen.durationTicks", hrD.durationTicks()),
                c.getInt("military.hospitalRegen.amplifier", hrD.amplifier())
        );

        return new MilitaryCfg(dp, hr);
    }

    // §14.5: волна = 3 моба, новая волна раз в 90с (черновые числа — доигровка/
    // нейтрализация до 5 волн подряд остаётся Фазе 4, тут только сам патруль
    // как живая примета, см. §14.2).
    public record DefensePatrolCfg(boolean enabled, String permBase, long periodTicks, int mobsPerWave, int maxAlive) {
        public static DefensePatrolCfg defaults() { return new DefensePatrolCfg(true, "unity.military.defense", 20L * 90, 3, 6); }
    }

    // Тот же паттерн, что и hospital.regenPulse (RegenPulseUpgrade) — только
    // область действия ZoneType.MILITARY вместо ZoneType.HOSPITAL.
    public record HospitalRegenCfg(boolean enabled, String permBase, long periodTicks, int durationTicks, int amplifier) {
        public static HospitalRegenCfg defaults() { return new HospitalRegenCfg(true, "unity.military.hospital_regen", 20L * 5, 20 * 5, 0); }
    }
}
