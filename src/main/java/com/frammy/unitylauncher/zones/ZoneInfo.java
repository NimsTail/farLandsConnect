package com.frammy.unitylauncher.zones;

import org.bukkit.Location;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

public class ZoneInfo {
    ZoneType zoneType;
    String zoneID;
    String zoneName;
    String zoneOwner;
    String markerID;
    List<Location> zoneCorners;
    String worldName;
    private double cachedDailyCost = 0;
    private long lastCostUpdate = 0;
    private static final long COST_CACHE_DURATION_MS = 5 * 60 * 1000; // 5 минут
    // --- NEW: история последних 7 дневных цен ---
    private final Deque<Double> last7DailyCosts = new ArrayDeque<>(7);
    private LocalDate lastDailySnapshotDate = null;

    // --- NEW: когда последний раз списали (ISO неделя) ---
    private Integer lastBilledWeek = null;           // номер недели
    private Integer lastBilledWeekYear = null;       // год «week-based year»

    public ZoneInfo(ZoneType zoneType, String zoneID, String zoneName,
                    String markerID, List<Location> zoneCorners, String zoneOwner) {
        this.zoneType = zoneType;
        this.zoneID = zoneID;
        this.zoneName = zoneName;
        this.markerID = markerID;
        this.zoneCorners = zoneCorners;
        this.zoneOwner = zoneOwner;
        this.worldName = (zoneCorners != null && !zoneCorners.isEmpty() && zoneCorners.get(0).getWorld() != null) ? zoneCorners.get(0).getWorld().getName() : null;
    }

    public ZoneType getType() { return zoneType; }
    public String getID() { return zoneID; }
    public String getName() { return zoneName; }
    public String getMarkerID() { return markerID; }
    public List<Location> getCorners() { return zoneCorners; }
    public String getOwner() { return zoneOwner; }
    public String getWorldName() { return worldName; } // ⬅️ геттер мира

    public void setType(ZoneType type) { this.zoneType = type; }
    public void setID(String id) { this.zoneID = id; }
    public void setName(String name) { this.zoneName = name; }
    public void setMarkerID(String markerID) { this.markerID = markerID; }
    public void setCorners(List<Location> corners) {
        this.zoneCorners = corners;
        this.worldName = (corners != null && !corners.isEmpty() && corners.get(0).getWorld() != null) ? corners.get(0).getWorld().getName() : null;
    }
    public void setOwner(String owner) { this.zoneOwner = owner; }

    public double getCachedCost(Supplier<Double> calculateFunction) {
        long now = System.currentTimeMillis();
        if (now - lastCostUpdate > COST_CACHE_DURATION_MS) {
            cachedDailyCost = calculateFunction.get();
            lastCostUpdate = now;
        }
        return cachedDailyCost;
    }
    public void addDailyCost(double cost, LocalDate date) {
        // защита от повторного снапшота за тот же день
        if (lastDailySnapshotDate != null && lastDailySnapshotDate.equals(date)) return;

        lastDailySnapshotDate = date;

        last7DailyCosts.addLast(cost);
        while (last7DailyCosts.size() > 7) last7DailyCosts.removeFirst();
    }

    public double getRolling7DayTotal() {
        return last7DailyCosts.stream().mapToDouble(d -> d).sum();
    }

    public double getRolling7DayAverage() {
        return last7DailyCosts.isEmpty() ? 0.0 : getRolling7DayTotal() / last7DailyCosts.size();
    }

    public boolean shouldBillWeekly(LocalDate today) {
        // ISO-неделя
        WeekFields wf = WeekFields.ISO;
        int week = today.get(wf.weekOfWeekBasedYear());
        int yweek = today.get(wf.weekBasedYear());

        // Биллим, если:
        // 1) набралось >= 7 дневных точек (иначе нечего списывать корректно)
        // 2) неделя сменилась относительно последнего биллинга
        if (last7DailyCosts.size() < 7) return false;

        if (lastBilledWeek == null || lastBilledWeekYear == null) return true; // ещё ни разу не билили
        // биллим в момент смены недели: текущая неделя != последней биллинговой
        return !(lastBilledWeek == week && lastBilledWeekYear == yweek);
    }

    public void markBilled(LocalDate today) {
        WeekFields wf = WeekFields.ISO;
        lastBilledWeek = today.get(wf.weekOfWeekBasedYear());
        lastBilledWeekYear = today.get(wf.weekBasedYear());
    }

    // геттеры/сеттеры (твои) остаются как есть

}
