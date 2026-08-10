package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Oak_sapling71;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public class EnchantingBookshelfListener implements Listener {

    private static final int REQUIRED = 25;
    private static final int RADIUS = 2;

    private final Oak_sapling71 oakSapling71;

    public EnchantingBookshelfListener(Oak_sapling71 oakSapling71) {
        this.oakSapling71 = oakSapling71;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material placed = e.getBlockPlaced().getType();
        if (placed != Material.BOOKSHELF && placed != Material.ENCHANTING_TABLE) return;

        Block table = placed == Material.ENCHANTING_TABLE
                ? e.getBlockPlaced()
                : findNearbyEnchantingTable(e.getBlockPlaced());
        if (table == null) return;

        if (countBookshelves(table.getLocation()) >= REQUIRED) {
            oakSapling71.grant(e.getPlayer());
        }
    }

    private Block findNearbyEnchantingTable(Block from) {
        Location origin = from.getLocation();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    Block b = origin.clone().add(dx, dy, dz).getBlock();
                    if (b.getType() == Material.ENCHANTING_TABLE) return b;
                }
            }
        }
        return null;
    }

    private int countBookshelves(Location tableLoc) {
        int count = 0;
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Block b = tableLoc.clone().add(dx, dy, dz).getBlock();
                    if (b.getType() == Material.BOOKSHELF) count++;
                }
            }
        }
        return count;
    }
}
