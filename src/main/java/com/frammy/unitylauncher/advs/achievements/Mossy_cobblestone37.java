package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Mossy_cobblestone37 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "mossy_cobblestone37"
    );

    public Mossy_cobblestone37(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.MOSSY_COBBLESTONE,
                        "Кузнец-Первопроходец",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        11f,
                        0f,
                        "",
                        "Создать кузнечный шаблон из ",
                        "замшелого булыжника",
                        "",
                        "Награда: Рамка §6Замшелый булыжник"
                ),
                parent,
                1
        );
    }
}
