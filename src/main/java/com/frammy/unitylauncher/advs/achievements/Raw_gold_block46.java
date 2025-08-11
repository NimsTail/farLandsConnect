package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Raw_gold_block46 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "raw_gold_block46");


  public Raw_gold_block46(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.RAW_GOLD_BLOCK, "Золотая Лихорадка", AdvancementFrameType.TASK, true, true, 12f, 1f ,"", "Добыть золото на высоте выше Y = 20", "", "Награда: Рамка §6"Рудное Золото""), parent, 1);
  }
}