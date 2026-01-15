package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling72 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling72"
    );

    
private static final int FRAME_ID = 8;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling72(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.SHULKER_BOX,
                        "Хаос в инвентаре",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        18f,
                        "",
                        "Иметь полный инвентарь только из ",
                        "случайных предметов (ни одного ",
                        "одинакового)",
                        "Награда: Рамка §6Кирпич"
                ),
                parent,
                1
        );
    }
}
