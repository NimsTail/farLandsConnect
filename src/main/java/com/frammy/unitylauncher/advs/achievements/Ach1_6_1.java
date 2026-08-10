package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Ach1_6_1 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "ach1_6_1"
    );

    // No frame reward of its own — Ach1_6 grants all three (Гранит/Диорит/
    // Андезит) at once now, see infra/frames-catalog.md §3 item 2. Left
    // registered for the tree's visual chain; getFrameId() defaults to 0.

    public Ach1_6_1(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.DIORITE,
                        "Паспорт в Кармане",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        7f,
                        "",
                        "Стать гражданином любой из стран",
                        ""
                ),
                parent,
                1
        );
    }
}