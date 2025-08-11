package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling72 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling72");


  public Oak_sapling72(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.SHULKER_BOX, "Хаос в инвентаре", AdvancementFrameType.TASK, true, true, 19f, 1f ,"", " Иметь полный инвентарь только из ", "случайных предметов (ни одного ", "одинакового)", ""), parent, 1);
  }
}