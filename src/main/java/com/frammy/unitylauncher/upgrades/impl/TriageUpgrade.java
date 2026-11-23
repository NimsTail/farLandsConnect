package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.HospitalCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class TriageUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.triage");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.TriageCfg cfg = ctx.config().hospital().triage();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPotion(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        // КРИТИЧНО: не обрабатываем эффекты, которые поставил наш же плагин,
        // иначе будет бесконечная рекурсия и взрыв очереди scheduler.
        if (e.getCause() == EntityPotionEffectEvent.Cause.PLUGIN) return;

        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED
                && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) {
            return;
        }

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return;

        var cfg = C().hospital().triage();
        if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) return;

        PotionEffect ne = e.getNewEffect();
        if (ne == null) return;

        PotionEffectType t = ne.getType();
        if (t != PotionEffectType.POISON
                && t != PotionEffectType.WITHER
                && t != PotionEffectType.NAUSEA) {
            return;
        }

        int original = ne.getDuration();
        if (original <= 1) return;

        double reduction = Math.min(100.0, Math.max(0.0, cfg.reducePercent())) / 100.0;
        int reduced = (int) Math.floor(original * (1.0 - reduction));

        if (reduced >= original) return;

        e.setCancelled(true);

        // На следующий тик, чтобы не драться с внутренней логикой применения эффектов.
        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            if (!p.isOnline()) return;

            UpgradeCondition.applyPotionSmart(
                    p,
                    t,
                    Math.max(1, reduced),
                    ne.getAmplifier(),
                    ne.isAmbient(),
                    ne.hasParticles(),
                    ne.hasIcon()
            );

            if (C().core().debug()) {
                plugin().getLogger().info("[Hospital/Triage] " + p.getName()
                        + " " + t.getName()
                        + " dur " + original + " -> " + reduced
                        + " cause=" + e.getCause());
            }
        }, 1L);
    }
}
