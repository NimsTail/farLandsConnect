package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityLauncher;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * Executes country-settings-sync "jobs" from the site (GH #10 — see
 * CountrySyncRequest/routes/countries.ts's PUT /countries/:id/groups
 * handler, farlandsconnect repo). Editing a group's name/prefix/permissions
 * on the site only ever touched Postgres — the plugin's own MySQL
 * Countries.Permissions JSON (which CountryRegistryJdbc.getPlayerRolePrefix
 * and every zone/upgrade permission check actually reads) never changed, so
 * a prefix edit on the site silently never showed up in-game.
 *
 * Declarative: each job carries the country's FULL current group list, and
 * this just rebuilds the Permissions column from scratch rather than
 * patching it — same reasoning as UpgradeGrantPoller (a missed/duplicate
 * poll can't leave a partial merge behind).
 */
public class CountrySyncRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;

    public CountrySyncRequestPoller(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.log = log;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second. No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, periodTicks, periodTicks);
    }

    private void pollOnce() {
        List<FarLandsApiClient.PendingCountrySyncRequest> batch = api.fetchPendingCountrySyncRequests();
        for (FarLandsApiClient.PendingCountrySyncRequest req : batch) {
            process(req);
        }
    }

    private void process(FarLandsApiClient.PendingCountrySyncRequest req) {
        try {
            JsonArray permissions = buildPermissionsJson(req.groups());

            try (Connection con = UnityLauncher.DBConnect()) {
                if (con == null) {
                    api.reportCountrySyncRequestResult(req.id(), false, "db_error");
                    return;
                }

                try (PreparedStatement ps = con.prepareStatement("UPDATE Countries SET Permissions=? WHERE Name=?")) {
                    ps.setString(1, permissions.toString());
                    ps.setString(2, req.countryName());
                    int updated = ps.executeUpdate();
                    if (updated == 0) {
                        // Country doesn't exist yet on the plugin side (e.g. a
                        // group edit that raced a still-pending create) — not
                        // an error, the next country-create pass (or a manual
                        // repair-mirror) will bring it in with fresh data anyway.
                        api.reportCountrySyncRequestResult(req.id(), true, "country_not_found_skipped");
                        return;
                    }
                }
            }

            // 5s TTL means this would self-heal shortly anyway, but forcing it
            // makes a site-side prefix/name edit visible in-game right away
            // instead of leaving the "did it actually work?" question hanging.
            UnityLauncher.getInstance().countryRegistryJdbc.forceRefreshBlocking();

            api.reportCountrySyncRequestResult(req.id(), true, null);
        } catch (Exception e) {
            log.warning("[CountrySyncRequestPoller] processing " + req.id() + " failed: " + e);
            api.reportCountrySyncRequestResult(req.id(), false, "internal_error");
        }
    }

    /** Same shape as CountryCreateRequestPoller.roleJson, just filled from the site's current group snapshot instead of two hardcoded defaults. */
    private JsonArray buildPermissionsJson(JsonArray groups) {
        JsonArray out = new JsonArray();
        for (JsonElement el : groups) {
            JsonObject g = el.getAsJsonObject();
            JsonObject role = new JsonObject();
            role.addProperty("ID", g.get("localId").getAsInt());
            role.addProperty("Name", g.get("name").getAsString());
            role.addProperty("Index", g.get("index").getAsInt());
            role.addProperty("Prefix", g.has("prefix") && !g.get("prefix").isJsonNull() ? g.get("prefix").getAsString() : "");

            JsonObject perms = new JsonObject();
            perms.addProperty("invite", g.get("canInvite").getAsBoolean());
            perms.addProperty("players", g.get("canManageMembers").getAsBoolean());
            perms.addProperty("settings", g.get("canManageSettings").getAsBoolean());
            perms.addProperty("upgrades", g.get("canManageUpgrades").getAsBoolean());
            perms.addProperty("permissions", g.get("canManagePermissions").getAsBoolean());
            perms.addProperty("buildZones", g.get("canBuildZones").getAsBoolean());
            // military-diplomacy-design.md §3.2/§13 Фаза 2. canManageDiplomacy
            // deliberately NOT included — it's website-only, no in-game effect.
            perms.addProperty("viewMilitary", g.has("viewMilitary") && g.get("viewMilitary").getAsBoolean());
            role.add("Permissions", perms);

            // null on the site means "∞" (see GH #4/CountryDetailPage's zoneLimit
            // input) — ZoneValidationService's check is a plain `owned >= limit`
            // with no unlimited sentinel, so -1 would deny everyone instantly
            // instead of allowing everyone. Same large-number convention
            // CountryCreateRequestPoller's Leader role already uses (100000).
            role.addProperty("ZoneLimit", g.has("zoneLimit") && !g.get("zoneLimit").isJsonNull() ? g.get("zoneLimit").getAsInt() : 100000);
            out.add(role);
        }
        return out;
    }
}
