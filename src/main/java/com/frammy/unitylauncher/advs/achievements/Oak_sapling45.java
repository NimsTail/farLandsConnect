package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling45 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling45");


  public Oak_sapling45(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.RAW_IRON_BLOCK, "Железный Человек", AdvancementFrameType.TASK, true, true, 12f, 0f ,"", "Сделать блок рудного железа", "", "Награда: Рамка §b"Рудное Железо""), parent, 1);
  }
}