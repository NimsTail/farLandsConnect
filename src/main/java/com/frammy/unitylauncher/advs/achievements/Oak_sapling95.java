package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling95 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling95"
    );

    public Oak_sapling95(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.NETHERITE_AXE,
                        "Несломленный",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        15f,
                        6f,
                        "",
                        "Сломать 10 незеритовых топоров, ",
                        "добывая ресурсы",
                        "",
                        "Награда: Рамка §5Адское Синее Бревно"
                ),
                parent,
                1
        );
    }
}
