package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Tropical_fish51;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class TropicalFishCatchListener implements Listener {

    private static final int REQUIRED = 250;

    private final Tropical_fish51 tropicalFish51;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public TropicalFishCatchListener(UnityLauncher plugin, Tropical_fish51 tropicalFish51) {
        this.tropicalFish51 = tropicalFish51;
        this.countKey = new NamespacedKey(plugin, "tropicalfish51_count");
        this.doneKey = new NamespacedKey(plugin, "tropicalfish51_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(e.getCaught() instanceof Item item)) return;
        if (item.getItemStack().getType() != Material.TROPICAL_FISH) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            tropicalFish51.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
