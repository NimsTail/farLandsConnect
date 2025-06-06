/*package com.frammy.unitylauncher.advs.frameadv;

import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Color;
import org.bukkit.Material;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

public class Cactus extends BaseAdvancement  {

  public static AdvancementKey KEY = new AdvancementKey(AdvancementTabNamespaces.frameadv_NAMESPACE, "cactus");


  public Cactus(Advancement parent, float x, float y) {
    super(KEY.getKey(), new FancyAdvancementDisplay(Material.CACTUS, "Ай б#&ть!", AdvancementFrameType.TASK, true, true, x, y ,"", "Покрасить кожаный шлем в зелёный"), parent, 1);
    registerEvent(CraftItemEvent.class,(e)->{
      ItemStack result = e.getRecipe().getResult();
      Player p = (Player)e.getWhoClicked();
      if (result.getType() == Material.LEATHER_HELMET) {
        ItemMeta meta = result.getItemMeta();
        if (meta instanceof LeatherArmorMeta) {
          LeatherArmorMeta leatherMeta = (LeatherArmorMeta) meta;
          Color color = leatherMeta.getColor();

          if (color.equals(Color.GREEN)) {
          incrementProgression(p);
          }
        }
      }
    });
  }
}*/