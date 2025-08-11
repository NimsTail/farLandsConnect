package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling70 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling70");


  public Oak_sapling70(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.AMETHYST_CLUSTER, "Вижу Всё", AdvancementFrameType.TASK, true, true, 18f, 0f ,"", "Сделать подзорную трубу", "", "Награда: Рамка §6"Аметист""), parent, 1);
  }
}