package com.frammy.unitylauncher.upgrades;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class UpgradesConfig {
    public final boolean DEBUG;

    // psych support
    public final int psychSupportLuckTicks;

    // redstone
    public record RedstoneLevelCfg(String perm, String errmsg, Set<Material> allowed) {}

    public final RedstoneLevelCfg rsL1, rsL2;

    // golden food
    public final String goldenFoodPerm, goldenFoodMsgConsume, goldenFoodMsgCraft;
    public final Set<Material> premiumFoods;

    // furnace ore bonus
    public final String furnacePerm, furnaceErrmsg;
    public final double furnaceChance;
    public final boolean furnaceSfx;
    public final Set<Material> furnaceOutputs;

    // furnace heat boost
    public final String furnaceHeatPerm, furnaceHeatErrmsg;
    public final int furnaceHeatRadius;
    public final double furnaceHeatMaxPct;

    // hopper
    public final String hopperSmartPermBase, hopperErrmsg;
    public final int hopperTurboBudgetPerRun, hopperTurboTaskPeriodTicks;
    public final long hopperTurboEligibilityCacheMs;
    public final boolean hopperSlowModeLvl0;

    // tnt
    public final String tntPerm, tntErrmsg;
    public final Map<Material, Double> tntDupWhitelist;

    // effects
    public final long reapplyCooldownMs;
    public final int effectTicks;
    public final String permHaste, permSpeed, permResist;
    public final int effectsMaxLevel;

    // anti-phantom
    public final String antiPhantomPermBase, antiPhantomErrmsg;

    // brew fast
    public final String brewPerm, brewErrmsg;
    public final double brewSpeedPercent;

    // Signs
    public final String atmFile;
    public final String atmPerm;
    public final String signsPrefix;
    public final String msgSignUpdatedAll, msgSignBankCreated;
    public final String errNotOwner, errNoMoney, errInvalidFormat;
    public final double costShopListUpdate, costBankCreate;

    // church — Peace Bell
    public final String churchBellPerm;
    public final boolean churchBellSfx;
    public final int churchBellStayMinutes;
    public final int churchBellCooldownMinutes;

    // church — Pilgrimage
    public final String churchPilgrimagePerm;
    public final boolean churchPilgrimageSfx;
    public final int churchPilgrimageStayMinutes;
    public final int churchPilgrimageBuffMinutes;
    public final int churchPilgrimageAmplifier; // 0 => уровень I
    public final java.util.Set<String> churchPilgrimageEffects; // названия Bukkit-эффектов
    public final int churchPilgrimageCooldownMinutes; // 0 = без кулдауна

    // eco fuel (Экотопливо)
    public final String ecoFuelPerm;
    public final double ecoFuelMultiplier;

    // dust protection (Пыльезащита)
    public final String dustProtectionPerm;
    public final int dustProtectionMinY;
    public final int dustProtectionDurationTicks;

    // industrial recycler (Промышленный переработчик)
    public final String recyclerPerm;
    public final Set<Material> recyclerInputs;
    public final Map<Material, Double> recyclerExtraDrops;

    // farmland
    public final String farmlandPermBase, farmlandErrmsg;
    public final float farmlandBigFallThreshold;

    // livestock (Скотоводство+ и Скотоводство++)
    public final String livestockPlusPerm, livestockDoublePerm;
    public final double livestockPlusSpeedPercent;      // % ускорения роста
    public final double livestockDoubleChancePercent;   // % шанса двойни

    // crops low light / hydroponics
    public final String cropsLLPermBase;
    public final int    cropsLL_ScanPeriodTicks;
    public final int    cropsLL_PerZoneBudget;
    public final double cropsLL_MaxPercent;

    // bee pollination
    public final String beePollinationPerm;
    public final int    beePollinationRadius;
    public final double beePollinationBonusPercent;

    // colony / колониальные апгрейды
    public final String colonyZonePerm;

    // outpost (Форпост)
    public final String outpostPerm;
    public final double outpostCullPercent;

    // food ration (Продовольственный пай)
    public final String foodRationPerm;
    public final int foodRationPeriodTicks;
    public final int foodRationDurationTicks;
    public final int foodRationAmplifier;

    // TNT license (ТНТ-Лицензия)
    public final String tntLicensePerm;
    public final Set<Material> tntLicenseOres;
    public final double tntLicenseChancePerExtra; // 0..1
    public final int tntLicenseMaxExtra;

    private UpgradesConfig(JavaPlugin plugin, FileConfiguration c) {
        // 1) дефолты — чтобы при первом запуске получить полный файл
        addDefaults(c);
        plugin.saveConfig();

        DEBUG = c.getBoolean("upgrades.debug", false);

        psychSupportLuckTicks = Math.max(0, c.getInt("upgrades.psychSupport.luck_ticks", 600));

        rsL1 = new RedstoneLevelCfg(
                c.getString("upgrades.redstone.level1.perm", "unity.upgrade.redstone.1"),
                color(c.getString("upgrades.redstone.level1.errmsg", "&cНужен апгрейд 'Редстоун I'.")),
                toMaterialSet(c.getStringList("upgrades.redstone.level1.allowed"))
        );
        rsL2 = new RedstoneLevelCfg(
                c.getString("upgrades.redstone.level2.perm", "unity.upgrade.redstone.2"),
                color(c.getString("upgrades.redstone.level2.errmsg", "&cНужен апгрейд 'Редстоун II'.")),
                toMaterialSet(c.getStringList("upgrades.redstone.level2.allowed"))
        );

        goldenFoodPerm       = c.getString("upgrades.goldenFood.perm", "unity.food.golden.1");
        goldenFoodMsgConsume = color(c.getString("upgrades.goldenFood.errmsg_consume", "&cНужен апгрейд &6Золотая пища I"));
        goldenFoodMsgCraft   = color(c.getString("upgrades.goldenFood.errmsg_craft",   "&cКрафт заблокирован. Нужен &6Золотая пища I"));
        premiumFoods         = toMaterialSet(c.getStringList("upgrades.goldenFood.premiumFoods"));

        furnacePerm     = c.getString("upgrades.furnace.perm", "unity.furnace.ore_boost.1");
        furnaceErrmsg   = color(c.getString("upgrades.furnace.errmsg", ""));
        furnaceChance   = c.getDouble("upgrades.furnace.chance", 0.15);
        furnaceSfx      = c.getBoolean("upgrades.furnace.sfx", true);
        furnaceOutputs  = toMaterialSet(c.getStringList("upgrades.furnace.outputs"));

        furnaceHeatPerm   = c.getString("upgrades.furnaceHeat.perm", "unity.furnace.boost.1");
        furnaceHeatErrmsg = color(c.getString("upgrades.furnaceHeat.errmsg", ""));
        furnaceHeatRadius = Math.max(1, c.getInt("upgrades.furnaceHeat.radius", 6));
        furnaceHeatMaxPct = Math.max(0.0, c.getDouble("upgrades.furnaceHeat.max_percent", 15.0));

        hopperSmartPermBase        = c.getString("upgrades.hopper.perm", "unity.hopper.smart");
        hopperErrmsg               = color(c.getString("upgrades.hopper.errmsg", ""));
        hopperTurboBudgetPerRun    = c.getInt("upgrades.hopper.turboBudgetPerRun", 50);
        hopperTurboTaskPeriodTicks = c.getInt("upgrades.hopper.turboTaskPeriodTicks", 2);
        hopperTurboEligibilityCacheMs = c.getLong("upgrades.hopper.turboEligibilityCacheMs", 1000L);
        hopperSlowModeLvl0         = c.getBoolean("upgrades.hopper.slowMode_lvl0_everyOtherTick", true);

        tntPerm         = c.getString("upgrades.tntQuarry.perm", "unity.tnt.quarry.1");
        tntErrmsg       = color(c.getString("upgrades.tntQuarry.errmsg", ""));
        tntDupWhitelist = toMaterialDoubleMap(c.getConfigurationSection("upgrades.tntQuarry.dupWhitelist"));

        reapplyCooldownMs = c.getLong("upgrades.effects.reapplyCooldownMs", 4000);
        int seconds       = c.getInt("upgrades.effects.effectSeconds", 12);
        effectTicks       = 20 * Math.max(1, seconds);
        permHaste         = c.getString("upgrades.effects.perms.haste", "unity.zone.haste");
        permSpeed         = c.getString("upgrades.effects.perms.speed", "unity.zone.speed");
        permResist        = c.getString("upgrades.effects.perms.resistance", "unity.zone.resistance");
        effectsMaxLevel   = c.getInt("upgrades.effects.maxLevel", 2);

        antiPhantomPermBase = Optional.ofNullable(c.getString("upgrades.antiPhantom.perm_base"))
                .filter(s -> !s.isBlank()).orElse("unity.anti.phantom");
        antiPhantomErrmsg   = color(c.getString("upgrades.antiPhantom.errmsg", ""));

        brewPerm        = c.getString("upgrades.brew.perm", "unity.brew.speed");
        brewErrmsg      = color(c.getString("upgrades.brew.errmsg", ""));
        brewSpeedPercent= Math.max(0.0, c.getDouble("upgrades.brew.speed_percent", 25.0));

        // Signs (для отдельного файла с табличками)
        atmFile        = c.getString("upgrades.atm.file", "atm_limits.yml");

        String atmCfg = c.getString("upgrades.atm.permBase", null);
        if (atmCfg == null) {
            atmCfg = c.getString("upgrades.atm.limit", "unity.atm.limit.5"); // старый ключ/дефолт
        }
        if (atmCfg.matches(".+\\.\\d+$")) {
            // срежем завершающую цифру уровня → "unity.atm.limit"
            atmCfg = atmCfg.replaceFirst("\\.\\d+$", "");
        }
        atmPerm    = atmCfg.isBlank() ? "unity.atm.limit" : atmCfg;

        signsPrefix        = color(c.getString("signs.log.prefix", "&7[&6UL&7/&aSigns&7]&r "));
        msgSignUpdatedAll  = color(c.getString("signs.log.success.updated_all_shop_list", "&aОбновлены все &eSHOP_LIST &aтаблички в радиусе."));
        msgSignBankCreated = color(c.getString("signs.log.success.created_bank_sign", "&aБанковская табличка создана."));
        errNotOwner        = color(c.getString("signs.log.error.not_owner", "&cВы не владелец этой зоны."));
        errNoMoney         = color(c.getString("signs.log.error.no_money", "&cНедостаточно средств: нужно &e%cost%&c."));
        errInvalidFormat   = color(c.getString("signs.log.error.invalid_format", "&cНеверный формат таблички."));
        costShopListUpdate = c.getDouble("signs.balance.shop_list_update_cost", 25.0);
        costBankCreate     = c.getDouble("signs.balance.bank_create_cost", 50.0);

        // ===== Church: Peace Bell & Pilgrimage =====
        churchBellPerm            = c.getString("upgrades.church.peaceBell.perm", "unity.church.bell.1");
        churchBellSfx             = c.getBoolean("upgrades.church.peaceBell.sfx", true);
        churchBellStayMinutes     = Math.max(1, c.getInt("upgrades.church.peaceBell.stay_minutes", 2));
        churchBellCooldownMinutes = Math.max(0, c.getInt("upgrades.church.peaceBell.cooldown_minutes", 20));

        churchPilgrimagePerm            = c.getString("upgrades.church.pilgrimage.perm", "unity.church.pilgrimage.1");
        churchPilgrimageSfx             = c.getBoolean("upgrades.church.pilgrimage.sfx", true);
        churchPilgrimageStayMinutes     = Math.max(1, c.getInt("upgrades.church.pilgrimage.stay_minutes", 5));
        churchPilgrimageBuffMinutes     = Math.max(1, c.getInt("upgrades.church.pilgrimage.buff_minutes", 30));
        churchPilgrimageAmplifier       = Math.max(0, c.getInt("upgrades.church.pilgrimage.amplifier", 0));
        churchPilgrimageCooldownMinutes = Math.max(0, c.getInt("upgrades.church.pilgrimage.cooldown_minutes", 0));
        churchPilgrimageEffects         = new java.util.LinkedHashSet<>(c.getStringList("upgrades.church.pilgrimage.effects"));

        ecoFuelPerm      = c.getString("upgrades.ecoFuel.perm", "unity.furnace.ecofuel");
        ecoFuelMultiplier = Math.max(1.0, c.getDouble("upgrades.ecoFuel.multiplier", 2.0));

        dustProtectionPerm = c.getString("upgrades.dustProtection.perm", "unity.industrial.dust");
        dustProtectionMinY = c.getInt("upgrades.dustProtection.min_y", 40);
        int dustSec        = c.getInt("upgrades.dustProtection.duration_seconds", 30);
        dustProtectionDurationTicks = 20 * Math.max(1, dustSec);

        recyclerPerm     = c.getString("upgrades.recycler.perm", "unity.industrial.recycler");
        recyclerInputs   = toMaterialSet(c.getStringList("upgrades.recycler.inputs"));
        recyclerExtraDrops = toMaterialDoubleMap(c.getConfigurationSection("upgrades.recycler.extraDrops"));

        farmlandPermBase       = c.getString("upgrades.farmland.permBase", "unity.zone.farmland");
        farmlandErrmsg         = color(c.getString("upgrades.farmland.errmsg", ""));
        farmlandBigFallThreshold = (float) c.getDouble("upgrades.farmland.bigFallThreshold", 5.0);

        // livestock
        livestockPlusPerm            = c.getString("upgrades.livestock.plus.perm", "unity.fields.livestock.plus");
        livestockPlusSpeedPercent    = Math.max(0.0, c.getDouble("upgrades.livestock.plus.speed_percent", 25.0));
        livestockDoublePerm          = c.getString("upgrades.livestock.double.perm", "unity.fields.livestock.double");
        livestockDoubleChancePercent = Math.max(0.0, c.getDouble("upgrades.livestock.double.chance_percent", 5.0));

        // hydroponics / crops low light
        cropsLLPermBase      = c.getString("upgrades.cropsLowLight.perm", "unity.crops.lowlight");
        cropsLL_ScanPeriodTicks = Math.max(5, c.getInt("upgrades.cropsLowLight.scan_period_ticks", 40));
        cropsLL_PerZoneBudget   = Math.max(1, c.getInt("upgrades.cropsLowLight.per_zone_budget", 24));
        cropsLL_MaxPercent      = Math.max(0.0, c.getDouble("upgrades.cropsLowLight.max_percent", 15.0));

        // bee pollination
        beePollinationPerm        = c.getString("upgrades.beePollination.perm", "unity.fields.bee.pollination");
        beePollinationRadius      = Math.max(1, c.getInt("upgrades.beePollination.radius", 5));
        beePollinationBonusPercent= Math.max(0.0, c.getDouble("upgrades.beePollination.bonus_percent", 10.0));

        // === Colony zone / Колониальный апгрейд ===
        colonyZonePerm = c.getString("upgrades.colonyZone.perm", "unity.zone.colony");

        // === Outpost / Форпост ===
        outpostPerm = c.getString("upgrades.outpost.perm", "unity.colony.outpost");
        outpostCullPercent = Math.max(0.0, Math.min(100.0,
                c.getDouble("upgrades.outpost.cull_percent", 15.0)));

        // === Food Ration / Продовольственный пай ===
        foodRationPerm = c.getString("upgrades.foodRation.perm", "unity.colony.food.ration");
        int foodPeriodSec = c.getInt("upgrades.foodRation.period_seconds", 30);
        foodRationPeriodTicks = 20 * Math.max(1, foodPeriodSec);
        int foodDurSec = c.getInt("upgrades.foodRation.duration_seconds", 5);
        foodRationDurationTicks = 20 * Math.max(1, foodDurSec);
        foodRationAmplifier = Math.max(0, c.getInt("upgrades.foodRation.amplifier", 0)); // 0 = Saturation I

        // === TNT License / ТНТ-Лицензия ===
        tntLicensePerm = c.getString("upgrades.tntLicense.perm", "unity.tnt.license");
        tntLicenseOres = toMaterialSet(c.getStringList("upgrades.tntLicense.ores"));
        tntLicenseChancePerExtra = Math.max(0.0, Math.min(1.0,
                c.getDouble("upgrades.tntLicense.chance_per_extra", 0.4)));
        tntLicenseMaxExtra = Math.max(1, c.getInt("upgrades.tntLicense.max_extra", 2));

    }

    public static UpgradesConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();      // создаст ресурс, если его нет
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();
        return new UpgradesConfig(plugin, cfg);
    }

    private static void addDefaults(FileConfiguration c) {
        // === Общие ===
        c.addDefault("upgrades.debug", false);
        c.addDefault("upgrades.psychSupport.luck_ticks", 600);
        c.addDefault("upgrades.psychSupport.description",
                "Психподдержка: даёт всем игрокам эффект LUCK I на указанное количество тиков после респавна.");

        // === Industrial core: право на INDUSTRIAL-зоны ===
        c.addDefault("upgrades.industrialZone.description",
                "Индустриальная зона: разрешает покупать и устанавливать зоны типа INDUSTRIAL. " +
                        "Проверяется в логике создания зон.");
        c.addDefault("upgrades.industrialZone.perm", "unity.zone.industrial");

        // === Redstone ===
        c.addDefault("upgrades.redstone.description",
                "Базовый/Продвинутый редстоун: ограничивает, какие редстоун-блоки можно ставить без апгрейда. " +
                        "Список allowed определяет доступные блоки на каждом уровне.");
        c.addDefault("upgrades.redstone.level1.perm", "unity.upgrade.redstone.1");
        c.addDefault("upgrades.redstone.level1.errmsg", "&cНужен апгрейд 'Редстоун I'.");
        c.addDefault("upgrades.redstone.level1.allowed", Arrays.asList(
                "REDSTONE", "REPEATER", "REDSTONE_TORCH"
        ));

        c.addDefault("upgrades.redstone.level2.perm", "unity.upgrade.redstone.2");
        c.addDefault("upgrades.redstone.level2.errmsg", "&cНужен апгрейд 'Редстоун II'.");
        c.addDefault("upgrades.redstone.level2.allowed", Arrays.asList(
                "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON"
        ));

        // === Golden Food ===
        c.addDefault("upgrades.goldenFood.description",
                "Золотая пища: без апгрейда игрок не может есть/крафтить премиум-еду (premiumFoods). " +
                        "С апгрейдом ограничения снимаются.");
        c.addDefault("upgrades.goldenFood.perm", "unity.food.golden.1");
        c.addDefault("upgrades.goldenFood.errmsg_consume", "&cНужен апггрейд &6Золотая пища I");
        c.addDefault("upgrades.goldenFood.errmsg_craft", "&cКрафт заблокирован. Нужен &6Золотая пища I");
        c.addDefault("upgrades.goldenFood.premiumFoods", Arrays.asList(
                "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "GOLDEN_CARROT"
        ));

        // === Furnace Ore Bonus ===
        c.addDefault("upgrades.furnace.description",
                "Бонус к выплавке руды: при апгрейде печи в стране/колонии дают шанс (chance) " +
                        "получить +1 к результату выплавки для указанных outputs. sfx включает визуальные эффекты.");
        c.addDefault("upgrades.furnace.perm", "unity.furnace.ore_boost.1");
        c.addDefault("upgrades.furnace.errmsg", "");
        c.addDefault("upgrades.furnace.chance", 0.15);
        c.addDefault("upgrades.furnace.sfx", true);
        c.addDefault("upgrades.furnace.outputs", Arrays.asList(
                "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "NETHERITE_SCRAP"
        ));

        // === Furnace Heat Boost (Геотермальный буст) ===
        c.addDefault("upgrades.furnaceHeat.description",
                "Геотермальный буст: печи рядом с лавой/магмой работают быстрее. " +
                        "radius — радиус поиска источника тепла, max_percent — максимум ускорения.");
        c.addDefault("upgrades.furnaceHeat.perm", "unity.furnace.boost.1");
        c.addDefault("upgrades.furnaceHeat.errmsg", "");
        c.addDefault("upgrades.furnaceHeat.radius", 6);
        c.addDefault("upgrades.furnaceHeat.max_percent", 15.0);

        // === EcoFuel (Экотопливо) ===
        c.addDefault("upgrades.ecoFuel.description",
                "Экотопливо: при апгрейде страны топливо БАМБУК в печах горит дольше (multiplier раз).");
        c.addDefault("upgrades.ecoFuel.perm", "unity.furnace.ecofuel");
        c.addDefault("upgrades.ecoFuel.multiplier", 2.0);

        // === Hopper Smart / Turbo (Умные воронки) ===
        c.addDefault("upgrades.hopper.description",
                "Умные воронки: Lvl 0 (нет апгрейда) — замедление воронок (slowMode_lvl0_everyOtherTick). " +
                        "Lvl 2 и зона INDUSTRIAL — турбо-режим, runTurboTick() делает дополнительные операции переноса.");
        c.addDefault("upgrades.hopper.perm", "unity.hopper.smart");
        c.addDefault("upgrades.hopper.errmsg", "");
        c.addDefault("upgrades.hopper.turboBudgetPerRun", 50);
        c.addDefault("upgrades.hopper.turboTaskPeriodTicks", 2);
        c.addDefault("upgrades.hopper.turboEligibilityCacheMs", 1000L);
        c.addDefault("upgrades.hopper.slowMode_lvl0_everyOtherTick", true);

        // === TNT Quarry ===
        c.addDefault("upgrades.tntQuarry.description",
                "Промышленный взрыв (TNT Quarry): при взрыве TNT в стране с апгрейдом даёт шанс дополнительного дропа " +
                        "для whitelisted блоков (dupWhitelist: материал -> шанс).");
        c.addDefault("upgrades.tntQuarry.perm", "unity.tnt.quarry.1");
        c.addDefault("upgrades.tntQuarry.errmsg", "");
        c.addDefault("upgrades.tntQuarry.dupWhitelist", new java.util.HashMap<String, Double>() {{
            put("DIAMOND_ORE", 0.10);
            put("RAW_IRON_BLOCK", 0.12);
            put("RAW_GOLD_BLOCK", 0.12);
            put("RAW_COPPER_BLOCK", 0.12);
            put("ANCIENT_DEBRIS", 0.05);
        }});

        // === Industrial Recycler (Промышленный переработчик) ===
        c.addDefault("upgrades.recycler.description",
                "Промышленный переработчик: при добыче 'мусорных' блоков (inputs) в индустриальной зоне " +
                        "даёт шанс дополнительного дропа из extraDrops. Работает только при апгрейде страны.");
        c.addDefault("upgrades.recycler.perm", "unity.industrial.recycler");
        c.addDefault("upgrades.recycler.inputs", Arrays.asList(
                "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE"
        ));
        c.addDefault("upgrades.recycler.extraDrops", new java.util.LinkedHashMap<String, Double>() {{
            put("IRON_INGOT", 0.02);
            put("GOLD_INGOT", 0.01);
            put("DIAMOND", 0.005);
            put("ANCIENT_DEBRIS", 0.001);
        }});

        // === Effects (Мотивация и прочее) ===
        c.addDefault("upgrades.effects.description",
                "Эффекты в своей стране/колонии: при нахождении в зоне своего государства " +
                        "выдаются эффекты HASTE/SPEED/RESISTANCE. Пермишены берутся из upgrades.effects.perms.*, " +
                        "maxLevel задаёт потолок уровня.");
        c.addDefault("upgrades.effects.reapplyCooldownMs", 4000);
        c.addDefault("upgrades.effects.effectSeconds", 12);
        c.addDefault("upgrades.effects.perms.haste", "unity.zone.haste");         // «Мотивация»
        c.addDefault("upgrades.effects.perms.speed", "unity.zone.speed");
        c.addDefault("upgrades.effects.perms.resistance", "unity.zone.resistance");
        c.addDefault("upgrades.effects.maxLevel", 2);
        c.addDefault("upgrades.motivation.description",
                "Мотивация: эффект Haste в своей стране/колонии. Использует perms.haste из блока upgrades.effects.");

        // === Dust Protection (Пыльезащита) ===
        c.addDefault("upgrades.dustProtection.description",
                "Пыльезащита: рабочим в индустриальной зоне при спуске ниже min_y выдаётся NIGHT_VISION " +
                        "на duration_seconds секунд, если у страны есть апгрейд.");
        c.addDefault("upgrades.dustProtection.perm", "unity.industrial.dust");
        c.addDefault("upgrades.dustProtection.min_y", 40);
        c.addDefault("upgrades.dustProtection.duration_seconds", 30);

        // === Anti-Phantom ===
        c.addDefault("upgrades.antiPhantom.description",
                "Анти-фантом: в своей стране/колонии с апгрейдом фантомы не спавнятся рядом с игроком. " +
                        "Замораживает TIME_SINCE_REST для защищённых игроков.");
        c.addDefault("upgrades.antiPhantom.perm_base", "unity.anti.phantom");
        c.addDefault("upgrades.antiPhantom.errmsg", "");

        // === Brew Fast (Алхимия) ===
        c.addDefault("upgrades.brew.description",
                "Алхимия: в стране с апгрейдом стойки варят зелья быстрее на указанный процент (speed_percent). " +
                        "Реализовано в runBrewTick().");
        c.addDefault("upgrades.brew.perm", "unity.brew.speed");
        c.addDefault("upgrades.brew.errmsg", "");
        c.addDefault("upgrades.brew.speed_percent", 25.0);

        // === Brand (Маркировка бренда) ===
        c.addDefault("upgrades.brand.description",
                "Маркировка бренда: позволяет использовать механику бренда (лор с производителем) для предметов. " +
                        "Проверяется в BrandCommand / соответствующей логике.");
        c.addDefault("upgrades.brand.perm", "unity.industrial.brand");

        // === Netherite (Незеритовое улучшение) ===
        c.addDefault("upgrades.netherite.description",
                "Незеритовое улучшение: открывает доступ к незериту (крафт/использование). " +
                        "Требует отдельной логики ограничений.");
        c.addDefault("upgrades.netherite.perm", "unity.industrial.netherite");

        // === Beacon (Маяк) ===
        c.addDefault("upgrades.beacon.description",
                "Маяк: открывает возможность крафта и установки маяков. Проверяется отдельной логикой.");
        c.addDefault("upgrades.beacon.perm", "unity.industrial.beacon");

        // === Energy Saving (Энергосбережение) ===
        c.addDefault("upgrades.energySaving.description",
                "Энергосбережение: уменьшает стоимость активности редстоуна/механизмов в системе экономики чанков. " +
                        "multiplier < 1.0 уменьшает расход.");
        c.addDefault("upgrades.energySaving.perm", "unity.industrial.energy");
        c.addDefault("upgrades.energySaving.multiplier", 0.7);

        // === Loader (Шлюз-погрузчик) ===
        c.addDefault("upgrades.loader.description",
                "Шлюз-погрузчик: ускоряет загрузку/выгрузку вагонеток с сундуком рядом с медными блоками. " +
                        "Реализуется в отдельной логике работы рельсов.");
        c.addDefault("upgrades.loader.perm", "unity.industrial.loader");

        // === Signs / ATM ===
        c.addDefault("upgrades.atm.description",
                "ATM/банкоматы: отдельный YAML atm_limits.yml описывает лимиты. " +
                        "atm.permBase задаёт базовый префикс пермишенов лимитов. cost* — цены действий с табличками.");
        c.addDefault("upgrades.atm.file", "atm_limits.yml");
        c.addDefault("upgrades.atm.limit", "unity.atm.limit.5");
        c.addDefault("signs.log.prefix", "&7[&6UL&7/&aSigns&7]&r ");
        c.addDefault("signs.log.success.updated_all_shop_list", "&aОбновлены все &eSHOP_LIST &aтаблички в радиусе.");
        c.addDefault("signs.log.success.created_bank_sign", "&aБанковская табличка создана.");
        c.addDefault("signs.log.error.not_owner", "&cВы не владелец этой зоны.");
        c.addDefault("signs.log.error.no_money", "&cНедостаточно средств: нужно &e%cost%&c.");
        c.addDefault("signs.log.error.invalid_format", "&cНеверный формат таблички.");
        c.addDefault("signs.balance.shop_list_update_cost", 25.0);
        c.addDefault("signs.balance.bank_create_cost", 50.0);

        // === Church: Мирный колокол ===
        c.addDefault("upgrades.church.peaceBell.description",
                "Мирный колокол: после нахождения в церкви не менее stay_minutes " +
                        "снимает с игрока негативные эффекты и запускает cooldown_minutes пер-церковь.");
        c.addDefault("upgrades.church.peaceBell.perm", "unity.church.bell.1");
        c.addDefault("upgrades.church.peaceBell.sfx", true);
        c.addDefault("upgrades.church.peaceBell.stay_minutes", 2);
        c.addDefault("upgrades.church.peaceBell.cooldown_minutes", 20);

        // === Church: Паломничество ===
        c.addDefault("upgrades.church.pilgrimage.description",
                "Паломничество: если игрок достаточно долго находится в церкви и является гражданином страны зоны, " +
                        "он получает случайный положительный эффект из списка effects на buff_minutes. " +
                        "cooldown_minutes — индивидуальный кулдаун, amplifier — уровень эффекта.");
        c.addDefault("upgrades.church.pilgrimage.perm", "unity.church.pilgrimage.1");
        c.addDefault("upgrades.church.pilgrimage.sfx", true);
        c.addDefault("upgrades.church.pilgrimage.stay_minutes", 5);
        c.addDefault("upgrades.church.pilgrimage.buff_minutes", 30);
        c.addDefault("upgrades.church.pilgrimage.amplifier", 0);
        c.addDefault("upgrades.church.pilgrimage.cooldown_minutes", 0);
        c.addDefault("upgrades.church.pilgrimage.effects", Arrays.asList(
                "LUCK", "NIGHT_VISION", "WATER_BREATHING", "FIRE_RESISTANCE", "FAST_DIGGING"
        ));

        // === Fields zone / Сельхоз-зона ===
        c.addDefault("upgrades.fieldsZone.description",
                "Сельхоз-зона: разрешает покупать и устанавливать зоны полей/теплиц (GREENHOUSE / FIELDS). " +
                        "Проверяется в логике создания зон.");
        c.addDefault("upgrades.fieldsZone.perm", "unity.zone.fields");

        // === Шеф (элитная еда) — завязан на goldenFood ===
        c.addDefault("upgrades.chef.description",
                "Шеф: элитная еда (золотая морковь, золотое яблоко и т.д.). " +
                        "Реализация через upgrades.goldenFood.* и апгрейд страны goldenFood.");

        // === Livestock / Скотоводство ===
        c.addDefault("upgrades.livestock.plus.description",
                "Скотоводство+: детёныши животных в сельхоз-зонах растут быстрее на указанный процент. " +
                        "Реализуется через уменьшение времени до взросления.");
        c.addDefault("upgrades.livestock.plus.perm", "unity.fields.livestock.plus");
        c.addDefault("upgrades.livestock.plus.speed_percent", 25.0); // 20–30%, остановимся на 25 по умолчанию

        c.addDefault("upgrades.livestock.double.description",
                "Скотоводство++: при размножении животных в сельхоз-зонах есть шанс родить двойню.");
        c.addDefault("upgrades.livestock.double.perm", "unity.fields.livestock.double");
        c.addDefault("upgrades.livestock.double.chance_percent", 5.0);

        // === Farmland Protection / Нетрамбованная почва ===
        c.addDefault("upgrades.farmland.description",
                "Нетрамбованная почва: защита грядок от вытоптывания. " +
                        "Lv1 — защита от малых падений, Lv2 — полная защита. " +
                        "bigFallThreshold — минимальная высота сильного удара.");
        c.addDefault("upgrades.farmland.permBase", "unity.zone.farmland");
        c.addDefault("upgrades.farmland.errmsg", "");
        c.addDefault("upgrades.farmland.bigFallThreshold", 5.0);

        // === Hydroponics / Гидропоника (cropsLowLight) ===
        c.addDefault("upgrades.cropsLowLight.description",
                "Гидропоника: рост растений при низком уровне света под стеклянной крышей в теплицах (GREENHOUSE). " +
                        "Работает только при наличии апгрейда у страны.");
        c.addDefault("upgrades.cropsLowLight.perm", "unity.crops.lowlight");
        c.addDefault("upgrades.cropsLowLight.scan_period_ticks", 40);
        c.addDefault("upgrades.cropsLowLight.per_zone_budget", 24);
        c.addDefault("upgrades.cropsLowLight.max_percent", 15.0);

        // === Bee Pollination / Пчелиное опыление ===
        c.addDefault("upgrades.beePollination.description",
                "Пчелиное опыление: если рядом с грядками в теплице есть улей/улья, " +
                        "шанс ростка от гидропоники увеличивается на бонусный процент.");
        c.addDefault("upgrades.beePollination.perm", "unity.fields.bee.pollination");
        c.addDefault("upgrades.beePollination.radius", 5);
        c.addDefault("upgrades.beePollination.bonus_percent", 10.0);

        // === Colony zone / Колониальный апгрейд ===
        c.addDefault("upgrades.colonyZone.description",
                "Колониальный апгрейд: разрешает создавать зоны типа COLONY. " +
                        "Проверяется в логике создания зон.");
        c.addDefault("upgrades.colonyZone.perm", "unity.zone.colony");

        // === Outpost / Форпост ===
        c.addDefault("upgrades.outpost.description",
                "Форпост: усиленная защита от рейдов в колониях. " +
                        "Часть рейдеров из волн не спавнится, что делает волны ощутимо слабее.");
        c.addDefault("upgrades.outpost.perm", "unity.colony.outpost");
        c.addDefault("upgrades.outpost.cull_percent", 15.0); // ~минус 15% рейдеров

        // === Food Ration / Продовольственный пай ===
        c.addDefault("upgrades.foodRation.description",
                "Продовольственный пай: граждане в своих колониях периодически получают эффект Saturation I, " +
                        "что замедляет расход голода.");
        c.addDefault("upgrades.foodRation.perm", "unity.colony.food.ration");
        c.addDefault("upgrades.foodRation.period_seconds", 30);
        c.addDefault("upgrades.foodRation.duration_seconds", 5);
        c.addDefault("upgrades.foodRation.amplifier", 0); // Saturation I

        // === TNT License / ТНТ-Лицензия ===
        c.addDefault("upgrades.tntLicense.description",
                "ТНТ-Лицензия: в колониях взрывы TNT дают шанс дополнительных 'удачных' выпадений руды, " +
                        "подобно зачарованию Fortune.");
        c.addDefault("upgrades.tntLicense.perm", "unity.tnt.license");
        c.addDefault("upgrades.tntLicense.ores", Arrays.asList(
                "COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "REDSTONE_ORE",
                "LAPIS_ORE", "DIAMOND_ORE", "EMERALD_ORE",
                "DEEPSLATE_COAL_ORE", "DEEPSLATE_IRON_ORE", "DEEPSLATE_COPPER_ORE",
                "DEEPSLATE_GOLD_ORE", "DEEPSLATE_REDSTONE_ORE", "DEEPSLATE_LAPIS_ORE",
                "DEEPSLATE_DIAMOND_ORE", "DEEPSLATE_EMERALD_ORE"
        ));
        c.addDefault("upgrades.tntLicense.chance_per_extra", 0.4); // 40% шанс на +1 за попытку
        c.addDefault("upgrades.tntLicense.max_extra", 2);


        // Финализируем дефолты
        c.options().copyDefaults(true);
    }

    // ==== utils ====
    private static Set<Material> toMaterialSet(List<String> list) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String s : list) { Material m = safeMat(s); if (m != null) out.add(m); }
        return out;
    }
    private static Map<Material, Double> toMaterialDoubleMap(org.bukkit.configuration.ConfigurationSection sec) {
        Map<Material, Double> out = new HashMap<>(); if (sec == null) return out;
        for (String k : sec.getKeys(false)) { Material m = safeMat(k); if (m != null) out.put(m, sec.getDouble(k, 0.0)); }
        return out;
    }
    private static Material safeMat(String name) { try { return Material.valueOf(name.trim()); } catch (Exception e) { return null; } }
    private static String color(String s) { return org.bukkit.ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
}
