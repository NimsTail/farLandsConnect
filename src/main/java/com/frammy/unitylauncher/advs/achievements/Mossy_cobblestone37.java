package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Mossy_cobblestone37 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "mossy_cobblestone37"
    );

    
private static final int FRAME_ID = 34;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Mossy_cobblestone37(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.MOSSY_COBBLESTONE,
                        "Кузнец-Первопроходец",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        1f,
                        10f,
                        "",
                        "Заразить мхом (костной мукой) 1000 ",
                        "блоков рядом с мховым блоком",
                        "",
                        "Награда: Рамка §6Замшелый булыжник"
                ),
                parent,
                1
        );
    }
}
