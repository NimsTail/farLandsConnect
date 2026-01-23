package com.frammy.unitylauncher.zones.io;

import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class ZoneYamlRepository {

    private final File zonesFile;
    private YamlConfiguration zonesConfig;

    public ZoneYamlRepository(File zonesFile) {
        this.zonesFile = Objects.requireNonNull(zonesFile, "zonesFile");
        this.zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
    }

    public void reload() {
        this.zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
    }

    public void saveRaw() {
        try {
            zonesConfig.save(zonesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Загружает зоны из YAML в targetMap. Возвращает true, если в YAML были сделаны миграции и надо сохранить. */
    public boolean loadInto(Map<String, ZoneInfo> targetMap, Map<String, Long> lastCornersEditByMarker) {
        Objects.requireNonNull(targetMap, "targetMap");
        targetMap.clear();

        boolean needsSave = false;

        for (String typeKey : zonesConfig.getKeys(false)) {
            ZoneType zoneType;
            try {
                zoneType = ZoneType.valueOf(typeKey.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }

            ConfigurationSection owners = zonesConfig.getConfigurationSection(typeKey);
            if (owners == null) continue;

            for (String owner : owners.getKeys(false)) {
                ConfigurationSection zones = owners.getConfigurationSection(owner);
                if (zones == null) continue;

                for (String zoneId : zones.getKeys(false)) {
                    ConfigurationSection z = zones.getConfigurationSection(zoneId);
                    if (z == null) continue;

                    String name = z.getString("name", "Без названия");
                    String colorHex = z.getString("color", "#FFFFFF");
                    org.bukkit.Color color = hexToBukkit(colorHex);

                    // marker_ID с автогенерацией + миграцией marker_<uuid> -> <uuid>
                    String markerID = z.getString("marker_ID", null);
                    if (markerID == null || markerID.isBlank()) {
                        markerID = UUID.randomUUID().toString();
                        z.set("marker_ID", markerID);
                        needsSave = true;
                    } else if (markerID.startsWith("marker_") && markerID.length() > "marker_".length()) {
                        markerID = markerID.substring("marker_".length());
                        z.set("marker_ID", markerID);
                        needsSave = true;
                    }

                    String worldOverride = z.getString("world", null);

                    List<Location> corners = new ArrayList<>();
                    for (Map<?, ?> m : z.getMapList("corners")) {
                        try {
                            String wName = (worldOverride != null ? worldOverride : (String) m.get("world"));
                            World w = Bukkit.getWorld(wName);
                            if (w == null) continue;

                            double x = num(m, "x");
                            double y = num(m, "y");
                            double zz = num(m, "z");
                            float pitch = fnum(m, "pitch");
                            float yaw = fnum(m, "yaw");

                            corners.add(new Location(w, x, y, zz, yaw, pitch));
                        } catch (Throwable ignore) { }
                    }

                    // если world отсутствует наверху, но углы есть — проставим world из первого угла
                    if ((worldOverride == null || worldOverride.isBlank()) && !corners.isEmpty() && corners.getFirst().getWorld() != null) {
                        z.set("world", corners.getFirst().getWorld().getName());
                        needsSave = true;
                    }

                    long lastEdit = z.getLong("lastCornersEdit", -1L);
                    if (lastEdit > 0L && lastCornersEditByMarker != null) {
                        lastCornersEditByMarker.put(markerID, lastEdit);
                    }

                    ZoneInfo zi = new ZoneInfo(zoneType, zoneId, name, markerID, corners, owner, color);

                    String ownerCountryYaml = z.getString("ownerCountry", null);
                    if (ownerCountryYaml != null && !ownerCountryYaml.isBlank()) {
                        zi.setOwnerCountry(ownerCountryYaml);
                    }

                    targetMap.put(markerID, zi);
                }
            }
        }

        return needsSave;
    }

    /** Сохраняет все зоны в YAML (полностью переписывает файл). */
    public void saveFrom(Collection<ZoneInfo> zones, Map<String, Long> lastCornersEditByMarker) {
        zonesConfig = new YamlConfiguration();

        for (ZoneInfo z : zones) {
            String path = z.getType().name().toLowerCase(Locale.ROOT) + "." + z.getOwner() + "." + z.getID();

            zonesConfig.set(path + ".name", z.getName());

            if (z.getCountryName() != null && !z.getCountryName().isBlank()) {
                zonesConfig.set(path + ".ownerCountry", z.getCountryName());
            }

            zonesConfig.set(path + ".color", bukkitToHex(z.getFillColor() != null ? z.getFillColor() : org.bukkit.Color.WHITE));
            zonesConfig.set(path + ".marker_ID", z.getMarkerID());

            zonesConfig.set(path + ".world",
                    z.getCorners().isEmpty() || z.getCorners().getFirst().getWorld() == null
                            ? "world"
                            : z.getCorners().getFirst().getWorld().getName());

            List<Map<String, Object>> corners = new ArrayList<>();
            for (Location l : z.getCorners()) {
                Map<String, Object> m = new HashMap<>();
                m.put("world", l.getWorld().getName());
                m.put("x", l.getX());
                m.put("y", l.getY());
                m.put("z", l.getZ());
                m.put("pitch", l.getPitch());
                m.put("yaw", l.getYaw());
                corners.add(m);
            }
            zonesConfig.set(path + ".corners", corners);

            if (lastCornersEditByMarker != null) {
                Long lastEdit = lastCornersEditByMarker.get(z.getMarkerID());
                if (lastEdit != null && lastEdit > 0L) {
                    zonesConfig.set(path + ".lastCornersEdit", lastEdit);
                }
            }
        }

        saveRaw();
    }

    // ---- utils ----

    private static org.bukkit.Color hexToBukkit(String hex) {
        if (hex == null || hex.isEmpty()) return org.bukkit.Color.WHITE;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        int rgb = (int) Long.parseLong(s, 16);
        return org.bukkit.Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static String bukkitToHex(org.bukkit.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static double num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).doubleValue() : 0.0;
    }

    private static float fnum(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).floatValue() : 0.0f;
    }
}
