package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Tnt54 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "tnt54");


  public Tnt54(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.TNT, "Месть", AdvancementFrameType.TASK, true, true, 14f, 1f ,"", "Убить крипера динамитом", "", "Награда: Рамка §6"ТНТ""), parent, 1);
  }
}