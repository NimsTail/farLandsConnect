package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.strList;

public record CountryCfg(
        AntiPhantomCfg antiPhantom,
        EffectsCfg effects,
        ChurchCfg church,
        FarmlandCfg farmland,
        FoodRationCfg foodRation,
        GoldenFoodCfg goldenFood,
        LivestockCfg livestock,
        NetheriteBeaconGatingCfg netheriteBeaconGating,
        RedstoneGatingCfg redstoneGating,
        TntQuarryCfg tntQuarry
) {
    public static CountryCfg defaults() {
        return new CountryCfg(
                AntiPhantomCfg.defaults(),
                EffectsCfg.defaults(),
                ChurchCfg.defaults(),
                FarmlandCfg.defaults(),
                FoodRationCfg.defaults(),
                GoldenFoodCfg.defaults(),
                LivestockCfg.defaults(),
                NetheriteBeaconGatingCfg.defaults(),
                RedstoneGatingCfg.defaults(),
                TntQuarryCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        var ap = AntiPhantomCfg.defaults();
        dirty |= def(c, "country.antiPhantom.enabled", ap.enabled());
        dirty |= def(c, "country.antiPhantom.permBase", ap.permBase());

        var e = EffectsCfg.defaults();
        dirty |= def(c, "country.effects.enabled", e.enabled());
        dirty |= def(c, "country.effects.reapplyCooldownMs", e.reapplyCooldownMs());
        dirty |= def(c, "country.effects.effectsMaxLevel", e.effectsMaxLevel());
        dirty |= def(c, "country.effects.effectTicks", e.effectTicks());
        dirty |= def(c, "country.effects.permHaste", e.permHaste());
        dirty |= def(c, "country.effects.permSpeed", e.permSpeed());
        dirty |= def(c, "country.effects.permResist", e.permResist());

        var dp = DustProtectionCfg.defaults();
        dirty |= def(c, "country.effects.dustProtection.enabled", dp.enabled());
        dirty |= def(c, "country.effects.dustProtection.permBase", dp.permBase());
        dirty |= def(c, "country.effects.dustProtection.minY", dp.minY());
        dirty |= def(c, "country.effects.dustProtection.durationTicks", dp.durationTicks());

        var ch = ChurchCfg.defaults();
        dirty |= def(c, "country.church.enabled", ch.enabled());

        var bell = BellCfg.defaults();
        dirty |= def(c, "country.church.bell.enabled", bell.enabled());
        dirty |= def(c, "country.church.bell.permBase", bell.permBase());
        dirty |= def(c, "country.church.bell.stayMinutes", bell.stayMinutes());
        dirty |= def(c, "country.church.bell.cooldownMinutes", bell.cooldownMinutes());
        dirty |= def(c, "country.church.bell.sfx", bell.sfx());

        var pil = PilgrimageCfg.defaults();
        dirty |= def(c, "country.church.pilgrimage.enabled", pil.enabled());
        dirty |= def(c, "country.church.pilgrimage.permBase", pil.permBase());
        dirty |= def(c, "country.church.pilgrimage.stayMinutes", pil.stayMinutes());
        dirty |= def(c, "country.church.pilgrimage.cooldownMinutes", pil.cooldownMinutes());
        dirty |= def(c, "country.church.pilgrimage.effects", pil.effects());
        dirty |= def(c, "country.church.pilgrimage.amplifier", pil.amplifier());
        dirty |= def(c, "country.church.pilgrimage.buffMinutes", pil.buffMinutes());
        dirty |= def(c, "country.church.pilgrimage.sfx", pil.sfx());

        var f = FarmlandCfg.defaults();
        dirty |= def(c, "country.farmland.enabled", f.enabled());
        dirty |= def(c, "country.farmland.permBase", f.permBase());
        dirty |= def(c, "country.farmland.bigFallThreshold", f.bigFallThreshold());

        var fr = FoodRationCfg.defaults();
        dirty |= def(c, "country.foodRation.enabled", fr.enabled());
        dirty |= def(c, "country.foodRation.permBase", fr.permBase());
        dirty |= def(c, "country.foodRation.periodTicks", fr.periodTicks());
        dirty |= def(c, "country.foodRation.minFoodLevel", fr.minFoodLevel());
        dirty |= def(c, "country.foodRation.addFoodAmount", fr.addFoodAmount());
        dirty |= def(c, "country.foodRation.addSaturation", fr.addSaturation());

        var gf = GoldenFoodCfg.defaults();
        dirty |= def(c, "country.goldenFood.enabled", gf.enabled());
        dirty |= def(c, "country.goldenFood.permBase", gf.permBase());
        dirty |= def(c, "country.goldenFood.premiumFoods", gf.premiumFoods());
        dirty |= def(c, "country.goldenFood.msgConsume", gf.msgConsume());
        dirty |= def(c, "country.goldenFood.msgCraft", gf.msgCraft());
        dirty |= def(c, "country.goldenFood.sfx", gf.sfx());

        var ls = LivestockCfg.defaults();
        dirty |= def(c, "country.livestock.enabled", ls.enabled());
        dirty |= def(c, "country.livestock.permBase", ls.permBase());
        dirty |= def(c, "country.livestock.speedPercent", ls.speedPercent());
        dirty |= def(c, "country.livestock.doubleChancePercent", ls.doubleChancePercent());

        var nb = NetheriteBeaconGatingCfg.defaults();
        dirty |= def(c, "country.netheriteBeaconGating.enabled", nb.enabled());
        dirty |= def(c, "country.netheriteBeaconGating.netheritePermBase", nb.netheritePermBase());
        dirty |= def(c, "country.netheriteBeaconGating.beaconPermBase", nb.beaconPermBase());
        dirty |= def(c, "country.netheriteBeaconGating.blockCraft", nb.blockCraft());
        dirty |= def(c, "country.netheriteBeaconGating.blockUse", nb.blockUse());
        dirty |= def(c, "country.netheriteBeaconGating.errmsgNetherite", nb.errmsgNetherite());
        dirty |= def(c, "country.netheriteBeaconGating.errmsgBeacon", nb.errmsgBeacon());

        var rs = RedstoneGatingCfg.defaults();
        dirty |= def(c, "country.redstoneGating.enabled", rs.enabled());
        dirty |= def(c, "country.redstoneGating.permBase", rs.permBase());

        dirty |= def(c, "country.redstoneGating.blockCraftLvl0", rs.blockCraftLvl0());
        dirty |= def(c, "country.redstoneGating.blockUseLvl0", rs.blockUseLvl0());
        dirty |= def(c, "country.redstoneGating.blockCraftLvl1", rs.blockCraftLvl1());
        dirty |= def(c, "country.redstoneGating.blockUseLvl1", rs.blockUseLvl1());

        dirty |= def(c, "country.redstoneGating.errmsgLvl0", rs.errmsgLvl0());
        dirty |= def(c, "country.redstoneGating.errmsgLvl1", rs.errmsgLvl1());

        var tq = TntQuarryCfg.defaults();
        dirty |= def(c, "country.tntQuarry.enabled", tq.enabled());
        dirty |= def(c, "country.tntQuarry.permBase", tq.permBase());
        dirty |= def(c, "country.tntQuarry.dupWhitelist", tq.dupWhitelist());
        dirty |= def(c, "country.tntQuarry.licenseOres", tq.licenseOres());
        dirty |= def(c, "country.tntQuarry.licenseMaxExtra", tq.licenseMaxExtra());
        dirty |= def(c, "country.tntQuarry.licenseChancePerExtra", tq.licenseChancePerExtra());

        return dirty;
    }

    public static CountryCfg read(FileConfiguration c) {
        var d = defaults();

        var apD = AntiPhantomCfg.defaults();
        var antiPhantom = new AntiPhantomCfg(
                c.getBoolean("country.antiPhantom.enabled", apD.enabled()),
                str(c, "country.antiPhantom.permBase", apD.permBase())
        );

        var eD = EffectsCfg.defaults();
        var dpD = DustProtectionCfg.defaults();
        var dust = new DustProtectionCfg(
                c.getBoolean("country.effects.dustProtection.enabled", dpD.enabled()),
                str(c, "country.effects.dustProtection.permBase", dpD.permBase()),
                c.getInt("country.effects.dustProtection.minY", dpD.minY()),
                c.getInt("country.effects.dustProtection.durationTicks", dpD.durationTicks())
        );

        var effects = new EffectsCfg(
                c.getBoolean("country.effects.enabled", eD.enabled()),
                c.getLong("country.effects.reapplyCooldownMs", eD.reapplyCooldownMs()),
                c.getInt("country.effects.effectsMaxLevel", eD.effectsMaxLevel()),
                c.getInt("country.effects.effectTicks", eD.effectTicks()),
                str(c, "country.effects.permHaste", eD.permHaste()),
                str(c, "country.effects.permSpeed", eD.permSpeed()),
                str(c, "country.effects.permResist", eD.permResist()),
                dust
        );

        var chD = ChurchCfg.defaults();
        var bellD = BellCfg.defaults();
        var bell = new BellCfg(
                c.getBoolean("country.church.bell.enabled", bellD.enabled()),
                str(c, "country.church.bell.permBase", bellD.permBase()),
                c.getInt("country.church.bell.stayMinutes", bellD.stayMinutes()),
                c.getInt("country.church.bell.cooldownMinutes", bellD.cooldownMinutes()),
                c.getBoolean("country.church.bell.sfx", bellD.sfx())
        );

        var pilD = PilgrimageCfg.defaults();
        var pilgr = new PilgrimageCfg(
                c.getBoolean("country.church.pilgrimage.enabled", pilD.enabled()),
                str(c, "country.church.pilgrimage.permBase", pilD.permBase()),
                c.getInt("country.church.pilgrimage.stayMinutes", pilD.stayMinutes()),
                c.getInt("country.church.pilgrimage.cooldownMinutes", pilD.cooldownMinutes()),
                strList(c, "country.church.pilgrimage.effects", pilD.effects()),
                c.getInt("country.church.pilgrimage.amplifier", pilD.amplifier()),
                c.getInt("country.church.pilgrimage.buffMinutes", pilD.buffMinutes()),
                c.getBoolean("country.church.pilgrimage.sfx", pilD.sfx())
        );

        var church = new ChurchCfg(
                c.getBoolean("country.church.enabled", chD.enabled()),
                bell,
                pilgr
        );

        var fD = FarmlandCfg.defaults();
        var farmland = new FarmlandCfg(
                c.getBoolean("country.farmland.enabled", fD.enabled()),
                str(c, "country.farmland.permBase", fD.permBase()),
                (float) c.getDouble("country.farmland.bigFallThreshold", fD.bigFallThreshold())
        );

        var frD = FoodRationCfg.defaults();
        var foodRation = new FoodRationCfg(
                c.getBoolean("country.foodRation.enabled", frD.enabled()),
                str(c, "country.foodRation.permBase", frD.permBase()),
                c.getLong("country.foodRation.periodTicks", frD.periodTicks()),
                c.getLong("country.foodRation.minFoodLevel", frD.minFoodLevel()),
                c.getInt("country.foodRation.addFoodAmount", frD.addFoodAmount()),
                (float) c.getDouble("country.foodRation.addSaturation", frD.addSaturation())
        );

        var gfD = GoldenFoodCfg.defaults();
        var gf = new GoldenFoodCfg(
                c.getBoolean("country.goldenFood.enabled", gfD.enabled()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.goldenFood.permBase", gfD.permBase()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(c, "country.goldenFood.premiumFoods", gfD.premiumFoods()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.goldenFood.msgConsume", gfD.msgConsume()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.goldenFood.msgCraft", gfD.msgCraft()),
                c.getBoolean("country.goldenFood.sfx", gfD.sfx())
        );

        var lsD = LivestockCfg.defaults();
        var livestock = new LivestockCfg(
                c.getBoolean("country.livestock.enabled", lsD.enabled()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.livestock.permBase", lsD.permBase()),
                c.getDouble("country.livestock.speedPercent", lsD.speedPercent()),
                c.getDouble("country.livestock.doubleChancePercent", lsD.doubleChancePercent())
        );

        var nbD = NetheriteBeaconGatingCfg.defaults();
        var nb = new NetheriteBeaconGatingCfg(
                c.getBoolean("country.netheriteBeaconGating.enabled", nbD.enabled()),
                str(c, "country.netheriteBeaconGating.netheritePermBase", nbD.netheritePermBase()),
                str(c, "country.netheriteBeaconGating.beaconPermBase", nbD.beaconPermBase()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(
                        c, "country.netheriteBeaconGating.blockCraft", nbD.blockCraft()
                ),
                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(
                        c, "country.netheriteBeaconGating.blockUse", nbD.blockUse()
                ),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(
                        c, "country.netheriteBeaconGating.errmsgNetherite", nbD.errmsgNetherite()
                ),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(
                        c, "country.netheriteBeaconGating.errmsgBeacon", nbD.errmsgBeacon()
                )
        );

        var rsD = RedstoneGatingCfg.defaults();
        var rs = new RedstoneGatingCfg(
                c.getBoolean("country.redstoneGating.enabled", rsD.enabled()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.redstoneGating.permBase", rsD.permBase()),

                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(c, "country.redstoneGating.blockCraftLvl0", rsD.blockCraftLvl0()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(c, "country.redstoneGating.blockUseLvl0", rsD.blockUseLvl0()),

                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(c, "country.redstoneGating.blockCraftLvl1", rsD.blockCraftLvl1()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.strList(c, "country.redstoneGating.blockUseLvl1", rsD.blockUseLvl1()),

                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.redstoneGating.errmsgLvl0", rsD.errmsgLvl0()),
                com.frammy.unitylauncher.upgrades.config.CfgIO.str(c, "country.redstoneGating.errmsgLvl1", rsD.errmsgLvl1())
        );

        var tqD = TntQuarryCfg.defaults();
        var tq = new TntQuarryCfg(
                c.getBoolean("country.tntQuarry.enabled", tqD.enabled()),
                str(c, "country.tntQuarry.permBase", tqD.permBase()),
                strList(c, "country.tntQuarry.dupWhitelist", tqD.dupWhitelist()),
                strList(c, "country.tntQuarry.licenseOres", tqD.licenseOres()),
                c.getInt("country.tntQuarry.licenseMaxExtra", tqD.licenseMaxExtra()),
                c.getDouble("country.tntQuarry.licenseChancePerExtra", tqD.licenseChancePerExtra())
        );

        return new CountryCfg(antiPhantom, effects, church, farmland, foodRation, gf, livestock, nb, rs, tq);
    }

    public record AntiPhantomCfg(boolean enabled, String permBase) {
        public static AntiPhantomCfg defaults() { return new AntiPhantomCfg(true, "unity.country.anti_phantom"); }
    }

    public record EffectsCfg(
            boolean enabled,
            long reapplyCooldownMs,
            int effectsMaxLevel,
            int effectTicks,
            String permHaste,
            String permSpeed,
            String permResist,
            DustProtectionCfg dustProtection
    ) {
        public static EffectsCfg defaults() {
            return new EffectsCfg(
                    true,
                    2000L,
                    2,
                    20 * 5,
                    "unity.zone.haste",
                    "unity.zone.speed",
                    "unity.zone.resistance",
                    DustProtectionCfg.defaults()
            );
        }
    }

    public record DustProtectionCfg(boolean enabled, String permBase, int minY, int durationTicks) {
        public static DustProtectionCfg defaults() { return new DustProtectionCfg(true, "unity.zone.dust_protection", 60, 20 * 60 * 3); }
    }

    public record ChurchCfg(boolean enabled, BellCfg bell, PilgrimageCfg pilgrimage) {
        public static ChurchCfg defaults() { return new ChurchCfg(true, BellCfg.defaults(), PilgrimageCfg.defaults()); }
    }

    public record BellCfg(boolean enabled, String permBase, int stayMinutes, int cooldownMinutes, boolean sfx) {
        public static BellCfg defaults() { return new BellCfg(true, "unity.church.bell", 3, 30, true); }
    }

    public record PilgrimageCfg(
            boolean enabled,
            String permBase,
            int stayMinutes,
            int cooldownMinutes,
            List<String> effects,
            int amplifier,
            int buffMinutes,
            boolean sfx
    ) {
        public static PilgrimageCfg defaults() {
            return new PilgrimageCfg(
                    true,
                    "unity.church.pilgrimage",
                    5,
                    180,
                    List.of("REGENERATION", "ABSORPTION", "SPEED"),
                    0,
                    10,
                    true
            );
        }
    }

    public record FarmlandCfg(boolean enabled, String permBase, float bigFallThreshold) {
        public static FarmlandCfg defaults() { return new FarmlandCfg(true, "unity.country.farmland", 3.5f); }
    }

    public record FoodRationCfg(
            boolean enabled,
            String permBase,
            long periodTicks,
            long minFoodLevel,
            int addFoodAmount,
            float addSaturation
    ) {
        public static FoodRationCfg defaults() {
            return new FoodRationCfg(true, "unity.country.food_ration", 20L * 2, 6, 1, 0.5f);
        }
    }

    public record GoldenFoodCfg(
            boolean enabled,
            String permBase,
            java.util.List<String> premiumFoods,
            String msgConsume,
            String msgCraft,
            boolean sfx
    ) {
        public static GoldenFoodCfg defaults() {
            return new GoldenFoodCfg(
                    true,
                    "unity.country.golden_food",
                    java.util.List.of("GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE"),
                    "§cТвоя страна ещё не открыла доступ к золотой еде.",
                    "§cТвоя страна ещё не открыла крафт золотой еды.",
                    true
            );
        }
    }

    public record LivestockCfg(
            boolean enabled,
            String permBase,
            double speedPercent,
            double doubleChancePercent
    ) {
        public static LivestockCfg defaults() {
            return new LivestockCfg(
                    true,
                    "unity.country.livestock",
                    25.0,
                    10.0
            );
        }
    }

    public record NetheriteBeaconGatingCfg(
            boolean enabled,

            String netheritePermBase,
            String beaconPermBase,

            java.util.List<String> blockCraft,
            java.util.List<String> blockUse,

            String errmsgNetherite,
            String errmsgBeacon
    ) {
        public static NetheriteBeaconGatingCfg defaults() {
            return new NetheriteBeaconGatingCfg(
                    true,

                    "unity.gating.netherite",
                    "unity.gating.beacon",

                    java.util.List.of(
                            "NETHERITE_INGOT",
                            "NETHERITE_BLOCK",
                            "NETHERITE_SWORD",
                            "NETHERITE_PICKAXE",
                            "NETHERITE_AXE",
                            "NETHERITE_SHOVEL",
                            "NETHERITE_HOE",
                            "NETHERITE_HELMET",
                            "NETHERITE_CHESTPLATE",
                            "NETHERITE_LEGGINGS",
                            "NETHERITE_BOOTS",
                            "BEACON"
                    ),

                    java.util.List.of(
                            "NETHERITE_BLOCK",
                            "BEACON",
                            "NETHERITE_HELMET",
                            "NETHERITE_CHESTPLATE",
                            "NETHERITE_LEGGINGS",
                            "NETHERITE_BOOTS"
                    ),

                    "§cТвоя страна ещё не открыла Незерит.",
                    "§cТвоя страна ещё не открыла Маяк."
            );
        }
    }

    public record RedstoneGatingCfg(
            boolean enabled,
            String permBase,

            java.util.List<String> blockCraftLvl0,
            java.util.List<String> blockUseLvl0,

            java.util.List<String> blockCraftLvl1,
            java.util.List<String> blockUseLvl1,

            String errmsgLvl0,
            String errmsgLvl1
    ) {
        public static RedstoneGatingCfg defaults() {
            return new RedstoneGatingCfg(
                    true,
                    "unity.upgrade.redstone",

                    // lvl0: полностью закрыто
                    java.util.List.of(
                            "REDSTONE",
                            "REDSTONE_TORCH",
                            "REDSTONE_BLOCK",
                            "REPEATER",
                            "COMPARATOR",
                            "OBSERVER",
                            "PISTON",
                            "STICKY_PISTON",
                            "DISPENSER",
                            "DROPPER",
                            "HOPPER"
                    ),
                    java.util.List.of(
                            "REDSTONE",
                            "REDSTONE_TORCH",
                            "REPEATER",
                            "COMPARATOR",
                            "OBSERVER",
                            "PISTON",
                            "STICKY_PISTON",
                            "DISPENSER",
                            "DROPPER",
                            "HOPPER"
                    ),

                    // lvl1: частично открыто (пример — оставляем “сложное” на lvl2)
                    java.util.List.of(
                            "OBSERVER",
                            "STICKY_PISTON"
                    ),
                    java.util.List.of(
                            "OBSERVER",
                            "STICKY_PISTON"
                    ),

                    "§cТвоя страна ещё не открыла редстоун.",
                    "§eЭта часть редстоуна доступна со 2 уровня."
            );
        }
    }

    public record TntQuarryCfg(
            boolean enabled,
            String permBase,
            List<String> dupWhitelist,
            List<String> licenseOres,
            int licenseMaxExtra,
            double licenseChancePerExtra
    ) {
        public static TntQuarryCfg defaults() {
            return new TntQuarryCfg(
                    true,
                    "unity.tnt.quarry",
                    List.of(
                            "STONE=0.25",
                            "DEEPSLATE=0.10"
                    ),
                    List.of(
                            "COAL_ORE",
                            "DEEPSLATE_COAL_ORE",
                            "IRON_ORE",
                            "DEEPSLATE_IRON_ORE"
                    ),
                    3,
                    0.20
            );
        }
    }
}
