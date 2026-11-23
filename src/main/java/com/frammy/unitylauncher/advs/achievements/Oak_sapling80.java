package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling80 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling80"
    );

    
private static final int FRAME_ID = 0;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling80(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.CLOCK,
                        "Вечный Гость",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        3f,
                        20f,
                        "",
                        "Находиться на сервере непрерывно ",
                        "24 часа",
                        ""
                ),
                parent,
                1
        );
    }
}
