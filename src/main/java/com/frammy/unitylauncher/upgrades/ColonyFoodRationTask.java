package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Objects;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;
import static com.frammy.unitylauncher.upgrades.UpgradeCondition.playerCountryCanonical;

public final class ColonyFoodRationTask extends BukkitRunnable {

    private final Plugin plugin;
    private final UpgradesConfig C;
    private final ZoneManager zoneManager;

    public ColonyFoodRationTask(UnityLauncher plugin, UpgradesConfig config) {
        this.plugin = plugin;
        this.C = config;
        this.zoneManager = plugin.getZoneManager();
    }

    public void start() {
        long period = Math.max(20L, C.foodRationPeriodTicks);
        this.runTaskTimer(plugin, 100L, period);
        plugin.getLogger().info("[FoodRation] Task started (period " + period + " ticks)");
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.isOnline() || p.isDead()) continue;

            // 1) Какая у игрока страна (каноническое имя, как в остальных апгрейдах)
            String playerCountryCanon = playerCountryCanonical(p.getName());
            if (playerCountryCanon == null || playerCountryCanon.isBlank()) continue;

            // 2) В какой зоне он сейчас стоит
            ZoneInfo z = zoneManager.getZoneAt(p.getLocation());
            if (z == null || z.getType() != ZoneType.COLONY) continue;

            // 3) Колония должна принадлежать той же стране
            String zoneCountryCanon = zoneManager.getCountryCanonicalOfZone(z);
            if (zoneCountryCanon == null || !Objects.equals(zoneCountryCanon, playerCountryCanon)) continue;

            // 4) У этой страны должен быть апгрейд Продовольственный пай
            int lvl = countryMaxLevel(playerCountryCanon, C.foodRationPerm, 1);
            if (lvl <= 0) continue;

            // 5) Даём Saturation
            int duration  = C.foodRationDurationTicks;
            int amplifier = C.foodRationAmplifier;
            if (duration <= 0) continue;

            PotionEffectType type = PotionEffectType.SATURATION;
            if (type == null) continue;

            p.addPotionEffect(new PotionEffect(
                    type,
                    duration,
                    amplifier,
                    true,   // ambient
                    false,  // particles
                    true    // icon
            ));
        }
    }
}
