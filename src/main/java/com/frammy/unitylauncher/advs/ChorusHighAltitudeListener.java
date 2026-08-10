package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Oak_sapling33;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;

public class ChorusHighAltitudeListener implements Listener {

    private static final double MIN_Y = 500;

    private final Oak_sapling33 oakSapling33;

    public ChorusHighAltitudeListener(Oak_sapling33 oakSapling33) {
        this.oakSapling33 = oakSapling33;
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (e.getItem().getType() != Material.CHORUS_FRUIT) return;
        if (e.getPlayer().getLocation().getY() <= MIN_Y) return;

        oakSapling33.grant(e.getPlayer());
    }
}
