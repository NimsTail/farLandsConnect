package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.auth.FarLandsApiClient;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors chunk activity to the site's /stats heatmap (POST
 * /plugin/stats/activity) — a plain, read-only observation of
 * ActivityTracker's existing per-chunk stats. Deliberately does NOT touch
 * ChunkStats.playerActivity itself: that field also feeds zone billing
 * (ActivityWeights/ZoneActivityCalculations) and must not be reset or altered
 * for reporting purposes. Instead this just watches ChunkStats.lastUpdated to
 * find chunks touched since the last report — one representative point (chunk
 * center, in block coordinates) per active chunk per cycle.
 */
public class HeatmapReporter {

    private static final int BATCH_SIZE = 500; // backend cap per request

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final ActivityTracker tracker;
    private volatile long lastReportMs;

    public HeatmapReporter(JavaPlugin plugin, FarLandsApiClient api, ActivityTracker tracker) {
        this.plugin = plugin;
        this.api = api;
        this.tracker = tracker;
    }

    /** Call once from onEnable. periodTicks: 6000 ticks = 5 minutes. No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        this.lastReportMs = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::reportOnce, periodTicks, periodTicks);
    }

    private void reportOnce() {
        long since = lastReportMs;
        lastReportMs = System.currentTimeMillis();

        Map<String, List<int[]>> pointsByWorld = new HashMap<>();
        for (Map.Entry<String, ChunkStats> entry : tracker.getChunkStatsMap().entrySet()) {
            if (entry.getValue().lastUpdated < since) continue;

            ParsedKey parsed = parseChunkKey(entry.getKey());
            if (parsed == null) continue;
            pointsByWorld.computeIfAbsent(parsed.world(), k -> new ArrayList<>())
                    .add(new int[]{parsed.chunkX() * 16 + 8, parsed.chunkZ() * 16 + 8});
        }

        for (Map.Entry<String, List<int[]>> byWorld : pointsByWorld.entrySet()) {
            List<int[]> points = byWorld.getValue();
            for (int i = 0; i < points.size(); i += BATCH_SIZE) {
                api.reportActivity(byWorld.getKey(), points.subList(i, Math.min(i + BATCH_SIZE, points.size())));
            }
        }
    }

    private record ParsedKey(String world, int chunkX, int chunkZ) {}

    /** Keys look like "world:12,-5" — see ActivityTracker.getChunkKey. */
    private static ParsedKey parseChunkKey(String key) {
        int colon = key.indexOf(':');
        if (colon < 0) return null;
        String world = key.substring(0, colon);
        String[] coords = key.substring(colon + 1).split(",", 2);
        if (coords.length != 2) return null;
        try {
            return new ParsedKey(world, Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
