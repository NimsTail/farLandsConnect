package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Diamond79 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "diamond79");


  public Diamond79(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.DIAMOND, "Распродажа", AdvancementFrameType.TASK, true, true, 21f, 1f ,"", "Продать в общей сумме предметов", "на 4000 ф. граней", "", "Награда: Рамка §c"Спавнер""), parent, 1);
  }
}