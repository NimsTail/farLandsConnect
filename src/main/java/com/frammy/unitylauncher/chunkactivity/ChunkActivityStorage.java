package com.frammy.unitylauncher.chunkactivity;

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

        return s;
    }
}
