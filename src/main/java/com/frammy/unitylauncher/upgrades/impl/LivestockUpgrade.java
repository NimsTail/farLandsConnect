package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Animals;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityBreedEvent;

import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class LivestockUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.livestock");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().country().livestock();
        return cfg.enabled() && (cfg.speedPercent() > 0.0 || cfg.doubleChancePercent() > 0.0);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onAnimalBreed(EntityBreedEvent e) {
        if (!(e.getEntity() instanceof Animals baby)) return;

        Location loc = baby.getLocation();

        // Важно: greenhouse часто вложен в country/colony -> zoneAt() вернёт не greenhouse.
        if (!UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.GREENHOUSE)) return;

        // Страна-владелец берётся "глубоко" (COUNTRY сначала, потом COLONY), чтобы greenhouse мог быть внутри.
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        var cfg = C().country().livestock();
        if (!cfg.enabled()) return;

        int lvl = countryMaxLevel(country, cfg.permBase(), 2);
        if (lvl < 1) return;

        // Level 1+: ускоренный рост (уменьшаем отрицательный возраст -> быстрее к 0)
        if (cfg.speedPercent() > 0.0) {
            int age = baby.getAge();
            if (age < 0) {
                double k = Math.max(0.0, Math.min(1.0, cfg.speedPercent() / 100.0));
                int newAge = (int) Math.round(age * (1.0 - k));
                baby.setAge(newAge);

                if (C().core().debug()) {
                    plugin().getLogger().info("[Upgrades/Livestock] faster growth for " + baby.getType()
                            + " at " + loc + " country=" + country + " lvl=" + lvl
                            + " speed=" + cfg.speedPercent() + "% age " + age + " -> " + newAge);
                }
            }
        }

        // Level 2: шанс двойни
        if (lvl >= 2 && cfg.doubleChancePercent() > 0.0) {
            if (ThreadLocalRandom.current().nextDouble(100.0) < cfg.doubleChancePercent()) {
                World w = loc.getWorld();
                if (w != null) {
                    var spawned = w.spawnEntity(loc, baby.getType());
                    if (spawned instanceof Animals a2) a2.setBaby();

                    if (C().core().debug()) {
                        plugin().getLogger().info("[Upgrades/Livestock] twin spawned for " + baby.getType()
                                + " at " + loc + " country=" + country + " lvl=" + lvl);
                    }
                }
            }
        }
    }

}
