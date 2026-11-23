package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Oak_sapling52;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class ScaffoldingMaxHeightListener implements Listener {

    private final Oak_sapling52 oakSapling52;

    public ScaffoldingMaxHeightListener(Oak_sapling52 oakSapling52) {
        this.oakSapling52 = oakSapling52;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        int maxHeight = p.getWorld().getMaxHeight();
        if (p.getLocation().getBlockY() < maxHeight - 2) return;

        var below = p.getLocation().clone().subtract(0, 1, 0).getBlock();
        if (below.getType() != Material.SCAFFOLDING) return;

        oakSapling52.grant(p);
    }
}
