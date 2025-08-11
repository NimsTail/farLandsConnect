package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Netherite_scrap56 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "netherite_scrap56");


  public Netherite_scrap56(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.NETHERITE_SCRAP, "Путь к Совершенству", AdvancementFrameType.TASK, true, true, 15f, 1f ,"", "Переплавить незеритовый обломок", "", "Награда: Рамка §6"Незеритовый ", "§6обломок""), parent, 1);
  }
}