package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling84 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling84");


  public Oak_sapling84(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.DRAGON_HEAD, "Покоритель Края", AdvancementFrameType.TASK, true, true, 22f, 0f ,"", "Убить Эндер Дракона", "", "Награда: Рамка §4"Портал Края""), parent, 1);
  }
}