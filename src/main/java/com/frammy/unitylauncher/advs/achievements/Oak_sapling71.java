package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling71 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling71"
    );

    public Oak_sapling71(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.ENCHANTING_TABLE,
                        "Колдун",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        19f,
                        0f,
                        "",
                        "Поставь 15 книжных полок вокруг",
                        "стола зачаровывания",
                        "",
                        "Награда: Рамка §6Стол Зачаровывания"
                ),
                parent,
                1
        );
    }
}
