package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Расчёт активности/стоимости зон + суточный биллинг.
 * Минимум аллокаций, без Stream API.
 */
public class ZoneActivityCalculations {

    /* ===== Кэш дневной цены ===== */
        private record CostCacheEntry(double cost, long ts, String sig) {
    }

    private final ZoneManager zm;
    private static final long COST_TTL_MS = 5 * 60_000L; // 5 минут
    private final Map<String, CostCacheEntry> costCache = new ConcurrentHashMap<>();

    public ZoneActivityCalculations(ZoneManager zoneManager) { this.zm = Objects.requireNonNull(zoneManager); }

    /* ===== Планировщик: 00:05 раз в сутки ===== */
    public void startZoneBillingScheduler() {
        long delay  = ticksUntilNextTime();
        long period = 20L * 60 * 60 * 24;
        Bukkit.getScheduler().runTaskTimerAsynchronously(zm.ul, this::snapshotAndMaybeBillAllZones, delay, period);
    }

    private long ticksUntilNextTime(){
        long nowMs = System.currentTimeMillis();
        ZonedDateTime now  = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zm.zoneId);
        ZonedDateTime next = now.withHour(0).withMinute(5).withSecond(0).withNano(0);
        if (!next.isAfter(now)) next = next.plusDays(1);
        long diffMs = java.time.Duration.between(now, next).toMillis();
        return Math.max(1L, diffMs / 50L);
    }

    /** Снимок стоимости за день + авто-биллинг. */
    public void snapshotAndMaybeBillAllZones() {
        LocalDate today = LocalDate.now(zm.zoneId);

        // 1) зафиксировать дневную стоимость
        for (ZoneInfo z : zm.zoneList.values()) {
            double daily = calculateZoneDailyCostCached(z, zm.activityTracker.getChunkStatsMap(), zm.activityTracker.getWeights());
            z.addDailyCost(today, daily);
        }

        // 2) авто-списание по расписанию
        for (ZoneInfo z : zm.zoneList.values()) {
            LocalDate next = z.getNextBillingDate();
            if (LocalDate.now(zm.zoneId).isBefore(next)) continue;

            double due = z.getDueSinceLastBill(today);
            try {
                z.markBilled(today);
                if (due > 0) {
                    // zm.ul.moneyManager.withdraw(z.getOwner(), due);
                    Bukkit.getLogger().info("[Zones] Auto-billed " + z.getName() + " owner=" + z.getOwner()
                            + " amount=" + String.format(Locale.US, "%.2f", due));
                }
            } catch (Exception ex) {
                Bukkit.getLogger().warning("[Zones] Auto-billing failed " + z.getName() + ": " + ex.getMessage());
            }
        }
    }

    /* ===== Почасовой ряд (старые → новые) ===== */
    public List<Double> getZoneHourlySeries(ZoneInfo zone,
                                            Map<String, ChunkStats> statsMap,
                                            int maxHours) {
        Map<Chunk, Double> fractions = getChunkFractions(zone.getCorners());
        if (fractions.isEmpty()) return Collections.emptyList();

        // Собираем hourlySamples всех чанков
        Map<String, double[]> chunkSamples = new HashMap<>(fractions.size()*2);
        int maxLen = 0;
        for (Chunk ch : fractions.keySet()) {
            String key = chunkKey(ch);
            ChunkStats s = statsMap.get(key);
            if (s == null || s.hourlySamples.isEmpty()) continue;

            // Deque<Double> → double[] (экономим боксы)
            int n = s.hourlySamples.size();
            if (n > maxLen) maxLen = n;
            double[] arr = new double[n];
            int i=0; for (Double v : s.hourlySamples) arr[i++] = (v!=null? v:0.0);
            chunkSamples.put(key, arr);
        }
        if (maxLen == 0) return Collections.emptyList();

        int take = Math.max(1, Math.min(maxLen, maxHours));
        ZoneTypeData td = zm.zoneLimits.get(zone.getType());
        double typeMul = (td!=null ? td.priceMultiplier() : 1.0);

        List<Double> out = new ArrayList<>(take);
        for (int offset = take-1; offset >= 0; offset--) {
            double sum=0, wsum=0;
            for (Map.Entry<Chunk, Double> e : fractions.entrySet()) {
                double frac = e.getValue();
                double[] arr = chunkSamples.get(chunkKey(e.getKey()));
                if (arr == null) continue;
                int idx = arr.length - 1 - offset;
                if (idx < 0) continue;
                sum  += arr[idx] * frac;
                wsum += frac;
            }
            out.add((wsum>0 ? (sum/wsum) : 0.0) * typeMul);
        }
        return out;
    }

    /* ===== Дневная цена с кэшем ===== */
    public void invalidateZoneCost(ZoneInfo zone) { if (zone!=null) costCache.remove(zone.getID()); }

    public double calculateZoneDailyCostCached(ZoneInfo zone,
                                               Map<String, ChunkStats> statsMap,
                                               ActivityWeights weights) {
        double typeMul = overlapAwareTypeMultiplier(zone);
        String key = zone.getID();
        String sig = costSignature(zone.getCorners(), typeMul);
        long now = System.currentTimeMillis();

        CostCacheEntry hit = costCache.get(key);
        if (hit != null && hit.sig.equals(sig) && (now - hit.ts) < COST_TTL_MS) return hit.cost;

        Map<Chunk, Double> fr = getChunkFractions(zone.getCorners());
        if (fr.isEmpty()) { costCache.put(key, new CostCacheEntry(0, now, sig)); return 0; }

        double twv=0, tw=0;
        for (Map.Entry<Chunk, Double> e : fr.entrySet()) {
            ChunkStats st = statsMap.get(chunkKey(e.getKey()));
            if (st == null) continue;
            double dailyAvg = st.getDailyAverage(weights);
            double f = e.getValue();
            twv += dailyAvg * f;
            tw  += f;
        }
        double base = tw>0? twv/tw : 0.0;
        double cost = base * typeMul;
        costCache.put(key, new CostCacheEntry(cost, now, sig));
        return cost;
    }

    /* ===== Покрытие чанков полигоном ===== */
    private static final int STEP = 4; // 4x4=16 выборок на чанк (баланс точности/скорости)

    /**
     * Доли покрытия чанков полигоном по XZ. Без аллокаций Vector2d.
     */
    public Map<Chunk, Double> getChunkFractions(List<Location> corners) {
        Map<Chunk, Double> res = new HashMap<>();
        if (corners == null || corners.size() < 3) return res;

        World w = corners.getFirst().getWorld();
        if (w == null) return res;

        // полигоны в массивы + AABB
        int n = corners.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        double minX=Double.POSITIVE_INFINITY, maxX=Double.NEGATIVE_INFINITY;
        double minZ=Double.POSITIVE_INFINITY, maxZ=Double.NEGATIVE_INFINITY;
        for (int i=0;i<n;i++){
            Location L = corners.get(i);
            double x=L.getX(), z=L.getZ();
            xs[i]=x; zs[i]=z;
            if (x<minX) minX=x; if (x>maxX) maxX=x;
            if (z<minZ) minZ=z; if (z>maxZ) maxZ=z;
        }

        int cMinX = (int)Math.floor(minX/16.0);
        int cMaxX = (int)Math.floor(maxX/16.0);
        int cMinZ = (int)Math.floor(minZ/16.0);
        int cMaxZ = (int)Math.floor(maxZ/16.0);

        double stepSize = 16.0/STEP;
        for (int cx=cMinX; cx<=cMaxX; cx++) {
            double baseX = cx*16.0 + stepSize/2.0;
            for (int cz=cMinZ; cz<=cMaxZ; cz++) {
                double baseZ = cz*16.0 + stepSize/2.0;

                int inside=0, total=STEP*STEP;
                for (int i=0;i<STEP;i++){
                    double x = baseX + i*stepSize;
                    for (int j=0;j<STEP;j++){
                        double z = baseZ + j*stepSize;
                        if (pointInPoly(x,z,xs,zs)) inside++;
                    }
                }
                if (inside>0){
                    double frac = (double)inside/total;
                    Chunk ch = w.getChunkAt(cx, cz);
                    res.put(ch, frac);
                }
            }
        }
        return res;
    }

    /* ===== Пересечения/мультипликатор ===== */
    private double overlapAwareTypeMultiplier(ZoneInfo target){
        ZoneTypeData base = zm.zoneLimits.get(target.getType());
        double baseMul = base!=null ? base.priceMultiplier() : 1.0;

        List<Location> tCorners = target.getCorners();
        if (tCorners == null || tCorners.size()<3) return baseMul;
        World tw = tCorners.getFirst().getWorld();
        if (tw == null) return baseMul;

        // ключи чанков цели
        Set<String> tKeys = chunkKeysForCorners(tCorners);
        if (tKeys.isEmpty()) return baseMul;

        double sum = baseMul; int cnt = 1;
        for (ZoneInfo other : zm.zoneList.values()){
            if (other==null || other==target) continue;
            List<Location> oc = other.getCorners();
            if (oc==null || oc.size()<3) continue;
            World ow = oc.getFirst().getWorld();
            if (ow==null || !ow.equals(tw)) continue;

            if (intersectsByChunks(tKeys, chunkKeysForCorners(oc))) {
                ZoneTypeData ztd = zm.zoneLimits.get(other.getType());
                if (ztd != null) { sum += ztd.priceMultiplier(); cnt++; }
            }
        }
        return sum / cnt;
    }

    /* ===== Вспомогательные ===== */
    private static boolean intersectsByChunks(Set<String> a, Set<String> b){
        if (a.size()<b.size()) { // пробегаем по меньшему
            for (String k : a) if (b.contains(k)) return true;
        } else {
            for (String k : b) if (a.contains(k)) return true;
        }
        return false;
    }

    private Set<String> chunkKeysForCorners(List<Location> corners){
        Map<Chunk, Double> fr = getChunkFractions(corners);
        Set<String> keys = new HashSet<>(fr.size()*2);
        for (Chunk c : fr.keySet()) keys.add(chunkKey(c));
        return keys;
    }

    private static String chunkKey(Chunk c){ return c.getWorld().getName()+":"+c.getX()+","+c.getZ(); }

    private static String costSignature(List<Location> corners, double mul){
        if (corners==null || corners.isEmpty()) return "empty|mul="+mul;
        World w = corners.getFirst().getWorld();
        String world = (w!=null? w.getName() : "null");
        StringBuilder sb = new StringBuilder(world).append('|').append(corners.size()).append('|');
        for (Location L : corners) {
            sb.append(L.getBlockX()).append(',').append(L.getBlockZ()).append(';');
        }
        sb.append("|mul=").append(mul);
        return sb.toString();
    }

    /** Ray-casting 2D без аллокаций: XZ в массивах xs/zs. */
    private static boolean pointInPoly(double x, double z, double[] xs, double[] zs){
        boolean inside=false;
        int n = xs.length;
        for (int i=0,j=n-1;i<n;j=i++){
            double xi=xs[i], zi=zs[i];
            double xj=xs[j], zj=zs[j];
            boolean inter = ((zi>z)!=(zj>z)) && (x < (xj - xi)*(z - zi)/(zj - zi + 0.0) + xi);
            if (inter) inside = !inside;
        }
        return inside;
    }
}
