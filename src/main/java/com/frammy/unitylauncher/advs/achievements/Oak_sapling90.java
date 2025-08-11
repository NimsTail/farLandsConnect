package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling90 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling90");


  public Oak_sapling90(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.BARRIER, "Мир без Блоков", AdvancementFrameType.TASK, true, true, 24f, 1f ,"", "Прожить день, не поставив ни ", "одного блока", ""), parent, 1);
  }
}