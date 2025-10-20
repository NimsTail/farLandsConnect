package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneActivityCalculations {

    /* ===== Кэш дневной цены ===== */
        private record CostCacheEntry(double cost, long ts, String sig) {
    }

    private final ZoneManager zm;
    private static final long COST_TTL_MS = 5 * 60_000L; // 5 минут
    private final Map<String, CostCacheEntry> costCache = new ConcurrentHashMap<>();

    public ZoneActivityCalculations(ZoneManager zoneManager) { this.zm = Objects.requireNonNull(zoneManager); }

    // === СНАПШОТ зоны без Bukkit-ссылок ===
    private record ZoneSnapshot(
            String id,
            ZoneType type,
            String worldName,
            List<Point2> polygonXZ // только X/Z
    ) {
        static ZoneSnapshot from(ZoneInfo z) {
            if (z == null) return null;
            World w = z.getWorld();
            if (w == null) return null;
            List<Location> corners = z.getCorners();
            if (corners == null || corners.size() < 3) return null;

            List<Point2> pts = new ArrayList<>(corners.size());
            for (Location L : corners) pts.add(new Point2(L.getX(), L.getZ()));
            return new ZoneSnapshot(z.getID(), z.getType(), w.getName(), pts);
        }
    }
    private record Point2(double x, double z) {}

    // Подсчёт дневной цены по снапшоту зоны и снапшоту статистики
    private double calculateDailyCostSnapshot(ZoneSnapshot snap,
                                              Map<String, ChunkStats> statsMap,
                                              ActivityWeights weights,
                                              double typeMul) {
        Map<String, Double> fractions = getChunkFractionsKeys(snap.worldName(), snap.polygonXZ());
        if (fractions.isEmpty()) return 0.0;

        double twv = 0, tw = 0;
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            ChunkStats st = statsMap.get(e.getKey());
            if (st == null) continue;
            double dailyAvg = st.getDailyAverage(weights);
            double f = e.getValue();
            twv += dailyAvg * f;
            tw  += f;
        }
        double base = tw > 0 ? twv / tw : 0.0;
        return base * typeMul;
    }

    // Оценка пересечений типов зон по чанковым ключам (без Bukkit)
    private double overlapAwareTypeMultiplierSnapshot(ZoneSnapshot target, Collection<ZoneSnapshot> all) {
        ZoneTypeData base = zm.zoneLimits.get(target.type());
        double baseMul = base != null ? base.priceMultiplier() : 1.0;

        Set<String> tKeys = chunkKeysForPolygon(target.worldName(), target.polygonXZ());
        if (tKeys.isEmpty()) return baseMul;

        double sum = baseMul; int cnt = 1;
        for (ZoneSnapshot other : all) {
            if (other == null || other == target) continue;
            if (!Objects.equals(other.worldName(), target.worldName())) continue;

            Set<String> oKeys = chunkKeysForPolygon(other.worldName(), other.polygonXZ());
            if (intersectsByChunks(tKeys, oKeys)) {
                ZoneTypeData ztd = zm.zoneLimits.get(other.type());
                if (ztd != null) { sum += ztd.priceMultiplier(); cnt++; }
            }
        }
        return sum / cnt;
    }

    // Возвращает доли покрытия чанков: ключ формата "world:x,z" -> fraction (0..1)
    private Map<String, Double> getChunkFractionsKeys(String worldName, List<Point2> poly) {
        Map<String, Double> res = new HashMap<>();
        if (poly == null || poly.size() < 3 || worldName == null) return res;

        int n = poly.size();
        double[] xs = new double[n];
        double[] zs = new double[n];
        double minX=Double.POSITIVE_INFINITY, maxX=Double.NEGATIVE_INFINITY;
        double minZ=Double.POSITIVE_INFINITY, maxZ=Double.NEGATIVE_INFINITY;
        for (int i=0;i<n;i++){
            double x = poly.get(i).x();
            double z = poly.get(i).z();
            xs[i]=x; zs[i]=z;
            if (x<minX) minX=x; if (x>maxX) maxX=x;
            if (z<minZ) minZ=z; if (z>maxZ) maxZ=z;
        }

        int cMinX = (int)Math.floor(minX/16.0);
        int cMaxX = (int)Math.floor(maxX/16.0);
        int cMinZ = (int)Math.floor(minZ/16.0);
        int cMaxZ = (int)Math.floor(maxZ/16.0);

        double stepSize = 16.0/STEP;
        int total = STEP*STEP;

        for (int cx=cMinX; cx<=cMaxX; cx++) {
            double baseX = cx*16.0 + stepSize/2.0;
            for (int cz=cMinZ; cz<=cMaxZ; cz++) {
                double baseZ = cz*16.0 + stepSize/2.0;
                int inside=0;
                for (int i=0;i<STEP;i++){
                    double x = baseX + i*stepSize;
                    for (int j=0;j<STEP;j++){
                        double z = baseZ + j*stepSize;
                        if (pointInPoly(x,z,xs,zs)) inside++;
                    }
                }
                if (inside>0){
                    double frac = (double)inside/total;
                    String key = worldName + ":" + cx + "," + cz;
                    res.put(key, frac);
                }
            }
        }
        return res;
    }

    private Set<String> chunkKeysForPolygon(String worldName, List<Point2> poly){
        return getChunkFractionsKeys(worldName, poly).keySet();
    }

    // ===== Планировщик: 00:05 раз в сутки =====
    public void startZoneBillingScheduler() {
        long delay  = ticksUntilNextTime();
        long period = 20L * 60 * 60 * 24;

        // Запускаем на ГЛАВНОМ потоке: снимем снапшоты и дернём async-вычисления
        Bukkit.getScheduler().runTaskTimer(zm.ul, () -> {
            try {
                snapshotAndMaybeBillAllZones(); // внутри сам разделит на sync/async этапы
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[Zones] Billing tick failed: " + t.getMessage());
            }
        }, delay, period);
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
        final LocalDate today = LocalDate.now(zm.zoneId);

        // --- СНЯТИЕ СНАПШОТОВ (SAFE: главный поток) ---
        // 1) зоны (immutable снапшоты без Bukkit-объектов)
        final Map<String, ZoneSnapshot> zoneSnaps = new HashMap<>();
        final Map<String, ZoneInfo> zoneById = new HashMap<>();
        for (ZoneInfo z : new ArrayList<>(zm.zoneList.values())) { // снимок списка
            ZoneSnapshot snap = ZoneSnapshot.from(z);
            if (snap != null) {
                zoneSnaps.put(snap.id(), snap);
                zoneById.put(snap.id(), z);
            }
        }

        // 2) копия карты статистики (сама ConcurrentHashMap, чтение потокобезопасно)
        final Map<String, ChunkStats> statsCopy = new HashMap<>(zm.activityTracker.getChunkStatsMap());

        // 3) веса — просто значение
        final ActivityWeights weights = zm.activityTracker.getWeights();

        // --- ВЫЧИСЛЕНИЯ (ASYNC: без Bukkit) ---
        Bukkit.getScheduler().runTaskAsynchronously(zm.ul, () -> {
            try {
                // суточная стоимость по снапшотам
                final Map<String, Double> dailyCosts = new HashMap<>();
                for (ZoneSnapshot s : zoneSnaps.values()) {
                    double mul = overlapAwareTypeMultiplierSnapshot(s, zoneSnaps.values());
                    double cost = calculateDailyCostSnapshot(s, statsCopy, weights, mul);
                    dailyCosts.put(s.id(), cost);
                }

                // --- ЗАПИСЬ РЕЗУЛЬТАТОВ (обратно на главный поток) ---
                Bukkit.getScheduler().runTask(zm.ul, () -> {
                    // 1) фиксируем дневные стоимости
                    for (Map.Entry<String, Double> e : dailyCosts.entrySet()) {
                        ZoneInfo z = zoneById.get(e.getKey());
                        if (z != null) z.addDailyCost(today, e.getValue());
                    }

                    // 2) авто-списание по расписанию (только sync!)
                    for (ZoneInfo z : zoneById.values()) {
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
                });

            } catch (Throwable t) {
                Bukkit.getLogger().warning("[Zones] Async cost calc failed: " + t.getMessage());
            }
        });
    }

    /* ===== Почасовой ряд (старые → новые) ===== */
    public List<Double> getZoneHourlySeries(ZoneInfo zone,
                                            Map<String, ChunkStats> statsMap,
                                            int maxHours) {
        World w = zone.getWorld();
        if (w == null) return Collections.emptyList();

        // Полигон → ключи чанков
        List<Location> corners = zone.getCorners();
        if (corners == null || corners.size() < 3) return Collections.emptyList();
        List<Point2> poly = new ArrayList<>(corners.size());
        for (Location L : corners) poly.add(new Point2(L.getX(), L.getZ()));
        Map<String, Double> fractions = getChunkFractionsKeys(w.getName(), poly);
        if (fractions.isEmpty()) return Collections.emptyList();

        // Соберём hourlySamples по ключам "world:x,z"
        Map<String, double[]> chunkSamples = new HashMap<>(fractions.size()*2);
        int maxLen = 0;
        for (String key : fractions.keySet()) {
            ChunkStats s = statsMap.get(key);
            if (s == null || s.hourlySamples.isEmpty()) continue;
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
            for (Map.Entry<String, Double> e : fractions.entrySet()) {
                double frac = e.getValue();
                double[] arr = chunkSamples.get(e.getKey());
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

    public double calculateZoneDailyCostCached(ZoneInfo zone,
                                               Map<String, ChunkStats> statsMap,
                                               ActivityWeights weights) {
        double typeMul = overlapAwareTypeMultiplier(zone);
        String key = zone.getID();

        // сигнатура без привязки к Chunk
        String sig = costSignature(zone.getCorners(), typeMul);
        long now = System.currentTimeMillis();

        CostCacheEntry hit = costCache.get(key);
        if (hit != null && hit.sig.equals(sig) && (now - hit.ts) < COST_TTL_MS) return hit.cost;

        World w = zone.getWorld();
        if (w == null) { costCache.put(key, new CostCacheEntry(0, now, sig)); return 0; }

        // БЕЗОПАСНО: считаем доли в ключах "world:x,z"
        List<Location> corners = zone.getCorners();
        List<Point2> poly = new ArrayList<>(corners.size());
        for (Location L : corners) poly.add(new Point2(L.getX(), L.getZ()));
        Map<String, Double> fr = getChunkFractionsKeys(w.getName(), poly);

        if (fr.isEmpty()) { costCache.put(key, new CostCacheEntry(0, now, sig)); return 0; }

        double twv=0, tw=0;
        for (Map.Entry<String, Double> e : fr.entrySet()) {
            ChunkStats st = statsMap.get(e.getKey());
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

    /* ===== Пересечения/мультипликатор ===== */
    private double overlapAwareTypeMultiplier(ZoneInfo target){
        ZoneTypeData base = zm.zoneLimits.get(target.getType());
        double baseMul = base != null ? base.priceMultiplier() : 1.0;

        List<Location> tCorners = target.getCorners();
        if (tCorners == null || tCorners.size() < 3) return baseMul;
        World tw = tCorners.getFirst().getWorld();
        if (tw == null) return baseMul;

        // ключи чанков цели
        Set<String> tKeys = chunkKeysForCorners(tCorners);
        if (tKeys.isEmpty()) return baseMul;

        double sum = baseMul;
        int cnt = 1;

        for (ZoneInfo other : zm.zoneList.values()){
            if (other == null || other == target) continue;

            List<Location> oc = other.getCorners();
            if (oc == null || oc.size() < 3) continue;
            World ow = oc.getFirst().getWorld();
            if (ow == null || !ow.equals(tw)) continue;

            // быстрый предчек по чанкам
            if (!intersectsByChunks(tKeys, chunkKeysForCorners(oc))) continue;

            // учтём только те пересечения, которые действительно разрешены правилами
            if (!zm.canZonesCoexist(target, other)) {
                // пересечение есть, но правила запрещают — НЕ усредняем по нему
                continue;
            }

            ZoneTypeData ztd = zm.zoneLimits.get(other.getType());
            if (ztd != null) {
                sum += ztd.priceMultiplier();
                cnt++;
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
        Set<String> keys = new HashSet<>();
        if (corners == null || corners.size() < 3) return keys;
        World w = corners.getFirst().getWorld();
        if (w == null) return keys;

        List<Point2> poly = new ArrayList<>(corners.size());
        for (Location L : corners) poly.add(new Point2(L.getX(), L.getZ()));
        keys.addAll(getChunkFractionsKeys(w.getName(), poly).keySet());
        return keys;
    }

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
