package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Shield66;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SamePlaceDeathListener implements Listener {

    // "То же место" — infra/frames-catalog.md §4: 5-block radius by default.
    private static final double RADIUS = 5.0;
    private static final int REQUIRED_STREAK = 3;

    private final Shield66 shield66;
    private final Map<UUID, Location> lastDeathLocation = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> streak = new ConcurrentHashMap<>();

    public SamePlaceDeathListener(Shield66 shield66) {
        this.shield66 = shield66;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        Location here = p.getLocation();
        Location last = lastDeathLocation.get(p.getUniqueId());

        boolean sameSpot = last != null
                && last.getWorld() != null
                && last.getWorld().equals(here.getWorld())
                && last.distance(here) <= RADIUS;

        int count = sameSpot ? streak.getOrDefault(p.getUniqueId(), 1) + 1 : 1;
        streak.put(p.getUniqueId(), count);
        lastDeathLocation.put(p.getUniqueId(), here);

        if (count >= REQUIRED_STREAK) {
            shield66.grant(p);
            streak.remove(p.getUniqueId());
        }
    }
}
