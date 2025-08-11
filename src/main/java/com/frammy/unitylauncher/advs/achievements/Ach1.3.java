package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.3 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.3");


  public Ach1.3(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.JUNGLE_WOOD, "Сладкая Жизнь", AdvancementFrameType.TASK, true, true, 4f, 0f ,"", "Сделать стак печенья", "", "Награда: Рамка §b"Джунгли""), parent, 1);
  }
}