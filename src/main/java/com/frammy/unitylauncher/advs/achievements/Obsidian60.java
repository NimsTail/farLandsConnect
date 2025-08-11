package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Obsidian60 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "obsidian60");


  public Obsidian60(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.OBSIDIAN, "Запасы Вечности", AdvancementFrameType.TASK, true, true, 15f, 5f ,"", "Добыть стак обломков незерита", "", "Награда: Рамка §5"Портал в Ад"", ""), parent, 1);
  }
}