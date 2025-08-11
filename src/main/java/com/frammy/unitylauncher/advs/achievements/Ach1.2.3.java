package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.2.3 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.2.3");


  public Ach1.2.3(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.BUBBLE_CORAL, "Дельфинья Находка", AdvancementFrameType.TASK, true, true, 3f, 3f ,"", "Поднять фиолетовый коралл, ", "выброшенный дельфином", "", "Награда: Рамка §6"Фиолетовый Коралл""), parent, 1);
  }
}