package com.frammy.unitylauncher.auth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * farlandsconnect GH#32 (раунд 10, фидбек 2026-08-18) — "если захваченный
 * сектор линии фронта занимает часть военной зоны, снижается эффективность
 * зоны на этот %, сразу и в игру, не только на сайт". In-memory mirror of
 * each MILITARY zone's current combat-effectiveness percent (source of
 * truth is Postgres — see farlandsconnect's lib/militaryEffectiveness.ts),
 * same shape as WarStatusCache's fetchActiveWars poll.
 *
 * Keyed by markerId — the same ZoneInfo.getMarkerID() every
 * MilitaryDefenseSubtype*Upgrade class already uses to find "its" zone.
 * Missing entry (zone never reported, or the site bridge is disabled) reads
 * as 100 — full effectiveness, never a surprise silent nerf if this cache
 * hasn't warmed up yet or the site is unreachable.
 */
public final class MilitaryEffectivenessCache {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;

    private final Map<String, Integer> percentByMarker = new ConcurrentHashMap<>();

    public MilitaryEffectivenessCache(JavaPlugin plugin, FarLandsApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** 0-100. Defaults to 100 (full effectiveness) for any zone not yet seen — see class javadoc. */
    public int effectivenessOf(String markerId) {
        if (markerId == null) return 100;
        Integer v = percentByMarker.get(markerId);
        return v != null ? v : 100;
    }

    /**
     * Convenience for tick loops that want a uniform "does this trigger fire
     * this tick" throttle instead of scaling individual numeric knobs per
     * mechanic (see LiveDefensePostUpgrade/AuraUpgrade/ScorchUpgrade/
     * CrossbowUpgrade — each just gates its tick body on this instead of
     * reworking cooldowns/chances/radii bespoke per class).
     */
    public boolean rollActive(String markerId) {
        int pct = effectivenessOf(markerId);
        if (pct >= 100) return true;
        if (pct <= 0) return false;
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < pct;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second. No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshOnce, 0L, periodTicks);
    }

    private void refreshOnce() {
        var entries = api.fetchMilitaryEffectiveness();
        Map<String, Integer> fresh = new ConcurrentHashMap<>();
        for (var e : entries) fresh.put(e.markerId(), e.percent());

        percentByMarker.keySet().retainAll(fresh.keySet());
        percentByMarker.putAll(fresh);
    }
}
