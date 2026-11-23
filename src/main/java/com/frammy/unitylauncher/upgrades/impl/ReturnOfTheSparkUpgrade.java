package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * "Возвращение искры" (4_returnOfTheSpark, empty.returnOfTheSpark в старом каталоге).
 * Игрок, возрождающийся на территории своей страны, получает короткий заряд бодрости
 * (скорость + регенерация) — "искра жизни" возвращается быстрее.
 */
public final class ReturnOfTheSparkUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.return_of_the_spark");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().returnOfTheSpark().enabled();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        var loc = e.getRespawnLocation();

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return;
        String owner = UpgradeCondition.locationCountryOwner(loc);
        if (owner == null || !pc.equalsIgnoreCase(owner)) return;

        var cfg = C().country().returnOfTheSpark();
        if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) return;

        int dur = Math.max(20, cfg.buffDurationTicks());
        // применяем на следующий тик — на момент RESPAWN эффекты иногда сбрасываются сервером
        org.bukkit.Bukkit.getScheduler().runTask(plugin(), () -> {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, dur, 0, true, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, dur, 0, true, true, true));
        });

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/ReturnOfTheSpark] respawn buff for " + p.getName() + " owner=" + owner);
        }
    }
}
