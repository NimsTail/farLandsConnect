package com.frammy.unitylauncher.upgrades.redstone;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

public enum RedstoneTier {
    BASIC(EnumSet.of(
            Material.REDSTONE,
            Material.REDSTONE_WIRE,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_BLOCK,
            Material.LEVER,
            Material.NOTE_BLOCK,
            Material.PISTON,
            Material.STICKY_PISTON,
            Material.DISPENSER,
            Material.DROPPER
    )),

    ADVANCED(EnumSet.of(
            Material.REPEATER,
            Material.COMPARATOR,
            Material.OBSERVER,
            Material.DAYLIGHT_DETECTOR,
            Material.SCULK_SENSOR,
            Material.CALIBRATED_SCULK_SENSOR,
            Material.HOPPER,
            Material.HOPPER_MINECART,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL,
            Material.TRIPWIRE_HOOK,
            Material.TRIPWIRE
    ));

    private final Set<Material> materials;

    RedstoneTier(Set<Material> mats) {
        this.materials = mats;
    }

    /** Возвращает true, если данный материал входит в этот уровень ред стоуна */
    public boolean contains(Material mat) {
        return materials.contains(mat);
    }

    /** Удобная проверка: какой уровень включает материал (или null, если не найдено). */
    public static RedstoneTier of(Material mat) {
        for (RedstoneTier tier : values()) {
            if (tier.contains(mat)) return tier;
        }
        return null;
    }
}
