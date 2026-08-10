package com.frammy.unitylauncher.bluemapheat;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class BlueMapHeatService {

    private final UnityLauncher plugin;

    private HeatRegionWriter writer;
    private BukkitTask flushTask;

    private final int maxRegionsPerFlush = 20;

    public BlueMapHeatService(UnityLauncher plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    public void start() {
        Plugin blueMap = Bukkit.getPluginManager().getPlugin("BlueMap");
        if (blueMap == null || !blueMap.isEnabled()) {
            plugin.getLogger().warning("[BlueMapHeat] BlueMap not found/enabled. Overlay disabled.");
            return;
        }

        String override = plugin.getConfig().getString("bluemapheat.webRootOverride", "").trim();

        Path bluemapWebRoot;
        plugin.getLogger().info("Working dir: " + System.getProperty("user.dir"));
        plugin.getLogger().info("Override: " + override);
        if (!override.isEmpty()) {
            bluemapWebRoot = Paths.get(override);
            plugin.getLogger().info("Override absolute: " + bluemapWebRoot.toAbsolutePath().normalize());
        } else {
            bluemapWebRoot = blueMap.getDataFolder().toPath().resolve("web");
        }

        plugin.getLogger().info("BlueMap data folder: " + blueMap.getDataFolder().getAbsolutePath());
        plugin.getLogger().info("WebRoot: " + bluemapWebRoot.toAbsolutePath().normalize());

        WebappAssetDeployer.deploy(plugin, bluemapWebRoot);

        // ВАЖНО: этот же bluemapWebRoot передавать в HeatRegionWriter/Export, где пишутся r.*.json

        // ограничение отдаления для webapp
        int maxZoomDistance = 5000;
        WebappConfPatcher.patch(blueMap.getDataFolder().toPath().resolve("webapp.conf"), maxZoomDistance);

        this.writer = new HeatRegionWriter(plugin, bluemapWebRoot, this);

        // периодически сбрасываем dirty-регионы (ASYNC)
        // настройки (можно вынести в конфиг позже)
        // раз в 2 секунды async
        long flushPeriodTicks = 40L;
        this.flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                writer.flushDirty(maxRegionsPerFlush);
            } catch (Throwable t) {
                plugin.getLogger().warning("[BlueMapHeat] flushDirty failed: " + t.getMessage());
            }
        }, flushPeriodTicks, flushPeriodTicks);

        plugin.getLogger().info("[BlueMapHeat] Started. WebRoot=" + bluemapWebRoot);
    }

    public void stop() {
        if (flushTask != null) flushTask.cancel();
        flushTask = null;

        writer = null;
    }

    public void onPlayerEnteredChunk(Player p, int chunkX, int chunkZ) {
        if (writer == null) return;

        String world = p.getWorld().getName();

        // 1) активность: используем ваш ActivityTracker (он сам обновляет статистику кучей событий)
        // Тут мы только помечаем регион dirty, чтобы он выгрузился в json.
        writer.markDirty(world, chunkX, chunkZ);

        // 2) Можно дополнительно “подбодрить” активность простым счетчиком входов в чанк:
        writer.bumpVisits(world, chunkX, chunkZ, 1);
    }

    /**
     * Вызывается writer'ом при генерации JSON: отдаем activity/tax для чанка.
     * Activity берем из tracker.getChunkStatsMap(): ключи "world:cx,cz".
     */
    long getActivityValue(String world, int cx, int cz) {
        try {
            Map<String, ChunkStats> stats = plugin.getActivityTracker().getChunkStatsMap();
            ChunkStats st = stats.get(world + ":" + cx + "," + cz);
            if (st == null) return 0L;

            // дневное среднее (double) -> long (масштабируем *100, чтобы не терять дроби)
            ActivityWeights w = plugin.getActivityTracker().getWeights();
            double daily = st.getDailyAverage(w);
            long v = Math.round(daily * 100.0);
            return Math.max(0L, v);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * "Ценность земли" для тепловой карты — раньше здесь была цена ЗОНЫ
     * (calculateZoneDailyCostCached), размазанная по чанкам пропорционально
     * площади — эта величина НИКОГДА не совпадала с тем, что реально
     * биллится (calculateDailyCostSnapshot в ZoneActivityCalculations).
     * Теперь считаем то же самое, что реально влияет на due_cost: прямое
     * значение LandValueWeights по чанку — без зон, без кэша, без
     * периодического пересчёта (это дёшево вычислять на лету).
     */
    long getTaxValue(String world, int cx, int cz) {
        try {
            ZoneManager zm = plugin.getZoneManager();
            if (zm == null || zm.activityTracker == null) return 0L;

            ChunkStats st = zm.activityTracker.getChunkStatsMap().get(world + ":" + cx + "," + cz);
            if (st == null) return 0L;

            double v = zm.activityTracker.getLandValueWeights().calculateValue(st);
            return Math.max(0L, Math.round(v * 100.0));
        } catch (Throwable t) {
            return 0L;
        }
    }

    HeatRegionWriter writer() { return writer; }
}
