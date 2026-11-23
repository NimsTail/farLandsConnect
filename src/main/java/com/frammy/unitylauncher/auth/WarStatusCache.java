package com.frammy.unitylauncher.auth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * infra/military-diplomacy-design.md §13 Фаза 4 — in-memory mirror of which
 * country pairs are currently at WAR, polled from the site (source of
 * truth is Postgres CountryRelation, not this cache). Consumed by
 * upgrades that need to know "is my country at war with THAT one specifically"
 * (Поддержка атаки) rather than just "is my country at war with anyone".
 *
 * Keyed by country NAME (lowercased) — the plugin's own canonical country
 * identifier, same as everywhere else in this codebase (LuckPerms groups
 * excepted, which use the numeric MySQL id instead).
 */
public final class WarStatusCache {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;

    // Each entry is "lower(a)|lower(b)" with a < b lexicographically —
    // normalized once on write so isAtWar doesn't care about argument order.
    private final Set<String> activePairs = ConcurrentHashMap.newKeySet();

    public WarStatusCache(JavaPlugin plugin, FarLandsApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    private static String key(String a, String b) {
        String la = a.toLowerCase(Locale.ROOT);
        String lb = b.toLowerCase(Locale.ROOT);
        return la.compareTo(lb) <= 0 ? la + "|" + lb : lb + "|" + la;
    }

    public boolean isAtWar(String countryA, String countryB) {
        if (countryA == null || countryB == null || countryA.equalsIgnoreCase(countryB)) return false;
        return activePairs.contains(key(countryA, countryB));
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second. No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshOnce, 0L, periodTicks);
    }

    private void refreshOnce() {
        var wars = api.fetchActiveWars();
        Set<String> fresh = ConcurrentHashMap.newKeySet();
        for (var w : wars) fresh.add(key(w.countryA(), w.countryB()));

        activePairs.retainAll(fresh);
        activePairs.addAll(fresh);
    }
}
