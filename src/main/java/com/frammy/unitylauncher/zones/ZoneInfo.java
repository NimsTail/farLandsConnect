package com.frammy.unitylauncher.zones;

import org.bukkit.Location;

import java.util.List;

public class ZoneInfo {
    ZoneType zoneType;
    String zoneID;
    String zoneName;
    String zoneOwner;
    String markerID;
    List<Location> zoneCorners;

    public ZoneInfo(ZoneType zoneType, String zoneID, String zoneName, String markerID, List<Location> zoneCorners, String zoneOwner) {
        this.zoneType = zoneType;
        this.zoneID = zoneID;
        this.zoneName = zoneName;
        this.markerID = markerID;
        this.zoneCorners = zoneCorners;
        this.zoneOwner = zoneOwner;
    }
    public ZoneType getType() {
        return zoneType;
    }
    public String getID() {
        return zoneID;
    }
    public String getName() {
        return zoneName;
    }
    public String getMarkerID() {
        return markerID;
    }
    public List<Location> getCorners() {
        return zoneCorners;
    }
    public String getOwner() {
        return zoneOwner;
    }

    public void setType(ZoneType type) {
        this.zoneType = type;
    }
    public void setID(String id) {
        this.zoneID = id;
    }
    public void setName(String name) {
        this.zoneName = name;
    }
    public void setMarkerID(String markerID) {
        this.markerID = markerID;
    }
    public void setCorners(List<Location> corners) {
        this.zoneCorners = corners;
    }
    public void setOwner(String owner) {
        this.zoneOwner = owner;
    }

}
