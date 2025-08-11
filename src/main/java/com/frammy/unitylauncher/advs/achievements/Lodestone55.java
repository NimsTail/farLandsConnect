package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Lodestone55 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "lodestone55");


  public Lodestone55(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.LODESTONE, "Точка Притяжения", AdvancementFrameType.TASK, true, true, 15f, 0f ,"", "Поставить магнетит в аду", "", "Награда: Рамка §b"Магнетит""), parent, 1);
  }
}