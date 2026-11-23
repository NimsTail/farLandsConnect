package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling45;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class RawIronMiningListener implements Listener {

    private static final int REQUIRED = 500;

    private final Oak_sapling45 oakSapling45;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public RawIronMiningListener(UnityLauncher plugin, Oak_sapling45 oakSapling45) {
        this.oakSapling45 = oakSapling45;
        this.countKey = new NamespacedKey(plugin, "oaksapling45_count");
        this.doneKey = new NamespacedKey(plugin, "oaksapling45_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Material type = e.getBlock().getType();
        if (type != Material.IRON_ORE && type != Material.DEEPSLATE_IRON_ORE) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            oakSapling45.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
