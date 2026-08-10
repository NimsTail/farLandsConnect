package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Ach1_2_1 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "ach1_2_1"
    );
    
    private static final int FRAME_ID = 18;

    @Override
    protected int getFrameId() {
        return FRAME_ID;
    }

    public Ach1_2_1(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.TUBE_CORAL,
                        "Синий Экзекутор",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        2f,
                        "",
                        "Держать в инвентаре одновременно все",
                        "варианты синего коралла",
                        "",
                        "Награда: Рамка §6Синий Коралл"
                ),
                parent,
                1
        );
    }
}