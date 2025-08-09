package com.frammy.unitylauncher.zones;

public class ZoneTypeData {
    private final String displayName;
    private final double areaLimit;
    private final int index;
    private final double minSize;
    private final boolean allowOverlap;
    private final double priceMultiplier;


    public ZoneTypeData(String displayName, double areaLimit, int index, double minSize, boolean allowOverlap, double priceMultiplier) {
        this.displayName = displayName;
        this.areaLimit = areaLimit;
        this.minSize = minSize;
        this.allowOverlap = allowOverlap;
        this.index = index;
        this.priceMultiplier = priceMultiplier;
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
    public double getPriceMultiplier(){return priceMultiplier;}
}
