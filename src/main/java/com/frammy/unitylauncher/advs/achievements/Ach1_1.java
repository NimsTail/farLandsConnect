package com.frammy.unitylauncher.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;

public class Ach1_1 extends BaseAdvancement  {

    public static AdvancementKey KEY = new AdvancementKey(
            AdvancementTabNamespaces.achievements_NAMESPACE,
            "ach1_1"
    );

    public Ach1_1(Advancement parent) {
        super(
                KEY.getKey(),
                new FancyAdvancementDisplay(
                        Material.OAK_SAPLING,
                        "Вишнёвая Мастерская",
                        AdvancementFrameType.TASK,
                        true,
                        true,
                        2f,
                        0f,
                        "",
                        "Скрафтить все виды предметов из ",
                        "вишнёвой древесины",
                        "",
                        "Награда: Рамка §bВишня"
                ),
                parent,
                1
        );
    }
}