package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Sculk68;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class SculkDestroyListener implements Listener {

    private static final int REQUIRED = 1000;

    private final Sculk68 sculk68;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public SculkDestroyListener(UnityLauncher plugin, Sculk68 sculk68) {
        this.sculk68 = sculk68;
        this.countKey = new NamespacedKey(plugin, "sculk68_count");
        this.doneKey = new NamespacedKey(plugin, "sculk68_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.SCULK) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            sculk68.grant(p); // выдаст обе рамки — см. Sculk68.getFrameIds()
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
