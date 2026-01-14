package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;

public record HospitalCfg(
        PsychSupportCfg psychSupport,
        DietCfg diet,
        RegenPulseCfg regenPulse,
        SanitaryZoneCfg sanitaryZone,
        BloodGiftCfg bloodGift,
        SafeZoneCfg safeZone,
        TriageCfg triage
) {
    public static HospitalCfg defaults() {
        return new HospitalCfg(
                PsychSupportCfg.defaults(),
                DietCfg.defaults(),
                RegenPulseCfg.defaults(),
                SanitaryZoneCfg.defaults(),
                BloodGiftCfg.defaults(),
                SafeZoneCfg.defaults(),
                TriageCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        var ps = PsychSupportCfg.defaults();
        dirty |= def(c, "hospital.psychSupport.enabled", ps.enabled());
        dirty |= def(c, "hospital.psychSupport.permBase", ps.permBase());
        dirty |= def(c, "hospital.psychSupport.luckDurationTicks", ps.luckDurationTicks());

        var diet = DietCfg.defaults();
        dirty |= def(c, "hospital.diet.enabled", diet.enabled());
        dirty |= def(c, "hospital.diet.permBase", diet.permBase());
        dirty |= def(c, "hospital.diet.saturationBonus", diet.saturationBonus());

        var rp = RegenPulseCfg.defaults();
        dirty |= def(c, "hospital.regenPulse.enabled", rp.enabled());
        dirty |= def(c, "hospital.regenPulse.permBase", rp.permBase());
        dirty |= def(c, "hospital.regenPulse.periodTicks", rp.periodTicks());
        dirty |= def(c, "hospital.regenPulse.durationTicks", rp.durationTicks());
        dirty |= def(c, "hospital.regenPulse.amplifier", rp.amplifier());

        var sz = SanitaryZoneCfg.defaults();
        dirty |= def(c, "hospital.sanitaryZone.enabled", sz.enabled());
        dirty |= def(c, "hospital.sanitaryZone.permBase", sz.permBase());
        dirty |= def(c, "hospital.sanitaryZone.radiusBlocks", sz.radiusBlocks());
        dirty |= def(c, "hospital.sanitaryZone.spawnMultiplier", sz.spawnMultiplier());
        dirty |= def(c, "hospital.sanitaryZone.rebuildPeriodTicks", sz.rebuildPeriodTicks());

        var bg = BloodGiftCfg.defaults();
        dirty |= def(c, "hospital.bloodGift.enabled", bg.enabled());
        dirty |= def(c, "hospital.bloodGift.permBase", bg.permBase());
        dirty |= def(c, "hospital.bloodGift.durationMinutes", bg.durationMinutes());

        dirty |= def(c, "hospital.bloodGift.absorptionEnabled", bg.absorptionEnabled());
        dirty |= def(c, "hospital.bloodGift.absorptionAmplifier", bg.absorptionAmplifier());

        dirty |= def(c, "hospital.bloodGift.regenEnabled", bg.regenEnabled());
        dirty |= def(c, "hospital.bloodGift.regenTicks", bg.regenTicks());
        dirty |= def(c, "hospital.bloodGift.regenAmplifier", bg.regenAmplifier());

        dirty |= def(c, "hospital.bloodGift.msgStart", bg.msgStart());
        dirty |= def(c, "hospital.bloodGift.msgEnd", bg.msgEnd());

        var safe = SafeZoneCfg.defaults();
        dirty |= def(c, "hospital.safeZone.enabled", safe.enabled());
        dirty |= def(c, "hospital.safeZone.permBase", safe.permBase());
        dirty |= def(c, "hospital.safeZone.damageMultiplier", safe.damageMultiplier());

        var tri = TriageCfg.defaults();
        dirty |= def(c, "hospital.triage.enabled", tri.enabled());
        dirty |= def(c, "hospital.triage.permBase", tri.permBase());
        dirty |= def(c, "hospital.triage.reducePercent", tri.reducePercent());

        return dirty;
    }

    public static HospitalCfg read(FileConfiguration c) {
        var d = defaults();

        var psD = PsychSupportCfg.defaults();
        var ps = new PsychSupportCfg(
                c.getBoolean("hospital.psychSupport.enabled", psD.enabled()),
                str(c, "hospital.psychSupport.permBase", psD.permBase()),
                c.getInt("hospital.psychSupport.luckDurationTicks", psD.luckDurationTicks())
        );

        var dietD = DietCfg.defaults();
        var diet = new DietCfg(
                c.getBoolean("hospital.diet.enabled", dietD.enabled()),
                str(c, "hospital.diet.permBase", dietD.permBase()),
                c.getDouble("hospital.diet.saturationBonus", dietD.saturationBonus())
        );

        var rpD = RegenPulseCfg.defaults();
        var rp = new RegenPulseCfg(
                c.getBoolean("hospital.regenPulse.enabled", rpD.enabled()),
                str(c, "hospital.regenPulse.permBase", rpD.permBase()),
                c.getLong("hospital.regenPulse.periodTicks", rpD.periodTicks()),
                c.getInt("hospital.regenPulse.durationTicks", rpD.durationTicks()),
                c.getInt("hospital.regenPulse.amplifier", rpD.amplifier())
        );

        var szD = SanitaryZoneCfg.defaults();
        var sz = new SanitaryZoneCfg(
                c.getBoolean("hospital.sanitaryZone.enabled", szD.enabled()),
                str(c, "hospital.sanitaryZone.permBase", szD.permBase()),
                c.getInt("hospital.sanitaryZone.radiusBlocks", szD.radiusBlocks()),
                c.getDouble("hospital.sanitaryZone.spawnMultiplier", szD.spawnMultiplier()),
                c.getLong("hospital.sanitaryZone.rebuildPeriodTicks", szD.rebuildPeriodTicks())
        );

        var bgD = BloodGiftCfg.defaults();
        var bg = new BloodGiftCfg(
                c.getBoolean("hospital.bloodGift.enabled", bgD.enabled()),
                str(c, "hospital.bloodGift.permBase", bgD.permBase()),
                c.getInt("hospital.bloodGift.durationMinutes", bgD.durationMinutes()),

                c.getBoolean("hospital.bloodGift.absorptionEnabled", bgD.absorptionEnabled()),
                c.getInt("hospital.bloodGift.absorptionAmplifier", bgD.absorptionAmplifier()),

                c.getBoolean("hospital.bloodGift.regenEnabled", bgD.regenEnabled()),
                c.getInt("hospital.bloodGift.regenTicks", bgD.regenTicks()),
                c.getInt("hospital.bloodGift.regenAmplifier", bgD.regenAmplifier()),

                str(c, "hospital.bloodGift.msgStart", bgD.msgStart()),
                str(c, "hospital.bloodGift.msgEnd", bgD.msgEnd())
        );

        var safeD = SafeZoneCfg.defaults();
        var safe = new SafeZoneCfg(
                c.getBoolean("hospital.safeZone.enabled", safeD.enabled()),
                str(c, "hospital.safeZone.permBase", safeD.permBase()),
                c.getDouble("hospital.safeZone.damageMultiplier", safeD.damageMultiplier())
        );

        var triD = TriageCfg.defaults();
        var tri = new TriageCfg(
                c.getBoolean("hospital.triage.enabled", triD.enabled()),
                str(c, "hospital.triage.permBase", triD.permBase()),
                c.getDouble("hospital.triage.reducePercent", triD.reducePercent())
        );

        return new HospitalCfg(ps, diet, rp, sz, bg, safe, tri);
    }

    public record PsychSupportCfg(boolean enabled, String permBase, int luckDurationTicks) {
        public static PsychSupportCfg defaults() { return new PsychSupportCfg(true, "unity.hospital.psych_support", 20 * 20); }
    }

    public record DietCfg(boolean enabled, String permBase, double saturationBonus) {
        public static DietCfg defaults() { return new DietCfg(true, "unity.hospital.diet", 0.75); }
    }

    public record RegenPulseCfg(boolean enabled, String permBase, long periodTicks, int durationTicks, int amplifier) {
        public static RegenPulseCfg defaults() { return new RegenPulseCfg(true, "unity.hospital.regen_pulse", 20L * 5, 20 * 5, 0); }
    }

    public record SanitaryZoneCfg(boolean enabled, String permBase, int radiusBlocks, double spawnMultiplier, long rebuildPeriodTicks) {
        public static SanitaryZoneCfg defaults() { return new SanitaryZoneCfg(true, "unity.hospital.sanitary_zone", 64, 0.6, 20L * 60); }
    }

    public record BloodGiftCfg(
            boolean enabled,
            String permBase,
            int durationMinutes,

            boolean absorptionEnabled,
            int absorptionAmplifier,

            boolean regenEnabled,
            int regenTicks,
            int regenAmplifier,

            String msgStart,
            String msgEnd
    ) {
        public static BloodGiftCfg defaults() {
            return new BloodGiftCfg(
                    true,
                    "unity.hospital.blood_gift",
                    10,

                    true,
                    0,        // amp 0 = 2 hearts

                    true,
                    20 * 4,
                    0,

                    "§a✚ Дар крови активирован на §e%d§a мин.",
                    "§eЭффект 'Дар крови' закончился"
            );
        }
    }

    public record SafeZoneCfg(boolean enabled, String permBase, double damageMultiplier) {
        public static SafeZoneCfg defaults() { return new SafeZoneCfg(true, "unity.hospital.safe_zone", 0.8); }
    }

    public record TriageCfg(boolean enabled, String permBase, double reducePercent) {
        public static TriageCfg defaults() { return new TriageCfg(true, "unity.hospital.triage", 35.0); }
    }
}
