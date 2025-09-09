package com.frammy.unitylauncher.zones;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ZoneActivityCalculations {

    private static class CostCacheEntry {
        final double cost;
        final long ts;
        final String signature;
        CostCacheEntry(double cost, long ts, String signature) {
            this.cost = cost; this.ts = ts; this.signature = signature;
        }
    }

    public ZoneManager zm;
    private static final long COST_TTL_MS = 5 * 60_000L;
    private final Map<String, CostCacheEntry> costCache = new ConcurrentHashMap<>();

    public ZoneActivityCalculations(ZoneManager zoneManager) {
        this.zm = zoneManager;
    }

    public void startZoneBillingScheduler() {
        long delay = ticksUntilNextTime(0, 5, 0);      // старт в 00:05
        long period = 20L * 60 * 60 * 24;              // 24 часа

        Bukkit.getScheduler().runTaskTimerAsynchronously(
                zm.ul,
                this::snapshotAndMaybeBillAllZones,
                delay,
                period
        );
    }
    private long ticksUntilNextTime(int hour, int minute, int second) {
        long nowMs = System.currentTimeMillis();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(nowMs), zm.zoneId);
        java.time.ZonedDateTime next = now.withHour(hour).withMinute(minute).withSecond(second).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long diffMs = java.time.Duration.between(now, next).toMillis();
        return Math.max(1L, diffMs / 50L); // 20 тиков = 1 секунда
    }

    public void snapshotAndMaybeBillAllZones() {
        LocalDate today = LocalDate.now(zm.zoneId);

        // 1) фиксируем дневную цену для каждой зоны
        for (ZoneInfo zone : zm.zoneList.values()) {
            double baseDaily = calculateZoneDailyCostCached(zone, zm.activityTracker.getChunkStatsMap(), zm.activityTracker.getWeights());
            ZoneTypeData typeData = zm.zoneLimits.get(zone.getType());
            //double typeMult = (typeData != null) ? typeData.getPriceMultiplier() : 1.0;
            double finalDailyCost = baseDaily;

            zone.addDailyCost(today, finalDailyCost);
        }

        // 2) авто-биллинг по расписанию: если наступила/перешагнули дату nextBillingDate → списать «долг» и сдвинуть
        for (ZoneInfo zone : zm.zoneList.values()) {
            LocalDate nextDate = zone.getNextBillingDate();
            if (!LocalDate.now(zm.zoneId).isBefore(nextDate)) {
                double due = zone.getDueSinceLastBill(today); // может быть 7, может быть меньше/больше, если сервер простаивал
                if (due > 0) {
                    try {
                        //    moneyManager.withdraw(zone.getOwner(), due);
                        zone.markBilled(today);
                        Bukkit.getLogger().info("[Zones] Auto-billed " + zone.getName() + " owner=" + zone.getOwner() + " amount=" + String.format(Locale.US,"%.2f", due));
                    } catch (Exception ex) {
                        Bukkit.getLogger().warning("[Zones] Auto-billing failed " + zone.getName() + " : " + ex.getMessage());
                    }
                } else {
                    // даже если нечего списывать — обновим график, чтобы неделя не «залипала»
                    zone.markBilled(today);
                }
            }
        }
    }

    public List<Double> getZoneHourlySeries(ZoneInfo zone,
                                             Map<String, ChunkStats> statsMap,
                                             ActivityWeights weights,
                                             int maxHours) {
        // 1) доли чанков, покрытых зоной
        Map<Chunk, Double> fractions = getChunkFractions(zone.getCorners());

        // 2) подготовка: получим для каждого чанка список его hourlySamples (старые -> новые)
        Map<String, List<Double>> chunkSamples = new HashMap<>();
        int maxLen = 0;

        for (Map.Entry<Chunk, Double> e : fractions.entrySet()) {
            Chunk ch = e.getKey();
            String key = ch.getWorld().getName() + ":" + ch.getX() + "," + ch.getZ();
            ChunkStats s = statsMap.get(key);
            if (s == null) continue;

            // hourlySamples — Deque<Double> (старое -> новое). Превратим в List
            List<Double> lst = new ArrayList<>(s.hourlySamples);
            if (!lst.isEmpty()) {
                chunkSamples.put(key, lst);
                maxLen = Math.max(maxLen, lst.size());
            }
        }

        if (maxLen == 0) return Collections.emptyList();

        // 3) для каждого "часа назад" собираем взвешенную цену зоны
        List<Double> series = new ArrayList<>();
        int take = Math.min(maxLen, maxHours);

        // множитель типа зоны (если используешь его в цене)
        ZoneTypeData typeData = zm.zoneLimits.get(zone.getType());
        double typeMult = (typeData != null) ? typeData.getPriceMultiplier() : 1.0;

        // идём от более старых к более новым (чтобы при выводе легко подписывать H-xx)
        for (int offset = take - 1; offset >= 0; offset--) {
            double sum = 0.0;
            double wsum = 0.0;

            for (Map.Entry<Chunk, Double> e : fractions.entrySet()) {
                Chunk ch = e.getKey();
                double frac = e.getValue();

                String key = ch.getWorld().getName() + ":" + ch.getX() + "," + ch.getZ();
                List<Double> lst = chunkSamples.get(key);
                if (lst == null || lst.isEmpty()) continue;

                int idx = lst.size() - 1 - offset; // берем "offset" с конца
                if (idx < 0) continue; // в этом чанке нет такого старого часа

                double hourlySample = lst.get(idx); // это уже wt*time + wp*place + wb*break
                sum += hourlySample * frac;
                wsum += frac;
            }

            double zoneHour = (wsum > 0) ? (sum / wsum) * typeMult : 0.0;
            series.add(zoneHour);
        }

        return series; // размер <= maxHours, порядок: старые -> новые
    }



    private String cornersSignature(List<Location> corners) {
        if (corners == null || corners.isEmpty()) return "empty";
        String world = corners.get(0).getWorld().getName();
        StringBuilder sb = new StringBuilder(world).append('|').append(corners.size()).append('|');
        for (Location loc : corners) {
            sb.append(loc.getBlockX()).append(',').append(loc.getBlockZ()).append(';');
        }
        return sb.toString();
    }

    private String costSignature(ZoneInfo zone, double typeMultiplier) {
        // углы зоны + мультипликатор (учитывает пересечения)
        return cornersSignature(zone.getCorners()) + "|mul=" + typeMultiplier;
    }

    public void invalidateZoneCost(ZoneInfo zone) { costCache.remove(zone.getID()); }

    public double calculateZoneDailyCostCached(ZoneInfo zone, Map<String, ChunkStats> statsMap, ActivityWeights weights) {
        // узнаём актуальный мультипликатор (с учётом пересечений)
        double typeMultiplier = getOverlapAwareTypeMultiplier(zone);
        String key = zone.getID();
        String sig = costSignature(zone, typeMultiplier);
        long now = System.currentTimeMillis();

        CostCacheEntry hit = costCache.get(key);
        if (hit != null && hit.signature.equals(sig) && (now - hit.ts) < COST_TTL_MS) {
            return hit.cost;
        }

        // ——— оригинальный расчёт цены ———
        Map<Chunk, Double> chunkFractions = getChunkFractions(zone.getCorners());

        double totalWeightedValue = 0.0, totalWeight = 0.0;
        for (Map.Entry<Chunk, Double> e : chunkFractions.entrySet()) {
            Chunk c = e.getKey();
            double fraction = e.getValue();

            String ck = c.getWorld().getName() + ":" + c.getX() + "," + c.getZ();
            ChunkStats stats = statsMap.get(ck);
            if (stats == null) continue;

            double dailyAvg = stats.getDailyAverage(weights);
            totalWeightedValue += dailyAvg * fraction;
            totalWeight += fraction;
        }
        double base = totalWeight > 0 ? totalWeightedValue / totalWeight : 0.0;
        double cost = base * typeMultiplier;

        costCache.put(key, new CostCacheEntry(cost, now, sig));
        return cost;
    }

    public Map<Chunk, Double> getChunkFractions(List<Location> corners) {
        Map<Chunk, Double> result = new HashMap<>();

        if (corners.size() < 3) return result;

        // Преобразуем в 2D
        List<Vector2d> polygon = corners.stream()
                .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                .collect(Collectors.toList());

        World world = corners.get(0).getWorld(); // предполагаем одна зона = один мир

        // Определяем границы
        double minX = polygon.stream().mapToDouble(p -> p.getX()).min().orElse(0);
        double maxX = polygon.stream().mapToDouble(p -> p.getX()).max().orElse(0);
        double minZ = polygon.stream().mapToDouble(p -> p.getY()).min().orElse(0);
        double maxZ = polygon.stream().mapToDouble(p -> p.getY()).max().orElse(0);

        // Количество шагов в чанке (разрешение)
        int step = 4;
        double stepSize = 16.0 / step;

        int chunkMinX = (int) Math.floor(minX / 16);
        int chunkMaxX = (int) Math.floor(maxX / 16);
        int chunkMinZ = (int) Math.floor(minZ / 16);
        int chunkMaxZ = (int) Math.floor(maxZ / 16);

        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                int inside = 0;
                int total = step * step;

                for (int i = 0; i < step; i++) {
                    for (int j = 0; j < step; j++) {
                        double x = cx * 16 + i * stepSize + stepSize / 2;
                        double z = cz * 16 + j * stepSize + stepSize / 2;

                        if (zm.isPointInsidePolygon(new Vector2d(x, z), polygon)) {
                            inside++;
                        }
                    }
                }

                if (inside > 0) {
                    double fraction = (double) inside / total;
                    Chunk chunk = world.getChunkAt(cx, cz);
                    result.put(chunk, fraction);
                }
            }
        }

        return result;
    }
    private String chunkKey(Chunk c) {
        return c.getWorld().getName() + ":" + c.getX() + "," + c.getZ();
    }

    private Set<String> chunkKeysForCorners(List<Location> corners) {
        Map<Chunk, Double> fractions = getChunkFractions(corners);
        Set<String> keys = new HashSet<>();
        for (Chunk c : fractions.keySet()) {
            keys.add(chunkKey(c));
        }
        return keys;
    }

    /**
     * Возвращает средний мультипликатор типа зоны с учётом пересечений.
     * В пересечения попадают зоны в том же мире, у которых есть общий чанк.
     */
    private double getOverlapAwareTypeMultiplier(ZoneInfo target) {
        ZoneTypeData base = zm.zoneLimits.get(target.getType());
        double baseMul = (base != null ? base.getPriceMultiplier() : 1.0);

        Set<String> targetChunks = chunkKeysForCorners(target.getCorners());
        if (targetChunks.isEmpty()) return baseMul;

        List<Double> multipliers = new ArrayList<>();
        multipliers.add(baseMul);

        for (ZoneInfo other : zm.zoneList.values()) {
            if (other == null || other == target) continue;
            if (other.getCorners() == null || other.getCorners().size() < 3) continue;
            if (!other.getCorners().get(0).getWorld().equals(target.getCorners().get(0).getWorld())) continue;

            // быстрый пропуск по AABB (опционально, если есть getBoundingBox)
            // if (!aabbIntersects(target, other)) continue;

            Set<String> otherChunks = chunkKeysForCorners(other.getCorners());
            boolean intersects = false;
            for (String k : otherChunks) {
                if (targetChunks.contains(k)) {
                    intersects = true;
                    break;
                }
            }
            if (intersects) {
                ZoneTypeData ztd = zm.zoneLimits.get(other.getType());
                if (ztd != null) multipliers.add(ztd.getPriceMultiplier());
            }
        }

        return multipliers.stream().mapToDouble(d -> d).average().orElse(baseMul);
    }
}
