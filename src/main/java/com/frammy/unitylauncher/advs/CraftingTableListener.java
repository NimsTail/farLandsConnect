package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling28;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class CraftingTableListener implements Listener {

    private static final int REQUIRED = 500;
    // Personal 2x2 crafting grid inventories report size 5 (4 grid + result);
    // a 3x3 crafting table reports 10 (9 grid + result) — only count those.
    private static final int TABLE_INVENTORY_SIZE = 10;

    private final Oak_sapling28 oakSapling28;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public CraftingTableListener(UnityLauncher plugin, Oak_sapling28 oakSapling28) {
        this.oakSapling28 = oakSapling28;
        this.countKey = new NamespacedKey(plugin, "oaksapling28_count");
        this.doneKey = new NamespacedKey(plugin, "oaksapling28_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getInventory() instanceof CraftingInventory craftingInventory)) return;
        if (craftingInventory.getSize() < TABLE_INVENTORY_SIZE) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRecipe() == null || e.getRecipe().getResult() == null) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int crafted = e.getRecipe().getResult().getAmount();
        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + crafted;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            oakSapling28.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
