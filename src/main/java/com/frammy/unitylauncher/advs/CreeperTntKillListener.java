package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Tnt54;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;

public class CreeperTntKillListener implements Listener {

    private static final double CREDIT_RADIUS = 10.0;

    private final Tnt54 tnt54;

    public CreeperTntKillListener(Tnt54 tnt54) {
        this.tnt54 = tnt54;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof Creeper creeper)) return;
        if (!(creeper.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity)) return;
        if (!(byEntity.getDamager() instanceof TNTPrimed)) return;

        // Vanilla doesn't track "who lit this TNT" on the entity itself —
        // approximate ownership by nearest player at the moment of the kill.
        Player nearest = creeper.getLocation().getNearbyPlayers(CREDIT_RADIUS).stream().findFirst().orElse(null);
        if (nearest != null) {
            tnt54.grant(nearest);
        }
    }
}
