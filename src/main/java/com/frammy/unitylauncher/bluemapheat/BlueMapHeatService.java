package com.frammy.unitylauncher.bluemapheat;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class BlueMapHeatService {

    private final UnityLauncher plugin;

    private HeatRegionWriter writer;
    private BukkitTask flushTask;

    // настройки (можно вынести в конфиг позже)
    private final long flushPeriodTicks = 40L; // раз в 2 секунды async
    private final int maxRegionsPerFlush = 20;
    private final int maxZoomDistance = 5000; // ограничение отдаления для webapp

    private final AtomicReference<Map<String, Double>> taxByChunkRef =
            new AtomicReference<>(Map.of()); // key "world:cx,cz" -> tax
    private BukkitTask taxRebuildTask;

    private final long taxRebuildPeriodTicks = 20L * 60L * 5L;

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
        if (!override.isEmpty()) {
            bluemapWebRoot = Paths.get(override);
        } else {
            bluemapWebRoot = blueMap.getDataFolder().toPath().resolve("web");
        }

        plugin.getLogger().info("[BlueMapHeat] Started. WebRoot=" + bluemapWebRoot);

        WebappAssetDeployer.deploy(plugin, bluemapWebRoot);

        // ВАЖНО: этот же bluemapWebRoot передавать в HeatRegionWriter/Export, где пишутся r.*.json

        WebappConfPatcher.patch(blueMap.getDataFolder().toPath().resolve("webapp.conf"), maxZoomDistance);

        this.writer = new HeatRegionWriter(plugin, bluemapWebRoot, this);

        // периодически сбрасываем dirty-регионы (ASYNC)
        this.flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                writer.flushDirty(maxRegionsPerFlush);
            } catch (Throwable t) {
                plugin.getLogger().warning("[BlueMapHeat] flushDirty failed: " + t.getMessage());
            }
        }, flushPeriodTicks, flushPeriodTicks);

        // пересчитываем "налог по чанкам" (ASYNC)
        this.taxRebuildTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                rebuildTaxByChunkCache();
            } catch (Throwable t) {
                plugin.getLogger().warning("[BlueMapHeat] rebuildTaxByChunkCache failed: " + t.getMessage());
            }
        }, 40L, taxRebuildPeriodTicks);

        plugin.getLogger().info("[BlueMapHeat] Started. WebRoot=" + bluemapWebRoot);
    }

    public void stop() {
        if (flushTask != null) flushTask.cancel();
        flushTask = null;

        if (taxRebuildTask != null) taxRebuildTask.cancel();
        taxRebuildTask = null;

        writer = null;
        taxByChunkRef.set(Map.of());
    }

    private void rebuildTaxByChunkCache() {
        ZoneManager zm = plugin.getZoneManager();
        if (zm == null || zm.activityTracker == null) {
            taxByChunkRef.set(Map.of());
            return;
        }

        // снапшоты
        Map<String, ChunkStats> chunkStats = zm.activityTracker.getChunkStatsMap();
        ActivityWeights weights = zm.activityTracker.getWeights();

        // считаем в локальную мапу и в конце atomically swap
        Map<String, Double> out = new HashMap<>(64_000);

        for (ZoneInfo zone : zm.getAllZonesSnapshot()) {
            if (zone == null) continue;

            // если зона без страны — можно либо пропускать, либо всё равно показывать "стоимость"
            // Тут логично показывать налог только если привязана к стране
            if (!zone.hasCountry()) continue;

            String world = (zone.getWorld() != null ? zone.getWorld().getName() : null);
            if (world == null) continue;

            // стоимость зоны за день (у вас уже кешируется внутри ZoneActivityCalculations)
            double zoneDailyCost = plugin.getZoneActivityCalculations()
                    .calculateZoneDailyCostCached(zone, chunkStats, weights);

            if (zoneDailyCost <= 0.0) continue;

            // доли чанков
            List<Location> corners = zone.getCorners();
            if (corners == null || corners.size() < 3) continue;

            Map<String, Double> fractions = plugin.getZoneActivityCalculations()
                    .getChunkFractionsForCorners(world, new ArrayList<>(corners)); // копия списка

            if (fractions.isEmpty()) continue;

            double sumFrac = 0.0;
            for (double f : fractions.values()) sumFrac += f;
            if (sumFrac <= 0.0) continue;

            // распределяем стоимость зоны по чанкам
            for (Map.Entry<String, Double> e : fractions.entrySet()) {
                double f = e.getValue();
                if (f <= 0.0) continue;

                double add = zoneDailyCost * (f / sumFrac);
                if (add <= 0.0) continue;

                out.merge(e.getKey(), add, Double::sum);
            }
        }

        taxByChunkRef.set(out);
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

    long getTaxValue(String world, int cx, int cz) {
        try {
            Map<String, Double> m = taxByChunkRef.get();
            Double v = m.get(world + ":" + cx + "," + cz);
            if (v == null || v <= 0.0) return 0L;

            // *100 чтобы в UI был "целый" long (копейки)
            return Math.max(0L, Math.round(v * 100.0));
        } catch (Throwable t) {
            return 0L;
        }
    }

    HeatRegionWriter writer() { return writer; }
}
