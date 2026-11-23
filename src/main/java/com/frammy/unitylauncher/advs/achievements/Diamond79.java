package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Diamond79 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "diamond79"
    );

    
private static final int FRAME_ID = 27;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Diamond79(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.GLOWSTONE,
                        "Распродажа",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        20f,
                        "",
                        "Продать в общей сумме предметов",
                        "на 4000F",
                        "",
                        "Награда: Рамка §cСветокамень"
                ),
                parent,
                1
        );
    }
}
