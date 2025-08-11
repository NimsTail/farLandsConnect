package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling69 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling69");


  public Oak_sapling69(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.SCULK_SENSOR, "Шёпот Глубин", AdvancementFrameType.TASK, true, true, 17f, 1f ,"", "Получить скалк сенсор", "", "Награда: Рамка §b"Портал Вардена""), parent, 1);
  }
}