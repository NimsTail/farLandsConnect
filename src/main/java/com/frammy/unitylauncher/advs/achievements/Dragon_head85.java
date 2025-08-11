package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Dragon_head85 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "dragon_head85");


  public Dragon_head85(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.ELYTRA, "Полет в Никуда", AdvancementFrameType.TASK, true, true, 22f, 1f ,"", "Умереть в пустоте с надетыми", "элитрами", "", "Награда: Рамка §c"Бедрок""), parent, 1);
  }
}