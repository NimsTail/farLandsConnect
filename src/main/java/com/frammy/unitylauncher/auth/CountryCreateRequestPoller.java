package com.frammy.unitylauncher.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.logging.Logger;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * Executes country-create "jobs" from the site (see CountryCreateRequest/
 * routes/countries.ts in the farlandsconnect repo). A country created on the
 * new site only exists in its own Postgres — this plugin's ZoneManager/
 * UpgradeCondition/ZoneQuotaService all resolve a country by name through
 * CountryRegistryJdbc.getCountryId(), which only reads the plugin's OWN
 * MySQL `Countries` table. Without this bridge a site-created country is
 * invisible in-game: no COUNTRY zone, ADD_SHAPE, UPGRADE_TYPE, or upgrade
 * grant referencing it can ever resolve. Mirrors old/files/addCountry.php's
 * INSERT + LuckPerms group creation 1:1 (same JSON shapes), since that's the
 * only schema the plugin's read side (CountryRegistryJdbc) actually
 * understands — just via the LuckPerms Java API instead of raw SQL for the
 * group, and via this HTTP queue instead of a shared PHP/MySQL connection
 * for the trigger.
 *
 * See infra/game-integration-architecture.md in the farlandsconnect repo.
 */
public class CountryCreateRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;
    private static final Gson GSON = new Gson();

    public CountryCreateRequestPoller(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
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
        List<FarLandsApiClient.PendingCountryCreateRequest> batch = api.fetchPendingCountryCreateRequests();
        // JDBC on this same connection is already how CountryRegistryJdbc/ZoneWebRequestService
        // operate off the main thread, and we don't touch any Bukkit/world API here, so no
        // need to hop to the main thread (matches UpgradeGrantPoller's reasoning).
        for (FarLandsApiClient.PendingCountryCreateRequest req : batch) {
            process(req);
        }
    }

    private void process(FarLandsApiClient.PendingCountryCreateRequest req) {
        try {
            Integer countryId = insertCountryIfAbsent(req.countryName(), req.founderUsername());
            if (countryId == null) {
                api.reportCountryCreateRequestResult(req.id(), false, "db_error");
                return;
            }
            createLuckPermsGroup(countryId);
            api.reportCountryCreateRequestResult(req.id(), true, null);
        } catch (Exception e) {
            log.warning("[CountryCreateRequestPoller] processing " + req.id() + " failed: " + e);
            api.reportCountryCreateRequestResult(req.id(), false, "internal_error");
        }
    }

    /** Idempotent: if a country with this name already exists, just returns its Id (repair-mirror retries land here). */
    private Integer insertCountryIfAbsent(String countryName, String founderUsername) throws Exception {
        try (Connection con = DBConnect()) {
            if (con == null) return null;

            try (PreparedStatement check = con.prepareStatement("SELECT Id FROM Countries WHERE Name=? LIMIT 1")) {
                check.setString(1, countryName);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) return rs.getInt("Id");
                }
            }

            JsonObject countryInfo = new JsonObject();
            countryInfo.addProperty("Area", 0);
            countryInfo.addProperty("Name", countryName);
            countryInfo.addProperty("Money", 0);
            countryInfo.addProperty("MapUrl", "");
            countryInfo.addProperty("Capital", "");
            countryInfo.addProperty("BGPicture", "");
            countryInfo.addProperty("Description", "");
            countryInfo.addProperty("FlagPicture", "");
            countryInfo.addProperty("TransferFee", 1.25);
            countryInfo.addProperty("TimeMultiplier", 0.9);

            JsonObject players = new JsonObject();
            players.addProperty(founderUsername, "0");

            JsonArray permissions = new JsonArray();
            permissions.add(roleJson(0, "Leader", 1000, true, 100000));
            permissions.add(roleJson(1, "Citizen", 1, false, 0));

            // Upgrades is a plain "nodeId:level,nodeId2:level2" string (same format
            // old/upgrades.html's loadTree() parsed, see get_skill_tree.php) — NOT
            // signalable, just a NOT NULL column with no DB-level default. Empty for
            // a freshly founded country.
            try (PreparedStatement insert = con.prepareStatement(
                    "INSERT INTO Countries (`Name`,`CountryInfo`,`Players`,`Permissions`,`Upgrades`) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, countryName);
                insert.setString(2, GSON.toJson(countryInfo));
                insert.setString(3, GSON.toJson(players));
                insert.setString(4, GSON.toJson(permissions));
                insert.setString(5, "");
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            }
        }
        return null;
    }

    private JsonObject roleJson(int id, String name, int index, boolean allPerms, int zoneLimit) {
        JsonObject role = new JsonObject();
        role.addProperty("ID", id);
        role.addProperty("Name", name);
        role.addProperty("Index", index);
        role.addProperty("Prefix", "&f");
        JsonObject perms = new JsonObject();
        for (String key : new String[]{"invite", "players", "settings", "upgrades", "permissions", "buildZones"}) {
            perms.addProperty(key, allPerms);
        }
        role.add("Permissions", perms);
        role.addProperty("ZoneLimit", zoneLimit);
        return role;
    }

    /** Same shape as old's addCountry.php step 6.5 — group name is "country_<Countries.Id>", inherits "default". */
    private void createLuckPermsGroup(int countryId) {
        LuckPerms lp;
        try {
            lp = LuckPermsProvider.get();
        } catch (Throwable t) {
            log.warning("[CountryCreateRequestPoller] LuckPerms unavailable, skipping group creation for country_" + countryId);
            return;
        }

        String groupName = "country_" + countryId;
        Group group = lp.getGroupManager().getGroup(groupName);
        if (group == null) {
            group = lp.getGroupManager().createAndLoadGroup(groupName).join();
        }
        group.data().add(InheritanceNode.builder("default").build());
        lp.getGroupManager().saveGroup(group).join();
    }
}
