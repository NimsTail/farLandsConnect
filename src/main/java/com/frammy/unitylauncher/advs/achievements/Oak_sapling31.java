package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling31 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling31"
    );

    
private static final int FRAME_ID = 37;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling31(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.HONEYCOMB,
                        "Пчелиный Барон",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        4f,
                        1f,
                        "",
                        "Развести 50 пчёл",
                        "",
                        "Награда: Рамка §cМёд"
                ),
                parent,
                1
        );
    }
}
