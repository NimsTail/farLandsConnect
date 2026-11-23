package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.strList;

public record ColonyCfg(
        OutpostRaidCullCfg outpostRaidCull
) {
    public static ColonyCfg defaults() {
        return new ColonyCfg(
                OutpostRaidCullCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        var op = OutpostRaidCullCfg.defaults();
        dirty |= def(c, "colony.outpostRaidCull.enabled", op.enabled());
        dirty |= def(c, "colony.outpostRaidCull.permBase", op.permBase());
        dirty |= def(c, "colony.outpostRaidCull.cullPercent", op.cullPercent());

        return dirty;
    }

    public static ColonyCfg read(FileConfiguration c) {
        var d = defaults();

        var opD = OutpostRaidCullCfg.defaults();
        var op = new OutpostRaidCullCfg(
                c.getBoolean("colony.outpostRaidCull.enabled", opD.enabled()),
                str(c, "colony.outpostRaidCull.permBase", opD.permBase()),
                c.getDouble("colony.outpostRaidCull.cullPercent", opD.cullPercent())
        );

        return new ColonyCfg(op);
    }

    public record OutpostRaidCullCfg(
            boolean enabled,
            String permBase,
            double cullPercent
    ) {
        public static OutpostRaidCullCfg defaults() {
            return new OutpostRaidCullCfg(
                    true,
                    "unity.colony.outpost_raid_cull",
                    35.0
            );
        }
    }

}
