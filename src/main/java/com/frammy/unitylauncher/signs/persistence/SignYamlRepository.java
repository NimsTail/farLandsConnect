package com.frammy.unitylauncher.signs.persistence;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignState;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.storage.SignStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

public record SignYamlRepository(UnityLauncher plugin, File dataFolder,
                                 Function<String, String> playerCountryCanonical) {

    private File file() {
        return new File(dataFolder, "signData.yml");
    }

    public void save(SignStore store, boolean debug) {
        File shopFile = file();
        YamlConfiguration shopConfig = new YamlConfiguration();

        for (Map.Entry<Location, SignVariables> entry : store.signs().entrySet()) {
            Location loc = entry.getKey();
            SignVariables vars = entry.getValue();
            if (loc == null || vars == null || loc.getWorld() == null) continue;

            String path = "signs." + plugin.encodeLocation(loc);

            shopConfig.set(path + ".text", vars.getSignText());
            shopConfig.set(path + ".scrollLines", vars.getScrollLines());
            shopConfig.set(path + ".isConfigurable", vars.isConfigurable());
            shopConfig.set(path + ".isPaused", vars.isPaused());
            shopConfig.set(path + ".owner", vars.getOwnerName());
            shopConfig.set(path + ".ownerCountry", vars.getOwnerCountry());
            shopConfig.set(path + ".category", vars.getSignCategory().toString());
            if (vars.getSignCategory() != SignCategory.ATM) {
                shopConfig.set(path + ".state", vars.getSignState() != null ? vars.getSignState().toString() : null);
            }

            shopConfig.set(path + ".markerID", vars.getMarkerID());

            shopConfig.set(path + ".location.world", loc.getWorld().getName());
            shopConfig.set(path + ".location.x", loc.getBlockX());
            shopConfig.set(path + ".location.y", loc.getBlockY());
            shopConfig.set(path + ".location.z", loc.getBlockZ());
        }

        try {
            shopConfig.save(shopFile);
            if (debug) Bukkit.getLogger().info("[Signs] Saved to signData.yml (" + store.signs().size() + " signs)");
        } catch (IOException e) {
            Bukkit.getLogger().severe("[Signs] Save error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void load(SignStore store, boolean debug, Runnable onShopListRebuildLater) {
        File shopFile = file();
        if (!shopFile.exists()) return;

        YamlConfiguration shopConfig = YamlConfiguration.loadConfiguration(shopFile);
        ConfigurationSection signsSection = shopConfig.getConfigurationSection("signs");
        if (signsSection == null) return;

        for (String key : signsSection.getKeys(false)) {
            ConfigurationSection section = signsSection.getConfigurationSection(key);
            if (section == null) continue;

            String worldName = section.getString("location.world");
            int x = section.getInt("location.x");
            int y = section.getInt("location.y");
            int z = section.getInt("location.z");

            if (worldName == null) continue;
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            Location loc = SignStore.keyLoc(new Location(world, x, y, z));

            List<String> text = section.getStringList("text");
            List<Integer> scrollLines = section.getIntegerList("scrollLines");
            boolean isConfigurable = section.getBoolean("isConfigurable");
            boolean isPaused = section.getBoolean("isPaused");
            String owner = section.getString("owner");
            String ownerCountry = section.getString("ownerCountry");
            if (ownerCountry == null || ownerCountry.isBlank()) {
                ownerCountry = playerCountryCanonical.apply(owner);
            }
            String markerID = section.getString("markerID");

            SignCategory category;
            try {
                category = SignCategory.valueOf(section.getString("category", "SHOP_INFO"));
            } catch (IllegalArgumentException ignored) {
                category = SignCategory.SHOP_INFO;
            }

            SignState state;
            if (category == SignCategory.ATM) state = SignState.ATM_MENU;
            else if (category == SignCategory.SHOP_SOURCE) state = SignState.SHOP_UNDEFINED;
            else state = SignState.SHOP_DEFINED;

            String stateStr = section.getString("state");
            if (category != SignCategory.ATM && stateStr != null && !stateStr.isBlank()) {
                try { state = SignState.valueOf(stateStr); } catch (IllegalArgumentException ignored) {}
            }

            // Пишем текст в мир (если табличка существует)
            try {
                if (loc.getBlock().getState() instanceof Sign sign) {
                    for (int i = 0; i < Math.min(4, text.size()); i++) sign.setLine(i, text.get(i));
                    sign.update();
                    store.originalSignTexts().putIfAbsent(loc, sign.getLines().clone());
                }
            } catch (Throwable ignored) {
            }

            SignVariables vars = new SignVariables(owner, ownerCountry, text, scrollLines, isConfigurable, isPaused, category, state, markerID);
            store.put(loc, vars);

            // Индекс контейнеров для SHOP_SOURCE
            if (category == SignCategory.SHOP_SOURCE) {
                Location stored = store.parseContainerLocation(vars, world);
                if (stored != null) {
                    for (Location cLoc : resolveContainerLocations(stored)) {
                        store.bindContainer(SignStore.keyLoc(cLoc), loc);
                    }
                }
            }

        }

        if (onShopListRebuildLater != null) onShopListRebuildLater.run();
        if (debug) Bukkit.getLogger().info("[Signs] Loaded " + store.signs().size() + " signs");
    }

    private static List<Location> resolveContainerLocations(Location anyHalf) {
        if (anyHalf == null || anyHalf.getWorld() == null) return List.of();

        var st = anyHalf.getBlock().getState();

        if (st instanceof Container c) {
            // обычный контейнер
            return List.of(c.getLocation());
        }

        // если это Chest, можно попробовать вытащить DoubleChest через InventoryHolder
        if (st instanceof Chest chest) {
            var holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                List<Location> out = new ArrayList<>(2);
                if (dc.getLeftSide() instanceof Chest cl) out.add(cl.getLocation());
                if (dc.getRightSide() instanceof Chest cr) out.add(cr.getLocation());
                if (!out.isEmpty()) return out;
            }
            return List.of(chest.getLocation());
        }

        return List.of();
    }

}
