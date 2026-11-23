package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_3;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

public class VineClimbListener implements Listener {

    private static final double REQUIRED_METERS = 5000.0; // 5 км

    private static final Set<Material> CLIMBABLE = Set.of(
            Material.VINE, Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
            Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
            Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT
    );

    private final Ach1_3 ach1_3;
    private final NamespacedKey distanceKey;
    private final NamespacedKey doneKey;

    public VineClimbListener(UnityLauncher plugin, Ach1_3 ach1_3) {
        this.ach1_3 = ach1_3;
        this.distanceKey = new NamespacedKey(plugin, "ach1_3_distance");
        this.doneKey = new NamespacedKey(plugin, "ach1_3_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().distanceSquared(e.getTo()) == 0) return;

        Player p = e.getPlayer();
        if (!CLIMBABLE.contains(p.getLocation().getBlock().getType())) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        double delta = e.getFrom().distance(e.getTo());
        double total = pdc.getOrDefault(distanceKey, PersistentDataType.DOUBLE, 0.0) + delta;
        pdc.set(distanceKey, PersistentDataType.DOUBLE, total);

        if (total >= REQUIRED_METERS) {
            ach1_3.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}
