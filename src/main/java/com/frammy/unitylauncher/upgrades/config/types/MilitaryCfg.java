package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 2+4, §14.2/§14.5.
public record MilitaryCfg(
        DefensePatrolCfg defensePatrol,
        HospitalRegenCfg hospitalRegen,
        AttackSupportCfg attackSupport,
        LogisticsCfg logistics
) {
    public static MilitaryCfg defaults() {
        return new MilitaryCfg(DefensePatrolCfg.defaults(), HospitalRegenCfg.defaults(), AttackSupportCfg.defaults(), LogisticsCfg.defaults());
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

        var as = AttackSupportCfg.defaults();
        dirty |= def(c, "military.attackSupport.enabled", as.enabled());
        dirty |= def(c, "military.attackSupport.permBase", as.permBase());
        dirty |= def(c, "military.attackSupport.periodTicks", as.periodTicks());
        dirty |= def(c, "military.attackSupport.durationTicks", as.durationTicks());
        dirty |= def(c, "military.attackSupport.strengthAmplifier", as.strengthAmplifier());
        dirty |= def(c, "military.attackSupport.speedAmplifier", as.speedAmplifier());

        var lg = LogisticsCfg.defaults();
        dirty |= def(c, "military.logistics.enabled", lg.enabled());
        dirty |= def(c, "military.logistics.permBase", lg.permBase());
        dirty |= def(c, "military.logistics.periodTicks", lg.periodTicks());

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

        var asD = AttackSupportCfg.defaults();
        var as = new AttackSupportCfg(
                c.getBoolean("military.attackSupport.enabled", asD.enabled()),
                str(c, "military.attackSupport.permBase", asD.permBase()),
                c.getLong("military.attackSupport.periodTicks", asD.periodTicks()),
                c.getInt("military.attackSupport.durationTicks", asD.durationTicks()),
                c.getInt("military.attackSupport.strengthAmplifier", asD.strengthAmplifier()),
                c.getInt("military.attackSupport.speedAmplifier", asD.speedAmplifier())
        );

        var lgD = LogisticsCfg.defaults();
        var lg = new LogisticsCfg(
                c.getBoolean("military.logistics.enabled", lgD.enabled()),
                str(c, "military.logistics.permBase", lgD.permBase()),
                c.getLong("military.logistics.periodTicks", lgD.periodTicks())
        );

        return new MilitaryCfg(dp, hr, as, lg);
    }

    // §14.5: волна = 3 моба, новая волна раз в 90с (черновые числа).
    public record DefensePatrolCfg(boolean enabled, String permBase, long periodTicks, int mobsPerWave, int maxAlive) {
        public static DefensePatrolCfg defaults() { return new DefensePatrolCfg(true, "unity.military.defense", 20L * 90, 3, 6); }
    }

    // Тот же паттерн, что и hospital.regenPulse (RegenPulseUpgrade) — только
    // область действия ZoneType.MILITARY вместо ZoneType.HOSPITAL.
    public record HospitalRegenCfg(boolean enabled, String permBase, long periodTicks, int durationTicks, int amplifier) {
        public static HospitalRegenCfg defaults() { return new HospitalRegenCfg(true, "unity.military.hospital_regen", 20L * 5, 20 * 5, 0); }
    }

    // §3.3 "Поддержка атаки" — Strength I/Speed I гражданам, физически
    // находящимся в зоне СТРАНЫ-ЦЕЛИ войны (не своей!), пока активна WAR с
    // этой целью. Требует WarStatusCache (Фаза 4). BREAK_ANCHOR-нейтрализация
    // (§14.2 — наковальня) НЕ реализована в этой версии: анкер-биндинг
    // (по аналогии с сундуком у шопа, §14.3) как отдельная инфраструктура
    // ещё не построен ни для одного апгрейда — открытый пункт на будущее.
    public record AttackSupportCfg(boolean enabled, String permBase, long periodTicks, int durationTicks, int strengthAmplifier, int speedAmplifier) {
        public static AttackSupportCfg defaults() { return new AttackSupportCfg(true, "unity.military.attack_support", 20L * 5, 20 * 8, 0, 0); }
    }

    // §3.3/§14.2 "Логистика" — примета (звук генератора у склада), без
    // реального экономического эффекта в этой версии: снижение стоимости
    // содержания требует апгрейдов с per-объектной ценой, которых в текущей
    // системе прайсинга (страна покупает апгрейд целиком, не по объектам)
    // нет — открытый пункт на будущее, как и BREAK_ANCHOR выше.
    public record LogisticsCfg(boolean enabled, String permBase, long periodTicks) {
        public static LogisticsCfg defaults() { return new LogisticsCfg(true, "unity.military.logistics", 20L * 20); }
    }
}
