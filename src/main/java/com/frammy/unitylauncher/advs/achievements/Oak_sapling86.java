package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Oak_sapling86 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "oak_sapling86");


  public Oak_sapling86(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.PURPUR_BLOCK, "Фиолетовый Каменщик", AdvancementFrameType.TASK, true, true, 22f, 2f ,"", "Скрафтить пурпурный кирпич", "", "Награда: Рамка §6"Пурпурный Кирпич""), parent, 1);
  }
}