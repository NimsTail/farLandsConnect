package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ActivityTracker implements Listener {

    private final UnityLauncher plugin;
    private final ActivityWeights weights = new ActivityWeights();
    private final LandValueWeights landValueWeights = new LandValueWeights();

    // Подключается ПОСЛЕ конструктора (UnityLauncher создаёт ActivityTracker раньше
    // ZoneManager) — используется только для того, чтобы приглушить вес визита
    // владельца/согражданина зоны (см. computeVisitorWeight). Может быть null
    // в короткое окно между стартом плагина и вызовом setZoneManager — тогда
    // просто используем обычный вес 1.0, ничего не ломается.
    private com.frammy.unitylauncher.zones.ZoneManager zoneManager;
    private static final double MEMBER_VISITOR_WEIGHT = 0.25;

    public void setZoneManager(com.frammy.unitylauncher.zones.ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
    }

    // ключ: "world:x,z"
    private final Map<String, ChunkStats> chunkStatsMap = new ConcurrentHashMap<>();

    // main-thread only (сессии игроков)
    private final Map<UUID, PlayerChunkSession> sessions = new HashMap<>();

    private BukkitTask saveTask;
    private BukkitTask coolingTask;
    private BukkitTask hourlySampleTask;
    private BukkitTask visitorResetTask;
    private final BukkitTask pruneTask;
    public ActivityTracker(UnityLauncher plugin) {
        this.plugin = plugin;

        // загрузка
        Map<String, ChunkStats> loaded = ChunkActivityStorage.loadFromFile(plugin.getDataFolder());
        if (!loaded.isEmpty()) {
            chunkStatsMap.putAll(loaded);
            plugin.getLogger().info("[Heatmap] Loaded " + loaded.size() + " chunks from disk");
        } else {
            plugin.getLogger().info("[Heatmap] No previous chunk stats found");
        }
        pruneTask = Bukkit.getScheduler().runTaskTimer(plugin, this::pruneOldChunksSync, 20L*60L*60L, 20L*60L*60L);

        startTasks();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        // стартуем сессию сразу, даже если игрок стоит AFK
        sessions.put(p.getUniqueId(), new PlayerChunkSession(getChunkKey(p.getLocation().getChunk()), now));
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        closeSession(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            sessions.put(p.getUniqueId(), new PlayerChunkSession(getChunkKey(p.getLocation().getChunk()), System.currentTimeMillis()));
        });
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        closeSession(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            sessions.put(p.getUniqueId(), new PlayerChunkSession(getChunkKey(p.getLocation().getChunk()), System.currentTimeMillis()));
        });
    }

    public ActivityWeights getWeights() { return weights; }
    public LandValueWeights getLandValueWeights() { return landValueWeights; }
    public Map<String, ChunkStats> getChunkStatsMap() { return chunkStatsMap; }

    public void stop() {
        if (saveTask != null) { saveTask.cancel(); saveTask = null; }
        if (coolingTask != null) { coolingTask.cancel(); coolingTask = null; }
        if (hourlySampleTask != null) { hourlySampleTask.cancel(); hourlySampleTask = null; }
        if (visitorResetTask != null) { visitorResetTask.cancel(); visitorResetTask = null; }
        if (pruneTask != null) pruneTask.cancel();

        for (Player p : Bukkit.getOnlinePlayers()) {
            closeSession(p);
        }

        saveAllToDisk();
        sessions.clear();
    }

    private void pruneOldChunksSync() {
        long now = System.currentTimeMillis();
        long keepMs = 14L * 24L * 60L * 60L * 1000L;

        // если значение в целом мизерное — считаем нулём
        final double EPS = 0.0001;

        chunkStatsMap.entrySet().removeIf(e -> {
            ChunkStats s = e.getValue();
            if (s == null) return true;

            boolean old = (now - s.lastUpdated) > keepMs;
            if (!old) return false;

            // вместо "history empty" — проверяем "history почти нулевая"
            double avg = s.hourlySamples.isEmpty()
                    ? weights.calculateValue(s)
                    : s.hourlySamples.stream().mapToDouble(d -> d).average().orElse(0.0);

            boolean tinyHistory = Math.abs(avg) <= EPS;

            boolean zeroNow =
                    s.timeSpent == 0 &&
                            s.blocksPlaced == 0 &&
                            s.blocksBroken == 0 &&
                            s.itemDrops == 0 &&
                            s.entitySpawns == 0 &&
                            s.tickLoad == 0.0 &&
                            s.playerActivity == 0.0 &&
                            s.structureBonus == 0.0;

            return tinyHistory && zeroNow;
        });
    }

    private void startTasks() {
        // cooling + hourly sample — СИНХРОННО (без гонок)
        coolingTask = Bukkit.getScheduler().runTaskTimer(plugin, this::applyCoolingSync,
                20L * 60L * 10L, 20L * 60L * 10L);

        long hour = 20L * 60L * 60L;
        hourlySampleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::recordHourlySamplesSync,
                hour, hour);

        // disk save — async, но с snapshot
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Map<String, ChunkStats> snapshot = snapshotSync(); // тут мы уже в main thread
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> ChunkActivityStorage.saveToFile(snapshot, plugin.getDataFolder()));
        }, 20L * 60L * 5L, 20L * 60L * 5L);

        // окно уникальных посетителей — раз в неделю, отдельно от угасающей активности
        long week = 20L * 60L * 60L * 24L * 7L;
        visitorResetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::resetVisitorWindowsSync, week, week);
    }

    private void resetVisitorWindowsSync() {
        for (ChunkStats s : chunkStatsMap.values()) s.resetVisitorWindow();
        plugin.getLogger().info("[Heatmap] Недельное окно уникальных посетителей сброшено");
    }

    private void applyCoolingSync() {
        long now = System.currentTimeMillis();
        for (ChunkStats s : chunkStatsMap.values()) {
            s.applyCooling(now);
        }
    }

    private void recordHourlySamplesSync() {
        for (ChunkStats s : chunkStatsMap.values()) {
            s.recordHourlySample(weights);
        }
        plugin.getLogger().info("[Heatmap] Hourly samples recorded");
    }

    public void forceSampleNow() {
        recordHourlySamplesSync();
        plugin.getLogger().info("[Heatmap] Forced hourly sample recorded");
    }

    public void saveAllToDisk() {
        // синхронный snapshot + sync save тоже норм, но оставим async задачу как основную
        ChunkActivityStorage.saveToFile(snapshotSync(), plugin.getDataFolder());
    }

    private Map<String, ChunkStats> snapshotSync() {
        // вызывается из async (saveTask), поэтому snapshot строим через sync-call
        // но Bukkit API из async трогать нельзя — мы не трогаем, только данные.
        // ВАЖНО: ChunkStats мутабельный — делаем deep copy.
        Map<String, ChunkStats> out = new HashMap<>(chunkStatsMap.size());
        for (Map.Entry<String, ChunkStats> e : chunkStatsMap.entrySet()) {
            out.put(e.getKey(), e.getValue().copy());
        }
        return out;
    }

    /* ===================== PUBLIC API (дергают listeners) ===================== */

    public void addTimeInChunk(String chunkKey, long millis) {
        if (millis <= 0) return;
        ChunkStats s = chunkStatsMap.computeIfAbsent(chunkKey, k -> new ChunkStats());
        s.addTime(millis);
    }

    public void incBlocksPlaced(Chunk chunk, Material material, int amount) {
        if (chunk == null || amount <= 0) return;
        ChunkStats s = statsFor(chunk);
        s.blocksPlaced += amount;
        s.addBuildVolume(BuildMaterialWeights.weightFor(material) * amount, 0);
        s.recordMaterialPlaced(material);
        s.lastUpdated = System.currentTimeMillis();
    }

    public void incBlocksBroken(Chunk chunk, Material material, int amount) {
        if (chunk == null || amount <= 0) return;
        ChunkStats s = statsFor(chunk);
        s.blocksBroken += amount;
        s.addBuildVolume(0, BuildMaterialWeights.weightFor(material) * amount);
        s.lastUpdated = System.currentTimeMillis();
    }

    public void incItemDrops(Chunk chunk, int amount) {
        if (chunk == null || amount <= 0) return;
        ChunkStats s = statsFor(chunk);
        s.itemDrops += amount;
        s.lastUpdated = System.currentTimeMillis();
    }

    public void incEntitySpawns(Chunk chunk, int count) {
        if (chunk == null || count <= 0) return;
        ChunkStats s = statsFor(chunk);
        s.entitySpawns += count;
        s.lastUpdated = System.currentTimeMillis();
    }

    public void incTickLoad(Chunk chunk, double loadUnits) {
        if (chunk == null || loadUnits <= 0) return;
        ChunkStats s = statsFor(chunk);
        s.tickLoad += loadUnits; // multiplier потом подключишь
        s.lastUpdated = System.currentTimeMillis();
    }

    public void recordPlayerActivity(Chunk chunk, double score) {
        if (chunk == null || score <= 0) return;
        ChunkStats s = statsFor(chunk);
        s.playerActivity += score;
        s.lastUpdated = System.currentTimeMillis();
    }

    public String getChunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + ":" + chunk.getX() + "," + chunk.getZ();
    }

    private ChunkStats statsFor(Chunk chunk) {
        return chunkStatsMap.computeIfAbsent(getChunkKey(chunk), k -> new ChunkStats());
    }

    /* ===================== SESSIONS (time spent) ===================== */

    public void handleMoveChunk(Player player, Location from, Location to) {
        if (player == null || from == null || to == null || to.getWorld() == null) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // если сессии ещё нет — стартуем её на текущем чанке
        PlayerChunkSession prev = sessions.get(uuid);
        if (prev == null) {
            Chunk cur = to.getChunk();
            sessions.put(uuid, new PlayerChunkSession(getChunkKey(cur), now));
            statsFor(cur).addVisitor(uuid, computeVisitorWeight(player, to));
            return;
        }

        Chunk fromChunk = from.getChunk();
        Chunk toChunk = to.getChunk();
        if (fromChunk.equals(toChunk)) return;

        long spent = now - prev.enterTime();
        addTimeInChunk(prev.currentChunk(), spent);
        sessions.put(uuid, new PlayerChunkSession(getChunkKey(toChunk), now));

        // "ценность земли" через трафик — уникальные посетители чанка, а НЕ
        // время владельца в нём (см. LandValueWeights)
        statsFor(toChunk).addVisitor(uuid, computeVisitorWeight(player, to));
    }

    /**
     * Обычный посторонний визит весит 1.0. Если игрок — владелец зоны в этой
     * точке, либо гражданин страны, которой принадлежит эта зона, вес сильно
     * ниже: иначе хозяин, просто играя у себя дома, сам накручивает себе же
     * налог на землю через "трафик". Вне зон (или пока ZoneManager ещё не
     * подключен на старте плагина) — обычный вес 1.0: земля без владельца не
     * может иметь "своего", чей визит нужно было бы приглушать.
     */
    private double computeVisitorWeight(Player player, Location loc) {
        if (zoneManager != null && zoneManager.isOwnerOrMemberAt(loc, player.getName())) {
            return MEMBER_VISITOR_WEIGHT;
        }
        return 1.0;
    }

    private void closeSession(Player p) {
        UUID uuid = p.getUniqueId();
        PlayerChunkSession prev = sessions.remove(uuid);
        if (prev == null) return;

        long now = System.currentTimeMillis();
        long spent = now - prev.enterTime();
        addTimeInChunk(prev.currentChunk(), spent);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { closeSession(e.getPlayer()); }
    @EventHandler public void onKick(PlayerKickEvent e) { closeSession(e.getPlayer()); }
    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        closeSession(e.getPlayer());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = e.getPlayer();
            if (!p.isOnline()) return;
            Location to = p.getLocation();
            sessions.put(p.getUniqueId(), new PlayerChunkSession(getChunkKey(to.getChunk()), System.currentTimeMillis()));
        });
    }

}
