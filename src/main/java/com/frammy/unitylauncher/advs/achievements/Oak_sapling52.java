package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling52 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling52");


  public Oak_sapling52(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.SCAFFOLDING, "Небоскрёб", AdvancementFrameType.TASK, true, true, 13f, 0f ,"", "Достигнуть максимальной высоты ", "подмостками", "", "", "Награда: Рамка §b"Подмосток""), parent, 1);
  }
}