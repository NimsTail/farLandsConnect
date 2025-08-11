package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Feather62 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "feather62");


  public Feather62(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.FEATHER, "Дно Пробито", AdvancementFrameType.TASK, true, true, 16f, 1f ,"", "Погибнуть, упав с максимальной ", "высоты мира до его минимальной ", "точки", ""), parent, 1);
  }
}