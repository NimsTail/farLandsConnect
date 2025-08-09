package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChunkActivityStorage {

    public static void saveToFile(Map<String, ChunkStats> statsMap, File dataFolder) {
        File file = new File(dataFolder, "chunk_activity.yml");
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, ChunkStats> entry : statsMap.entrySet()) {
            String key = entry.getKey();
            ChunkStats s = entry.getValue();

            config.set(key + ".timeSpent", s.timeSpent);
            config.set(key + ".blocksPlaced", s.blocksPlaced);
            config.set(key + ".blocksBroken", s.blocksBroken);
            config.set(key + ".lastUpdated", s.lastUpdated);

            // hourlySamples -> List<Double>
            config.set(key + ".hourlySamples", new ArrayList<>(s.hourlySamples));
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

        for (String key : config.getKeys(false)) {
            ChunkStats s = new ChunkStats();
            s.timeSpent     = config.getLong(key + ".timeSpent", 0L);
            s.blocksPlaced  = config.getInt(key + ".blocksPlaced", 0);
            s.blocksBroken  = config.getInt(key + ".blocksBroken", 0);
            s.lastUpdated   = config.getLong(key + ".lastUpdated", System.currentTimeMillis());

            List<Double> samples = config.getDoubleList(key + ".hourlySamples");
            if (samples != null) {
                for (Double d : samples) {
                    // защитимся от null
                    if (d != null) s.hourlySamples.addLast(d);
                }
                // ограничим размер до 24 (на всякий)
                while (s.hourlySamples.size() > 24) s.hourlySamples.removeFirst();
            }

            map.put(key, s);
        }

        return map;
    }
}
