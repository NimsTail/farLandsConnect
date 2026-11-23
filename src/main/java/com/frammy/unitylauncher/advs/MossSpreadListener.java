package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Mossy_cobblestone37;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class MossSpreadListener implements Listener {

    // Was 100 — BlockFertilizeEvent.getBlocks() reports every block a single
    // bonemeal use converts (moss spreads to ~20-40+ blocks per use in a
    // good spot), so 100 was reachable in ~3 clicks. Bumped 10x so it
    // actually takes sustained effort instead of a moment.
    private static final int REQUIRED = 1000;

    private final Mossy_cobblestone37 mossyCobblestone37;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public MossSpreadListener(UnityLauncher plugin, Mossy_cobblestone37 mossyCobblestone37) {
        this.mossyCobblestone37 = mossyCobblestone37;
        this.countKey = new NamespacedKey(plugin, "mossy37_count");
        this.doneKey = new NamespacedKey(plugin, "mossy37_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onFertilize(BlockFertilizeEvent e) {
        if (e.getBlock().getType() != Material.MOSS_BLOCK) return;

        Player p = e.getPlayer();
        if (p == null) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + e.getBlocks().size();
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            mossyCobblestone37.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
