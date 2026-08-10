package com.frammy.unitylauncher.advs;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.function.Predicate;

/** Зелёный жабосвет — единственный предмет во всём инвентаре (включая броню/офхенд). */
public class SingleItemInventoryCondition implements Predicate<Player> {

    @Override
    public boolean test(Player p) {
        PlayerInventory inv = p.getInventory();

        if (!isEmptyOrTarget(inv.getItemInOffHand())) return false;
        for (ItemStack armor : inv.getArmorContents()) {
            if (!isEmptyOrTarget(armor)) return false;
        }

        boolean foundOne = false;
        for (ItemStack item : inv.getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) continue;
            if (item.getType() != Material.VERDANT_FROGLIGHT) return false;
            foundOne = true;
        }
        return foundOne;
    }

    private boolean isEmptyOrTarget(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }
}
