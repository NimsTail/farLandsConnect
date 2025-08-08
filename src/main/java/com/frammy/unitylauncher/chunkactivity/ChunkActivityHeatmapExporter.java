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

                    double weight;
                    if (i == 0 && j == 0) {
                        weight = 1.0; // center
                    } else if (i == 0 || j == 0) {
                        weight = 0.5; // edge neighbor
                    } else {
                        weight = 0.25; // corner neighbor
                    }

                    double value = original.getOrDefault(neighborKey, 0.0);
                    total += value * weight;
                    weightSum += weight;
                }
            }

            double smoothedValue = total / weightSum;
            smoothed.put(cx + ";" + cz, smoothedValue);
        }

        return smoothed;
    }
    private static double getPercentile(Collection<Double> values, double percentile) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(index, 0));
    }

    public static void exportHeatmapToBlueMapLayer(Map<String, ChunkStats> statsMap, String worldName, ActivityWeights weights) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(api -> {
            api.getMap(worldName).ifPresent(map -> {
                MarkerSet markerSet = new MarkerSet("chunk-activity");
                markerSet.setLabel("Ценность земли");

                Map<String, Double> rawActivityMap = new HashMap<>();

                for (Map.Entry<String, ChunkStats> entry : statsMap.entrySet()) {
                    String chunkKey = entry.getKey();
                    if (!chunkKey.startsWith(worldName + ":")) continue;

                    String[] coords = chunkKey.split(":")[1].split(",");
                    int chunkX = Integer.parseInt(coords[0]);
                    int chunkZ = Integer.parseInt(coords[1]);

                    double value = weights.calculateValue(entry.getValue());

                    rawActivityMap.put(chunkX + ";" + chunkZ, value);
                }

                // 🔄 Применяем сглаживание
                Map<String, Double> activityMap = smoothActivityMap(rawActivityMap);

                // 🔢 Пересчитываем maxValue по сглаженным данным
                double maxValue = activityMap.values().stream()
                        .mapToDouble(Double::doubleValue)
                        .max().orElse(0);

                if (maxValue == 0) {
                    Bukkit.getLogger().warning("[Heatmap] Нет активности для отображения на карте.");
                    return;
                }

                int chunkRadius = 250;
                int chunkSize = chunkRadius * 2;
                boolean[][] used = new boolean[chunkSize][chunkSize];

                // 🔲 Неактивные чанки (activity = 0)
                for (int cx = -chunkRadius; cx < chunkRadius; cx++) {
                    for (int cz = -chunkRadius; cz < chunkRadius; cz++) {
                        int indexX = cx + chunkRadius;
                        int indexZ = cz + chunkRadius;

                        if (used[indexX][indexZ]) continue;
                        if (activityMap.containsKey(cx + ";" + cz)) continue;

                        int width = 1;
                        int height = 1;

                        while (cx + width < chunkRadius &&
                                !used[cx + width + chunkRadius][indexZ] &&
                                !activityMap.containsKey((cx + width) + ";" + cz)) {
                            width++;
                        }

                        boolean expand = true;
                        while (cz + height < chunkRadius && expand) {
                            for (int dx = 0; dx < width; dx++) {
                                int testX = cx + dx;
                                int testZ = cz + height;
                                if (used[testX + chunkRadius][testZ + chunkRadius] ||
                                        activityMap.containsKey(testX + ";" + testZ)) {
                                    expand = false;
                                    break;
                                }
                            }
                            if (expand) height++;
                        }

                        for (int dx = 0; dx < width; dx++) {
                            for (int dz = 0; dz < height; dz++) {
                                used[cx + dx + chunkRadius][cz + dz + chunkRadius] = true;
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

                // 🔥 Активные чанки
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
                    marker.setLabel(String.format("%.2f", value) + " | X: " + cx + "; Z:" + cz);
                    marker.setDetail(String.format("%.2f", value));

                    double cutoff = getPercentile(activityMap.values(), 95.0);

                    double normalized = Math.min(1.0, Math.log1p(value) / Math.log1p(cutoff));
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
            });
        });
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

        return new Color(r, g, 0, 0.4f); // Полупрозрачный
    }
}
