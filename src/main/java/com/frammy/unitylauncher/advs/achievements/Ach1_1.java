package com.frammy.unitylauncher.advs.achievements;
import com.frammy.unitylauncher.advs.FrameRewardAdvancement;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.FramesDao;
import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;
import org.bukkit.entity.Player;

public class Ach1_1 extends FrameRewardAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "ach1_1"
    );
    
    private static final int FRAME_ID = 15;

    @Override
    protected int getFrameId() {
        return FRAME_ID;
    }

    public Ach1_1(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.CHERRY_LOG,
                        "Вишнёвая Мастерская",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        1f,
                        1f,
                        "",
                        "Выложить ковёр из 150 блоков розовых ",
                        "лепестков, поставленных игроком",
                        "",
                        "Награда: Рамка §bВишня"
                ),
                parent,
                1
        );
    }
}