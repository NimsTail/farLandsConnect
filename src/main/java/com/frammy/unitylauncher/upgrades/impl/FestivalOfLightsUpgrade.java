package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * "Праздник Огней" (3_festivalOfLights, empty.festivalOfLights в старом каталоге).
 * Периодически осыпает искрами/поднимает настроение игрокам, стоящим в Зоне Церкви своей страны.
 */
public final class FestivalOfLightsUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("country.festival_of_lights");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().festivalOfLights().enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().festivalOfLights();
        long period = Math.max(20L, cfg.periodTicks());

        tasks.add(new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin(), period, period));

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/FestivalOfLights] task started period=" + period);
    }

    private void tick() {
        var cfg = C().country().festivalOfLights();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;

            Location loc = p.getLocation();
            if (!UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.CHURCH)) continue;

            String pc = UpgradeCondition.playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) continue;
            String owner = UpgradeCondition.locationCountryOwner(loc);
            if (owner == null || !pc.equalsIgnoreCase(owner)) continue;

            if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) continue;

            int dur = Math.max(20, cfg.buffDurationTicks());
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, dur, 0, true, true, true));

            var pos = loc.clone().add(0, 1.2, 0);
            p.getWorld().spawnParticle(Particle.FIREWORK, pos, 18, 0.6, 0.6, 0.6, 0.02);
            p.getWorld().playSound(pos, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.6f, 1.3f);

            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/FestivalOfLights] buff for " + p.getName() + " owner=" + owner);
            }
        }
    }
}
