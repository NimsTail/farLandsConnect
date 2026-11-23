package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Shroomlight58;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class ShroomlightNetherWalkListener implements Listener {

    private static final double REQUIRED_METERS = 1000.0;

    private final Shroomlight58 shroomlight58;
    private final NamespacedKey distanceKey;
    private final NamespacedKey doneKey;

    public ShroomlightNetherWalkListener(UnityLauncher plugin, Shroomlight58 shroomlight58) {
        this.shroomlight58 = shroomlight58;
        this.distanceKey = new NamespacedKey(plugin, "shroomlight58_distance");
        this.doneKey = new NamespacedKey(plugin, "shroomlight58_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().distanceSquared(e.getTo()) == 0) return;

        Player p = e.getPlayer();
        if (p.getWorld().getEnvironment() != World.Environment.NETHER) return;
        // "В основной руке" — не офхенд.
        if (p.getInventory().getItemInMainHand().getType() != Material.SHROOMLIGHT) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        double delta = e.getFrom().distance(e.getTo());
        double total = pdc.getOrDefault(distanceKey, PersistentDataType.DOUBLE, 0.0) + delta;
        pdc.set(distanceKey, PersistentDataType.DOUBLE, total);

        if (total >= REQUIRED_METERS) {
            shroomlight58.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
