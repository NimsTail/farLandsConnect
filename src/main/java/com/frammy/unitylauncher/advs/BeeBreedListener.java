package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling31;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class BeeBreedListener implements Listener {

    private static final int REQUIRED = 50;

    private final Oak_sapling31 oakSapling31;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public BeeBreedListener(UnityLauncher plugin, Oak_sapling31 oakSapling31) {
        this.oakSapling31 = oakSapling31;
        this.countKey = new NamespacedKey(plugin, "oaksapling31_count");
        this.doneKey = new NamespacedKey(plugin, "oaksapling31_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreed(EntityBreedEvent e) {
        if (!(e.getEntity() instanceof Bee)) return;
        if (!(e.getBreeder() instanceof Player p)) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            oakSapling31.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
