package com.frammy.unitylauncher.upgrades.industry;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.zones.ZoneInfo;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;

public class LavaBoostedFurnacesListener implements Listener {

    private static final String PERM = "unity.furnace.lava_boost";
    private static final int RADIUS = 8;

    @EventHandler
    public void onBurn(FurnaceBurnEvent e) {
        Block furnace = e.getBlock();

        // Определяем страну по зоне
        var zm = UnityLauncher.getInstance().getZoneManager();
        ZoneInfo zone = zm.getZoneAt(furnace.getLocation());
        if (zone == null) return;

        String country = zone.getName();
        if (country == null || country.isBlank()) return;

        // Проверка апгрейда страны
        if (!UpgradeCondition.hasGlobalUpgrade(country, PERM)) return;

        // Поиск ближайшей лавы/магмы в радиусе 8 блоков
        double bestBoost = 0.0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = -RADIUS; dy <= RADIUS; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    Block b = furnace.getRelative(dx, dy, dz);
                    Material type = b.getType();
                    if (type == Material.LAVA || type == Material.MAGMA_BLOCK) {
                        double dist = furnace.getLocation().distance(b.getLocation());
                        if (dist <= RADIUS) {
                            double boost = getBoost(dist);
                            if (boost > bestBoost) {
                                bestBoost = boost;
                            }
                        }
                    }
                }
            }
        }

        if (bestBoost <= 0) return;

        // Ускорение печи: уменьшаем burnTime
        int oldBurnTime = e.getBurnTime();
        int newBurnTime = (int) Math.max(1, oldBurnTime * (1.0 - bestBoost));
        e.setBurnTime(newBurnTime);

        // Визуальный эффект: искры над печью
        furnace.getWorld().spawnParticle(
                Particle.FLAME,
                furnace.getLocation().add(0.5, 1.0, 0.5), // над печкой
                3,   // количество
                0.2, 0.1, 0.2, // разброс по X/Y/Z
                0.01 // "скорость" частицы (минимальная, для спокойного эффекта)
        );
    }

    /**
     * Кривая усиления в зависимости от расстояния.
     * 0 -> 30%, 1 -> 23%, дальше плавное падение к 0%.
     */
    private double getBoost(double dist) {
        if (dist < 0.5) return 0.30;
        if (dist < 1.5) return 0.23;
        if (dist >= 8.0) return 0.0;
        // линейный спад от 23% до 0% на интервале [1, 8]
        return 0.23 * (1.0 - (dist - 1) / 7.0);
    }
}
