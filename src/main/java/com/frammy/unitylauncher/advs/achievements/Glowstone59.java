package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Glowstone59 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "glowstone59"
    );

    
private static final int FRAME_ID = 8;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Glowstone59(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.GLOWSTONE,
                        "Точка Невозврата",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        5f,
                        14f,
                        "",
                        "Создать якорь возрождения и ",
                        "улучшить до 4 уровня",
                        "",
                        "Награда: Рамка §6Синий Коралл"
                ),
                parent,
                1
        );
    }
}
