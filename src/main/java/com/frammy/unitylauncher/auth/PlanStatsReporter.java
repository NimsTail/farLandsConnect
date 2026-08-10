package com.frammy.unitylauncher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.logging.Logger;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * Periodically mirrors the Plan plugin's per-player playtime/mob-kills/
 * deaths into the site's Postgres (PlanStats — see
 * routes/stats.ts + routes/plugin.ts in the farlandsconnect repo), powering
 * the "Мой перформанс"/"Игроки" stats tabs. Same aggregate query
 * old/files/getPlayers.php used against plan_users/plan_sessions.
 *
 * Plan isn't installed on the server yet — plan_users/plan_sessions won't
 * exist until it is. This reporter no-ops quietly on any SQL error (missing
 * tables included) rather than logging every ~10 minutes; once Plan is
 * installed, the very next tick starts reporting real data with no further
 * change needed on either side.
 */
public class PlanStatsReporter {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;

    private static final String SQL = """
        SELECT
          pu.name AS plan_name,
          SUM((ps.session_end - ps.session_start - IFNULL(ps.afk_time,0)) / 1000) AS playtime_seconds,
          SUM(IFNULL(ps.mob_kills,0)) AS mob_kills,
          SUM(IFNULL(ps.deaths,0)) AS deaths
        FROM plan_users pu
        JOIN plan_sessions ps ON ps.user_id = pu.id
        GROUP BY pu.name
        """;

    public PlanStatsReporter(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.log = log;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second — pass a long period (this is a full-table scan). No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::syncOnce, periodTicks, periodTicks);
    }

    private void syncOnce() {
        JsonArray players = new JsonArray();
        try (Connection con = DBConnect()) {
            if (con == null) return;
            try (PreparedStatement ps = con.prepareStatement(SQL); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("username", rs.getString("plan_name"));
                    o.addProperty("playtimeSeconds", rs.getLong("playtime_seconds"));
                    o.addProperty("mobKills", rs.getLong("mob_kills"));
                    o.addProperty("deaths", rs.getLong("deaths"));
                    players.add(o);
                }
            }
        } catch (Exception e) {
            // Plan not installed yet (plan_users/plan_sessions missing) or a transient DB
            // error — best-effort reporter, skip this tick silently.
            return;
        }
        if (players.size() == 0) return;

        JsonObject body = new JsonObject();
        body.add("players", players);
        api.reportPlanStatsSync(body);
    }
}
