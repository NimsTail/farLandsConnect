package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling28 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling28"
    );

    
private static final int FRAME_ID = 35;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Oak_sapling28(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.CRAFTING_TABLE,
                        "Фабрика",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        4f,
                        "",
                        "Скрафтить суммарно 500 предметов",
                        "на верстаке",
                        "Награда: Рамка §6Верстак"
                ),
                parent,
                1
        );
    }
}
