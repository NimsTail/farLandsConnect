package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Oak_sapling53;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * "Активировать сразу 30 поршней одним рычагом" — a lever flip opens a
 * short collection window; any BlockPistonExtendEvent within RADIUS of that
 * lever during the window counts toward it. Radius-bounded so unrelated
 * redstone elsewhere on the server doesn't get credited to someone else's
 * lever flip.
 */
public class LeverPistonListener implements Listener {

    private static final int REQUIRED = 30;
    private static final double RADIUS = 32.0;
    private static final long WINDOW_TICKS = 3L;

    private static final class Window {
        final Player player;
        final Location leverLoc;
        int count = 0;

        Window(Player player, Location leverLoc) {
            this.player = player;
            this.leverLoc = leverLoc;
        }
    }

    private final UnityLauncher plugin;
    private final Oak_sapling53 oakSapling53;
    private final List<Window> openWindows = new ArrayList<>();

    public LeverPistonListener(UnityLauncher plugin, Oak_sapling53 oakSapling53) {
        this.plugin = plugin;
        this.oakSapling53 = oakSapling53;
    }

    @EventHandler(ignoreCancelled = true)
    public void onLever(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.LEVER) return;

        Window window = new Window(e.getPlayer(), e.getClickedBlock().getLocation());
        openWindows.add(window);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            openWindows.remove(window);
            if (window.count >= REQUIRED) {
                oakSapling53.grant(window.player);
            }
        }, WINDOW_TICKS);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent e) {
        for (Window w : openWindows) {
            if (e.getBlock().getLocation().distance(w.leverLoc) <= RADIUS) {
                w.count++;
            }
        }
    }
}
