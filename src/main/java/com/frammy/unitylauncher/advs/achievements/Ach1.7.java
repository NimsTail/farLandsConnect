package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.7 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.7");


  public Ach1.7(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.WHEAT, "Хлеб для Зрелища", AdvancementFrameType.TASK, true, true, 8f, 0f ,"", "Продать жителю пшеницу", "", "Награда: Рамка §b"Пшеница""), parent, 1);
  }
}