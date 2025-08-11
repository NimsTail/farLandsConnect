package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling32 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling32");


  public Oak_sapling32(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.GLOW_BERRIES, "Ягодка Светится", AdvancementFrameType.TASK, true, true, 10f, 0f ,"", "Съест светящиеся ягоды", "", "Награда: Рамка §b"Светящиеся Ягоды""), parent, 1);
  }
}