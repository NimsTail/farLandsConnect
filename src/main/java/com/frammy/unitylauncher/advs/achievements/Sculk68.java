package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Sculk68 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "sculk68"
    );

    
private static final int FRAME_ID = 8;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Sculk68(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.SCULK,
                        "Очищение",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        1f,
                        16f,
                        "",
                        "Уничтожь 1000 скалковых блоков",
                        "",
                        "Награда: 2 Рамки §bУкреплённый Глубинный Сланец"
                ),
                parent,
                1
        );
    }
}
