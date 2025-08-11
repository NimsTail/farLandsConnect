package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Dried_kelp35 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "dried_kelp35");


  public Dried_kelp35(Advancement parent) {
    super(KEY.getKey(), new AdvancementDisplay(Material.DRIED_KELP, "Ламинария на завтрак, обед и ужин", AdvancementFrameType.TASK, true, true, 10f, 3f , "Съесть 2000 жареных ламинарий", "", "Награда: Рамка §5"Водоросли""), parent, 1);
  }
}