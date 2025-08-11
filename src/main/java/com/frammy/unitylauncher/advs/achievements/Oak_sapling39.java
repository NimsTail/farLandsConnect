package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling39 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling39");


  public Oak_sapling39(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.RED_BED, "Малая Родина", AdvancementFrameType.TASK, true, true, 7f, 4f ,"", "Быть гражданином страны с ", "населением 5+ игроков", ""), parent, 1);
  }
}