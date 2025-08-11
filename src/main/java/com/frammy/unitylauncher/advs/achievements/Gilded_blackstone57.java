package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Gilded_blackstone57 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "gilded_blackstone57");


  public Gilded_blackstone57(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.GILDED_BLACKSTONE, "Пиглинский Бизнес", AdvancementFrameType.TASK, true, true, 15f, 2f ,"", "Торговаться с пиглинами с помощью ", "позолоченного чернита", "", "Награда: Рамка §6"Позолоченный", "§6Чернит""), parent, 1);
  }
}