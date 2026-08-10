package com.frammy.unitylauncher.advs;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.function.Predicate;

/** Full leather armor set, each piece dyed green (dominant green channel). */
public class GreenArmorCondition implements Predicate<Player> {

    @Override
    public boolean test(Player p) {
        EntityEquipment eq = p.getEquipment();
        if (eq == null) return false;
        return isGreenLeather(eq.getHelmet())
                && isGreenLeather(eq.getChestplate())
                && isGreenLeather(eq.getLeggings())
                && isGreenLeather(eq.getBoots());
    }

    private boolean isGreenLeather(ItemStack item) {
        if (item == null) return false;
        Material t = item.getType();
        if (t != Material.LEATHER_HELMET && t != Material.LEATHER_CHESTPLATE
                && t != Material.LEATHER_LEGGINGS && t != Material.LEATHER_BOOTS) return false;
        if (!(item.getItemMeta() instanceof LeatherArmorMeta meta)) return false;

        var c = meta.getColor();
        return c.getGreen() > c.getRed() + 30 && c.getGreen() > c.getBlue() + 30;
    }
}
