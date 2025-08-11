package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling43 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling43");


  public Oak_sapling43(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.TOTEM_OF_UNDYING, "Дезертир", AdvancementFrameType.TASK, true, true, 7f, 7f ,"", "Выйти из своей страны и ", "присоединиться к другой", ""), parent, 1);
  }
}