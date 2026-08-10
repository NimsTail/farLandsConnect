package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling87;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class GuardianKillListener implements Listener {

    private static final int REQUIRED = 1000;

    private final Oak_sapling87 oakSapling87;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    public GuardianKillListener(UnityLauncher plugin, Oak_sapling87 oakSapling87) {
        this.oakSapling87 = oakSapling87;
        this.countKey = new NamespacedKey(plugin, "oaksapling87_count");
        this.doneKey = new NamespacedKey(plugin, "oaksapling87_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        // ElderGuardian extends Guardian, so this covers both.
        if (!(e.getEntity() instanceof Guardian)) return;
        Player p = e.getEntity().getKiller();
        if (p == null) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            oakSapling87.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
