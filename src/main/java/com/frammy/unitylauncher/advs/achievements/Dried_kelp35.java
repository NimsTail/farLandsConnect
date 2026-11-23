package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Dried_kelp35 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "dried_kelp35"
    );

    
private static final int FRAME_ID = 30;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Dried_kelp35(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.DRIED_KELP,
                        "Ламинария на завтрак, обед и ужин",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        4f,
                        9f,
                        "Съесть 2000 жареных ламинарий",
                        "",
                        "Награда: Рамка §5Водоросли"
                ),
                parent,
                1
        );
    }
}