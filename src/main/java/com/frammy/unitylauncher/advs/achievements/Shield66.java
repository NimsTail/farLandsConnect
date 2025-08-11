package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Shield66 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "shield66");


  public Shield66(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.SHIELD, "Потерянная Душа ", AdvancementFrameType.TASK, true, true, 16f, 5f ,"", "Умереть на одном и том же месте 3", "раза подряд ", ""), parent, 1);
  }
}