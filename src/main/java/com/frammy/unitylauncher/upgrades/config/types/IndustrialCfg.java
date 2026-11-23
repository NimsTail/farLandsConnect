package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;
import com.frammy.unitylauncher.upgrades.config.CfgIO;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.strList;

public record IndustrialCfg(
        BrewSpeedCfg brewSpeed,
        EcoFuelCfg ecoFuel,
        FurnaceHeatCfg furnaceHeat,
        FurnaceOreBonusCfg furnaceOreBonus,
        HopperSmartCfg hopperSmart,
        LoaderCfg loader,
        RecyclerCfg recycler
) {
    public static IndustrialCfg defaults() {
        return new IndustrialCfg(
                BrewSpeedCfg.defaults(),
                EcoFuelCfg.defaults(),
                FurnaceHeatCfg.defaults(),
                FurnaceOreBonusCfg.defaults(),
                HopperSmartCfg.defaults(),
                LoaderCfg.defaults(),
                RecyclerCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        var bs = BrewSpeedCfg.defaults();
        dirty |= def(c, "industrial.brewSpeed.enabled", bs.enabled());
        dirty |= def(c, "industrial.brewSpeed.speedPercent", bs.speedPercent());
        dirty |= def(c, "industrial.brewSpeed.permBase", bs.permBase());

        var eco = EcoFuelCfg.defaults();
        dirty |= def(c, "industrial.ecoFuel.enabled", eco.enabled());
        dirty |= def(c, "industrial.ecoFuel.permBase", eco.permBase());
        dirty |= def(c, "industrial.ecoFuel.bambooMultiplier", eco.bambooMultiplier());
        dirty |= def(c, "industrial.ecoFuel.bambooBlockMultiplier", eco.bambooBlockMultiplier());

        var fh = FurnaceHeatCfg.defaults();
        dirty |= def(c, "industrial.furnaceHeat.enabled", fh.enabled());
        dirty |= def(c, "industrial.furnaceHeat.permBase", fh.permBase());
        dirty |= def(c, "industrial.furnaceHeat.radius", fh.radius());
        dirty |= def(c, "industrial.furnaceHeat.maxPct", fh.maxPct());
        dirty |= def(c, "industrial.furnaceHeat.sfx", fh.sfx());

        var fo = FurnaceOreBonusCfg.defaults();
        dirty |= def(c, "industrial.furnaceOreBonus.enabled", fo.enabled());
        dirty |= def(c, "industrial.furnaceOreBonus.permBase", fo.permBase());
        dirty |= def(c, "industrial.furnaceOreBonus.chance", fo.chance());
        dirty |= def(c, "industrial.furnaceOreBonus.outputs", fo.outputs());
        dirty |= def(c, "industrial.furnaceOreBonus.sfx", fo.sfx());

        var hs = HopperSmartCfg.defaults();
        dirty |= def(c, "industrial.hopperSmart.enabled", hs.enabled());
        dirty |= def(c, "industrial.hopperSmart.permBase", hs.permBase());
        dirty |= def(c, "industrial.hopperSmart.slowModeLvl0", hs.slowModeLvl0());
        dirty |= def(c, "industrial.hopperSmart.turboTaskPeriodTicks", hs.turboTaskPeriodTicks());
        dirty |= def(c, "industrial.hopperSmart.turboBudgetPerRun", hs.turboBudgetPerRun());
        dirty |= def(c, "industrial.hopperSmart.turboEligibilityCacheMs", hs.turboEligibilityCacheMs());
        dirty |= def(c, "industrial.hopperSmart.requireIndustrialZoneForTurbo", hs.requireIndustrialZoneForTurbo());

        var ld = LoaderCfg.defaults();
        dirty |= def(c, "industrial.loader.enabled", ld.enabled());
        dirty |= def(c, "industrial.loader.permBase", ld.permBase());
        dirty |= def(c, "industrial.loader.radius", ld.radius());

        dirty |= def(c, "industrial.loader.speed", ld.speed());
        dirty |= def(c, "industrial.loader.itemsPerSecondAt100", ld.itemsPerSecondAt100());
        dirty |= def(c, "industrial.loader.maxBurst", ld.maxBurst());
        dirty |= def(c, "industrial.loader.maxMovesPerTick", ld.maxMovesPerTick());
        dirty |= def(c, "industrial.loader.candidateTtlMs", ld.candidateTtlMs());

        var r = RecyclerCfg.defaults();
        dirty |= def(c, "industrial.recycler.enabled", r.enabled());
        dirty |= def(c, "industrial.recycler.permBase", r.permBase());
        dirty |= def(c, "industrial.recycler.inputs", r.inputs());
        dirty |= def(c, "industrial.recycler.extraDrops", r.extraDrops());

        return dirty;
    }

    public static IndustrialCfg read(FileConfiguration c) {
        var bsD = BrewSpeedCfg.defaults();
        var brew = new BrewSpeedCfg(
                c.getBoolean("industrial.brewSpeed.enabled", bsD.enabled()),
                c.getDouble("industrial.brewSpeed.speedPercent", bsD.speedPercent()),
                str(c, "industrial.brewSpeed.permBase", bsD.permBase())
        );

        var ecoD = EcoFuelCfg.defaults();
        var eco = new EcoFuelCfg(
                c.getBoolean("industrial.ecoFuel.enabled", ecoD.enabled()),
                str(c, "industrial.ecoFuel.permBase", ecoD.permBase()),
                c.getDouble("industrial.ecoFuel.bambooMultiplier", ecoD.bambooMultiplier()),
                c.getDouble("industrial.ecoFuel.bambooBlockMultiplier", ecoD.bambooBlockMultiplier())
        );

        var fhD = FurnaceHeatCfg.defaults();
        var heat = new FurnaceHeatCfg(
                c.getBoolean("industrial.furnaceHeat.enabled", fhD.enabled()),
                str(c, "industrial.furnaceHeat.permBase", fhD.permBase()),
                c.getInt("industrial.furnaceHeat.radius", fhD.radius()),
                c.getDouble("industrial.furnaceHeat.maxPct", fhD.maxPct()),
                c.getBoolean("industrial.furnaceHeat.sfx", fhD.sfx())
        );

        var foD = FurnaceOreBonusCfg.defaults();
        var fo = new FurnaceOreBonusCfg(
                c.getBoolean("industrial.furnaceOreBonus.enabled", foD.enabled()),
                CfgIO.str(c, "industrial.furnaceOreBonus.permBase", foD.permBase()),
                c.getDouble("industrial.furnaceOreBonus.chance", foD.chance()),
                CfgIO.strList(c, "industrial.furnaceOreBonus.outputs", foD.outputs()),
                c.getBoolean("industrial.furnaceOreBonus.sfx", foD.sfx())
        );

        var hsD = HopperSmartCfg.defaults();
        var hs = new HopperSmartCfg(
                c.getBoolean("industrial.hopperSmart.enabled", hsD.enabled()),
                CfgIO.str(c, "industrial.hopperSmart.permBase", hsD.permBase()),
                c.getBoolean("industrial.hopperSmart.slowModeLvl0", hsD.slowModeLvl0()),
                c.getLong("industrial.hopperSmart.turboTaskPeriodTicks", hsD.turboTaskPeriodTicks()),
                c.getInt("industrial.hopperSmart.turboBudgetPerRun", hsD.turboBudgetPerRun()),
                c.getLong("industrial.hopperSmart.turboEligibilityCacheMs", hsD.turboEligibilityCacheMs()),
                c.getBoolean("industrial.hopperSmart.requireIndustrialZoneForTurbo", hsD.requireIndustrialZoneForTurbo())
        );

        var ldD = LoaderCfg.defaults();
        var ld = new LoaderCfg(
                c.getBoolean("industrial.loader.enabled", ldD.enabled()),
                CfgIO.str(c, "industrial.loader.permBase", ldD.permBase()),
                c.getInt("industrial.loader.radius", ldD.radius()),

                c.getInt("industrial.loader.speed", ldD.speed()),
                c.getInt("industrial.loader.itemsPerSecondAt100", ldD.itemsPerSecondAt100()),
                c.getInt("industrial.loader.maxBurst", ldD.maxBurst()),
                c.getInt("industrial.loader.maxMovesPerTick", ldD.maxMovesPerTick()),
                c.getLong("industrial.loader.candidateTtlMs", ldD.candidateTtlMs())
        );

        var rD = RecyclerCfg.defaults();
        var r = new RecyclerCfg(
                c.getBoolean("industrial.recycler.enabled", rD.enabled()),
                str(c, "industrial.recycler.permBase", rD.permBase()),
                strList(c, "industrial.recycler.inputs", rD.inputs()),
                strList(c, "industrial.recycler.extraDrops", rD.extraDrops())
        );

        return new IndustrialCfg(brew, eco, heat, fo, hs, ld, r);
    }

    public record BrewSpeedCfg(boolean enabled, double speedPercent, String permBase) {
        public static BrewSpeedCfg defaults() { return new BrewSpeedCfg(true, 30.0, "unity.industrial.brew"); }
    }

    public record EcoFuelCfg(
            boolean enabled,
            String permBase,
            double bambooMultiplier,
            double bambooBlockMultiplier
    ) {
        public static EcoFuelCfg defaults() {
            return new EcoFuelCfg(
                    true,
                    "unity.industrial.ecofuel",
                    2.0,
                    2.5
            );
        }
    }

    public record FurnaceHeatCfg(boolean enabled, String permBase, int radius, double maxPct, boolean sfx) {
        public static FurnaceHeatCfg defaults() { return new FurnaceHeatCfg(true, "unity.industrial.furnace_heat", 4, 30.0, true); }
    }

    public record FurnaceOreBonusCfg(
            boolean enabled,
            String permBase,
            double chance,
            java.util.List<String> outputs,
            boolean sfx
    ) {
        public static FurnaceOreBonusCfg defaults() {
            return new FurnaceOreBonusCfg(
                    true,
                    "unity.industrial.furnace_ore_bonus",
                    0.15,
                    java.util.List.of(
                            "IRON_INGOT",
                            "GOLD_INGOT",
                            "COPPER_INGOT",
                            "NETHERITE_SCRAP"
                    ),
                    true
            );
        }
    }

    public record HopperSmartCfg(
            boolean enabled,

            String permBase,
            boolean slowModeLvl0,

            long turboTaskPeriodTicks,
            int turboBudgetPerRun,
            long turboEligibilityCacheMs,

            boolean requireIndustrialZoneForTurbo
    ) {
        public static HopperSmartCfg defaults() {
            return new HopperSmartCfg(
                    true,

                    "unity.industrial.hopper_smart",
                    true,

                    1L,
                    200,
                    1500L,

                    true
            );
        }
    }

    public record LoaderCfg(
            boolean enabled,
            String permBase,
            int radius,

            int speed,                 // 1..100 (уровень)
            int itemsPerSecondAt100,    // сколько items/sec при speed=100
            int maxBurst,              // максимум накопленных "переносов"
            int maxMovesPerTick,        // лимит доп. переносов за тик
            long candidateTtlMs         // сколько держим кандидата после lastSeen
    ) {
        public static LoaderCfg defaults() {
            return new LoaderCfg(
                    true,
                    "unity.industrial.loader",
                    3,

                    25,     // speed
                    60,     // itemsPerSecondAt100
                    6,      // maxBurst
                    4,      // maxMovesPerTick
                    1500L   // candidateTtlMs
            );
        }
    }

    public record RecyclerCfg(
            boolean enabled,
            String permBase,
            java.util.List<String> inputs,
            java.util.List<String> extraDrops
    ) {
        public static RecyclerCfg defaults() {
            return new RecyclerCfg(
                    true,
                    "unity.industrial.recycler",
                    java.util.List.of(
                            "IRON_BLOCK",
                            "COPPER_BLOCK",
                            "GOLD_BLOCK"
                    ),
                    java.util.List.of(
                            "IRON_INGOT=0.05",
                            "COPPER_INGOT=0.05"
                    )
            );
        }
    }

}
