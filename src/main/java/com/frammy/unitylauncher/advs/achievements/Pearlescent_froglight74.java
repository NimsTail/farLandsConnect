package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Pearlescent_froglight74 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "pearlescent_froglight74"
    );

    public Pearlescent_froglight74(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.PEARLESCENT_FROGLIGHT,
                        "Одинокий Свет",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        20f,
                        1f,
                        "",
                        "Поставь фиолетовый жабосвет в ",
                        "точке, где в радиусе 50 блоков ",
                        "нет других источников света",
                        "",
                        "Награда: Рамка §4Фиолетовый Жабосвет"
                ),
                parent,
                1
        );
    }
}
