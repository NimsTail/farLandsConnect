package com.frammy.unitylauncher.zones;

import org.bukkit.Location;
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
}
