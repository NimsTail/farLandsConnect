package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Oak_sapling30 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "oak_sapling30"
    );

    public Oak_sapling30(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.CHERRY_LEAVES,
                        "Вишнёвая Выдержка",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        3f,
                        1f,
                        "",
                        "15 дней находиться в вишневом биоме",
                        "",
                        "Награда: Рамка §cБольшие Розовые Лепестки"
                ),
                parent,
                1
        );
    }
}
