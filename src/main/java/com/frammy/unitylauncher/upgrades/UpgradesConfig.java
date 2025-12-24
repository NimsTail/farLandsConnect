package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Ⓕ  FARLANDS / UNITYLAUNCHER  —  UpgradesConfig
 *
 * Главные принципы:
 * 1) Мы НЕ перезаписываем существующий конфиг. Добавляем только отсутствующие ключи.
 * 2) Значения perm для апгрейдов-уровней храним БЕЗ ".1/.2" (base prefix).
 *    Потому что UpgradeCondition.countryMaxLevel() сам добавляет ".1..N".
 * 3) Есть минимальная "миграция": если новый ключ отсутствует, а старый есть — переносим.
 *    Также нормализуем perm-base: срезаем ".<число>" на конце.
 *
 * ВАЖНО ПРО КОММЕНТАРИИ:
 * Bukkit YamlConfiguration НЕ сохраняет YAML-комментарии (# ...) надёжно.
 * Поэтому описания храним отдельными ключами "*.description" (как в старом конфиге).
 */
public final class UpgradesConfig {

    // -------------------------------
    // Singleton
    // -------------------------------
    private static UpgradesConfig INSTANCE;

    public static UpgradesConfig get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("UpgradesConfig.get() called before load().");
        }
        return INSTANCE;
    }

    public static UpgradesConfig load(UnityLauncher plugin) {
        INSTANCE = new UpgradesConfig(plugin);
        return INSTANCE;
    }

    // -------------------------------
    // Internal
    // -------------------------------
    private final UnityLauncher plugin;
    private final File file;
    private final YamlConfiguration cfg;

    private static final Pattern TRAILING_DOT_NUMBER = Pattern.compile("\\.\\d+$");

    private UpgradesConfig(UnityLauncher plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "upgrades.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);

        // 1) Defaults
        applyHeader();
        addDefaults(cfg);

        // 2) Migrations / compatibility
        migrateLegacyKeys(cfg);
        normalizePermBases(cfg);

        // 3) Ensure defaults are copied only for missing values
        cfg.options().copyDefaults(true);

        // 4) Save (only fills missing keys + keeps existing)
        saveQuiet();

        // 5) Read all fields
        // Core
        this.debug = cfg.getBoolean("core.debug", false);

        // --- commands
        this.brandPerm = cfg.getString("commands.brand.perm", "unity.brand");

        // Zone unlocks (zone purchase/build permissions)
        this.industrialZonePerm = cfg.getString("zones.unlock.industrial.perm", "unity.zone.industrial");
        this.fieldsZonePerm     = cfg.getString("zones.unlock.fields.perm", "unity.zone.fields");
        this.colonyZonePerm     = cfg.getString("zones.unlock.colony.perm", "unity.zone.colony");
        this.parkZonePerm       = cfg.getString("zones.unlock.park.perm", "unity.zone.park");
        this.bankZonePerm       = cfg.getString("zones.unlock.bank.perm", "unity.zone.bank");
        this.hospitalZonePerm   = cfg.getString("zones.unlock.hospital.perm", "unity.zone.hospital");
        this.libraryZonePerm    = cfg.getString("zones.unlock.library.perm", "unity.zone.library");
        this.churchZonePerm     = cfg.getString("zones.unlock.church.perm", "unity.zone.church");

        // -------------------------------
        // INDUSTRIAL / LISTENER
        // -------------------------------

        // ===== redstone (L1/L2)
        this.rsL1 = readRedstoneLevel("industrial.redstone.level1");
        this.rsL2 = readRedstoneLevel("industrial.redstone.level2");

        // ===== golden food
        this.goldenFoodPerm = cfg.getString("fields.goldenFood.perm", "unity.food.golden");
        this.goldenFoodMsgConsume = cfg.getString("fields.goldenFood.msg_consume", "&cУ вашей страны нет права на эту еду.");
        this.goldenFoodMsgCraft   = cfg.getString("fields.goldenFood.msg_craft", "&cУ вашей страны нет права крафтить эту еду.");

        this.premiumFoods = new HashSet<>();
        for (String s : cfg.getStringList("fields.goldenFood.premiumFoods")) {
            try { premiumFoods.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }

        // ===== netherite / beacon locks
        this.netheritePerm = cfg.getString("industrial.netherite.perm", "unity.netherite");
        this.netheriteErrmsg = cfg.getString("industrial.netherite.errmsg", "&cВаша страна ещё не открыла незерит.");
        this.beaconPerm = cfg.getString("industrial.beacon.perm", "unity.beacon");
        this.beaconErrmsg = cfg.getString("industrial.beacon.errmsg", "&cВаша страна ещё не открыла маяки.");

        // ===== energy saving (economy tick load multiplier)
        this.energySavingPerm = cfg.getString("industrial.energySaving.perm", "unity.industrial.energy");
        this.energySavingMultiplier = cfg.getDouble("industrial.energySaving.multiplier", 0.7);

        // ===== furnace ore bonus
        this.furnacePerm = cfg.getString("industrial.furnaceOreBonus.perm", "unity.furnace.ore_boost");
        this.furnaceChance = cfg.getDouble("industrial.furnaceOreBonus.chance", 0.10);
        this.furnaceSfx = cfg.getBoolean("industrial.furnaceOreBonus.sfx", true);

        this.furnaceOutputs = new HashSet<>();
        for (String s : cfg.getStringList("industrial.furnaceOreBonus.outputs")) {
            try { furnaceOutputs.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }

        // ===== furnace heat boost
        this.furnaceHeatPerm = cfg.getString("industrial.furnaceHeat.perm", "unity.furnace.boost");
        this.furnaceHeatRadius = cfg.getInt("industrial.furnaceHeat.radius", 5);
        this.furnaceHeatMaxPct = cfg.getDouble("industrial.furnaceHeat.max_percent", 40.0);

        // ===== eco fuel
        this.ecoFuelPerm = cfg.getString("industrial.ecoFuel.perm", "unity.ecofuel");
        this.ecoFuelMultiplier = cfg.getDouble("industrial.ecoFuel.multiplier", 1.5);

        // ===== hopper smart / turbo
        this.hopperSmartPermBase = cfg.getString("industrial.hopper.permBase", "unity.hopper");
        this.hopperSlowModeLvl0 = cfg.getBoolean("industrial.hopper.slow_mode_lvl0", true);

        this.hopperTurboTaskPeriodTicks = cfg.getLong("industrial.hopper.turbo_task_period_ticks", 1L);
        this.hopperTurboBudgetPerRun = cfg.getInt("industrial.hopper.turbo_budget_per_run", 250);
        this.hopperTurboEligibilityCacheMs = cfg.getLong("industrial.hopper.turbo_eligibility_cache_ms", 1500L);

        // (твои новые поля остаются — вдруг где-то ещё используются)
        this.hopperSlowdownLevel0 = cfg.getInt("industrial.hopper.slowdown_level0_ticks", 8);
        this.hopperTurboLevel2 = cfg.getInt("industrial.hopper.turbo_level2_ticks", 1);

        // ===== TNT quarry + license
        this.tntPerm = cfg.getString("industrial.tntQuarry.perm", "unity.tnt.quarry");
        this.tntDupWhitelist = new EnumMap<>(Material.class);
        var sec = cfg.getConfigurationSection("industrial.tntQuarry.dupWhitelist");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    Material m = Material.valueOf(k.toUpperCase(Locale.ROOT));
                    double prob = cfg.getDouble("industrial.tntQuarry.dupWhitelist." + k, 0.0);
                    tntDupWhitelist.put(m, prob);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        this.tntLicensePerm = cfg.getString("colony.tntLicense.perm", "unity.tnt.license");
        this.tntLicenseOres = new HashSet<>();
        for (String s : cfg.getStringList("colony.tntLicense.ores")) {
            try { tntLicenseOres.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        this.tntLicenseMaxExtra = cfg.getInt("colony.tntLicense.max_extra", 2);
        this.tntLicenseChancePerExtra = cfg.getDouble("colony.tntLicense.chance_per_extra", 0.25);

        // ===== effects system
        this.reapplyCooldownMs = cfg.getLong("effects.reapply_cooldown_ms", 700L);
        this.effectsMaxLevel = cfg.getInt("effects.max_level", 2);
        this.effectTicks = cfg.getInt("effects.effect_ticks", 220);

        this.permHaste = cfg.getString("effects.perm_haste", "unity.effects.haste");
        this.permSpeed = cfg.getString("effects.perm_speed", "unity.effects.speed");
        this.permResist = cfg.getString("effects.perm_resist", "unity.effects.resist");

        // ===== psych support
        this.psychSupportLuckTicks = cfg.getInt("hospital.psychSupport.luck_duration_ticks", 200);

        // ===== farmland protection
        this.farmlandPermBase = cfg.getString("fields.farmlandProtect.permBase", "unity.farmland.protect");
        this.farmlandBigFallThreshold = (float) cfg.getDouble("fields.farmlandProtect.big_fall_threshold", 1.2);

        // ===== greenhouse low light
        this.cropsLLPermBase = cfg.getString("fields.greenhouseLowLight.permBase", "unity.greenhouse.lowlight");
        this.cropsLL_ScanPeriodTicks = cfg.getLong("fields.greenhouseLowLight.scan_period_ticks", 40L);
        this.cropsLL_PerZoneBudget = cfg.getInt("fields.greenhouseLowLight.per_zone_budget", 40);
        this.cropsLL_MaxPercent = cfg.getDouble("fields.greenhouseLowLight.max_percent", 30.0);

        // ===== bee pollination
        this.beePollinationPerm = cfg.getString("fields.beePollination.perm", "unity.bee.pollination");
        this.beePollinationBonusPercent = cfg.getDouble("fields.beePollination.bonus_percent", 10.0);
        this.beePollinationRadius = cfg.getInt("fields.beePollination.radius", 8);

        // ===== anti phantom
        this.antiPhantomPermBase = cfg.getString("colony.antiPhantom.permBase", "unity.antiphantom");

        // ===== brew speed
        this.brewPerm = cfg.getString("industrial.brewSpeed.perm", "unity.brew.speed");
        this.brewSpeedPercent = cfg.getDouble("industrial.brewSpeed.speed_percent", 30.0);

        // ===== church
        this.churchBellPerm = cfg.getString("church.peaceBell.perm", "unity.church.bell");
        this.churchBellStayMinutes = cfg.getInt("church.peaceBell.stay_minutes", 1);
        this.churchBellCooldownMinutes = cfg.getInt("church.peaceBell.cooldown_minutes", 10);
        this.churchBellSfx = cfg.getBoolean("church.peaceBell.sfx", true);

        this.churchPilgrimagePerm = cfg.getString("church.pilgrimage.perm", "unity.church.pilgrimage");
        this.churchPilgrimageStayMinutes = cfg.getInt("church.pilgrimage.stay_minutes", 1);
        this.churchPilgrimageBuffMinutes = cfg.getInt("church.pilgrimage.buff_minutes", 10);
        this.churchPilgrimageCooldownMinutes = cfg.getInt("church.pilgrimage.cooldown_minutes", 30);
        this.churchPilgrimageSfx = cfg.getBoolean("church.pilgrimage.sfx", true);
        this.churchPilgrimageAmplifier = cfg.getInt("church.pilgrimage.amplifier", 0);
        this.churchPilgrimageEffects = cfg.getStringList("church.pilgrimage.effects");

        // ===== dust protection (ticks)
        this.dustProtectionPerm = cfg.getString("industrial.dustProtection.perm", "unity.dust.protection");
        this.dustProtectionMinY = cfg.getInt("industrial.dustProtection.min_y", 32);
        this.dustProtectionDurationTicks = cfg.getInt("industrial.dustProtection.duration_ticks", 200);

        // ===== recycler
        this.recyclerPerm = cfg.getString("industrial.recycler.perm", "unity.recycler");
        this.recyclerDropChance = cfg.getDouble("industrial.recycler.dropChance", 0.15);

        this.recyclerInputs = new HashSet<>();
        for (String s : cfg.getStringList("industrial.recycler.inputs")) {
            try { recyclerInputs.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        this.recyclerExtraDrops = new EnumMap<>(Material.class);
        var ex = cfg.getConfigurationSection("industrial.recycler.extraDrops");
        if (ex != null) {
            for (String k : ex.getKeys(false)) {
                try {
                    Material m = Material.valueOf(k.toUpperCase(Locale.ROOT));
                    double prob = cfg.getDouble("industrial.recycler.extraDrops." + k, 0.0);
                    recyclerExtraDrops.put(m, prob);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // ===== outpost
        this.outpostPerm = cfg.getString("colony.outpost.perm", "unity.colony.outpost");
        this.outpostCullPercent = cfg.getDouble("colony.outpost.raid_cull_percent", 30.0); // percent 0..100

        // ===== livestock old names (aliases)
        this.livestockPlusPerm = cfg.getString("fields.livestock.plus_perm", "unity.livestock.plus");
        this.livestockPlusSpeedPercent = cfg.getDouble("fields.livestock.plus_speed_percent", 25.0);

        this.livestockDoublePerm = cfg.getString("fields.livestock.double_perm", "unity.livestock.double");
        this.livestockDoubleChancePercent = cfg.getDouble("fields.livestock.double_chance_percent", 5.0);

        // ===== loader (minecart)
        this.loaderPerm = cfg.getString("industrial.loader.perm", "unity.loader");
        this.loaderRadius = cfg.getInt("industrial.loader.radius", 4);
        this.loaderSpeedMultiplier = cfg.getDouble("industrial.loader.speed_multiplier", 2.0);

        // ===== food ration
        this.foodRationPerm = cfg.getString("colony.foodRation.perm", "unity.colony.foodration");
        this.foodRationPeriodTicks = cfg.getLong("colony.foodRation.period_ticks", 200L);
        this.foodRationDurationTicks = cfg.getInt("colony.foodRation.duration_ticks", 40);
        this.foodRationAmplifier = cfg.getInt("colony.foodRation.amplifier", 0);

        // -------------------------------
        // BANK
        // -------------------------------
        this.safeDepositPerm = cfg.getString("bank.safeDeposit.perm", "unity.bank.safedeposit");
        this.safeDepositMaxPerPlayer = cfg.getInt("bank.safeDeposit.max_per_player", 2);

        this.atmPermBase = cfg.getString("bank.atmNetwork.permBase", "unity.bank.atm");
        this.atmFeeInBank = cfg.getDouble("bank.atmNetwork.fee_in_bank", 0.0);
        this.atmFeeInCountry = cfg.getDouble("bank.atmNetwork.fee_in_country", 0.01);
        this.atmFeeForeign = cfg.getDouble("bank.atmNetwork.fee_foreign", 0.03);

        this.freeTransferPerm = cfg.getString("bank.freeTransfer.perm", "unity.bank.freeTransfer");
        this.depositInterestPerm = cfg.getString("bank.depositInterest.perm", "unity.bank.interest");
        this.depositInterestDailyPercent = cfg.getDouble("bank.depositInterest.daily_percent", 0.10);

        // -------------------------------
        // PARK
        // -------------------------------
        this.parkGardenerPerm = cfg.getString("park.gardenerHut.perm", "unity.park.gardener");
        this.parkGrowthChanceBonus = cfg.getDouble("park.gardenerHut.growth_chance_bonus", 0.20);

        this.parkQuietGuardPerm = cfg.getString("park.quietGuard.perm", "unity.park.quietguard");
        this.parkQuietGuardRadius = cfg.getInt("park.quietGuard.radius", 12);

        this.parkPondBedsPerm = cfg.getString("park.pondBeds.perm", "unity.park.pondbeds");
        this.parkSaturationBonus = cfg.getDouble("park.pondBeds.saturation_bonus", 0.6);
        this.parkSaturationCooldownMs = cfg.getLong("park.pondBeds.cooldown_ms", 8000L);

        this.parkQuietHourPerm = cfg.getString("park.quietHour.perm", "unity.park.quiethour");

        this.parkBenchesPerm = cfg.getString("park.benches.perm", "unity.park.benches");
        this.parkBenchRadius = cfg.getInt("park.benches.radius", 8);
        this.parkBenchRegenSeconds = cfg.getInt("park.benches.regen_seconds", 4);

        // -------------------------------
        // HOSPITAL
        // -------------------------------
        this.hospitalPsychSupportPerm = cfg.getString("hospital.psychSupport.perm", "unity.hospital.psych");
        this.hospitalPsychSupportLuckDurationTicks = cfg.getInt("hospital.psychSupport.luck_duration_ticks", 200);

        this.hospitalDietPerm = cfg.getString("hospital.diet.perm", "unity.hospital.diet");
        this.hospitalDietSaturationBonus = cfg.getDouble("hospital.diet.saturation_bonus", 0.8);

        this.hospitalRegenPulsePerm = cfg.getString("hospital.regenPulse.perm", "unity.hospital.regen");
        this.hospitalRegenPulsePeriodTicks = cfg.getLong("hospital.regenPulse.period_ticks", 200L);
        this.hospitalRegenPulseDurationTicks = cfg.getInt("hospital.regenPulse.duration_ticks", 80);
        this.hospitalRegenPulseAmplifier = cfg.getInt("hospital.regenPulse.amplifier", 0);

        this.hospitalSanitaryPerm = cfg.getString("hospital.sanitaryZone.perm", "unity.hospital.sanitary");
        this.hospitalSanitaryRadius = cfg.getInt("hospital.sanitaryZone.radius", 50);
        this.hospitalSanitarySpawnMultiplier = cfg.getDouble("hospital.sanitaryZone.spawn_multiplier", 0.5);

        this.hospitalBloodGiftPerm = cfg.getString("hospital.bloodGift.perm", "unity.hospital.bloodgift");
        this.hospitalBloodGiftAbsorptionEnabled =
                cfg.getBoolean("hospital.bloodGift.absorption.enabled", true);
        this.hospitalBloodGiftAbsorptionAmplifier =
                cfg.getInt("hospital.bloodGift.absorption.amplifier", 0);

        this.hospitalBloodGiftRegenEnabled =
                cfg.getBoolean("hospital.bloodGift.regen.enabled", true);
        this.hospitalBloodGiftRegenTicks =
                cfg.getInt("hospital.bloodGift.regen.ticks", 20 * 10);
        this.hospitalBloodGiftRegenAmplifier =
                cfg.getInt("hospital.bloodGift.regen.amplifier", 0);
        this.hospitalBloodGiftDurationMinutes =
                cfg.getInt("hospital.bloodGift.duration_minutes", 10);

        this.hospitalTriagePerm = cfg.getString("hospital.triage.perm", "unity.hospital.triage");
        this.hospitalTriageReducePercent = cfg.getInt("hospital.triage.reduce_percent", 50);

        // Safe Zone (damage reduction inside hospital)
        this.hospitalSafeZonePerm = cfg.getString("hospital.safeZone.perm", "unity.hospital.safezone");
        this.hospitalSafeZoneDamageMultiplier = cfg.getDouble("hospital.safeZone.damage_multiplier", 0.80);

        // -------------------------------
        // LIBRARY
        // -------------------------------
        this.libraryScrollsPerm = cfg.getString("library.scrollsOfEconomy.perm", "unity.library.scrolls");
        this.libraryScrollsExpCostMultiplier = cfg.getDouble("library.scrollsOfEconomy.exp_cost_multiplier", 0.80);

        this.libraryCalmPerm = cfg.getString("library.calm.perm", "unity.library.calm");
        this.libraryCalmChanceToCancelHunger = cfg.getDouble("library.calm.cancel_hunger_chance", 0.25);

        this.libraryEducationPerm = cfg.getString("library.educationInitiative.perm", "unity.library.education");
        this.libraryEducationRewardMultiplier = cfg.getDouble("library.educationInitiative.reward_multiplier", 1.25);

        // -------------------------------
        // STATE
        // -------------------------------
        this.stateContractsPerm = cfg.getString("state.contracts.perm", "unity.state.contracts");
        this.stateContractsMaxActive = cfg.getInt("state.contracts.max_active", 3);

        this.stateLuxuryTaxPerm = cfg.getString("state.luxuryTax.perm", "unity.state.luxuryTax");
        this.stateLuxuryTaxPercent = cfg.getDouble("state.luxuryTax.tax_percent", 0.05);
        this.stateLuxuryTaxItems = new HashSet<>();
        for (String s : cfg.getStringList("state.luxuryTax.items")) {
            try { this.stateLuxuryTaxItems.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }

        this.stateTollRoadsPerm = cfg.getString("state.tollRoads.perm", "unity.state.toll");
        this.stateTollRoadsFee = cfg.getDouble("state.tollRoads.fee", 5.0);

        this.stateExportRebatePerm = cfg.getString("state.exportRebate.perm", "unity.state.exportRebate");
        this.stateExportRebatePercent = cfg.getDouble("state.exportRebate.percent", 0.10);

        this.stateResourceFocusPerm = cfg.getString("state.resourceFocus.perm", "unity.state.resourceFocus");
        this.stateResourceFocusBonusPercent = cfg.getInt("state.resourceFocus.bonus_percent", 20);
        this.stateResourceFocusPenaltyPercent = cfg.getInt("state.resourceFocus.penalty_percent", 10);

        this.statePropagandaPerm = cfg.getString("state.propaganda.perm", "unity.state.propaganda");
        this.statePropagandaPeriodTicks = cfg.getLong("state.propaganda.period_ticks", 1200L);
        this.statePropagandaTemplates = cfg.getStringList("state.propaganda.templates");

        this.stateCensorshipPerm = cfg.getString("state.censorship.perm", "unity.state.censorship");
        this.stateCensorshipTriggerWords = cfg.getStringList("state.censorship.trigger_words");

        this.stateCurfewPerm = cfg.getString("state.curfew.perm", "unity.state.curfew");
        this.stateCurfewStartHour = cfg.getInt("state.curfew.start_hour", 23);
        this.stateCurfewEndHour   = cfg.getInt("state.curfew.end_hour", 6);

        this.stateRepairGuildPerm = cfg.getString("state.repairGuild.perm", "unity.state.repairGuild");
        this.stateRepairGuildReturnPercent = cfg.getInt("state.repairGuild.return_percent", 10);

        this.stateTradingZonePerm = cfg.getString("state.tradingZone.perm", "unity.state.tradingZone");
        this.stateTradingZoneExtraSlots = cfg.getInt("state.tradingZone.extra_slots", 2);

        this.stateSamplerPerm = cfg.getString("state.sampler.perm", "unity.state.sampler");
        this.stateSamplerCooldownHours = cfg.getInt("state.sampler.cooldown_hours", 24);

        this.stateHappyHourPerm = cfg.getString("state.happyHour.perm", "unity.state.happyHour");
        this.stateHappyHourDiscountPercent = cfg.getInt("state.happyHour.discount_percent", 15);

    }

    // -------------------------------
    // Public fields (read-only)
    // -------------------------------

    // Core
    public final boolean debug;

    // --- commands
    public final String brandPerm;

    // Zone unlock
    public final String industrialZonePerm;
    public final String fieldsZonePerm;
    public final String colonyZonePerm;
    public final String parkZonePerm;
    public final String bankZonePerm;
    public final String hospitalZonePerm;
    public final String libraryZonePerm;
    public final String churchZonePerm;

    // Redstone L1/L2
    public record RedstoneLevelCfg(String perm, String errmsg, Set<Material> allowed) {}
    public final RedstoneLevelCfg rsL1, rsL2;

    // Golden food
    public final String goldenFoodPerm;
    public final String goldenFoodMsgConsume;
    public final String goldenFoodMsgCraft;
    public final Set<Material> premiumFoods;

    // Netherite / beacon
    public final String netheritePerm, netheriteErrmsg;
    public final String beaconPerm, beaconErrmsg;

    // Energy saving (chunk economy tick load)
    public final String energySavingPerm;
    public final double energySavingMultiplier;

    // Furnace ore bonus
    public final String furnacePerm;
    public final double furnaceChance;
    public final boolean furnaceSfx;
    public final Set<Material> furnaceOutputs;

    // Furnace heat
    public final String furnaceHeatPerm;
    public final int furnaceHeatRadius;
    public final double furnaceHeatMaxPct;

    // Eco fuel
    public final String ecoFuelPerm;
    public final double ecoFuelMultiplier;

    // Hopper
    public final String hopperSmartPermBase;
    public final boolean hopperSlowModeLvl0;
    public final long hopperTurboTaskPeriodTicks;
    public final int hopperTurboBudgetPerRun;
    public final long hopperTurboEligibilityCacheMs;

    // (kept from your newer design)
    public final int hopperSlowdownLevel0;
    public final int hopperTurboLevel2;

    // TNT
    public final String tntPerm;
    public final Map<Material, Double> tntDupWhitelist;

    public final String tntLicensePerm;
    public final Set<Material> tntLicenseOres;
    public final int tntLicenseMaxExtra;
    public final double tntLicenseChancePerExtra;

    // Effects system
    public final long reapplyCooldownMs;
    public final String permHaste;
    public final String permSpeed;
    public final String permResist;
    public final int effectsMaxLevel;
    public final int effectTicks;

    // Psych support
    public final int psychSupportLuckTicks;

    // Farmland
    public final String farmlandPermBase;
    public final float farmlandBigFallThreshold;

    // Greenhouse Low-Light
    public final String cropsLLPermBase;
    public final long cropsLL_ScanPeriodTicks;
    public final int cropsLL_PerZoneBudget;
    public final double cropsLL_MaxPercent;

    // Bee pollination
    public final String beePollinationPerm;
    public final double beePollinationBonusPercent;
    public final int beePollinationRadius;

    // Anti phantom
    public final String antiPhantomPermBase;

    // Brew
    public final String brewPerm;
    public final double brewSpeedPercent;

    // Church
    public final String churchBellPerm;
    public final int churchBellStayMinutes;
    public final boolean churchBellSfx;
    public final int churchBellCooldownMinutes;

    public final String churchPilgrimagePerm;
    public final int churchPilgrimageStayMinutes;
    public final int churchPilgrimageCooldownMinutes;
    public final boolean churchPilgrimageSfx;
    public final int churchPilgrimageAmplifier;
    public final int churchPilgrimageBuffMinutes;
    public final List<String> churchPilgrimageEffects;

    // Dust protection
    public final String dustProtectionPerm;
    public final int dustProtectionMinY;
    public final int dustProtectionDurationTicks;

    // Recycler
    public final String recyclerPerm;
    public final double recyclerDropChance;
    public final Set<Material> recyclerInputs;
    public final Map<Material, Double> recyclerExtraDrops;

    // Outpost
    public final String outpostPerm;
    public final double outpostCullPercent;

    // Livestock (old names expected by listener)
    public final String livestockPlusPerm;
    public final double livestockPlusSpeedPercent;
    public final String livestockDoublePerm;
    public final double livestockDoubleChancePercent;

    // Loader
    public final String loaderPerm;
    public final int loaderRadius;
    public final double loaderSpeedMultiplier;

    // Food ration
    public final String foodRationPerm;
    public final long foodRationPeriodTicks;
    public final int foodRationDurationTicks;
    public final int foodRationAmplifier;

    // Bank
    public final String safeDepositPerm;
    public final int safeDepositMaxPerPlayer;

    public final String atmPermBase; // base prefix
    public final double atmFeeInBank;
    public final double atmFeeInCountry;
    public final double atmFeeForeign;

    public final String freeTransferPerm;
    public final String depositInterestPerm;
    public final double depositInterestDailyPercent;

    // Park
    public final String parkGardenerPerm;
    public final double parkGrowthChanceBonus;

    public final String parkQuietGuardPerm;
    public final int parkQuietGuardRadius;

    public final String parkPondBedsPerm;
    public final double parkSaturationBonus;
    public final long parkSaturationCooldownMs;

    public final String parkQuietHourPerm;

    public final String parkBenchesPerm;
    public final int parkBenchRadius;
    public final int parkBenchRegenSeconds;

    // Hospital
    public final String hospitalPsychSupportPerm;
    public final int hospitalPsychSupportLuckDurationTicks;

    public final String hospitalDietPerm;
    public final double hospitalDietSaturationBonus;

    public final String hospitalRegenPulsePerm;
    public final long hospitalRegenPulsePeriodTicks;
    public final int hospitalRegenPulseDurationTicks;
    public final int hospitalRegenPulseAmplifier;

    public final String hospitalSanitaryPerm;
    public final int hospitalSanitaryRadius;
    public final double hospitalSanitarySpawnMultiplier;

    public final String hospitalBloodGiftPerm;
    public final boolean hospitalBloodGiftAbsorptionEnabled;
    public final int hospitalBloodGiftAbsorptionAmplifier; // clamp до 0

    public final boolean hospitalBloodGiftRegenEnabled;
    public final int hospitalBloodGiftRegenTicks;
    public final int hospitalBloodGiftRegenAmplifier;
    public final int hospitalBloodGiftDurationMinutes;

    public final String hospitalTriagePerm;
    public final int hospitalTriageReducePercent;

    public final String hospitalSafeZonePerm;
    public final double hospitalSafeZoneDamageMultiplier;

    // Library
    public final String libraryScrollsPerm;
    public final double libraryScrollsExpCostMultiplier;

    public final String libraryCalmPerm;
    public final double libraryCalmChanceToCancelHunger;

    public final String libraryEducationPerm;
    public final double libraryEducationRewardMultiplier;

    // State
    public final String stateContractsPerm;
    public final int stateContractsMaxActive;

    public final String stateLuxuryTaxPerm;
    public final double stateLuxuryTaxPercent;
    public final Set<Material> stateLuxuryTaxItems;

    public final String stateTollRoadsPerm;
    public final double stateTollRoadsFee;

    public final String stateExportRebatePerm;
    public final double stateExportRebatePercent;

    public final String stateResourceFocusPerm;
    public final int stateResourceFocusBonusPercent;
    public final int stateResourceFocusPenaltyPercent;

    public final String statePropagandaPerm;
    public final long statePropagandaPeriodTicks;
    public final List<String> statePropagandaTemplates;

    public final String stateCensorshipPerm;
    public final List<String> stateCensorshipTriggerWords;

    public final String stateCurfewPerm;
    public final int stateCurfewStartHour;
    public final int stateCurfewEndHour;

    public final String stateRepairGuildPerm;
    public final int stateRepairGuildReturnPercent;

    public final String stateTradingZonePerm;
    public final int stateTradingZoneExtraSlots;

    public final String stateSamplerPerm;
    public final int stateSamplerCooldownHours;

    public final String stateHappyHourPerm;
    public final int stateHappyHourDiscountPercent;

    // -------------------------------
    // Defaults + Header
    // -------------------------------
    private void applyHeader() {
        cfg.options().header(String.join("\n",
                "ⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻ",
                "Ⓕ   FARLANDS / UnityLauncher — Upgrades Configuration",
                "Ⓕ",
                "Ⓕ  Правила:",
                "Ⓕ   • Значения *.perm / *.permBase для апгрейдов-уровней — БЕЗ '.1'.",
                "Ⓕ     (Уровни добавляет код: base.1, base.2 ...)",
                "Ⓕ   • Этот файл НЕ перезаписывает существующие значения. Добавляет только отсутствующие ключи.",
                "Ⓕ   • Описания ключей лежат в отдельных *.description (это намеренно, из-за ограничений YamlConfiguration).",
                "Ⓕ",
                "Ⓕ  Группы:",
                "Ⓕ   core, commands, zones.unlock, industrial, fields, colony, bank, park, hospital, library, state, church, churchV2",
                "ⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻⒻ",
                ""
        ));
    }

    private static void addDefaults(YamlConfiguration c) {
        // --- core
        c.addDefault("core.debug", false);
        c.addDefault("core.description",
                "Общие настройки апгрейдов. debug=true включает более подробные логи (для отладки).");

        // --- commands
        c.addDefault("commands.brand.perm", "unity.brand");
        c.addDefault("commands.brand.description",
                "Brand Marking (/brand): пермишен на использование команды маркировки (лор 'произведено игроком').");

        // --- zones.unlock
        c.addDefault("zones.unlock.description",
                "Разрешения на покупку/создание зон. Эти perm обычно выдаются стране/колонии, чтобы открыть тип зоны.");

        c.addDefault("zones.unlock.industrial.perm", "unity.zone.industrial");
        c.addDefault("zones.unlock.industrial.description",
                "Industrial Zone: открывает возможность покупать/создавать зоны типа INDUSTRIAL.");

        c.addDefault("zones.unlock.fields.perm", "unity.zone.fields");
        c.addDefault("zones.unlock.fields.description",
                "Fields/Greenhouse Zone: открывает возможность покупать/создавать сельхоз-зоны (FIELDS/GREENHOUSE).");

        c.addDefault("zones.unlock.colony.perm", "unity.zone.colony");
        c.addDefault("zones.unlock.colony.description",
                "Colony Zone: открывает возможность покупать/создавать зоны типа COLONY.");

        c.addDefault("zones.unlock.park.perm", "unity.zone.park");
        c.addDefault("zones.unlock.park.description",
                "Park Zone: открывает возможность покупать/создавать зоны типа PARK.");

        c.addDefault("zones.unlock.bank.perm", "unity.zone.bank");
        c.addDefault("zones.unlock.bank.description",
                "Bank Zone: открывает возможность покупать/создавать зоны типа BANK.");

        c.addDefault("zones.unlock.hospital.perm", "unity.zone.hospital");
        c.addDefault("zones.unlock.hospital.description",
                "Hospital Zone: открывает возможность покупать/создавать зоны типа HOSPITAL.");

        c.addDefault("zones.unlock.library.perm", "unity.zone.library");
        c.addDefault("zones.unlock.library.description",
                "Library Zone: открывает возможность покупать/создавать зоны типа LIBRARY.");

        c.addDefault("zones.unlock.church.perm", "unity.zone.church");
        c.addDefault("zones.unlock.church.description",
                "Church Zone: открывает возможность покупать/создавать зоны типа CHURCH (если/когда включено в логике зон).");

        // --- industrial

        // Redstone levels
        c.addDefault("industrial.redstone.level1.perm", "unity.redstone.1");
        c.addDefault("industrial.redstone.level1.errmsg", "&cНужен апгрейд редстоуна L1.");
        c.addDefault("industrial.redstone.level1.allowed", List.of("PISTON", "STICKY_PISTON", "OBSERVER"));

        c.addDefault("industrial.redstone.level2.perm", "unity.redstone.2");
        c.addDefault("industrial.redstone.level2.errmsg", "&cНужен апгрейд редстоуна L2.");
        c.addDefault("industrial.redstone.level2.allowed", List.of("DISPENSER", "DROPPER", "HOPPER"));

        // Golden food
        c.addDefault("fields.goldenFood.perm", "unity.food.golden");
        c.addDefault("fields.goldenFood.premiumFoods", List.of("GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE"));
        c.addDefault("fields.goldenFood.msg_consume", "&cУ вашей страны нет права есть это.");
        c.addDefault("fields.goldenFood.msg_craft", "&cУ вашей страны нет права крафтить это.");

        // Netherite / Beacon
        c.addDefault("industrial.netherite.perm", "unity.netherite");
        c.addDefault("industrial.netherite.errmsg", "&cВаша страна ещё не открыла незерит.");
        c.addDefault("industrial.beacon.perm", "unity.beacon");
        c.addDefault("industrial.beacon.errmsg", "&cВаша страна ещё не открыла маяки.");

        // Energy Saving (Энергосбережение)
        c.addDefault("industrial.energySaving.description",
                "Энергосбережение: уменьшает стоимость активности редстоуна/механизмов в системе экономики чанков. " +
                        "multiplier < 1.0 уменьшает расход.");
        c.addDefault("industrial.energySaving.perm", "unity.industrial.energy");
        c.addDefault("industrial.energySaving.multiplier", 0.7);

        // Furnace ore bonus
        c.addDefault("industrial.furnaceOreBonus.perm", "unity.furnace.ore_boost");
        c.addDefault("industrial.furnaceOreBonus.chance", 0.10);
        c.addDefault("industrial.furnaceOreBonus.sfx", true);
        c.addDefault("industrial.furnaceOreBonus.outputs", List.of("IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT"));

        // Furnace heat
        c.addDefault("industrial.furnaceHeat.perm", "unity.furnace.boost");
        c.addDefault("industrial.furnaceHeat.radius", 5);
        c.addDefault("industrial.furnaceHeat.max_percent", 40.0);

        // Eco fuel
        c.addDefault("industrial.ecoFuel.perm", "unity.ecofuel");
        c.addDefault("industrial.ecoFuel.multiplier", 1.5);

        // Hopper
        c.addDefault("industrial.hopper.permBase", "unity.hopper");
        c.addDefault("industrial.hopper.slow_mode_lvl0", true);
        c.addDefault("industrial.hopper.turbo_task_period_ticks", 1L);
        c.addDefault("industrial.hopper.turbo_budget_per_run", 250);
        c.addDefault("industrial.hopper.turbo_eligibility_cache_ms", 1500L);

        // Kept from newer design
        c.addDefault("industrial.hopper.slowdown_level0_ticks", 8);
        c.addDefault("industrial.hopper.turbo_level2_ticks", 1);

        // TNT
        c.addDefault("industrial.tntQuarry.perm", "unity.tnt.quarry");
        c.addDefault("industrial.tntQuarry.dupWhitelist.STONE", 0.05);

        c.addDefault("colony.tntLicense.perm", "unity.tnt.license");
        c.addDefault("colony.tntLicense.ores", List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE"));
        c.addDefault("colony.tntLicense.max_extra", 2);
        c.addDefault("colony.tntLicense.chance_per_extra", 0.25);

        // Effects
        c.addDefault("effects.reapply_cooldown_ms", 700L);
        c.addDefault("effects.max_level", 2);
        c.addDefault("effects.effect_ticks", 220);
        c.addDefault("effects.perm_haste", "unity.effects.haste");
        c.addDefault("effects.perm_speed", "unity.effects.speed");
        c.addDefault("effects.perm_resist", "unity.effects.resist");

        // Psych support (uses hospital key)
        c.addDefault("hospital.psychSupport.luck_duration_ticks", 200);

        // Farmland
        c.addDefault("fields.farmlandProtect.permBase", "unity.farmland.protect");
        c.addDefault("fields.farmlandProtect.big_fall_threshold", 1.2);

        // Greenhouse low-light
        c.addDefault("fields.greenhouseLowLight.permBase", "unity.greenhouse.lowlight");
        c.addDefault("fields.greenhouseLowLight.scan_period_ticks", 40L);
        c.addDefault("fields.greenhouseLowLight.per_zone_budget", 40);
        c.addDefault("fields.greenhouseLowLight.max_percent", 30.0);

        // Bees
        c.addDefault("fields.beePollination.perm", "unity.bee.pollination");
        c.addDefault("fields.beePollination.bonus_percent", 10.0);
        c.addDefault("fields.beePollination.radius", 8);

        // Anti phantom
        c.addDefault("colony.antiPhantom.permBase", "unity.antiphantom");

        // Brew
        c.addDefault("industrial.brewSpeed.perm", "unity.brew.speed");
        c.addDefault("industrial.brewSpeed.speed_percent", 30.0);

        // Church
        c.addDefault("church.peaceBell.perm", "unity.church.bell");
        c.addDefault("church.peaceBell.stay_minutes", 1);
        c.addDefault("church.peaceBell.cooldown_minutes", 10);
        c.addDefault("church.peaceBell.sfx", true);

        c.addDefault("church.pilgrimage.perm", "unity.church.pilgrimage");
        c.addDefault("church.pilgrimage.stay_minutes", 1);
        c.addDefault("church.pilgrimage.buff_minutes", 10);
        c.addDefault("church.pilgrimage.cooldown_minutes", 30);
        c.addDefault("church.pilgrimage.sfx", true);
        c.addDefault("church.pilgrimage.amplifier", 0);
        c.addDefault("church.pilgrimage.effects", List.of("SPEED", "REGENERATION"));

        // Dust protection
        c.addDefault("industrial.dustProtection.perm", "unity.dust.protection");
        c.addDefault("industrial.dustProtection.min_y", 32);
        c.addDefault("industrial.dustProtection.duration_ticks", 200);

        // Recycler
        c.addDefault("industrial.recycler.perm", "unity.recycler");
        c.addDefault("industrial.recycler.dropChance", 0.15);
        c.addDefault("industrial.recycler.inputs", List.of("COBBLESTONE", "GRAVEL"));
        c.addDefault("industrial.recycler.extraDrops.FLINT", 0.05);

        // Outpost
        c.addDefault("colony.outpost.perm", "unity.colony.outpost");
        c.addDefault("colony.outpost.raid_cull_percent", 30.0);

        // Livestock (old aliases used by listener)
        c.addDefault("fields.livestock.plus_perm", "unity.livestock.plus");
        c.addDefault("fields.livestock.plus_speed_percent", 25.0);
        c.addDefault("fields.livestock.double_perm", "unity.livestock.double");
        c.addDefault("fields.livestock.double_chance_percent", 5.0);

        // Loader
        c.addDefault("industrial.loader.perm", "unity.loader");
        c.addDefault("industrial.loader.radius", 4);
        c.addDefault("industrial.loader.speed_multiplier", 2.0);

        // Food ration
        c.addDefault("colony.foodRation.perm", "unity.colony.foodration");
        c.addDefault("colony.foodRation.period_ticks", 200L);
        c.addDefault("colony.foodRation.duration_ticks", 40);
        c.addDefault("colony.foodRation.amplifier", 0);

        // --- fields
        c.addDefault("fields.description",
                "Апгрейды сельхоз-зоны (FIELDS/GREENHOUSE): элитная еда, грядки, скотоводство, гидропоника, пчёлы.");

        c.addDefault("fields.goldenFood.perm", "unity.food.golden"); // base!
        c.addDefault("fields.goldenFood.description",
                "Chef / Golden Food: список premiumFoods — предметы, которые считаются 'элитной едой'. "
                        + "При отсутствии апгрейда их можно запретить (по механике слушателей).");
        c.addDefault("fields.goldenFood.premiumFoods", List.of(
                "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE"
        ));

        c.addDefault("fields.farmlandProtect.permBase", "unity.farmland.protect"); // base
        c.addDefault("fields.farmlandProtect.description",
                "Non-Trample Soil: защита грядок от вытаптывания/ломания. "
                        + "permBase используется как префикс уровней (base.1, base.2...).");

        c.addDefault("fields.livestock.permBase", "unity.livestock"); // base
        c.addDefault("fields.livestock.description",
                "Livestock+/Livestock++: ускорение роста животных и шанс двойни. "
                        + "growth_multiplier — во сколько раз быстрее растут (например 1.25 = +25%). "
                        + "twin_chance — шанс двойни (0.05 = 5%).");
        c.addDefault("fields.livestock.growth_multiplier", 1.25);
        c.addDefault("fields.livestock.twin_chance", 0.05);

        c.addDefault("fields.hydroponics.perm", "unity.hydroponics");
        c.addDefault("fields.hydroponics.description",
                "Hydroponics (рост при низком свете): max_percent — потолок бонуса роста в процентах.");
        c.addDefault("fields.hydroponics.max_percent", 30);

        c.addDefault("fields.beePollination.perm", "unity.bee.pollination");
        c.addDefault("fields.beePollination.description",
                "Bee Pollination: увеличивает шанс/эффект роста рядом с ульями. "
                        + "bonus_percent — величина бонуса в процентах.");
        c.addDefault("fields.beePollination.bonus_percent", 10);

        // --- bank
        c.addDefault("bank.description",
                "Апгрейды банка: личные сейфы, банкоматы, комиссии, бесплатные переводы, проценты по вкладам.");

        c.addDefault("bank.safeDeposit.perm", "unity.bank.safedeposit");
        c.addDefault("bank.safeDeposit.description",
                "Safe Deposit Boxes: разрешение на личные сейфы-сундуки. max_per_player — лимит сейфов на игрока.");
        c.addDefault("bank.safeDeposit.max_per_player", 2);

        c.addDefault("bank.atmNetwork.permBase", "unity.bank.atm"); // base
        c.addDefault("bank.atmNetwork.description",
                "ATM Network: настройки комиссий при операциях через банкоматы. "
                        + "fee_in_bank — комиссия в банке, fee_in_country — внутри своей страны, fee_foreign — в чужой стране. "
                        + "Значения в долях (0.03 = 3%).");
        c.addDefault("bank.atmNetwork.fee_in_bank", 0.0);
        c.addDefault("bank.atmNetwork.fee_in_country", 0.01);
        c.addDefault("bank.atmNetwork.fee_foreign", 0.03);

        c.addDefault("bank.freeTransfer.perm", "unity.bank.freeTransfer");
        c.addDefault("bank.freeTransfer.description",
                "Free Transfer: разрешение на переводы без комиссии (или с пониженной комиссией — зависит от логики менеджера).");

        c.addDefault("bank.depositInterest.perm", "unity.bank.interest");
        c.addDefault("bank.depositInterest.description",
                "Deposit Interest: проценты на вклад. daily_percent — дневной процент в долях (0.10 = 10%/день).");
        c.addDefault("bank.depositInterest.daily_percent", 0.10);

        // --- park
        c.addDefault("park.description",
                "Апгрейды парка: рост растений, 'тихий' радиус, пруды/клумбы, комендантский час, скамейки (реген/эффекты).");

        c.addDefault("park.gardenerHut.perm", "unity.park.gardener");
        c.addDefault("park.gardenerHut.description",
                "Gardener's Hut: повышает шанс/скорость роста растений. growth_chance_bonus — бонус в долях (0.20 = +20%).");
        c.addDefault("park.gardenerHut.growth_chance_bonus", 0.20);

        c.addDefault("park.quietGuard.perm", "unity.park.quietguard");
        c.addDefault("park.quietGuard.description",
                "Quiet Guard: радиус подавления/смягчения громких событий (зависит от обработчика). radius — радиус действия.");
        c.addDefault("park.quietGuard.radius", 12);

        c.addDefault("park.pondBeds.perm", "unity.park.pondbeds");
        c.addDefault("park.pondBeds.description",
                "Pond & Flowerbeds: бонус насыщения/сатурации. saturation_bonus — сколько добавлять, cooldown_ms — откат в мс.");
        c.addDefault("park.pondBeds.saturation_bonus", 0.6);
        c.addDefault("park.pondBeds.cooldown_ms", 8000L);

        c.addDefault("park.quietHour.perm", "unity.park.quiethour");
        c.addDefault("park.quietHour.description",
                "Quiet Hour: ночной режим парка (например запрет спавна монстров) — зависит от обработчика.");

        c.addDefault("park.benches.perm", "unity.park.benches");
        c.addDefault("park.benches.description",
                "Benches: эффекты/реген возле скамеек. radius — радиус, regen_seconds — период/частота регена (сек).");
        c.addDefault("park.benches.radius", 8);
        c.addDefault("park.benches.regen_seconds", 4);

        // --- hospital
        c.addDefault("hospital.description",
                "Апгрейды госпиталя: психподдержка, диета, реген-пульс, санитарная зона, дар крови, триаж, safe-zone.");

        c.addDefault("hospital.psychSupport.perm", "unity.hospital.psych");
        c.addDefault("hospital.psychSupport.description",
                "Psych Support: эффект Luck после смерти/респавна. luck_duration_ticks — длительность в тиках.");
        c.addDefault("hospital.psychSupport.luck_duration_ticks", 200);

        c.addDefault("hospital.diet.perm", "unity.hospital.diet");
        c.addDefault("hospital.diet.description",
                "Diet: бонус к сатурации при еде в зоне. saturation_bonus — сколько добавлять.");
        c.addDefault("hospital.diet.saturation_bonus", 0.8);

        c.addDefault("hospital.regenPulse.perm", "unity.hospital.regen");
        c.addDefault("hospital.regenPulse.description",
                "Regen Pulse: периодический Regeneration в зоне. period_ticks — период, duration_ticks — длительность, amplifier — уровень эффекта.");
        c.addDefault("hospital.regenPulse.period_ticks", 200L);
        c.addDefault("hospital.regenPulse.duration_ticks", 80);
        c.addDefault("hospital.regenPulse.amplifier", 0);

        c.addDefault("hospital.sanitaryZone.perm", "unity.hospital.sanitary");
        c.addDefault("hospital.sanitaryZone.description",
                "Sanitary Zone: снижает спавн мобов в радиусе. radius — радиус, spawn_multiplier — множитель (0.5 = в 2 раза меньше).");
        c.addDefault("hospital.sanitaryZone.radius", 50);
        c.addDefault("hospital.sanitaryZone.spawn_multiplier", 0.5);

        c.addDefault("hospital.bloodGift.perm", "unity.hospital.bloodgift");
        c.addDefault("hospital.bloodGift.absorption.enabled", true);
        c.addDefault("hospital.bloodGift.absorption.amplifier", 0); // 0 = +2 сердца (max)

        c.addDefault("hospital.bloodGift.regen.enabled", true);
        c.addDefault("hospital.bloodGift.regen.ticks", 20 * 10); // 10 секунд
        c.addDefault("hospital.bloodGift.regen.amplifier", 0);   // Regen I
        c.addDefault("hospital.bloodGift.duration_minutes", 10);
        c.addDefault("hospital.bloodGift.duration_minutes.description",
                "Длительность эффекта 'Дар крови' в минутах (Absorption + опционально Regeneration).");

        c.addDefault("hospital.triage.perm", "unity.hospital.triage");
        c.addDefault("hospital.triage.description",
                "Triage: сокращает длительность дебаффов на reduce_percent процентов (50 = -50% длительности).");
        c.addDefault("hospital.triage.reduce_percent", 50);

        c.addDefault("hospital.safeZone.perm", "unity.hospital.safezone");
        c.addDefault("hospital.safeZone.description",
                "Safe Zone: снижает входящий урон в зоне госпиталя. damage_multiplier (0.80 = -20% урона).");
        c.addDefault("hospital.safeZone.damage_multiplier", 0.80);

        // --- library
        c.addDefault("library.description",
                "Апгрейды библиотеки: скидки на зачарование, спокойствие (голод), бонусы к наградам (education).");

        c.addDefault("library.scrollsOfEconomy.perm", "unity.library.scrolls");
        c.addDefault("library.scrollsOfEconomy.description",
                "Scrolls of Economy: снижает стоимость зачарования. exp_cost_multiplier (0.80 = -20% опыта/стоимости).");
        c.addDefault("library.scrollsOfEconomy.exp_cost_multiplier", 0.80);

        c.addDefault("library.calm.perm", "unity.library.calm");
        c.addDefault("library.calm.description",
                "Calm: шанс не тратить голод/уменьшать расход. cancel_hunger_chance (0.25 = 25% отменить списание).");
        c.addDefault("library.calm.cancel_hunger_chance", 0.25);

        c.addDefault("library.educationInitiative.perm", "unity.library.education");
        c.addDefault("library.educationInitiative.description",
                "Education Initiative: увеличивает награды (квесты/активности) reward_multiplier (1.25 = +25%).");
        c.addDefault("library.educationInitiative.reward_multiplier", 1.25);

        // --- state
        c.addDefault("state.description",
                "Государственные апгрейды: контракты, налоги, пошлины, фокус ресурсов, пропаганда, цензура, комендантский час, гильдия ремонта и т.д.");

        c.addDefault("state.contracts.perm", "unity.state.contracts");
        c.addDefault("state.contracts.description",
                "State Contracts: разрешение на гос.заказы. max_active — максимум активных контрактов.");
        c.addDefault("state.contracts.max_active", 3);

        c.addDefault("state.luxuryTax.perm", "unity.state.luxuryTax");
        c.addDefault("state.luxuryTax.description",
                "Luxury Tax: налог на роскошь. tax_percent — доля (0.05 = 5%). items — список материалов, попадающих под налог.");
        c.addDefault("state.luxuryTax.tax_percent", 0.05);
        c.addDefault("state.luxuryTax.items", List.of("NETHERITE_INGOT", "DIAMOND", "EMERALD", "NETHERITE_BLOCK"));

        c.addDefault("state.tollRoads.perm", "unity.state.toll");
        c.addDefault("state.tollRoads.description",
                "Toll Roads: пошлина при пересечении границ/зон. fee — фиксированная сумма (зависит от экономики).");
        c.addDefault("state.tollRoads.fee", 5.0);

        c.addDefault("state.exportRebate.perm", "unity.state.exportRebate");
        c.addDefault("state.exportRebate.description",
                "Export Rebate: возврат налога/сборов при экспорте. percent — доля возврата (0.10 = 10%).");
        c.addDefault("state.exportRebate.percent", 0.10);

        c.addDefault("state.resourceFocus.perm", "unity.state.resourceFocus");
        c.addDefault("state.resourceFocus.description",
                "Resource Focus: бонус к выбранному ресурсу и штраф к остальным. bonus_percent/penalty_percent — проценты.");
        c.addDefault("state.resourceFocus.bonus_percent", 20);
        c.addDefault("state.resourceFocus.penalty_percent", 10);

        c.addDefault("state.propaganda.perm", "unity.state.propaganda");
        c.addDefault("state.propaganda.description",
                "Party Propaganda: авто-объявления в чат по шаблонам. period_ticks — период рассылки, templates — список сообщений.");
        c.addDefault("state.propaganda.period_ticks", 1200L);
        c.addDefault("state.propaganda.templates", List.of(
                "&aГосударство напоминает: работай, отдыхай, не лагай.",
                "&eНовости дня: налогов больше не стало... пока что."
        ));

        c.addDefault("state.censorship.perm", "unity.state.censorship");
        c.addDefault("state.censorship.description",
                "Censorship: фильтрация чата/табличек по trigger_words (слова-триггеры).");
        c.addDefault("state.censorship.trigger_words", List.of("badword1", "badword2"));

        c.addDefault("state.curfew.perm", "unity.state.curfew");
        c.addDefault("state.curfew.description",
                "Curfew: ограничения/эффекты ночью. start_hour/end_hour — часы (0..23).");
        c.addDefault("state.curfew.start_hour", 23);
        c.addDefault("state.curfew.end_hour", 6);

        c.addDefault("state.repairGuild.perm", "unity.state.repairGuild");
        c.addDefault("state.repairGuild.description",
                "Repair Guild: возврат части материалов при поломке инструментов. return_percent — процент возврата.");
        c.addDefault("state.repairGuild.return_percent", 10);

        c.addDefault("state.tradingZone.perm", "unity.state.tradingZone");
        c.addDefault("state.tradingZone.description",
                "Trading Zone: расширяет лимиты/слоты торговли. extra_slots — сколько дополнительных слотов выдавать.");
        c.addDefault("state.tradingZone.extra_slots", 2);

        c.addDefault("state.sampler.perm", "unity.state.sampler");
        c.addDefault("state.sampler.description",
                "Sampler: 'образцы товаров' с кулдауном. cooldown_hours — откат в часах.");
        c.addDefault("state.sampler.cooldown_hours", 24);

        c.addDefault("state.happyHour.perm", "unity.state.happyHour");
        c.addDefault("state.happyHour.description",
                "Happy Hour: скидки в определённые часы. discount_percent — величина скидки в процентах.");
        c.addDefault("state.happyHour.discount_percent", 15);

    }

    // -------------------------------
    // Migration + normalization
    // -------------------------------
    private static void migrateLegacyKeys(YamlConfiguration c) {
        // Переносы: только если новый ключ пустой/отсутствует, а старый есть.
        // Пример: если раньше было upgrades.goldenFood.perm -> теперь fields.goldenFood.perm

        // Map<oldKey, newKey>
        Map<String, String> moves = new LinkedHashMap<>();
        moves.put("upgrades.goldenFood.perm", "fields.goldenFood.perm");
        moves.put("upgrades.goldenFood.premiumFoods", "fields.goldenFood.premiumFoods");

        moves.put("upgrades.furnace.perm", "industrial.furnaceOreBonus.perm");
        moves.put("upgrades.furnace.chance", "industrial.furnaceOreBonus.chance");
        moves.put("upgrades.furnace.outputs", "industrial.furnaceOreBonus.outputs");

        moves.put("upgrades.furnaceHeat.perm", "industrial.furnaceHeat.perm");
        moves.put("upgrades.furnaceHeat.radius", "industrial.furnaceHeat.radius");
        moves.put("upgrades.furnaceHeat.max_percent", "industrial.furnaceHeat.max_percent");

        moves.put("upgrades.tntQuarry.perm", "industrial.tntQuarry.perm");
        moves.put("upgrades.tntQuarry.dupWhitelist", "industrial.tntQuarry.dupWhitelist");

        moves.put("upgrades.bank.safeDeposit.perm", "bank.safeDeposit.perm");
        moves.put("upgrades.bank.safeDeposit.max_per_player", "bank.safeDeposit.max_per_player");

        moves.put("upgrades.bank.atmNetwork.permBase", "bank.atmNetwork.permBase");
        moves.put("upgrades.bank.atmNetwork.fee_in_bank", "bank.atmNetwork.fee_in_bank");
        moves.put("upgrades.bank.atmNetwork.fee_in_country", "bank.atmNetwork.fee_in_country");
        moves.put("upgrades.bank.atmNetwork.fee_foreign", "bank.atmNetwork.fee_foreign");

        moves.put("upgrades.hospital.safeZone.perm", "hospital.safeZone.perm");
        moves.put("upgrades.hospital.safeZone.damageMultiplier", "hospital.safeZone.damage_multiplier");

        // Церковь: старые пути (если были)
        moves.put("upgrades.church.peaceBell.perm", "church.peaceBell.perm");
        moves.put("upgrades.church.pilgrimage.perm", "church.pilgrimage.perm");

        // (опционально) миграции старых description → новые *.description
        moves.put("upgrades.industrialZone.description", "zones.unlock.industrial.description");
        moves.put("upgrades.fieldsZone.description", "zones.unlock.fields.description");
        moves.put("upgrades.colonyZone.description", "zones.unlock.colony.description");

        moves.put("upgrades.goldenFood.description", "fields.goldenFood.description");
        moves.put("upgrades.furnace.description", "industrial.furnaceOreBonus.description");
        moves.put("upgrades.furnaceHeat.description", "industrial.furnaceHeat.description");
        moves.put("upgrades.ecoFuel.description", "industrial.ecoFuel.description");
        moves.put("upgrades.tntQuarry.description", "industrial.tntQuarry.description");
        moves.put("upgrades.recycler.description", "industrial.recycler.description");
        moves.put("upgrades.dustProtection.description", "industrial.dustProtection.description");
        moves.put("upgrades.brew.description", "industrial.brewSpeed.description");
        moves.put("upgrades.brand.description", "commands.brand.description");
        moves.put("upgrades.netherite.description", "industrial.netherite.description");
        moves.put("upgrades.beacon.description", "industrial.beacon.description");
        moves.put("upgrades.energySaving.description", "industrial.energySaving.description");
        moves.put("upgrades.energySaving.perm", "industrial.energySaving.perm");
        moves.put("upgrades.energySaving.multiplier", "industrial.energySaving.multiplier");

        moves.put("upgrades.outpost.description", "colony.outpost.description");
        moves.put("upgrades.foodRation.description", "colony.foodRation.description");
        moves.put("upgrades.tntLicense.description", "colony.tntLicense.description");

        moves.put("upgrades.church.peaceBell.description", "church.peaceBell.description");
        moves.put("upgrades.church.pilgrimage.description", "church.pilgrimage.description");

        for (Map.Entry<String, String> e : moves.entrySet()) {
            String oldKey = e.getKey();
            String newKey = e.getValue();
            if (c.contains(newKey)) continue;
            if (!c.contains(oldKey)) continue;

            Object val = c.get(oldKey);
            c.set(newKey, val);
        }
    }
    // ===== Helpers =====
    private RedstoneLevelCfg readRedstoneLevel(String basePath) {
        String perm = cfg.getString(basePath + ".perm", "");
        String msg  = cfg.getString(basePath + ".errmsg", "");
        Set<Material> allowed = EnumSet.noneOf(Material.class);
        for (String s : cfg.getStringList(basePath + ".allowed")) {
            try { allowed.add(Material.valueOf(s.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException ignored) {}
        }
        return new RedstoneLevelCfg(perm, msg, allowed);
    }

    private static void normalizePermBases(YamlConfiguration c) {
        // Срезаем ".1" у значений perm/permBase, если кто-то оставил старый дефолт или руками прописал.
        // Делаем точечно по известным ключам, чтобы не сломать что-то чужое.

        List<String> permKeys = List.of(
                "fields.goldenFood.perm",
                "industrial.furnaceOreBonus.perm",
                "industrial.furnaceHeat.perm",
                "industrial.tntQuarry.perm",
                "industrial.hopper.permBase",
                "industrial.energySaving.perm",
                "colony.tntLicense.perm",
                "colony.foodRation.perm",
                "church.peaceBell.perm",
                "church.pilgrimage.perm",
                "bank.atmNetwork.permBase"

        );

        for (String k : permKeys) {
            String v = c.getString(k);
            if (v == null) continue;
            String trimmed = TRAILING_DOT_NUMBER.matcher(v).replaceAll("");
            if (!trimmed.equals(v)) {
                c.set(k, trimmed);
            }
        }
    }

    private void saveQuiet() {
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            cfg.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("[UpgradesConfig] Failed to save upgrades.yml: " + ex.getMessage());
        }
    }
}
