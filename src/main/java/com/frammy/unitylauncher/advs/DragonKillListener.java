package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Oak_sapling84;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public class DragonKillListener implements Listener {

    private static final double CREDIT_RADIUS = 64.0;

    private final Oak_sapling84 oakSapling84;

    public DragonKillListener(Oak_sapling84 oakSapling84) {
        this.oakSapling84 = oakSapling84;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon dragon)) return;

        Player killer = dragon.getKiller();
        if (killer != null) {
            oakSapling84.grant(killer);
            return;
        }

        // Multiplayer dragon fights often leave getKiller() null (final hit
        // damage source ambiguous) — credit everyone still nearby instead.
        for (Player p : dragon.getLocation().getNearbyPlayers(CREDIT_RADIUS)) {
            oakSapling84.grant(p);
        }
    }
}
