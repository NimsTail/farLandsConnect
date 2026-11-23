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

    // Grants two distinct frames (was "×2 of the same one" — frames aren't
    // stackable/counted on the site, so that would've been a no-op the
    // second time; split into Глубинный Сланец + Укреплённый Глубинный
    // Сланец per infra/frames-catalog.md §6).
    private static final int[] FRAME_IDS = { 58, 64 };

@Override
protected int[] getFrameIds() {
    return FRAME_IDS;
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
                        "Награда: рамки §bГлубинный Сланец §fи §bУкреплённый Глубинный Сланец"
                ),
                parent,
                1
        );
    }
}
