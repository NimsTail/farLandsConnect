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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class PsychSupportUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.psych_support");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    // uuid -> durationTicks (сколько дать на респавне)
    private final Map<UUID, Integer> pendingLuck = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.PsychSupportCfg cfg = ctx.config().hospital().psychSupport();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onDisable() {
        pendingLuck.clear();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return;

        var cfg = C().hospital().psychSupport();
        if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) return;

        int dur = Math.max(20, cfg.luckDurationTicks());
        pendingLuck.put(p.getUniqueId(), dur);

        if (C().core().debug()) {
            plugin().getLogger().info("[Hospital/PsychSupport] queued Luck for " + p.getName() + " (dur=" + dur + ")");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();

        Integer dur = pendingLuck.remove(p.getUniqueId());
        if (dur == null) return;

        // Дадим через пару тиков после respawn — это самый стабильный момент
        Bukkit.getScheduler().runTaskLater(plugin(), () -> {
            if (!p.isOnline()) return;

            UpgradeCondition.applyPotionSmart(
                    p,
                    PotionEffectType.LUCK,
                    dur,
                    0,
                    true,
                    false,
                    true
            );

            if (C().core().debug()) {
                plugin().getLogger().info("[Hospital/PsychSupport] applied Luck to " + p.getName() + " after respawn (dur=" + dur + ")");
            }
        }, 3L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        pendingLuck.remove(e.getPlayer().getUniqueId());
    }
}
