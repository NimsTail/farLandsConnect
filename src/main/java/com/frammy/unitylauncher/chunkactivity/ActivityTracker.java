package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityTracker implements Listener {

    private final Map<String, ChunkStats> chunkStatsMap = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerChunkSession> playerChunks = new HashMap<>();

    private final UnityLauncher plugin;

    public ActivityTracker(UnityLauncher plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startAutoSave();
    }

    private void startAutoSave() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveAllToDisk, 20 * 60 * 5L, 20 * 60 * 5L); // каждые 5 минут
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getChunk().equals(event.getTo().getChunk())) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Chunk toChunk = event.getTo().getChunk();
        Chunk fromChunk = event.getFrom().getChunk();

        long now = System.currentTimeMillis();
        PlayerChunkSession session = playerChunks.get(uuid);

        if (session != null && session.currentChunk.equals(getChunkKey(fromChunk))) {
            long timeSpent = now - session.enterTime;
            getStats(fromChunk).addTime(timeSpent);
        }

        playerChunks.put(uuid, new PlayerChunkSession(getChunkKey(toChunk), now));
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Chunk chunk = event.getBlock().getChunk();
        getStats(chunk).blocksPlaced++;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Chunk chunk = event.getBlock().getChunk();
        getStats(chunk).blocksBroken++;
    }

    private ChunkStats getStats(Chunk chunk) {
        return chunkStatsMap.computeIfAbsent(getChunkKey(chunk), k -> new ChunkStats());
    }

    private String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + "," + chunk.getZ();
    }

    public Map<String, ChunkStats> getChunkStatsMap() {
        return chunkStatsMap;
    }

    public void saveAllToDisk() {
        ChunkActivityStorage.saveToFile(chunkStatsMap, plugin.getDataFolder());
    }
}
