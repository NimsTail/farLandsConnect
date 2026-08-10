package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Raw_gold_block46;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Золотая лихорадка" — break a naturally-generated gold ore above Y=20.
 * Without excluding player-placed ore, this was trivially farmable: mine
 * gold ore anywhere, place it above Y20, break it again. Placed locations
 * are tracked in-memory (matches this codebase's other session-scoped
 * anti-cheese checks, e.g. PinkPetalCarpetListener) and only need to
 * survive until broken again, not across restarts.
 */
public class GoldAboveGroundListener implements Listener {

    private static final int MIN_Y = 20;

    private final Raw_gold_block46 rawGoldBlock46;
    private final Set<String> playerPlacedOre = ConcurrentHashMap.newKeySet();

    public GoldAboveGroundListener(Raw_gold_block46 rawGoldBlock46) {
        this.rawGoldBlock46 = rawGoldBlock46;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material type = e.getBlockPlaced().getType();
        if (type != Material.GOLD_ORE && type != Material.DEEPSLATE_GOLD_ORE) return;

        playerPlacedOre.add(locKey(e.getBlockPlaced().getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Material type = e.getBlock().getType();
        if (type != Material.GOLD_ORE && type != Material.DEEPSLATE_GOLD_ORE) return;
        if (e.getBlock().getY() <= MIN_Y) return;

        String key = locKey(e.getBlock().getLocation());
        if (playerPlacedOre.remove(key)) return;

        rawGoldBlock46.grant(e.getPlayer());
    }

    private static String locKey(Location loc) {
        var world = loc.getWorld();
        return (world == null ? "?" : world.getName()) + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
