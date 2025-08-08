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
    private final ActivityWeights weights = new ActivityWeights();

    private final UnityLauncher plugin;

    public ActivityWeights getWeights() {
        return weights;
    }
    public ActivityTracker(UnityLauncher plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        startAutoSave();
        startDailySampleRecording();
    }

    private void startAutoSave() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveAllToDisk, 20 * 60 * 5L, 20 * 60 * 5L); // каждые 5 минут
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::applyCoolingToAll, 20 * 60 * 10L, 20 * 60 * 10L); // каждые 10 мин
    }
    private void startDailySampleRecording() {
        long oneDay = 20L * 60 * 60 * 24;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (ChunkStats stats : chunkStatsMap.values()) {
                stats.recordDailySample(getWeights());
            }
            Bukkit.getLogger().info("[Heatmap] Сохранён ежедневный срез активности.");
        }, oneDay, oneDay);
    }

    public void applyCoolingToAll() {
        long now = System.currentTimeMillis();
        double decayRatePerHour = 0.9; // уменьшается на 10% каждый час

        for (ChunkStats stats : chunkStatsMap.values()) {
            stats.applyCooling(now);
        }
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
        getStats(chunk).incrementPlace();
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Chunk chunk = event.getBlock().getChunk();
        getStats(chunk).incrementBreak();
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
