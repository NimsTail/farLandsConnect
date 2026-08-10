package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Ach1_2_3 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "ach1_2_3"
    );

    
private static final int FRAME_ID = 20;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Ach1_2_3(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.BUBBLE_CORAL,
                        "Дельфинья Находка",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        4f,
                        2f,
                        "",
                        "Обыскать 25 разных кораблекрушений",
                        "и руин океана",
                        "",
                        "Награда: Рамка §6Фиолетовый Коралл"
                ),
                parent,
                1
        );
    }
}
