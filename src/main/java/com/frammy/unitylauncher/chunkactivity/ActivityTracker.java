package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityTracker implements Listener {
    private final Map<String, ChunkStats> chunkStatsMap = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerChunkSession> playerChunks = new HashMap<>();
    private final UnityLauncher plugin;
    private final ActivityWeights weights = new ActivityWeights(); // + геттер, если надо
    public ActivityWeights getWeights() { return weights; }


    public ActivityTracker(UnityLauncher plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Загрузка сохранённой статистики
        Map<String, ChunkStats> loaded = ChunkActivityStorage.loadFromFile(plugin.getDataFolder());
        if (!loaded.isEmpty()) {
            chunkStatsMap.putAll(loaded);
            Bukkit.getLogger().info("[Heatmap] Loaded " + loaded.size() + " chunks from disk");
        } else {
            Bukkit.getLogger().info("[Heatmap] No previous chunk stats found");
        }

        startAutoSave();
        startHourlySampling();
    }

    private void startHourlySampling() {
        long hour = 20L * 60 * 60; // 1 час в тиках
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            for (ChunkStats stats : chunkStatsMap.values()) {
                stats.recordHourlySample(weights);
            }
            Bukkit.getLogger().info("[Heatmap] Hourly samples recorded");
        }, hour, hour);
    }

    // Для быстрой проверки в онлайне: можешь вызвать из команды
    public void forceSampleNow() {
        for (ChunkStats stats : chunkStatsMap.values()) {
            stats.recordHourlySample(weights);
        }
        Bukkit.getLogger().info("[Heatmap] Forced hourly sample recorded");
    }


    private void startAutoSave() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveAllToDisk, 20 * 60 * 5L, 20 * 60 * 5L); // каждые 5 минут
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::applyCoolingToAll, 20 * 60 * 10L, 20 * 60 * 10L); // каждые 10 мин
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

        if (session != null && session.currentChunk().equals(getChunkKey(fromChunk))) {
            long timeSpent = now - session.enterTime();
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

    // Удобный хелпер: получить/создать статистику чанка
    private ChunkStats statsFor(Chunk chunk) {
        if (chunk == null) return null;
        return getStats(chunk); // getStats уже делает computeIfAbsent по String-ключу
    }

    /** Изменение блоков (place/break) с бонусом структуры. */
    public void incBlocksChanged(Chunk chunk, int blocksDelta, double structureBonus) {
        ChunkStats s = statsFor(chunk);
        if (s == null) return;

        int abs = Math.abs(blocksDelta);
        if (blocksDelta >= 0) {
            s.blocksPlaced += abs;
        } else {
            s.blocksBroken += abs;
        }
        s.structureBonus += structureBonus * abs;
        s.lastUpdated = System.currentTimeMillis();
    }

    /** Выпавшие предметы (ItemSpawnEvent). */
    public void incItemDrops(Chunk chunk, int amount) {
        if (amount <= 0) return;
        ChunkStats s = statsFor(chunk);
        if (s == null) return;
        s.itemDrops += amount;
        s.lastUpdated = System.currentTimeMillis();
    }

    /** Спавн мобов. */
    public void incEntitySpawns(Chunk chunk, int count) {
        if (count <= 0) return;
        ChunkStats s = statsFor(chunk);
        if (s == null) return;
        s.entitySpawns += count;
        s.lastUpdated = System.currentTimeMillis();
    }

    /** Тиковая нагрузка от воронок / печек / редстоуна. */
    public void incTickLoad(Chunk chunk, double loadUnits) {
        if (loadUnits <= 0) return;
        ChunkStats s = statsFor(chunk);
        if (s == null) return;

        // Применяем множитель энергосбережения из апгрейда (если есть)
        double finalLoad = loadUnits * getEnergySavingMultiplier(chunk);

        s.tickLoad += finalLoad;
        s.lastUpdated = System.currentTimeMillis();
    }

    /** Получить множитель энергосбережения для чанка (из апгрейда страны). */
    private double getEnergySavingMultiplier(Chunk chunk) {
        try {
            var upgradesConfig = com.frammy.unitylauncher.upgrades.UpgradesConfig.get();
            var location = chunk.getBlock(8, 64, 8).getLocation();
            String country = com.frammy.unitylauncher.upgrades.UpgradeCondition.locationCountryOwner(location);

            if (country != null && !country.isBlank()) {
                int level = com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel(
                    country,
                    upgradesConfig.energySavingPerm,
                    1
                );
                if (level >= 1) {
                    return upgradesConfig.energySavingMultiplier;
                }
            }
        } catch (Throwable ignored) {
            // если что-то сломалось, возвращаем 1.0 (без бонуса)
        }
        return 1.0;
    }

    /** Активность игроков в чанке (дискретные события). */
    public void recordPlayerActivity(Chunk chunk, String playerName, double activityScore) {
        if (activityScore <= 0) return;
        ChunkStats s = statsFor(chunk);
        if (s == null) return;
        s.playerActivity += activityScore;
        s.lastUpdated = System.currentTimeMillis();
    }
}
