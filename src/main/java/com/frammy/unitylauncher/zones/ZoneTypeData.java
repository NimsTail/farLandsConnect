package com.frammy.unitylauncher.zones;

public class ZoneTypeData {
    private final String displayName;
    private final double areaLimit;
    private final int index;
    private final double minSize;
    private final boolean allowOverlap;


    public ZoneTypeData(String displayName, double areaLimit, int index, double minSize, boolean allowOverlap) {
        this.displayName = displayName;
        this.areaLimit = areaLimit;
        this.minSize = minSize;
        this.allowOverlap = allowOverlap;
        this.index = index;
    }

    public String getDisplayName() {
        return displayName;
    }
    public double getAreaLimit() {
        return areaLimit;
    }
    public int getIndex() {
        return index;
    }
    public double getMinSize(){return minSize;}
    public boolean getAllowOverlap(){return allowOverlap;}
}
