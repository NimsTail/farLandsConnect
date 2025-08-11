package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Ach1.1.1 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "ach1.1.1");


  public Ach1.1.1(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.OAK_SAPLING, "Вишнёвая Одиссея", AdvancementFrameType.TASK, true, true, 2f, 1f ,"", "Пять дней находиться в вишнёвом ", "биоме", "", "Награда: Рамка §6"Маленькие ", "§6Розовые Лепестки""), parent, 1);
  }
}