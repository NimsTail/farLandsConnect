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

    // farmland
    public final String farmlandPermBase, farmlandErrmsg;
    public final float farmlandBigFallThreshold;

    // crops low light
    public final int    cropsLL_ScanPeriodTicks;
    public final int    cropsLL_PerZoneBudget;
    public final double cropsLL_MaxPercent;

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

        farmlandPermBase       = c.getString("upgrades.farmland.permBase", "unity.zone.farmland");
        farmlandErrmsg         = color(c.getString("upgrades.farmland.errmsg", ""));
        farmlandBigFallThreshold = (float) c.getDouble("upgrades.farmland.bigFallThreshold", 5.0);

        cropsLL_ScanPeriodTicks = Math.max(1, c.getInt("upgrades.cropsLowLight.scan_period_ticks", 40));
        cropsLL_PerZoneBudget   = Math.max(1, c.getInt("upgrades.cropsLowLight.per_zone_budget", 24));
        cropsLL_MaxPercent      = Math.max(0.0, c.getDouble("upgrades.cropsLowLight.max_percent", 15.0));

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

        // === Redstone ===
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
        c.addDefault("upgrades.goldenFood.perm", "unity.food.golden.1");
        c.addDefault("upgrades.goldenFood.errmsg_consume", "&cНужен апгрейд &6Золотая пища I");
        c.addDefault("upgrades.goldenFood.errmsg_craft", "&cКрафт заблокирован. Нужен &6Золотая пища I");
        c.addDefault("upgrades.goldenFood.premiumFoods", Arrays.asList(
                "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE", "GOLDEN_CARROT"
        ));

        // === Furnace Ore Bonus ===
        c.addDefault("upgrades.furnace.perm", "unity.furnace.ore_boost.1");
        c.addDefault("upgrades.furnace.errmsg", "");
        c.addDefault("upgrades.furnace.chance", 0.15);
        c.addDefault("upgrades.furnace.sfx", true);
        c.addDefault("upgrades.furnace.outputs", Arrays.asList(
                "IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT", "NETHERITE_SCRAP"
        ));

        // === Furnace Heat Boost ===
        c.addDefault("upgrades.furnaceHeat.perm", "unity.furnace.boost.1");
        c.addDefault("upgrades.furnaceHeat.errmsg", "");
        c.addDefault("upgrades.furnaceHeat.radius", 6);
        c.addDefault("upgrades.furnaceHeat.max_percent", 15.0);

        // === Hopper Smart / Turbo ===
        c.addDefault("upgrades.hopper.perm", "unity.hopper.smart");
        c.addDefault("upgrades.hopper.errmsg", "");
        c.addDefault("upgrades.hopper.turboBudgetPerRun", 50);
        c.addDefault("upgrades.hopper.turboTaskPeriodTicks", 2);
        c.addDefault("upgrades.hopper.turboEligibilityCacheMs", 1000L);
        c.addDefault("upgrades.hopper.slowMode_lvl0_everyOtherTick", true);

        // === TNT Quarry ===
        c.addDefault("upgrades.tntQuarry.perm", "unity.tnt.quarry.1");
        c.addDefault("upgrades.tntQuarry.errmsg", "");
        c.addDefault("upgrades.tntQuarry.dupWhitelist", new HashMap<String, Double>() {{
            put("DIAMOND_ORE", 0.10);
            put("RAW_IRON_BLOCK", 0.12);
            put("RAW_GOLD_BLOCK", 0.12);
            put("RAW_COPPER_BLOCK", 0.12);
            put("ANCIENT_DEBRIS", 0.05);
        }});

        // === Effects (country/colony buffs) ===
        c.addDefault("upgrades.effects.reapplyCooldownMs", 4000);
        c.addDefault("upgrades.effects.effectSeconds", 12);
        c.addDefault("upgrades.effects.perms.haste", "unity.zone.haste");
        c.addDefault("upgrades.effects.perms.speed", "unity.zone.speed");
        c.addDefault("upgrades.effects.perms.resistance", "unity.zone.resistance");
        c.addDefault("upgrades.effects.maxLevel", 2);

        // === Farmland Protection ===
        c.addDefault("upgrades.farmland.permBase", "unity.zone.farmland");
        c.addDefault("upgrades.farmland.errmsg", "");
        c.addDefault("upgrades.farmland.bigFallThreshold", 5.0);

        // === Crops Low Light (теплицы) ===
        c.addDefault("upgrades.cropsLowLight.scan_period_ticks", 40);
        c.addDefault("upgrades.cropsLowLight.per_zone_budget", 24);
        c.addDefault("upgrades.cropsLowLight.max_percent", 15.0);

        // === Anti-Phantom ===
        c.addDefault("upgrades.antiPhantom.perm_base", "unity.anti.phantom");
        c.addDefault("upgrades.antiPhantom.errmsg", "");

        // === Brew Fast ===
        c.addDefault("upgrades.brew.perm", "unity.brew.speed");
        c.addDefault("upgrades.brew.errmsg", "");
        c.addDefault("upgrades.brew.speed_percent", 25.0);

        // === Signs / ATM ===
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
        c.addDefault("upgrades.church.peaceBell.perm", "unity.church.bell.1");
        c.addDefault("upgrades.church.peaceBell.sfx", true);
        c.addDefault("upgrades.church.peaceBell.stay_minutes", 2);
        c.addDefault("upgrades.church.peaceBell.cooldown_minutes", 20);

        // === Church: Паломничество ===
        c.addDefault("upgrades.church.pilgrimage.perm", "unity.church.pilgrimage.1");
        c.addDefault("upgrades.church.pilgrimage.sfx", true);
        c.addDefault("upgrades.church.pilgrimage.stay_minutes", 5);
        c.addDefault("upgrades.church.pilgrimage.buff_minutes", 30);
        c.addDefault("upgrades.church.pilgrimage.amplifier", 0);
        c.addDefault("upgrades.church.pilgrimage.cooldown_minutes", 0);
        c.addDefault("upgrades.church.pilgrimage.effects", Arrays.asList(
                "LUCK", "NIGHT_VISION", "WATER_BREATHING", "FIRE_RESISTANCE", "FAST_DIGGING"
        ));

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
