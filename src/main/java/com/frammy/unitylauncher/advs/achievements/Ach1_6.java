package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Ach1_6 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "ach1_6"
    );

    // Ach1_6/_1/_2 used to be three separate nodes with the identical
    // condition, each granting its own frame — see infra/frames-catalog.md
    // §3 item 2. Consolidated here: this is the one that actually grants a
    // reward (all three), _1/_2 stay in the tree for visual continuity but
    // no longer grant anything on their own.
    private static final int[] FRAME_IDS = { 22, 23, 24 };

@Override
protected int[] getFrameIds() {
    return FRAME_IDS;
}public Ach1_6(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.GRANITE,
                        "Паспорт в Кармане",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        1f,
                        7f,
                        "",
                        "Стать гражданином любой из стран",
                        "",
                        "Награда: Рамки §bГранит §f+ §bДиорит §f+ §bАндезит"
                ),
                parent,
                1
        );
    }
}