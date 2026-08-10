package com.frammy.unitylauncher.advs;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Generic "eat N of this item" counter — covers Dried_kelp35 (2000 dried
 * kelp) and Oak_sapling34 (500 rotten flesh); both are plain cumulative
 * consumption counts with no other condition.
 */
public class ConsumeCounterListener implements Listener {

    private final Material item;
    private final int required;
    private final FrameRewardAdvancement advancement;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public ConsumeCounterListener(Plugin plugin, String keyPrefix, Material item, int required,
                                   FrameRewardAdvancement advancement) {
        this.item = item;
        this.required = required;
        this.advancement = advancement;
        this.countKey = new NamespacedKey(plugin, keyPrefix + "_count");
        this.doneKey = new NamespacedKey(plugin, keyPrefix + "_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (e.getItem().getType() != item) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= required) {
            advancement.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
