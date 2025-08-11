package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling30 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling30");


  public Oak_sapling30(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.OAK_SAPLING, "Вишнёвая Выдержка ", AdvancementFrameType.TASK, true, true, 2f, 2f ,"", "15 дней находиться в вишневом биоме", "", "Награда: Рамка §c"Большие Розовые", "§cЛепестки""), parent, 1);
  }
}