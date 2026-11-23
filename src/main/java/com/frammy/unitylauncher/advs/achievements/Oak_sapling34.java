package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling34 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling34"
    );

    
private static final int FRAME_ID = 40;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling34(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.ROTTEN_FLESH,
                        "Эконом-Класс",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        3f,
                        9f,
                        "",
                        "Съесть 500 гнилой плоти",
                        "",
                        "Награда: Рамка §bПесок Душ"
                ),
                parent,
                1
        );
    }
}
