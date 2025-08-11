package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Water_bucket50 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "water_bucket50");


  public Water_bucket50(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.AXOLOTL_BUCKET, "Аква-Коллекционер", AdvancementFrameType.TASK, true, true, 6f, 2f ,"", "Собрать в ведро всех возможных ", "животных", "", "Награда: Рамка §5"Вода""), parent, 1);
  }
}