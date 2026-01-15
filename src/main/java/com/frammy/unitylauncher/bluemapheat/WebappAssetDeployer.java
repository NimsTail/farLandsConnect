package com.frammy.unitylauncher.bluemapheat;

import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.nio.file.*;

public final class WebappAssetDeployer {

    private WebappAssetDeployer() {}

    public static void deploy(Plugin plugin, Path bluemapWebRoot) {
        try {
            Files.createDirectories(bluemapWebRoot);

            Path assetsDir = bluemapWebRoot.resolve("assets");
            Files.createDirectories(assetsDir);

            Path dst = assetsDir.resolve("farlands-heat.js");

            try (InputStream in = plugin.getResource("bluemapheat/farlands-heat.js")) {
                if (in == null) {
                    plugin.getLogger().severe("[BlueMapHeat] Resource bluemapheat/farlands-heat.js not found in jar! Heat overlay DISABLED.");
                    return;

                }
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }

            plugin.getLogger().info("[BlueMapHeat] Deployed farlands-heat.js -> " + dst);
        } catch (Throwable t) {
            plugin.getLogger().warning("[BlueMapHeat] Failed deploy js: " + t.getMessage());
        }
    }
}
