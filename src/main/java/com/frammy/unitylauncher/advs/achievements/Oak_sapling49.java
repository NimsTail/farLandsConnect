package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling49 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling49");


  public Oak_sapling49(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.FISHING_ROD, "Мастер Рыбалки", AdvancementFrameType.TASK, true, true, 6f, 1f ,"", "Поймать 50 предметов на удочку", ""), parent, 1);
  }
}