package com.frammy.unitylauncher.upgrades.config;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;

public record CoreCfg(
        boolean enabled,
        boolean debug
) {
    public static CoreCfg defaults() {
        return new CoreCfg(true, false);
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;
        dirty |= def(c, "core.enabled", defaults().enabled());
        dirty |= def(c, "core.debug", defaults().debug());
        return dirty;
    }

    public static CoreCfg read(FileConfiguration c) {
        var d = defaults();
        return new CoreCfg(
                c.getBoolean("core.enabled", d.enabled()),
                c.getBoolean("core.debug", d.debug())
        );
    }
}
