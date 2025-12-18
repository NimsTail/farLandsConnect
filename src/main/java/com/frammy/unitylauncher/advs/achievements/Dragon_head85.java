package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Dragon_head85 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "dragon_head85"
    );

    public Dragon_head85(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.ELYTRA,
                        "Полёт в Никуда",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        21f,
                        "",
                        "Умереть в пустоте с надетыми",
                        "элитрами",
                        "",
                        "Награда: Рамка §cБедрок"
                ),
                parent,
                1
        );
    }
}
