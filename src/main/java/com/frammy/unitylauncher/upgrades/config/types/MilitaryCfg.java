package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 2+4, §14.2/§14.5.
public record MilitaryCfg(
        DefensePatrolCfg defensePatrol,
        HospitalRegenCfg hospitalRegen,
        AttackSupportCfg attackSupport,
        LogisticsCfg logistics,
        // GH#24 — оборонительные сооружения (саб-роли под специализацией DEFENSE).
        LiveDefenseCfg liveDefense,
        AuraCfg aura,
        ScorchCfg scorch,
        CrossbowCfg crossbow
) {
    public static MilitaryCfg defaults() {
        return new MilitaryCfg(
                DefensePatrolCfg.defaults(), HospitalRegenCfg.defaults(), AttackSupportCfg.defaults(), LogisticsCfg.defaults(),
                LiveDefenseCfg.defaults(), AuraCfg.defaults(), ScorchCfg.defaults(), CrossbowCfg.defaults()
        );
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

        var ld = LiveDefenseCfg.defaults();
        dirty |= def(c, "military.liveDefense.enabled", ld.enabled());
        dirty |= def(c, "military.liveDefense.permBase", ld.permBase());
        dirty |= def(c, "military.liveDefense.periodTicks", ld.periodTicks());
        dirty |= def(c, "military.liveDefense.cooldownTicks", ld.cooldownTicks());
        dirty |= def(c, "military.liveDefense.detectRadius", ld.detectRadius());

        var au = AuraCfg.defaults();
        dirty |= def(c, "military.aura.enabled", au.enabled());
        dirty |= def(c, "military.aura.permBase", au.permBase());
        dirty |= def(c, "military.aura.periodTicks", au.periodTicks());
        dirty |= def(c, "military.aura.durationTicks", au.durationTicks());
        dirty |= def(c, "military.aura.inactivityTicks", au.inactivityTicks());
        dirty |= def(c, "military.aura.amplifier", au.amplifier());

        var sc = ScorchCfg.defaults();
        dirty |= def(c, "military.scorch.enabled", sc.enabled());
        dirty |= def(c, "military.scorch.permBase", sc.permBase());
        dirty |= def(c, "military.scorch.periodTicks", sc.periodTicks());
        dirty |= def(c, "military.scorch.radius", sc.radius());
        dirty |= def(c, "military.scorch.fireTicks", sc.fireTicks());
        dirty |= def(c, "military.scorch.cooldownTicks", sc.cooldownTicks());

        var cb = CrossbowCfg.defaults();
        dirty |= def(c, "military.crossbow.enabled", cb.enabled());
        dirty |= def(c, "military.crossbow.permBase", cb.permBase());
        dirty |= def(c, "military.crossbow.periodTicks", cb.periodTicks());
        dirty |= def(c, "military.crossbow.radius", cb.radius());
        dirty |= def(c, "military.crossbow.damage", cb.damage());
        dirty |= def(c, "military.crossbow.blindSpotDegrees", cb.blindSpotDegrees());

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

        var ldD = LiveDefenseCfg.defaults();
        var ld = new LiveDefenseCfg(
                c.getBoolean("military.liveDefense.enabled", ldD.enabled()),
                str(c, "military.liveDefense.permBase", ldD.permBase()),
                c.getLong("military.liveDefense.periodTicks", ldD.periodTicks()),
                c.getLong("military.liveDefense.cooldownTicks", ldD.cooldownTicks()),
                c.getInt("military.liveDefense.detectRadius", ldD.detectRadius())
        );

        var auD = AuraCfg.defaults();
        var au = new AuraCfg(
                c.getBoolean("military.aura.enabled", auD.enabled()),
                str(c, "military.aura.permBase", auD.permBase()),
                c.getLong("military.aura.periodTicks", auD.periodTicks()),
                c.getInt("military.aura.durationTicks", auD.durationTicks()),
                c.getInt("military.aura.inactivityTicks", auD.inactivityTicks()),
                c.getInt("military.aura.amplifier", auD.amplifier())
        );

        var scD = ScorchCfg.defaults();
        var sc = new ScorchCfg(
                c.getBoolean("military.scorch.enabled", scD.enabled()),
                str(c, "military.scorch.permBase", scD.permBase()),
                c.getLong("military.scorch.periodTicks", scD.periodTicks()),
                c.getInt("military.scorch.radius", scD.radius()),
                c.getInt("military.scorch.fireTicks", scD.fireTicks()),
                c.getLong("military.scorch.cooldownTicks", scD.cooldownTicks())
        );

        var cbD = CrossbowCfg.defaults();
        var cb = new CrossbowCfg(
                c.getBoolean("military.crossbow.enabled", cbD.enabled()),
                str(c, "military.crossbow.permBase", cbD.permBase()),
                c.getLong("military.crossbow.periodTicks", cbD.periodTicks()),
                c.getInt("military.crossbow.radius", cbD.radius()),
                c.getDouble("military.crossbow.damage", cbD.damage()),
                c.getDouble("military.crossbow.blindSpotDegrees", cbD.blindSpotDegrees())
        );

        return new MilitaryCfg(dp, hr, as, lg, ld, au, sc, cb);
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

    // GH#24 идея 1 "Живой оборонительный пост" — волна мобов при пересечении
    // врагом границы объекта (не постоянный патруль, как у Обороны). Тип/сила
    // волны растут с уровнем — см. LiveDefensePostUpgrade.mobTypesForLevel.
    public record LiveDefenseCfg(boolean enabled, String permBase, long periodTicks, long cooldownTicks, int detectRadius) {
        public static LiveDefenseCfg defaults() { return new LiveDefenseCfg(true, "unity.military.live_defense", 20L, 20L * 30, 6); }
    }

    // GH#24 идея 2 "Ореол чужеродности" — пульс негативного эффекта врагу на
    // своей территории, длительность и промежуток неактивности примерно равны.
    public record AuraCfg(boolean enabled, String permBase, long periodTicks, int durationTicks, int inactivityTicks, int amplifier) {
        public static AuraCfg defaults() { return new AuraCfg(true, "unity.military.aura", 20L * 5, 20 * 8, 20 * 8, 0); }
    }

    // GH#24 идея 3 "Жгучий" — поджог одного атакующего в радиусе, на макс.
    // уровне добавляется шанс молнии (если цель не под крышей).
    public record ScorchCfg(boolean enabled, String permBase, long periodTicks, int radius, int fireTicks, long cooldownTicks) {
        public static ScorchCfg defaults() { return new ScorchCfg(true, "unity.military.scorch", 20L * 2, 12, 50, 20L * 10); }
    }

    // GH#24 идея 4 "Арбалет" — стрельба по замеченному врагу от якоря/центра
    // объекта, слепая зона под углом снизу (blindSpotDegrees от горизонтали).
    public record CrossbowCfg(boolean enabled, String permBase, long periodTicks, int radius, double damage, double blindSpotDegrees) {
        // GH#29 (фидбек раунд 3) — "повысь ему немного радиус действия" — было 20.
        public static CrossbowCfg defaults() { return new CrossbowCfg(true, "unity.military.crossbow", 20L * 3, 26, 3.0, 20.0); }
    }
}
