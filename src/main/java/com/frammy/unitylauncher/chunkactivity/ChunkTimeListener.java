package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public final class ChunkTimeListener implements Listener {
    private final ActivityTracker tracker;
    public ChunkTimeListener(ActivityTracker tracker) { this.tracker = tracker; }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        tracker.handleMoveChunk(e.getPlayer(), e.getFrom(), e.getTo());
    }
}
