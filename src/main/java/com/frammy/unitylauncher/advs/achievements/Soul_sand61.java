package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Soul_sand61 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "soul_sand61");


  public Soul_sand61(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.SOUL_SAND, "Убийца", AdvancementFrameType.TASK, true, true, 16f, 0f ,"", "Убить игрока", "", "Награда: Рамка §b"Песок Душ""), parent, 1);
  }
}