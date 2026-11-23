package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ChunkActivityStorage {

    private static final String ROOT = "chunks";

    // ":" и "," делаем безопасными (YAML-friendly)
    private static String enc(String chunkKey) {
        return chunkKey.replace(":", "@").replace(",", ";");
    }

    private static String dec(String encoded) {
        return encoded.replace("@", ":").replace(";", ",");
    }

    public static void saveToFile(Map<String, ChunkStats> statsMap, File dataFolder) {
        File file = new File(dataFolder, "chunk_activity.yml");
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, ChunkStats> entry : statsMap.entrySet()) {
            String key = entry.getKey();
            ChunkStats s = entry.getValue();
            if (s == null) continue;

            String path = ROOT + "." + enc(key);

            config.set(path + ".timeSpent", s.timeSpent);
            config.set(path + ".blocksPlaced", s.blocksPlaced);
            config.set(path + ".blocksBroken", s.blocksBroken);
            config.set(path + ".lastUpdated", s.lastUpdated);
            config.set(path + ".itemDrops", s.itemDrops);
            config.set(path + ".entitySpawns", s.entitySpawns);
            config.set(path + ".tickLoad", s.tickLoad);
            config.set(path + ".playerActivity", s.playerActivity);
            config.set(path + ".structureBonus", s.structureBonus);

            config.set(path + ".hourlySamples", new ArrayList<>(s.hourlySamples));

            config.set(path + ".netBuildVolume", s.netBuildVolume);
            // "uuid:weight" — вес визита (владелец/гражданин зоны считается ниже 1.0,
            // см. ActivityTracker.computeVisitorWeight) тоже должен переживать рестарт
            List<String> visitorStrs = new ArrayList<>(s.getVisitorWeights().size());
            for (Map.Entry<java.util.UUID, Double> e : s.getVisitorWeights().entrySet()) {
                visitorStrs.add(e.getKey() + ":" + e.getValue());
            }
            config.set(path + ".uniqueVisitors", visitorStrs);

            List<String> materialStrs = new ArrayList<>(s.getMaterialsPlaced().size());
            for (Material m : s.getMaterialsPlaced()) materialStrs.add(m.name());
            config.set(path + ".materialsPlaced", materialStrs);
        }

        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static Map<String, ChunkStats> loadFromFile(File dataFolder) {
        Map<String, ChunkStats> map = new HashMap<>();
        File file = new File(dataFolder, "chunk_activity.yml");
        if (!file.exists()) return map;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        // (A) новый формат: chunks.<encodedKey>.*
        ConfigurationSection root = config.getConfigurationSection(ROOT);
        if (root != null) {
            for (String encodedKey : root.getKeys(false)) {
                String chunkKey = dec(encodedKey);
                ChunkStats s = readStats(config, ROOT + "." + encodedKey);
                map.put(chunkKey, s);
            }
            return map;
        }

        // (B) legacy формат: top-level keys типа "world:x,z"
        // прочитаем и автоматически мигрируем при следующем save
        for (String key : config.getKeys(false)) {
            ChunkStats s = readStats(config, key);
            map.put(key, s);
        }
        return map;
    }

    private static ChunkStats readStats(YamlConfiguration config, String path) {
        ChunkStats s = new ChunkStats();
        s.timeSpent     = config.getLong(path + ".timeSpent", 0L);
        s.blocksPlaced  = config.getInt(path + ".blocksPlaced", 0);
        s.blocksBroken  = config.getInt(path + ".blocksBroken", 0);
        s.lastUpdated   = config.getLong(path + ".lastUpdated", System.currentTimeMillis());
        s.itemDrops     = config.getInt(path + ".itemDrops", 0);
        s.entitySpawns  = config.getInt(path + ".entitySpawns", 0);
        s.tickLoad      = config.getDouble(path + ".tickLoad", 0.0);
        s.playerActivity= config.getDouble(path + ".playerActivity", 0.0);
        s.structureBonus= config.getDouble(path + ".structureBonus", 0.0);

        List<Double> samples = config.getDoubleList(path + ".hourlySamples");
        for (Double d : samples) {
            if (d != null) s.hourlySamples.addLast(d);
        }
        while (s.hourlySamples.size() > 24) s.hourlySamples.removeFirst();

        s.netBuildVolume = config.getDouble(path + ".netBuildVolume", 0.0);
        for (String vs : config.getStringList(path + ".uniqueVisitors")) {
            try {
                // новый формат "uuid:weight"; старый формат — голый uuid (вес по умолчанию 1.0)
                int sep = vs.lastIndexOf(':');
                if (sep > 0) {
                    java.util.UUID id = java.util.UUID.fromString(vs.substring(0, sep));
                    double w = Double.parseDouble(vs.substring(sep + 1));
                    s.addVisitor(id, w);
                } else {
                    s.addVisitor(java.util.UUID.fromString(vs));
                }
            } catch (Exception ignored) {}
        }
        for (String ms : config.getStringList(path + ".materialsPlaced")) {
            try { s.recordMaterialPlaced(Material.valueOf(ms)); } catch (IllegalArgumentException ignored) {}
        }

        return s;
    }
}
