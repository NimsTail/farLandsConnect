package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.auth.FarLandsApiClient;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * infra/military-diplomacy-design.md §16/§13 Фаза 5 "Линия фронта". Every
 * ~60s (must match backend's FRONTIER_REPORT_PERIOD_MS, lib/frontierPressure.ts),
 * batches every online player currently standing inside a COUNTRY zone that
 * is a party to an active war — either the zone's own citizens (defending)
 * or an enemy country's citizens (attacking) — and reports raw coordinates.
 * All the segment/triangle/progress math happens server-side; this class
 * only gathers "who is where" on the main thread (Location reads aren't
 * thread-safe) and hands off to FarLandsApiClient's fire-and-forget POST.
 */
public final class FrontierPresenceReporter {

    private final JavaPlugin plugin;
    private final ZoneManager zoneManager;
    private final FarLandsApiClient api;

    public FrontierPresenceReporter(JavaPlugin plugin, ZoneManager zoneManager, FarLandsApiClient api) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.api = api;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second — pass 1200 (~60s) to match the backend's report window. Main-thread (sync) — reads Player#getLocation(). */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, periodTicks, periodTicks);
    }

    private void tick() {
        var ul = UnityLauncher.getInstance();
        JsonArray presences = new JsonArray();

        for (Player p : Bukkit.getOnlinePlayers()) {
            String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (playerCountry == null || playerCountry.isBlank()) continue;

            Location loc = p.getLocation();
            // GH#33: getZoneAt() would return whichever overlapping zone has the
            // highest priority (PLOT/SHOP/MILITARY etc.), not the COUNTRY territory
            // itself — silently dropping presence for anyone standing on built land.
            ZoneInfo zone = zoneManager.getCountryZoneAt(loc);
            if (zone == null) continue;
            String zoneCountry = zone.getCountryName();
            if (zoneCountry == null || zoneCountry.isBlank() || zone.getMarkerID() == null) continue;

            boolean own = playerCountry.equalsIgnoreCase(zoneCountry);
            if (!own && !ul.warStatusCache.isAtWar(playerCountry, zoneCountry)) continue; // not relevant — neutral/ally guest

            JsonObject o = new JsonObject();
            o.addProperty("username", p.getName());
            o.addProperty("zoneMarkerId", zone.getMarkerID());
            o.addProperty("x", loc.getX());
            o.addProperty("z", loc.getZ());
            presences.add(o);
        }

        if (presences.size() > 0) api.reportFrontierPresence(presences);
    }
}
