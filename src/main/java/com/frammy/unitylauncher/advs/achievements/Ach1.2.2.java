package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.2.2 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.2.2");


  public Ach1.2.2(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.FIRE_CORAL, "Охотник на Красное", AdvancementFrameType.TASK, true, true, 3f, 2f ,"", "Добыть все виды красного коралла", "", "Награда: Рамка §6"Красный Коралл""), parent, 1);
  }
}