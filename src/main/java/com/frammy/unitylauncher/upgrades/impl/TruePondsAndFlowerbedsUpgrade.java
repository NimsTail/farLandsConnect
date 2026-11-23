package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * "Пруды и Клумбы" (3_pondsAndFlowerbeds, empty.truePondsAndFlowerbeds в старом каталоге).
 * Игроки, отдыхающие у воды в Зоне Парка своей страны, периодически получают лёгкую регенерацию.
 */
public final class TruePondsAndFlowerbedsUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("country.true_ponds_and_flowerbeds");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().truePondsAndFlowerbeds().enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().truePondsAndFlowerbeds();
        long period = Math.max(20L, cfg.periodTicks());

        tasks.add(new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin(), period, period));

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/TruePondsAndFlowerbeds] task started period=" + period);
    }

    private void tick() {
        var cfg = C().country().truePondsAndFlowerbeds();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;

            Location loc = p.getLocation();
            if (!UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.PARK)) continue;
            if (!nearWater(loc, Math.max(1, cfg.waterRadius()))) continue;

            String pc = UpgradeCondition.playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) continue;
            String owner = UpgradeCondition.locationCountryOwner(loc);
            if (owner == null || !pc.equalsIgnoreCase(owner)) continue;

            if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) continue;

            int dur = Math.max(20, cfg.buffDurationTicks());
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, dur, 0, true, true, true));
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 1.0, 0), 6, 0.5, 0.4, 0.5, 0.0);

            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/TruePondsAndFlowerbeds] regen for " + p.getName() + " owner=" + owner);
            }
        }
    }

    private boolean nearWater(Location center, int radius) {
        var world = center.getWorld();
        if (world == null) return false;

        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (b.getType() == Material.WATER) return true;
                }
            }
        }
        return false;
    }
}
