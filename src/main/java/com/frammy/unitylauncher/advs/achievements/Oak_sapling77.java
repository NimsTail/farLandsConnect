package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling77 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling77"
    );

    public Oak_sapling77(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.OCHRE_FROGLIGHT,
                        "Созерцатель",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        20f,
                        2f,
                        "",
                        "Неподвижно смотри на жёлтый",
                        "жабосвет игровые сутки",
                        "",
                        "Награда: Рамка §4Жёлтый Жабосвет"
                ),
                parent,
                1
        );
    }
}