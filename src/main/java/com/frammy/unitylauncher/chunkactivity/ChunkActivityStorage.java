package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class ChunkActivityStorage {

    public static void saveToFile(Map<String, ChunkStats> statsMap, File dataFolder) {
        File file = new File(dataFolder, "chunk_activity.yml");
        YamlConfiguration config = new YamlConfiguration();

        for (Map.Entry<String, ChunkStats> entry : statsMap.entrySet()) {
            String key = entry.getKey();
            ChunkStats stats = entry.getValue();

            config.set(key + ".timeSpent", stats.timeSpent);
            config.set(key + ".blocksPlaced", stats.blocksPlaced);
            config.set(key + ".blocksBroken", stats.blocksBroken);
        }

        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
