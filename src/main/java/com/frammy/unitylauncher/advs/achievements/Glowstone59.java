package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Glowstone59 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "glowstone59");


  public Glowstone59(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.GLOWSTONE, "Точка Невозврата", AdvancementFrameType.TASK, true, true, 15f, 4f ,"", "Создать якорь возрождения и ", "улучшить до 4 уровня", "", "Награда: Рамка §6"Синий Коралл""), parent, 1);
  }
}