package com.frammy.unitylauncher.upgrades.industry;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.zones.ZoneInfo;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

/**
 * Апгрейд: +15% шанс получить доп. предмет при плавке руд.
 * Проверяется по глобальному апгрейду страны (LuckPerms Group).
 */
public class FurnaceOreBoostListener implements Listener {

    private static final String PERM = "unity.furnace.ore_boost";
    private static final double CHANCE = 0.15D;

    private final Random random = new Random();

    @EventHandler
    public void onSmelt(FurnaceSmeltEvent event) {
        // Определяем страну по локации печи
        var zm = UnityLauncher.getInstance().getZoneManager();
        ZoneInfo zone = zm.getZoneAt(event.getBlock().getLocation());
        if (zone == null) return;

        String country = zone.getName();
        if (country == null || country.isBlank()) return;

        // Проверяем глобальный апгрейд страны
        if (!UpgradeCondition.hasGlobalUpgrade(country, PERM)) {
            return;
        }

        // Разыгрываем шанс
        if (random.nextDouble() >= CHANCE) {
            return;
        }

        ItemStack result = event.getResult();
        if (result.getType() == Material.AIR) {
            return;
        }

        // Увеличиваем количество результата
        ItemStack boosted = result.clone();
        boosted.setAmount(result.getAmount() + 1);
        event.setResult(boosted);
    }
}
