package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling86;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class ShulkerKillListener implements Listener {

    private static final int REQUIRED = 5;

    private final Oak_sapling86 oakSapling86;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public ShulkerKillListener(UnityLauncher plugin, Oak_sapling86 oakSapling86) {
        this.oakSapling86 = oakSapling86;
        this.countKey = new NamespacedKey(plugin, "oaksapling86_count");
        this.doneKey = new NamespacedKey(plugin, "oaksapling86_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Shulker)) return;
        Player p = e.getEntity().getKiller();
        if (p == null) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            oakSapling86.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
