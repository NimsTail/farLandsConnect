package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Pearlescent_froglight74;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Set;

/**
 * "Поставить фиолетовый жабосвет там, где в радиусе 50 блоков нет других
 * источников света" — checks for known light-emitting block types rather
 * than raw light level (propagation makes that noisy). NOTE: a full 50-block
 * radius scan is ~1M block lookups worst case — capped vertical range to
 * ±10 and skips unloaded chunks to keep the (rare, deliberate) placement
 * from causing a real lag spike; still worth watching in practice.
 */
public class LonelyFroglightListener implements Listener {

    private static final int RADIUS_H = 50;
    private static final int RADIUS_V = 10;

    private static final Set<Material> LIGHT_SOURCES = Set.of(
            Material.TORCH, Material.WALL_TORCH, Material.SOUL_TORCH, Material.SOUL_WALL_TORCH,
            Material.LANTERN, Material.SOUL_LANTERN, Material.GLOWSTONE, Material.SEA_LANTERN,
            Material.SHROOMLIGHT, Material.OCHRE_FROGLIGHT, Material.VERDANT_FROGLIGHT,
            Material.PEARLESCENT_FROGLIGHT, Material.LAVA, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.JACK_O_LANTERN, Material.REDSTONE_LAMP, Material.END_ROD, Material.BEACON,
            Material.CONDUIT, Material.GLOW_LICHEN, Material.CRYING_OBSIDIAN, Material.RESPAWN_ANCHOR,
            Material.AMETHYST_CLUSTER
    );

    private final Pearlescent_froglight74 pearlescentFroglight74;

    public LonelyFroglightListener(Pearlescent_froglight74 pearlescentFroglight74) {
        this.pearlescentFroglight74 = pearlescentFroglight74;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.PEARLESCENT_FROGLIGHT) return;

        Location origin = e.getBlockPlaced().getLocation();
        if (hasNearbyLightSource(origin)) return;

        pearlescentFroglight74.grant(e.getPlayer());
    }

    private boolean hasNearbyLightSource(Location origin) {
        var world = origin.getWorld();
        if (world == null) return true;

        for (int dx = -RADIUS_H; dx <= RADIUS_H; dx++) {
            for (int dz = -RADIUS_H; dz <= RADIUS_H; dz++) {
                if (!world.isChunkLoaded((origin.getBlockX() + dx) >> 4, (origin.getBlockZ() + dz) >> 4)) continue;
                for (int dy = -RADIUS_V; dy <= RADIUS_V; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Block b = world.getBlockAt(origin.getBlockX() + dx, origin.getBlockY() + dy, origin.getBlockZ() + dz);
                    if (LIGHT_SOURCES.contains(b.getType())) return true;
                }
            }
        }
        return false;
    }
}
