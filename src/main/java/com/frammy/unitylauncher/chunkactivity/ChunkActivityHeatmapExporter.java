package com.frammy.unitylauncher.chunkactivity;

import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;

import java.util.*;

public class ChunkActivityHeatmapExporter {

    // сколько доверяем “моментальной” активности (остальное — суточное среднее)
    private static final double SNAP_ALPHA = 0.30;     // 0..1
    private static final double PERCENTILE = 95.0;     // для cutoff

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


    private static Optional<BlueMapMap> findMapForWorld(BlueMapAPI api, String worldName) {
        if (api == null || worldName == null || worldName.isBlank()) return Optional.empty();

        // BlueMap: map.getId() — id карты; map.getWorld().getId() — id мира (часто совпадает с именем мира, но не всегда)
        for (BlueMapMap map : api.getMaps()) {
            if (map == null) continue;

            try {
                String mapId = map.getId();
                if (mapId != null && mapId.equalsIgnoreCase(worldName)) {
                    return Optional.of(map);
                }
            } catch (Throwable ignored) {}

            try {
                String worldId = map.getWorld().getId();
                if (worldId != null && worldId.equalsIgnoreCase(worldName)) {
                    return Optional.of(map);
                }
            } catch (Throwable ignored) {}
        }

        return Optional.empty();
    }

    private static double getPercentile(Collection<Double> values) {
        if (values == null || values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil((ChunkActivityHeatmapExporter.PERCENTILE / 100.0) * sorted.size()) - 1;
        index = Math.max(index, 0);
        return sorted.get(index);
    }

    public static void exportHeatmapToBlueMapLayer(Map<String, ChunkStats> statsMap, String worldName, ActivityWeights weights) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(api -> {
            Optional<BlueMapMap> optMap = findMapForWorld(api, worldName);
            if (optMap.isEmpty()) {
                Bukkit.getLogger().warning("[Heatmap] BlueMap: не найдена карта для мира '" + worldName + "'.");
                return;
            }

            BlueMapMap map = optMap.get();

            // дальше твой код без изменений, только убираем api.getMap(worldName)
            MarkerSet markerSet = map.getMarkerSets().get("chunk-activity");
            if (markerSet == null) {
                markerSet = new MarkerSet("chunk-activity");
                markerSet.setLabel("Ценность земли");
                map.getMarkerSets().put("chunk-activity", markerSet);
            } else {
                markerSet.getMarkers().clear();
                markerSet.setLabel("Ценность земли");
            }

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
                double snap = weights.calculateValue(stats);
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

            // 5) Активные чанки с лог-нормализацией и cutoff по перцентилю
            double cutoff = getPercentile(activityMap.values());
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

                // не рисуем почти нулевые значения
                if (value <= 0.0001) continue;

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
        });
    }

    private static final float[][] GRADIENT_STOPS = {
            {50f, 50f, 50f},
            {117f, 113f, 101f},
            {193f, 193f, 165f},
            {211f, 211f, 156f},

            {255f, 255f, 180f}, // яркий светло-жёлтый
            {255f, 245f, 135f}, // насыщенный тёплый жёлтый
            {255f, 210f, 115f}, // сочный янтарно-оранжевый
            {255f, 170f, 95f},  // яркий мягкий оранжевый
            {255f, 140f, 85f},  // оранжево-красный
            {255f, 120f, 78f},  // насыщенный красный
            {250f, 105f, 70f},  // яркий тёмно-красный
            {235f, 90f, 65f},   // глубокий тёмно-красный
            {215f, 65f, 65f},   // бордовый
            {160f, 35f, 45f}    // глубокий бордовый
    };

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float smoothstep(float t) { return t * t * (3f - 2f * t); } // более мягкий переход
    private static int lerp255(float a, float b, float t) { return Math.round(a + (b - a) * t); }

    private static Color getColorFromValue(double normalized) {
        float n = clamp01((float) normalized);

        int segments = GRADIENT_STOPS.length - 1;
        float scaled = n * segments;                         // [0..segments]
        int i = Math.min((int) Math.floor(scaled), segments - 1);
        float t = scaled - i;
        float te = smoothstep(t);                            // сгладим переход внутри сегмента

        int r = lerp255(GRADIENT_STOPS[i][0], GRADIENT_STOPS[i + 1][0], te);
        int g = lerp255(GRADIENT_STOPS[i][1], GRADIENT_STOPS[i + 1][1], te);
        int b = lerp255(GRADIENT_STOPS[i][2], GRADIENT_STOPS[i + 1][2], te);

        float a = clamp01(0.4f + 0.1f * n);                 // 0.4 .. 0.6

        // ВАЖНО: конструктор BlueMap Color — (int r, int g, int b, float a)
        return new Color(r, g, b, a);
    }
}
