package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Gilded_blackstone57 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "gilded_blackstone57"
    );

    
private static final int FRAME_ID = 32;

@Override
protected int getFrameId() {
    return FRAME_ID;
}public Gilded_blackstone57(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.GILDED_BLACKSTONE,
                        "Пиглинский Бизнес",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        3f,
                        14f,
                        "",
                        "Торговаться с пиглинами с помощью ",
                        "позолоченного чернита",
                        "",
                        "Награда: Рамка §6Позолоченный Чернит"
                ),
                parent,
                1
        );
    }
}
