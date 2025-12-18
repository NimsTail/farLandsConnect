package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Obsidian60 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "obsidian60"
    );

    public Obsidian60(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.OBSIDIAN,
                        "Благоприятные обстоятельства",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        6f,
                        14f,
                        "",
                        "Ляг спать во всех биомах ",
                        "верхнего мира (53)",
                        "Награда: Рамка §5Портал в Ад"
                ),
                parent,
                1
        );
    }
}
