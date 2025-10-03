package com.frammy.unitylauncher.upgrades;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.util.*;

/**
 * LP-only версия: никакой базы.
 * Все апгрейды берём через player.hasPermission(upgrade.getKey()).
 */
public class MainUpgrades {

    private final Map<String, Upgrade> registry = new HashMap<>();

    public MainUpgrades() {
        registerBuiltins();
    }

    /** Регистрирует апгрейд */
    public void registerUpgrade(Upgrade upgrade) {
        registry.put(upgrade.getKey().toLowerCase(Locale.ROOT), upgrade);
    }

    /** Применить эффекты всех апгрейдов, которые разрешены у игрока */
    public void applyUpgradesFor(Player player) {
        for (Upgrade up : registry.values()) {
            if (player.hasPermission(up.getKey())) {
                try {
                    up.apply(player);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    public static Player getEntityOwner(Entity entity) {
        if (entity.hasMetadata("owner")) {
            List<MetadataValue> values = entity.getMetadata("owner");
            if (!values.isEmpty()) {
                String uuidStr = values.get(0).asString();
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    return entity.getServer().getPlayer(uuid);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    /** Проверка: есть ли у игрока конкретный апгрейд */
    public static boolean hasUpgrade(Player p, String key) {
        return p == null || !p.hasPermission(key);
    }

    private void registerBuiltins() {
        // INDUSTRY
        registerUpgrade(new IndustryUpgrades.RedstoneBasic());
        registerUpgrade(new IndustryUpgrades.RedstoneAdvanced());
        registerUpgrade(new IndustryUpgrades.HasteZone());
        registerUpgrade(new IndustryUpgrades.HasteZone2());
        registerUpgrade(new IndustryUpgrades.FurnaceOreBoost());
        registerUpgrade(new IndustryUpgrades.TntQuarry1());
        registerUpgrade(new IndustryUpgrades.TntQuarry2());
        registerUpgrade(new IndustryUpgrades.TntQuarry3());
        registerUpgrade(new IndustryUpgrades.SmartHoppers1());
        registerUpgrade(new IndustryUpgrades.SmartHoppers2());
        registerUpgrade(new IndustryUpgrades.FastMinecartIO());
        registerUpgrade(new IndustryUpgrades.ItemBranding());
        registerUpgrade(new IndustryUpgrades.LavaBoostedFurnaces());

        // FARMING
        registerUpgrade(new FarmingUpgrades.CookingBambooFuel());
        registerUpgrade(new FarmingUpgrades.FasterBrewing());
        registerUpgrade(new FarmingUpgrades.BrewingIngredientSave());
        registerUpgrade(new FarmingUpgrades.GreenhouseLowLight());
        registerUpgrade(new FarmingUpgrades.BreedingCooldown());
        registerUpgrade(new FarmingUpgrades.BeehiveGrowth());
        registerUpgrade(new FarmingUpgrades.NoFarmlandTrample());

        // MILITARY
        registerUpgrade(new MilitaryUpgrades.BeaconAccess());
        registerUpgrade(new MilitaryUpgrades.TotemOfUndying());
        registerUpgrade(new MilitaryUpgrades.ElytraAccess());
        registerUpgrade(new MilitaryUpgrades.TridentAccess());
        registerUpgrade(new MilitaryUpgrades.SculkAccess());
        registerUpgrade(new MilitaryUpgrades.FireChargeAccess());
    }
}
