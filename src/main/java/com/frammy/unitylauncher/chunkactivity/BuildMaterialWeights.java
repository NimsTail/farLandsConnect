package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.Material;

import java.util.EnumSet;
import java.util.Set;

/**
 * Дешёвые/сырые (некрафченные) материалы получают низкий вес в объёме
 * стройки — иначе бездумная закладка чанка диртом/камнем накручивала бы
 * "ценность земли" так же дёшево, как настоящая постройка. Правило
 * объективное (сырой блок из земли vs что-то обработанное), а не
 * субъективное вроде "красоты".
 */
public final class BuildMaterialWeights {
    private BuildMaterialWeights() {}

    private static final double FILLER_WEIGHT = 0.15;
    private static final double DEFAULT_WEIGHT = 1.0;

    // добывается копанием/добычей без какой-либо обработки — типичный
    // материал для "тупого" объёмного заполнения
    private static final Set<Material> FILLER = EnumSet.of(
            Material.DIRT, Material.GRASS_BLOCK, Material.DIRT_PATH, Material.MUD,
            Material.STONE, Material.COBBLESTONE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.TUFF,
            Material.NETHERRACK, Material.SAND, Material.RED_SAND, Material.GRAVEL,
            Material.SOUL_SAND, Material.SOUL_SOIL, Material.END_STONE,
            Material.BASALT, Material.SMOOTH_BASALT, Material.BLACKSTONE
    );

    public static double weightFor(Material material) {
        if (material == null) return 0.0;
        return FILLER.contains(material) ? FILLER_WEIGHT : DEFAULT_WEIGHT;
    }
}
