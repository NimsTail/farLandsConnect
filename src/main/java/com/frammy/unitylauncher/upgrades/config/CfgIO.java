package com.frammy.unitylauncher.upgrades.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public final class CfgIO {
    private CfgIO() {}

    public static boolean def(FileConfiguration c, String path, Object value) {
        if (!c.contains(path)) {
            c.set(path, value);
            return true;
        }
        return false;
    }

    public static String str(FileConfiguration c, String path, String fallback) {
        String v = c.getString(path);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    public static List<String> strList(FileConfiguration c, String path, List<String> fallback) {
        List<String> v = c.getStringList(path);
        return v.isEmpty() ? fallback : v;
    }
}
