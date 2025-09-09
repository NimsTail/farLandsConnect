package com.frammy.unitylauncher.zones.countryrelations;

public enum RelationStatus {
    HOSTILE, NEUTRAL, FRIENDLY;
    public static RelationStatus from(String s) {
        try { return valueOf(s.toUpperCase()); } catch (Exception e) { return NEUTRAL; }
    }
}
