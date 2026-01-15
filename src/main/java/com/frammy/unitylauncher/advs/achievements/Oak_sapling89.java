package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling89 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling89"
    );

    
private static final int FRAME_ID = 8;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling89(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.WATER_BUCKET,
                        "Прыжок Веры",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        1f,
                        23f,
                        "",
                        "Упасть в 1 блок воды с высоты 100+",
                        "блоков",
                        ""
                ),
                parent,
                1
        );
    }
}
