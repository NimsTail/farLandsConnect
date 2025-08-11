package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Diamond_sword64 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "diamond_sword64");


  public Diamond_sword64(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.DIAMOND_SWORD, "Возмездие", AdvancementFrameType.TASK, true, true, 16f, 3f ,"", "Умереть от моба и убить его через 30 ", "секунд после респавна ", ""), parent, 1);
  }
}