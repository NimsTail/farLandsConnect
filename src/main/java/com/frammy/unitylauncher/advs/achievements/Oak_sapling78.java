package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling78 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling78");


  public Oak_sapling78(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.NETHER_STAR, "Звёздный продавец", AdvancementFrameType.TASK, true, true, 21f, 0f ,"", "Иметь рейтинг выше 4.5 в магазине", "при 15+ заказов", "", "Награда: Рамка §4"Столб и Кварца""), parent, 1);
  }
}