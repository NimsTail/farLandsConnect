package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Shroomlight58 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "shroomlight58"
    );

    public Shroomlight58(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.SHROOMLIGHT,
                        "Путеводный Гриб",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        4f,
                        14f,
                        "",
                        "Пройди 1000 блоков по аду, держа ",
                        "грибосвет в одной из рук",
                        "",
                        "Награда: Рамка §6Грибосвет"
                ),
                parent,
                1
        );
    }
}
