package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling88 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling88"
    );

    public Oak_sapling88(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.PUFFERFISH,
                        "Рыбка",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        23f,
                        1f,
                        "",
                        "Провести 10 минут под водой",
                        ""
                ),
                parent,
                1
        );
    }
}
