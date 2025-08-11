package com.generatedadvancement.ultimateadvancementapi.advs.achievements;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.generatedadvancement.ultimateadvancementapi.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;

public class Sculk68 extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.achievements_NAMESPACE, "sculk68");


  public Sculk68(Advancement parent) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.SCULK, "Очищение", AdvancementFrameType.TASK, true, true, 17f, 0f ,"", "Уничтожь 10 000 скалковых блоков", "", "Награда: Рамка §5"Скалк""), parent, 1);
  }
}