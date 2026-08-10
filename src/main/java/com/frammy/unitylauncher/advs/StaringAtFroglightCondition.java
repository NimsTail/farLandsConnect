package com.frammy.unitylauncher.advs;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** "Неподвижно смотри на жёлтый жабосвет" — looking at an ochre froglight
 * within short range, and hasn't moved since the last check. */
public class StaringAtFroglightCondition implements Predicate<Player> {

    private static final double LOOK_RANGE = 6.0;

    private final Map<UUID, Location> lastLocation = new HashMap<>();

    @Override
    public boolean test(Player p) {
        Location current = p.getLocation();
        Location last = lastLocation.put(p.getUniqueId(), current);

        boolean stayedStill = last != null
                && last.getWorld() != null
                && last.getWorld().equals(current.getWorld())
                && last.distanceSquared(current) < 0.01;
        if (!stayedStill) return false;

        RayTraceResult hit = p.rayTraceBlocks(LOOK_RANGE);
        if (hit == null || hit.getHitBlock() == null) return false;
        return hit.getHitBlock().getType() == Material.OCHRE_FROGLIGHT;
    }
}
