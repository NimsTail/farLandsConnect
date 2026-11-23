package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Tropical_fish51 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "tropical_fish51"
    );

    
private static final int FRAME_ID = 62;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Tropical_fish51(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.TROPICAL_FISH,
                        "Тропический Улов",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        4f,
                        6f,
                        "",
                        "Выловить 250 тропической рыбы",
                        "",
                        "Награда: Рамка §5Текущая Вода"
                ),
                parent,
                1
        );
    }
}
