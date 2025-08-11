package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.8 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.8");


  public Ach1.8(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.CACTUS, "Модный Приговор", AdvancementFrameType.TASK, true, true, 9f, 0f ,"", "Покрасить кожаный шлем в зелёный", "", "Награда: Рамка §b"Кактус""), parent, 1);
  }
}