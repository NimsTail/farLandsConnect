package com.frammy.unitylauncher.signs.features.trash;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TrashSellConfig {

    public record Data(
            boolean enabled,
            int minStackSize,
            Set<Material> blacklist,
            Map<Material, Double> prices
    ) {}

    private static volatile Data DATA = new Data(true, 1, Set.of(), Map.of());

    public static Data get() { return DATA; }

    public static void load(JavaPlugin plugin) {
        File dir = plugin.getDataFolder();
        if (!dir.exists()) dir.mkdirs();

        File file = new File(dir, "trash-sell.yml");
        if (!file.exists()) {
            writeDefault(file);
        }

        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);

        boolean enabled = y.getBoolean("enabled", true);
        int minStackSize = Math.max(1, y.getInt("minStackSize", 1));

        // blacklist
        Set<Material> blacklist = new HashSet<>();
        for (String s : y.getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
        }

        // prices
        Map<Material, Double> prices = new HashMap<>();
        var sec = y.getConfigurationSection("prices");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) continue;
                double price = sec.getDouble(key, 0.0);
                if (price > 0.0) prices.put(m, price);
            }
        }

        DATA = new Data(enabled, minStackSize,
                Collections.unmodifiableSet(blacklist),
                Collections.unmodifiableMap(prices));
    }

    private static void writeDefault(File file) {
        // Минимальный дефолт. Ты потом накидаешь свои реальные цены.
        // Важно: ключи материалов — строго как в Bukkit: COBBLESTONE, DIRT, ...
        String txt = """
                enabled: true
                minStackSize: 1
                blacklist:
                  - AIR
                  - BEDROCK
                prices:
                  COBBLESTONE: 0.01
                  DIRT: 0.01
                  GRAVEL: 0.02
                """;
        try {
            java.nio.file.Files.writeString(file.toPath(), txt, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
}
