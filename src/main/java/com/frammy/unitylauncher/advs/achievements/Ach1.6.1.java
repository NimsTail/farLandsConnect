package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.6.1 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.6.1");


  public Ach1.6.1(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.DIORITE, "Паспорт в Кармане ", AdvancementFrameType.TASK, true, true, 7f, 1f ,"", "Стать гражданином любой из стран", "", "Награда: Рамка§b"Диорит""), parent, 1);
  }
}