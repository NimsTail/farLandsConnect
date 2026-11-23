package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Feather62;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "Погибнуть, упав с максимальной высоты мира до его минимальной точки" —
 * tracks each player's highest Y since they were last on the ground, then on
 * a fall death checks the drop covered (most of) the world's full height.
 */
public class FallDeathListener implements Listener {

    private static final double TOLERANCE = 8.0;

    private final Feather62 feather62;
    private final Map<UUID, Double> peakY = new HashMap<>();

    public FallDeathListener(Feather62 feather62) {
        this.feather62 = feather62;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.isOnGround()) {
            peakY.put(p.getUniqueId(), p.getLocation().getY());
            return;
        }
        double current = p.getLocation().getY();
        double known = peakY.getOrDefault(p.getUniqueId(), current);
        if (current > known) {
            peakY.put(p.getUniqueId(), current);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        EntityDamageEvent cause = p.getLastDamageCause();
        if (cause == null || cause.getCause() != EntityDamageEvent.DamageCause.FALL) return;

        double from = peakY.getOrDefault(p.getUniqueId(), p.getLocation().getY());
        double to = p.getLocation().getY();
        double worldRange = p.getWorld().getMaxHeight() - p.getWorld().getMinHeight();

        if (from - to >= worldRange - TOLERANCE) {
            feather62.grant(p);
        }
    }
}
