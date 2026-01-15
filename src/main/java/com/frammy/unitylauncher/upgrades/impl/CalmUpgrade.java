package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.LibraryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class CalmUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("library.calm");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private final Map<UUID, Long> lastCheck = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        LibraryCfg.CalmCfg cfg = ctx.config().library().calm();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // только снижение
        if (e.getFoodLevel() >= p.getFoodLevel()) return;

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null || z.getType() != ZoneType.LIBRARY) return;

        String country = UpgradeCondition.zoneCountryCanonical(z);
        if (country == null || country.isBlank()) return;

        var cfg = C().library().calm();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        long antispam = Math.max(0L, cfg.antispamMs());
        Long last = lastCheck.get(uuid);
        if (last != null && (now - last) < antispam) return;

        double chance = cfg.chanceToCancelHunger();
        if (chance <= 0.0) return;

        if (chance >= 1.0 || Math.random() < chance) {
            e.setCancelled(true);
            lastCheck.put(uuid, now);

            if (C().core().debug()) {
                plugin().getLogger().info("[Library/Calm] hunger loss prevented for " + p.getName() + " country=" + country);
            }
        }
    }
}
