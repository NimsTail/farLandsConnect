package com.frammy.unitylauncher.chunkactivity;

import com.flowpowered.math.vector.Vector2d;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;

import java.io.FileWriter;
import java.io.File;
import java.io.IOException;
import java.util.*;

public class ChunkActivityHeatmapExporter {

    // сколько доверяем “моментальной” активности (остальное — суточное среднее)
    private static final double SNAP_ALPHA = 0.30;     // 0..1
    private static final double PERCENTILE = 93.0;     // для cutoff
    private static final int CHUNK_RADIUS = 250;       // область покрытия вокруг 0,0 (в чанках)

    private static Map<String, Double> smoothActivityMap(Map<String, Double> original) {
        Map<String, Double> smoothed = new HashMap<>();

        int[] dx = {-1, 0, 1};
        int[] dz = {-1, 0, 1};

        for (String key : original.keySet()) {
            String[] coords = key.split(";");
            int cx = Integer.parseInt(coords[0]);
            int cz = Integer.parseInt(coords[1]);

            double total = 0;
            double weightSum = 0;

            for (int i : dx) {
                for (int j : dz) {
                    int nx = cx + i;
                    int nz = cz + j;
                    String neighborKey = nx + ";" + nz;

                    double weight = (i == 0 && j == 0) ? 1.0 : ((i == 0 || j == 0) ? 0.5 : 0.25);
                    double value = original.getOrDefault(neighborKey, 0.0);
                    total += value * weight;
                    weightSum += weight;
                }
            }

            double smoothedValue = (weightSum > 0) ? (total / weightSum) : 0.0;
            smoothed.put(cx + ";" + cz, smoothedValue);
        }

        return smoothed;
    }

    private static double getPercentile(Collection<Double> values, double percentile) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(index, 0);
        return sorted.get(index);
    }

    public static void exportHeatmapToBlueMapLayer(Map<String, ChunkStats> statsMap, String worldName, ActivityWeights weights) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(api -> api.getMap(worldName).ifPresent(map -> {
            MarkerSet markerSet = new MarkerSet("chunk-activity");
            markerSet.setLabel("Ценность земли");

            // 1) Собираем “сырые” значения (смесь snap + daily)
            Map<String, Double> rawActivityMap = new HashMap<>();

            for (Map.Entry<String, ChunkStats> entry : statsMap.entrySet()) {
                String chunkKey = entry.getKey();
                if (!chunkKey.startsWith(worldName + ":")) continue;

                String[] coords = chunkKey.split(":")[1].split(",");
                int chunkX = Integer.parseInt(coords[0]);
                int chunkZ = Integer.parseInt(coords[1]);

                ChunkStats stats = entry.getValue();

                // snap — что набрано в текущем интервале; daily — сглаженное за 24 часа
                double snap  = weights.calculateValue(stats);
                double daily = stats.getDailyAverage(weights);
                double value = SNAP_ALPHA * snap + (1.0 - SNAP_ALPHA) * daily;

                rawActivityMap.put(chunkX + ";" + chunkZ, value);
            }

            // 2) Сглаживаем соседями
            Map<String, Double> activityMap = smoothActivityMap(rawActivityMap);

            // Если вообще нет значений — выходим
            double maxValue = activityMap.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            if (maxValue <= 0) {
                Bukkit.getLogger().warning("[Heatmap] Нет активности для отображения на карте.");
                return;
            }

            // 3) Подготовим сет “занятых” для прямоугольников пустых чанков
            int chunkSize = CHUNK_RADIUS * 2;
            boolean[][] used = new boolean[chunkSize][chunkSize];

            // 4) Рисуем пустые (неактивные в карте) прямоугольниками, чтобы не было дыр
            for (int cx = -CHUNK_RADIUS; cx < CHUNK_RADIUS; cx++) {
                for (int cz = -CHUNK_RADIUS; cz < CHUNK_RADIUS; cz++) {
                    int indexX = cx + CHUNK_RADIUS;
                    int indexZ = cz + CHUNK_RADIUS;

                    if (used[indexX][indexZ]) continue;
                    if (activityMap.containsKey(cx + ";" + cz)) continue;

                    int width = 1;
                    int height = 1;

                    while (cx + width < CHUNK_RADIUS
                            && !used[cx + width + CHUNK_RADIUS][indexZ]
                            && !activityMap.containsKey((cx + width) + ";" + cz)) {
                        width++;
                    }

                    boolean expand = true;
                    while (cz + height < CHUNK_RADIUS && expand) {
                        for (int dx = 0; dx < width; dx++) {
                            int testX = cx + dx;
                            int testZ = cz + height;
                            if (used[testX + CHUNK_RADIUS][testZ + CHUNK_RADIUS]
                                    || activityMap.containsKey(testX + ";" + testZ)) {
                                expand = false;
                                break;
                            }
                        }
                        if (expand) height++;
                    }

                    for (int dx = 0; dx < width; dx++) {
                        for (int dz = 0; dz < height; dz++) {
                            used[cx + dx + CHUNK_RADIUS][cz + dz + CHUNK_RADIUS] = true;
                        }
                    }

                    int x = cx * 16;
                    int z = cz * 16;
                    int x2 = (cx + width) * 16;
                    int z2 = (cz + height) * 16;

                    List<Vector2d> square = Arrays.asList(
                            new Vector2d(x, z),
                            new Vector2d(x2, z),
                            new Vector2d(x2, z2),
                            new Vector2d(x, z2)
                    );

                    Shape shape = new Shape(square);
                    String id = "empty_" + cx + "_" + cz;
                    ShapeMarker marker = new ShapeMarker(id, shape, 256);
                    marker.setLabel("0 | X: " + cx + "; Z:" + cz);
                    marker.setDetail("0");

                    Color fill = getColorFromValue(0.0);
                    Color stroke = new Color(
                            Math.min(fill.getRed() + 40, 255),
                            Math.min(fill.getGreen() + 40, 255),
                            Math.min(fill.getBlue() + 40, 255),
                            0
                    );
                    marker.setColors(stroke, fill);
                    markerSet.getMarkers().put(id, marker);
                }
            }

            // 5) Активные чанки с лог-нормализацией и cutoff по перцентилю
            double cutoff = getPercentile(activityMap.values(), PERCENTILE);
            // защита от деления/логарифма на 0
            boolean useLog = cutoff > 0;

            for (Map.Entry<String, Double> entry : activityMap.entrySet()) {
                String[] coords = entry.getKey().split(";");
                int cx = Integer.parseInt(coords[0]);
                int cz = Integer.parseInt(coords[1]);
                double value = entry.getValue();

                int x = cx * 16;
                int z = cz * 16;

                List<Vector2d> square = Arrays.asList(
                        new Vector2d(x, z),
                        new Vector2d(x + 16, z),
                        new Vector2d(x + 16, z + 16),
                        new Vector2d(x, z + 16)
                );

                Shape shape = new Shape(square);
                String id = "active_" + cx + "_" + cz;
                ShapeMarker marker = new ShapeMarker(id, shape, 256);

                marker.setLabel(String.format(Locale.US, "%.2f | X: %d; Z: %d", value, cx, cz));
                marker.setDetail(String.format(Locale.US, "%.2f", value));

                double normalized;
                if (useLog) {
                    double vClamped = Math.min(value, cutoff);
                    normalized = Math.log1p(vClamped) / Math.log1p(cutoff);
                } else {
                    normalized = 0.0;
                }

                Color fill = getColorFromValue(normalized);
                Color stroke = new Color(
                        Math.min(fill.getRed() + 40, 255),
                        Math.min(fill.getGreen() + 40, 255),
                        Math.min(fill.getBlue() + 40, 255),
                        0
                );

                marker.setColors(stroke, fill);
                markerSet.getMarkers().put(id, marker);
            }

            map.getMarkerSets().put("chunk-activity", markerSet);
            Bukkit.getLogger().info("[Heatmap] Heatmap успешно экспортирован на карту BlueMap.");
        }));
    }

    private static Color getColorFromValue(double normalized) {
        normalized = Math.max(0, Math.min(1, normalized));
        int r, g;

        if (normalized < 0.5) {
            float t = (float) (normalized / 0.5);
            r = Math.round(t * 255);
            g = 255;
        } else {
            float t = (float) ((normalized - 0.5) / 0.5);
            r = 255;
            g = Math.round((1f - t * 0.5f) * 255);
        }

        return new Color(r, g, 0, 0.40f); // полупрозрачный
    }
}
