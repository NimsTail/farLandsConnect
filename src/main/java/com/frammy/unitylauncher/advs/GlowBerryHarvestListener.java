package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling32;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.CaveVines;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Вырастить и собрать 50 светящихся ягод с собственной лозы" — cave vines
 * grow straight down from where planted, and each grown segment is its own
 * block with no per-block ownership data (plain plant blocks aren't
 * TileState, so no PersistentDataContainer on the block itself). Ownership
 * is approximated by X/Z column: remembers which columns a player planted a
 * cave vine in (session-only, resets on plugin/server restart) and counts a
 * berry harvest anywhere in that column.
 */
public class GlowBerryHarvestListener implements Listener {

    private static final int REQUIRED = 50;

    private final Oak_sapling32 oakSapling32;
    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;
    private final Map<String, UUID> plantedColumns = new ConcurrentHashMap<>();

    public GlowBerryHarvestListener(UnityLauncher plugin, Oak_sapling32 oakSapling32) {
        this.oakSapling32 = oakSapling32;
        this.countKey = new NamespacedKey(plugin, "oaksapling32_count");
        this.doneKey = new NamespacedKey(plugin, "oaksapling32_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlant(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.CAVE_VINES) return;
        Block b = e.getBlockPlaced();
        plantedColumns.put(columnKey(b.getWorld().getName(), b.getX(), b.getZ()), e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Material type = e.getBlock().getType();
        if (type != Material.CAVE_VINES && type != Material.CAVE_VINES_PLANT) return;
        if (!(e.getBlock().getBlockData() instanceof CaveVines caveVines) || !caveVines.isBerries()) return;

        Block b = e.getBlock();
        UUID owner = plantedColumns.get(columnKey(b.getWorld().getName(), b.getX(), b.getZ()));
        if (owner == null || !owner.equals(e.getPlayer().getUniqueId())) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            oakSapling32.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    private String columnKey(String world, int x, int z) {
        return world + ":" + x + ":" + z;
    }
}
