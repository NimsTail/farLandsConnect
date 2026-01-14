package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;

public record ParkCfg(
        GardenerCfg gardener,
        QuietGuardCfg quietGuard,
        PondBedsCfg pondBeds,
        QuietHourCfg quietHour,
        BenchesCfg benches
) {
    public static ParkCfg defaults() {
        return new ParkCfg(
                GardenerCfg.defaults(),
                QuietGuardCfg.defaults(),
                PondBedsCfg.defaults(),
                QuietHourCfg.defaults(),
                BenchesCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;
        dirty |= GardenerCfg.addDefaults(c);
        dirty |= QuietGuardCfg.addDefaults(c);
        dirty |= PondBedsCfg.addDefaults(c);
        dirty |= QuietHourCfg.addDefaults(c);
        dirty |= BenchesCfg.addDefaults(c);
        return dirty;
    }

    public static ParkCfg read(FileConfiguration c) {
        return new ParkCfg(
                GardenerCfg.read(c),
                QuietGuardCfg.read(c),
                PondBedsCfg.read(c),
                QuietHourCfg.read(c),
                BenchesCfg.read(c)
        );
    }

    // 1) Gardener's Hut — bonus growth chance (+1 age)
    public record GardenerCfg(
            boolean enabled,
            String permBase,
            double extraGrowChance // 0..1
    ) {
        public static GardenerCfg defaults() {
            return new GardenerCfg(true, "unity.park.gardener", 0.10);
        }

        public static boolean addDefaults(FileConfiguration c) {
            boolean dirty = false;
            var d = defaults();
            dirty |= def(c, "park.gardener.enabled", d.enabled());
            dirty |= def(c, "park.gardener.permBase", d.permBase());
            dirty |= def(c, "park.gardener.extraGrowChance", d.extraGrowChance());
            return dirty;
        }

        public static GardenerCfg read(FileConfiguration c) {
            var d = defaults();
            return new GardenerCfg(
                    c.getBoolean("park.gardener.enabled", d.enabled()),
                    c.getString("park.gardener.permBase", d.permBase()),
                    c.getDouble("park.gardener.extraGrowChance", d.extraGrowChance())
            );
        }
    }

    // 2) Quiet Guard — makes annoying sounds quieter inside park (ProtocolLib required)
    public record QuietGuardCfg(
            boolean enabled,
            String permBase,

            // Доп. ограничение: если 0 — не ограничиваем (кроме проверки “в парке”)
            int radiusBlocks,

            // Итоговая громкость = oldVolume * volumeMultiplier (0..1)
            double volumeMultiplier,

            // Полезно, чтобы “совсем ноль” не превращал мир в странную тишину
            double minVolume,

            // Совпадение по prefix (startsWith), напр. "minecraft:block.portal" или "block.portal"
            List<String> soundPrefixes,

            // Точное совпадение, если хочешь точечно добить конкретный звук
            List<String> soundKeys
    ) {
        public static QuietGuardCfg defaults() {
            return new QuietGuardCfg(
                    true,
                    "unity.park.quiet_guard",
                    0,
                    0.35,
                    0.0,
                    List.of(
                            "minecraft:block.portal",
                            "minecraft:entity.minecart",
                            "minecraft:block.piston",
                            "minecraft:block.observer"
                    ),
                    List.of()
            );
        }

        public static boolean addDefaults(org.bukkit.configuration.file.FileConfiguration c) {
            boolean dirty = false;
            var d = defaults();

            dirty |= def(c, "park.quietGuard.enabled", d.enabled());
            dirty |= def(c, "park.quietGuard.permBase", d.permBase());

            dirty |= def(c, "park.quietGuard.radiusBlocks", d.radiusBlocks());
            dirty |= def(c, "park.quietGuard.volumeMultiplier", d.volumeMultiplier());
            dirty |= def(c, "park.quietGuard.minVolume", d.minVolume());

            dirty |= def(c, "park.quietGuard.soundPrefixes", d.soundPrefixes());
            dirty |= def(c, "park.quietGuard.soundKeys", d.soundKeys());

            return dirty;
        }

        public static QuietGuardCfg read(org.bukkit.configuration.file.FileConfiguration c) {
            var d = defaults();

            return new QuietGuardCfg(
                    c.getBoolean("park.quietGuard.enabled", d.enabled()),
                    c.getString("park.quietGuard.permBase", d.permBase()),

                    c.getInt("park.quietGuard.radiusBlocks", d.radiusBlocks()),
                    c.getDouble("park.quietGuard.volumeMultiplier", d.volumeMultiplier()),
                    c.getDouble("park.quietGuard.minVolume", d.minVolume()),

                    c.getStringList("park.quietGuard.soundPrefixes"),
                    c.getStringList("park.quietGuard.soundKeys")
            );
        }
    }

    // 3) Pond & Flowerbeds — saturation bonus over time
    public record PondBedsCfg(
            boolean enabled,
            String permBase,
            long periodTicks,
            long cooldownMs,
            double saturationBonus
    ) {
        public static PondBedsCfg defaults() {
            return new PondBedsCfg(true, "unity.park.pond_beds", 100L, 3500L, 0.5);
        }

        public static boolean addDefaults(FileConfiguration c) {
            boolean dirty = false;
            var d = defaults();
            dirty |= def(c, "park.pondBeds.enabled", d.enabled());
            dirty |= def(c, "park.pondBeds.permBase", d.permBase());
            dirty |= def(c, "park.pondBeds.periodTicks", d.periodTicks());
            dirty |= def(c, "park.pondBeds.cooldownMs", d.cooldownMs());
            dirty |= def(c, "park.pondBeds.saturationBonus", d.saturationBonus());
            return dirty;
        }

        public static PondBedsCfg read(FileConfiguration c) {
            var d = defaults();
            return new PondBedsCfg(
                    c.getBoolean("park.pondBeds.enabled", d.enabled()),
                    c.getString("park.pondBeds.permBase", d.permBase()),
                    c.getLong("park.pondBeds.periodTicks", d.periodTicks()),
                    c.getLong("park.pondBeds.cooldownMs", d.cooldownMs()),
                    c.getDouble("park.pondBeds.saturationBonus", d.saturationBonus())
            );
        }
    }

    // 4) Quiet Hour — blocks hostile natural spawns inside park
    public record QuietHourCfg(
            boolean enabled,
            String permBase
    ) {
        public static QuietHourCfg defaults() {
            return new QuietHourCfg(true, "unity.park.quiet_hour");
        }

        public static boolean addDefaults(FileConfiguration c) {
            boolean dirty = false;
            var d = defaults();
            dirty |= def(c, "park.quietHour.enabled", d.enabled());
            dirty |= def(c, "park.quietHour.permBase", d.permBase());
            return dirty;
        }

        public static QuietHourCfg read(FileConfiguration c) {
            var d = defaults();
            return new QuietHourCfg(
                    c.getBoolean("park.quietHour.enabled", d.enabled()),
                    c.getString("park.quietHour.permBase", d.permBase())
            );
        }
    }

    // 5) Benches — regeneration while sneaking near “bench” blocks in park
    public record BenchesCfg(
            boolean enabled,
            String permBase,
            long periodTicks,
            int nearRadiusBlocks,
            int regenDurationTicks,
            int regenAmplifier
    ) {
        public static BenchesCfg defaults() {
            return new BenchesCfg(true, "unity.park.benches", 100L, 6, 60, 0);
        }

        public static boolean addDefaults(FileConfiguration c) {
            boolean dirty = false;
            var d = defaults();
            dirty |= def(c, "park.benches.enabled", d.enabled());
            dirty |= def(c, "park.benches.permBase", d.permBase());
            dirty |= def(c, "park.benches.periodTicks", d.periodTicks());
            dirty |= def(c, "park.benches.nearRadiusBlocks", d.nearRadiusBlocks());
            dirty |= def(c, "park.benches.regenDurationTicks", d.regenDurationTicks());
            dirty |= def(c, "park.benches.regenAmplifier", d.regenAmplifier());
            return dirty;
        }

        public static BenchesCfg read(FileConfiguration c) {
            var d = defaults();
            return new BenchesCfg(
                    c.getBoolean("park.benches.enabled", d.enabled()),
                    c.getString("park.benches.permBase", d.permBase()),
                    c.getLong("park.benches.periodTicks", d.periodTicks()),
                    c.getInt("park.benches.nearRadiusBlocks", d.nearRadiusBlocks()),
                    c.getInt("park.benches.regenDurationTicks", d.regenDurationTicks()),
                    c.getInt("park.benches.regenAmplifier", d.regenAmplifier())
            );
        }
    }
}
