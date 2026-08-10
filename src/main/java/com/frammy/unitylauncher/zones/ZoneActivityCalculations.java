package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.bank.BankInvoicesDao;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import com.frammy.unitylauncher.chunkactivity.LandValueWeights;
import com.frammy.unitylauncher.zones.geom.ZoneOverlapRules;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneActivityCalculations {

    /* ===== Кэш дневной цены ===== */
    private record CostCacheEntry(double cost, long ts, String sig) {}

    private final ZoneManager zm;
    private static final long COST_TTL_MS = 5 * 60_000L; // 5 минут
    private final Map<String, CostCacheEntry> costCache = new ConcurrentHashMap<>();
    private final BankInvoicesDao bankInvoicesDao; // твой реальный класс/интерфейс
    private final UnityLauncher plugin;

    public ZoneActivityCalculations(ZoneManager zoneManager, UnityLauncher plugin, BankInvoicesDao bankInvoicesDao) {
        this.zm = Objects.requireNonNull(zoneManager);
        this.plugin = Objects.requireNonNull(plugin);
        this.bankInvoicesDao = Objects.requireNonNull(bankInvoicesDao);
    }

    // === СНАПШОТ зоны без Bukkit-ссылок ===
    // Мульти-полигон: shapesXZ содержит ОДИН список точек на каждую фигуру зоны
    // (для подавляющего большинства зон — одна фигура, список из одного элемента).
    private record ZoneSnapshot(
            String id,
            ZoneType type,
            String worldName,
            List<List<Point2>> shapesXZ
    ) {
        static ZoneSnapshot from(ZoneInfo z) {
            if (z == null) return null;
            World w = z.getWorld();
            if (w == null) return null;

            List<List<Point2>> shapes = new ArrayList<>();
            for (List<Location> corners : z.getShapes()) {
                if (corners == null || corners.size() < 3) continue;
                List<Point2> pts = new ArrayList<>(corners.size());
                for (Location L : corners) pts.add(new Point2(L.getX(), L.getZ()));
                shapes.add(pts);
            }
            if (shapes.isEmpty()) return null;
            return new ZoneSnapshot(z.getID(), z.getType(), w.getName(), shapes);
        }
    }
    private record Point2(double x, double z) {}

    // Подсчёт дневной цены по снапшоту зоны и снапшоту статистики.
    // Цена = (активность + ценность земли) × множитель типа зоны.
    // Это две НЕЗАВИСИМЫЕ величины: активность коррелирует с зарплатой
    // игрока (то же самое время/действия), а ценность земли — со стройкой
    // (накопительно, не угасает) и трафиком чужих игроков (не владельца) —
    // так налог на зону не превращается в самообложение за свою же игру.
    private double calculateDailyCostSnapshot(ZoneSnapshot snap,
                                              Map<String, ChunkStats> statsMap,
                                              ActivityWeights weights,
                                              LandValueWeights landWeights,
                                              double typeMul) {
        Map<String, Double> fractions = getChunkFractionsForShapes(snap.worldName(), snap.shapesXZ());
        if (fractions.isEmpty()) return 0.0;

        double twvActivity = 0, twvLand = 0, tw = 0;
        for (Map.Entry<String, Double> e : fractions.entrySet()) {
            ChunkStats st = statsMap.get(e.getKey());
            if (st == null) continue;
            double dailyAvg = st.getDailyAverage(weights);
            double landValue = landWeights.calculateValue(st);
            double f = e.getValue();
            twvActivity += dailyAvg * f;
            twvLand     += landValue * f;
            tw += f;
        }
        double activityBase = tw > 0 ? twvActivity / tw : 0.0;
        double landBase     = tw > 0 ? twvLand / tw : 0.0;
        return (activityBase + landBase) * typeMul;
    }

    // Оценка пересечений типов зон по чанковым ключам (без Bukkit)
    private double overlapAwareTypeMultiplierSnapshot(ZoneSnapshot target, Collection<ZoneSnapshot> all) {
        ZoneTypeData base = zm.zoneLimits.get(target.type());
        double baseMul = base != null ? base.priceMultiplier() : 1.0;

        Set<String> tKeys = chunkKeysForShapes(target.worldName(), target.shapesXZ());
        if (tKeys.isEmpty()) return baseMul;

        double sum = baseMul; int cnt = 1;
        for (ZoneSnapshot other : all) {
            if (other == null || other == target) continue;
            if (!Objects.equals(other.worldName(), target.worldName())) continue;

            Set<String> oKeys = chunkKeysForShapes(other.worldName(), other.shapesXZ());
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

    /**
     * Мульти-полигон: объединяет доли покрытия чанков по ВСЕМ фигурам зоны.
     * Части одной зоны по конструкции разнесены (ZoneGeometry.MIN_GAP_BETWEEN_SHAPES),
     * поэтому пересечение по чанкам между ними — редкий краевой случай; на всякий
     * случай доли суммируются с ограничением 1.0, чтобы не переплатить дважды.
     */
    private Map<String, Double> getChunkFractionsForShapes(String worldName, List<List<Point2>> shapes) {
        Map<String, Double> merged = new HashMap<>();
        if (shapes == null) return merged;
        for (List<Point2> poly : shapes) {
            for (Map.Entry<String, Double> e : getChunkFractionsKeys(worldName, poly).entrySet()) {
                merged.merge(e.getKey(), e.getValue(), (a, b) -> Math.min(1.0, a + b));
            }
        }
        return merged;
    }

    private Set<String> chunkKeysForShapes(String worldName, List<List<Point2>> shapes){
        return getChunkFractionsForShapes(worldName, shapes).keySet();
    }

    // ===== Планировщик: 00:05 раз в сутки =====
    public void startZoneBillingScheduler() {
        long delay  = ticksUntilNextTime();
        long period = 20L * 60 * 60 * 24;

        // Запускаем на ГЛАВНОМ потоке: снимем снапшоты и дернём async-вычисления
        // внутри сам разделит на sync/async этапы
        Bukkit.getScheduler().runTaskTimer(zm.ul, this::snapshotAndMaybeBillAllZones, delay, period);
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
        final LandValueWeights landWeights = zm.activityTracker.getLandValueWeights();

        // --- ВЫЧИСЛЕНИЯ (ASYNC: без Bukkit) ---
        Bukkit.getScheduler().runTaskAsynchronously(zm.ul, () -> {
            try {
                // суточная стоимость по снапшотам
                final Map<String, Double> dailyCosts = new HashMap<>();
                for (ZoneSnapshot s : zoneSnaps.values()) {
                    double mul = overlapAwareTypeMultiplierSnapshot(s, zoneSnaps.values());
                    double cost = calculateDailyCostSnapshot(s, statsCopy, weights, landWeights, mul);
                    dailyCosts.put(s.id(), cost);
                }
                // --- ЗАПИСЬ РЕЗУЛЬТАТОВ (обратно на главный поток) ---
                Bukkit.getScheduler().runTask(zm.ul, () -> {
                    // 1) фиксируем дневные стоимости в самих зонах
                    for (Map.Entry<String, Double> e : dailyCosts.entrySet()) {
                        ZoneInfo z = zoneById.get(e.getKey());
                        if (z != null) {
                            z.addDailyCost(today, e.getValue());
                        }
                    }

                    // 2) собираем дневной налог по странам
                    Map<String, Double> countryDailyTax = new HashMap<>();
                    for (Map.Entry<String, Double> e : dailyCosts.entrySet()) {
                        ZoneInfo z = zoneById.get(e.getKey());
                        if (z == null) continue;
                        if (!z.hasCountry()) continue; // зона не привязана к стране — пропускаем

                        String country = z.getCountryName();
                        if (country == null || country.isBlank()) continue;

                        double cost = e.getValue();
                        if (cost <= 0.0) continue;

                        // аккумулируем
                        countryDailyTax.merge(country, cost, Double::sum);
                    }

                    // 3) пишем в БД: WeeklyTaxDue и общий счётчик Taxes
                    if (!countryDailyTax.isEmpty()) {
                        var reg = zm.ul.countryRegistryJdbc;
                        for (Map.Entry<String, Double> e : countryDailyTax.entrySet()) {
                            String country = e.getKey();
                            double dailyTax = round2(e.getValue()); // округлим до 2 знаков, чтобы не плодить мусор

                            if (dailyTax <= 0.0) continue;

                            // накопительный недельный долг
                            reg.addWeeklyTaxDue(country, dailyTax);
                            // общий накопленный налог (история)
                            reg.addCountryTaxes(country, dailyTax);
                        }
                    }

                    // 4) авто-списание по расписанию (как было)
                    for (ZoneInfo z : zoneById.values()) {
                        if (today.isBefore(z.getNextBillingDate())) continue;

                        double dueRaw = z.getDueSinceLastBill(today);
                        double amount = round2(dueRaw);
                        if (amount <= 0.0) {
                            z.markBilled(today);
                            continue;
                        }

                        String owner = z.getOwner();
                        if (owner == null || owner.isBlank()) {
                            Bukkit.getLogger().warning("[Zones] Auto-billing skipped for zone " + z.getName()
                                    + ": owner is null/empty, amount=" + String.format(Locale.US, "%.2f", amount));
                            z.markBilled(today);
                            continue;
                        }

                        try {
                            int fromUserId = 0;
                            Instant dueAt = Instant.now().plusSeconds(7 * 24 * 3600L); // например 7 дней

                            UnityCommands.getInstance().getPlayerInfo(owner, data -> {
                                if (data == null) return;
                                int toUserId = data.userId; // <-- важно: нужен именно user_id из БД

                                bankInvoicesDao.createToUserAsync(
                                        fromUserId,
                                        toUserId,
                                        amount,
                                        "Оплата зоны: " + z.getName() + " (начисление за период)",
                                        dueAt
                                );

                                // и только после успешного "выставления" (мы делаем async fire-and-forget),
                                // помечаем billed, чтобы не выставить второй раз
                                Bukkit.getScheduler().runTask(plugin, () -> z.markBilled(today));
                            });

                            Bukkit.getLogger().info("[Zones] Auto-billed " + z.getName()
                                    + " owner=" + owner
                                    + " amount=" + String.format(Locale.US, "%.2f", amount));
                        } catch (Exception ex) {
                            Bukkit.getLogger().warning("[Zones] Auto-billing failed for zone " + z.getName()
                                    + " owner=" + owner
                                    + " amount=" + String.format(Locale.US, "%.2f", amount)
                                    + " error=" + ex.getMessage());
                        }
                    }

                    // 5) освежаем due_cost / дату следующего списания в веб-вьюхе для
                    // ВСЕХ зон — иначе сайт показывал бы накопленную сумму только с
                    // момента последнего изменения границ/имени/цвета зоны
                    for (ZoneInfo z : zoneById.values()) {
                        zm.refreshWebView(z);
                    }
                });
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[Zones] Async cost calc failed: " + t.getMessage());
            }
        });
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }


    /* ===== Почасовой ряд (старые → новые) ===== */
    public List<Double> getZoneHourlySeries(ZoneInfo zone,
                                            Map<String, ChunkStats> statsMap,
                                            int maxHours) {
        World w = zone.getWorld();
        if (w == null) return Collections.emptyList();

        // Полигоны (мульти-полигон) → объединённые ключи чанков
        Map<String, Double> fractions = getChunkFractionsForShapes(w.getName(), pointsFromShapes(zone.getShapes()));
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

        // сигнатура без привязки к Chunk (по ВСЕМ фигурам зоны — мульти-полигон)
        String sig = costSignatureMulti(zone.getShapes(), typeMul);
        long now = System.currentTimeMillis();

        CostCacheEntry hit = costCache.get(key);
        if (hit != null && hit.sig.equals(sig) && (now - hit.ts) < COST_TTL_MS) return hit.cost;

        World w = zone.getWorld();
        if (w == null) { costCache.put(key, new CostCacheEntry(0, now, sig)); return 0; }

        // БЕЗОПАСНО: считаем доли в ключах "world:x,z", объединённые по всем фигурам зоны
        Map<String, Double> fr = getChunkFractionsForShapes(w.getName(), pointsFromShapes(zone.getShapes()));

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

        World tw = target.getWorld();
        if (tw == null) return baseMul;

        // ключи чанков цели (по всем фигурам)
        Set<String> tKeys = chunkKeysForShapesLoc(target.getShapes());
        if (tKeys.isEmpty()) return baseMul;

        double sum = baseMul;
        int cnt = 1;

        for (ZoneInfo other : zm.zoneList.values()){
            if (other == null || other == target) continue;

            World ow = other.getWorld();
            if (ow == null || !ow.equals(tw)) continue;

            // быстрый предчек по чанкам (по всем фигурам обеих зон)
            if (!intersectsByChunks(tKeys, chunkKeysForShapesLoc(other.getShapes()))) continue;

            // учтём только те пересечения, которые действительно разрешены правилами
            if (!ZoneOverlapRules.canZonesCoexist(target, other, zm.zoneLimits)) {
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

    /** Point2-полигон на каждую фигуру зоны (мульти-полигон) — для передачи в getChunkFractionsForShapes/*ForCorners. */
    private static List<List<Point2>> pointsFromShapes(List<List<Location>> shapes) {
        List<List<Point2>> out = new ArrayList<>(shapes.size());
        for (List<Location> corners : shapes) {
            if (corners == null || corners.size() < 3) continue;
            List<Point2> poly = new ArrayList<>(corners.size());
            for (Location L : corners) poly.add(new Point2(L.getX(), L.getZ()));
            out.add(poly);
        }
        return out;
    }

    private Set<String> chunkKeysForShapesLoc(List<List<Location>> shapes){
        if (shapes == null || shapes.isEmpty()) return Set.of();
        World w = shapes.get(0).isEmpty() ? null : shapes.get(0).getFirst().getWorld();
        if (w == null) return Set.of();
        return getChunkFractionsForShapes(w.getName(), pointsFromShapes(shapes)).keySet();
    }

    private static String costSignatureMulti(List<List<Location>> shapes, double mul){
        if (shapes == null || shapes.isEmpty()) return "empty|mul="+mul;
        World w = shapes.get(0).isEmpty() ? null : shapes.get(0).getFirst().getWorld();
        String world = (w!=null? w.getName() : "null");
        StringBuilder sb = new StringBuilder(world).append('|').append(shapes.size()).append('|');
        for (List<Location> corners : shapes) {
            sb.append('[').append(corners.size()).append(']');
            for (Location L : corners) {
                sb.append(L.getBlockX()).append(',').append(L.getBlockZ()).append(';');
            }
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

    /**
     * Публичный доступ к долям покрытия чанков полигоном зоны.
     * Ключ: "world:cx,cz" -> fraction (0..1)
     *
     * Важно: безопасно вызывать async, если corners уже не трогаются из другого потока.
     * Рекомендация: вызывать на главном потоке или передавать snapshot (копию списка).
     */
    public Map<String, Double> getChunkFractionsForCorners(String worldName, List<Location> corners) {
        Map<String, Double> res = new HashMap<>();
        if (worldName == null || corners == null || corners.size() < 3) return res;

        List<Point2> poly = new ArrayList<>(corners.size());
        for (Location L : corners) poly.add(new Point2(L.getX(), L.getZ()));
        return getChunkFractionsKeys(worldName, poly);
    }

}
