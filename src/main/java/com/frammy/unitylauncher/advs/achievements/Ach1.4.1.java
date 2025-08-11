package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.4.1 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.4.1");


  public Ach1.4.1(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.FILLED_MAP, "Гражданин Мира", AdvancementFrameType.TASK, true, true, 5f, 1f ,"", "Побывать в 5 странах сервера", ""), parent, 1);
  }
}