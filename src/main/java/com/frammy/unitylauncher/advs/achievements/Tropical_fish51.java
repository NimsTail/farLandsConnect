package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Tropical_fish51 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "tropical_fish51"
    );

    public Tropical_fish51(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.TROPICAL_FISH,
                        "Тропический Улов",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        6f,
                        3f,
                        "",
                        "Выловить 200 тропической рыбы",
                        "",
                        "Награда: Рамка §5Текущая Вода"
                ),
                parent,
                1
        );
    }
}
