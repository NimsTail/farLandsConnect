package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.visibilities.HiddenVisibility;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling91 extends FrameRewardAdvancement implements HiddenVisibility {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling91"
    );

    
private static final int FRAME_ID = 0;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling91(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.ENDER_PEARL,
                        "Одинокий Воин",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        1f,
                        1f,
                        "",
                        "§5Зайти на сервер когда на нём",
                        "§5никого нет",
                        ""
                ),
                parent,
                1
        );
    }
}
