package com.frammy.unitylauncher.advs;

import io.papermc.paper.event.player.PlayerTradeEvent;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_7;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class WheatTradeListener implements Listener {

    private static final int REQUIRED = 1000;

    private final Ach1_7 ach1_7;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public WheatTradeListener(UnityLauncher plugin, Ach1_7 ach1_7) {
        this.ach1_7 = ach1_7;
        this.countKey = new NamespacedKey(plugin, "ach1_7_wheat_count");
        this.doneKey = new NamespacedKey(plugin, "ach1_7_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent e) {
        int wheatAmount = e.getTrade().getIngredients().stream()
                .filter(item -> item.getType() == Material.WHEAT)
                .mapToInt(ItemStack::getAmount)
                .sum();
        if (wheatAmount <= 0) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + wheatAmount;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            ach1_7.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
