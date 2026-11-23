package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * "Святая Аура" (6_holyAura, empty.holyAura в старом каталоге).
 * Периодически снимает негативные эффекты с граждан страны, где бы они ни находились
 * на её территории (scope=country) — благословение распространяется на всю страну.
 */
public final class HolyAuraUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("country.holy_aura");

    private static final Set<PotionEffectType> NEGATIVE_EFFECTS = Set.of(
            PotionEffectType.BAD_OMEN,
            PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.NAUSEA,
            PotionEffectType.POISON,
            PotionEffectType.SLOWNESS,
            PotionEffectType.UNLUCK,
            PotionEffectType.WEAKNESS,
            PotionEffectType.WITHER
    );

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().holyAura().enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().holyAura();
        long period = Math.max(20L, cfg.periodTicks());

        tasks.add(new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin(), period, period));

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/HolyAura] task started period=" + period);
    }

    private void tick() {
        var cfg = C().country().holyAura();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;

            String pc = UpgradeCondition.playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) continue;
            String owner = UpgradeCondition.locationCountryOwner(p.getLocation());
            if (owner == null || !pc.equalsIgnoreCase(owner)) continue;

            if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) continue;

            boolean curedAny = false;
            for (PotionEffectType t : NEGATIVE_EFFECTS) {
                if (p.hasPotionEffect(t)) {
                    p.removePotionEffect(t);
                    curedAny = true;
                }
            }

            if (curedAny) {
                p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().clone().add(0, 1.0, 0), 8, 0.4, 0.6, 0.4, 0.01);
                if (C().core().debug()) {
                    plugin().getLogger().info("[Upgrades/HolyAura] cured negatives for " + p.getName() + " owner=" + owner);
                }
            }
        }
    }
}
