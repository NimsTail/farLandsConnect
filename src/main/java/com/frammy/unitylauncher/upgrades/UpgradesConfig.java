package com.frammy.unitylauncher.upgrades;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

public final class UpgradesConfig {
    public final boolean DEBUG;

    // redstone
    public final String redstonePerm1, redstonePerm2, redstoneMsg1, redstoneMsg2;
    public final Set<Material> redstoneBasic, redstoneAdvanced;

    // golden food
    public final String goldenFoodPerm, goldenFoodMsgConsume, goldenFoodMsgCraft;
    public final Set<Material> premiumFoods;

    // furnace
    public final String furnacePerm;
    public final double furnaceChance;
    public final boolean furnaceSfx;
    public final Set<Material> furnaceOutputs;

    // hopper
    public final String hopperSmartPermBase;
    public final int hopperTurboBudgetPerRun;
    public final int hopperTurboTaskPeriodTicks;
    public final long hopperTurboEligibilityCacheMs;
    public final boolean hopperSlowModeLvl0;

    // tnt
    public final String tntPerm;
    public final Map<Material, Double> tntDupWhitelist;

    // effects
    public final long reapplyCooldownMs;
    public final int effectTicks; // уже в тиках
    public final String permHaste, permSpeed, permResist;
    public final int effectsMaxLevel;

    // farmland
    public final String farmlandPermBase;
    public final float farmlandBigFallThreshold;

    private UpgradesConfig(FileConfiguration c) {
        DEBUG = c.getBoolean("upgrades.debug", false);

        // redstone
        redstonePerm1 = c.getString("upgrades.redstone.perm_lvl1", "unity.upgrade.redstone.1");
        redstonePerm2 = c.getString("upgrades.redstone.perm_lvl2", "unity.upgrade.redstone.2");
        redstoneMsg1 = color(c.getString("upgrades.redstone.msg_need_lvl1", "&cНужен апгрейд 'Редстоун I'."));
        redstoneMsg2 = color(c.getString("upgrades.redstone.msg_need_lvl2", "&cНужен апгрейд 'Редстоун II'."));
        redstoneBasic   = toMaterialSet(c.getStringList("upgrades.redstone.basic"));
        redstoneAdvanced= toMaterialSet(c.getStringList("upgrades.redstone.advanced"));

        // golden food
        goldenFoodPerm = c.getString("upgrades.goldenFood.perm", "unity.food.golden.1");
        goldenFoodMsgConsume = color(c.getString("upgrades.goldenFood.msg_consume_blocked", "&cНужен апгрейд &6Золотая пища I"));
        goldenFoodMsgCraft   = color(c.getString("upgrades.goldenFood.msg_craft_blocked", "&cКрафт заблокирован. Нужен &6Золотая пища I"));
        premiumFoods = toMaterialSet(c.getStringList("upgrades.goldenFood.premiumFoods"));

        // furnace
        furnacePerm   = c.getString("upgrades.furnace.perm", "unity.furnace.ore_boost.1");
        furnaceChance = c.getDouble("upgrades.furnace.chance", 0.15);
        furnaceSfx    = c.getBoolean("upgrades.furnace.sfx", true);
        furnaceOutputs= toMaterialSet(c.getStringList("upgrades.furnace.outputs"));

        // hopper
        hopperSmartPermBase       = c.getString("upgrades.hopper.smartPerm", "unity.hopper.smart");
        hopperTurboBudgetPerRun   = c.getInt("upgrades.hopper.turboBudgetPerRun", 50);
        hopperTurboTaskPeriodTicks= c.getInt("upgrades.hopper.turboTaskPeriodTicks", 2);
        hopperTurboEligibilityCacheMs = c.getLong("upgrades.hopper.turboEligibilityCacheMs", 1000L);
        hopperSlowModeLvl0        = c.getBoolean("upgrades.hopper.slowMode_lvl0_everyOtherTick", true);

        // tnt
        tntPerm = c.getString("upgrades.tntQuarry.perm", "unity.tnt.quarry.1");
        tntDupWhitelist = toMaterialDoubleMap(c.getConfigurationSection("upgrades.tntQuarry.dupWhitelist"));

        // effects
        reapplyCooldownMs = c.getLong("upgrades.effects.reapplyCooldownMs", 4000);
        int seconds = c.getInt("upgrades.effects.effectSeconds", 12);
        effectTicks = 20 * Math.max(1, seconds);
        permHaste = c.getString("upgrades.effects.perms.haste", "unity.zone.haste");
        permSpeed = c.getString("upgrades.effects.perms.speed", "unity.zone.speed");
        permResist= c.getString("upgrades.effects.perms.resistance", "unity.zone.resistance");
        effectsMaxLevel = c.getInt("upgrades.effects.maxLevel", 2);

        // farmland
        farmlandPermBase = c.getString("upgrades.farmland.permBase", "unity.zone.farmland");
        farmlandBigFallThreshold = (float) c.getDouble("upgrades.farmland.bigFallThreshold", 5.0);
    }

    public static UpgradesConfig load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        return new UpgradesConfig(plugin.getConfig());
    }

    private static Set<Material> toMaterialSet(List<String> list) {
        return list.stream()
                .map(s -> safeMat(s.trim()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
    }

    private static Map<Material, Double> toMaterialDoubleMap(org.bukkit.configuration.ConfigurationSection sec) {
        Map<Material, Double> out = new HashMap<>();
        if (sec == null) return out;
        for (String key : sec.getKeys(false)) {
            Material m = safeMat(key);
            if (m == null) continue;
            out.put(m, sec.getDouble(key, 0.0));
        }
        return out;
    }

    private static Material safeMat(String name) {
        try { return Material.valueOf(name); } catch (Exception ignored) { return null; }
    }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
}
