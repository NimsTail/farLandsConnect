package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * "Энергосбережение" (13_energySaving, empty.energy_saving в старом каталоге).
 * Пока игрок находится в Индустриальной зоне своей страны — потеря голода смягчается
 * (часть падения food level компенсируется на лету).
 */
public final class EnergySavingUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.energy_saving");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().country().energySaving();
        return cfg.enabled() && cfg.reductionPercent() > 0.0;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        int oldLevel = p.getFoodLevel();
        int newLevel = e.getFoodLevel();
        if (newLevel >= oldLevel) return; // голод не падает — смягчать нечего

        if (!UpgradeCondition.isInsideZoneTypeRaw(p.getLocation(), ZoneType.INDUSTRIAL)) return;

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return;
        String owner = UpgradeCondition.locationCountryOwner(p.getLocation());
        if (owner == null || !pc.equalsIgnoreCase(owner)) return;

        var cfg = C().country().energySaving();
        if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) return;

        double k = Math.max(0.0, Math.min(1.0, cfg.reductionPercent() / 100.0));
        int drop = oldLevel - newLevel;
        int softenedDrop = (int) Math.round(drop * (1.0 - k));
        int adjusted = Math.max(newLevel, oldLevel - softenedDrop);
        if (adjusted != newLevel) e.setFoodLevel(adjusted);

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/EnergySaving] softened hunger drop for " + p.getName()
                    + " " + oldLevel + "->" + newLevel + " adjusted=" + adjusted);
        }
    }
}
