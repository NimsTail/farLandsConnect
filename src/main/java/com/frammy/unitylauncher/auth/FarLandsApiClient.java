package com.frammy.unitylauncher.auth;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Fire-and-forget bridge to the new site's backend (`/plugin/*`, see
 * infra/auth-api-contract.md in the farlandsconnect repo). Never blocks the
 * caller and never throws — a backend outage must not affect login/register
 * on the game server, this is a best-effort mirror, not the source of truth
 * for in-game auth.
 */
public class FarLandsApiClient {

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final Logger log;
    private final String baseUrl; // e.g. https://farlands.frammy.lat, empty = disabled
    private final String token;

    public FarLandsApiClient(Logger log, String baseUrl, String token) {
        this.log = log;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.token = token == null ? "" : token.trim();
    }

    public boolean isEnabled() {
        return !baseUrl.isEmpty() && !token.isEmpty();
    }

    /** Call after a successful local registration, while the plaintext password is still in hand. */
    public void upsertUser(String username, String plaintextPassword) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", plaintextPassword);
        send("POST", "/plugin/users", body);
    }

    /** Call after a successful local password change. */
    public void changePassword(String username, String newPlaintextPassword) {
        JsonObject body = new JsonObject();
        body.addProperty("password", newPlaintextPassword);
        send("POST", "/plugin/users/" + encode(username) + "/password", body);
    }

    /** Call on player quit — powers the "last seen" line on the site profile. */
    public void lastSeen(String username) {
        send("POST", "/plugin/users/" + encode(username) + "/last-seen", new JsonObject());
    }

    /**
     * Call after any successful balance mutation (bank withdraw, shop sale, zone
     * billing, ATM fee, ...) — mirrors it into the site's /bank transaction history.
     * amount must be positive; direction says which way it moved.
     */
    public void transaction(String username, double amount, boolean isDeposit) {
        transaction(username, amount, isDeposit, null);
    }

    /**
     * Same as above, plus a human-readable reason — the site's endpoint
     * (routes/plugin.ts, /plugin/users/:username/transactions) already
     * accepted an optional `note` in the body long before this plugin ever
     * sent one; every in-game-originated balance change (salary, ATM ops,
     * transfers, shop sales, admin adjustments...) showed up on the site as
     * a bare "Пополнение (игра)"/"Списание (игра)" with nothing else to go
     * on. Callers should pass something short and specific — see
     * UnityCommands.applyMoneyDelta(String, double, String) for the usual
     * entry point.
     */
    public void transaction(String username, double amount, boolean isDeposit, String note) {
        if (!(amount > 0)) return;
        JsonObject body = new JsonObject();
        body.addProperty("amount", amount);
        body.addProperty("direction", isDeposit ? "deposit" : "withdrawal");
        if (note != null && !note.isBlank()) body.addProperty("note", note);
        send("POST", "/plugin/users/" + encode(username) + "/transactions", body);
    }

    /** Call after granting a profile-wall frame in-game (achievement completed, event won). */
    public void grantFrame(String username, int frameId) {
        send("POST", "/plugin/users/" + encode(username) + "/frames/" + frameId + "/grant", new JsonObject());
    }

    /** A pending money-request row from the site (transfer/invoice-pay/salary-claim/daydeal_confirm). */
    public record PendingMoneyRequest(
            String id, String kind, String fromUsername, String toUsername, double amount, Double debitAmount, String note) {}

    /**
     * Blocking GET — called from MoneyRequestPoller's own scheduler thread
     * (never the main server thread), so blocking here is fine and simpler
     * than threading a callback through per-request processing.
     */
    public List<PendingMoneyRequest> fetchPendingMoneyRequests() {
        List<PendingMoneyRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/money-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/money-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingMoneyRequest(
                        o.get("id").getAsString(),
                        o.get("kind").getAsString(),
                        nullableString(o, "fromUsername"),
                        nullableString(o, "toUsername"),
                        o.get("amount").getAsDouble(),
                        o.has("debitAmount") && !o.get("debitAmount").isJsonNull() ? o.get("debitAmount").getAsDouble() : null,
                        nullableString(o, "note")));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingMoneyRequests failed: " + e);
        }
        return out;
    }

    private static String nullableString(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    /** A pending zone-request row from the site (see ZoneRequestPoller, routes/countries.ts). */
    public record PendingZoneRequest(
            String id, String action, String username, String markerId,
            String zoneType, String zoneName, String worldName, JsonObject payload) {}

    /** Blocking GET — called from ZoneRequestPoller's own scheduler thread, mirrors fetchPendingMoneyRequests. */
    public List<PendingZoneRequest> fetchPendingZoneRequests() {
        List<PendingZoneRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/zone-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/zone-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingZoneRequest(
                        o.get("id").getAsString(),
                        o.get("action").getAsString(),
                        o.get("username").getAsString(),
                        nullableString(o, "markerId"),
                        nullableString(o, "zoneType"),
                        nullableString(o, "zoneName"),
                        nullableString(o, "worldName"),
                        o.has("payload") && !o.get("payload").isJsonNull() ? o.getAsJsonObject("payload") : null));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingZoneRequests failed: " + e);
        }
        return out;
    }

    /** Reports how a zone request was resolved — see ZoneRequestPoller. */
    public void reportZoneRequestResult(String requestId, boolean success, String message, String markerId) {
        reportZoneRequestResult(requestId, success, message, markerId, null);
    }

    /** Same as above, plus a zones_sync snapshot (JsonArray) — only meaningful for action "zones_sync". */
    public void reportZoneRequestResult(String requestId, boolean success, String message, String markerId, JsonArray zonesSnapshot) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        if (markerId != null) body.addProperty("markerId", markerId);
        if (zonesSnapshot != null) body.add("zonesSnapshot", zonesSnapshot);
        send("POST", "/plugin/zone-requests/" + encode(requestId) + "/result", body);
    }

    /** A pending password-change request from the site (see PasswordChangeRequestPoller). */
    public record PendingPasswordRequest(String id, String username, String newPassword) {}

    /** Blocking GET — called from PasswordChangeRequestPoller's own scheduler thread. */
    public List<PendingPasswordRequest> fetchPendingPasswordRequests() {
        List<PendingPasswordRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/password-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/password-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingPasswordRequest(o.get("id").getAsString(), o.get("username").getAsString(), nullableString(o, "newPassword")));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingPasswordRequests failed: " + e);
        }
        return out;
    }

    /** Reports how a password-change request was resolved — see PasswordChangeRequestPoller. */
    public void reportPasswordRequestResult(String requestId, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        send("POST", "/plugin/password-requests/" + encode(requestId) + "/result", body);
    }

    /** Reports how a money request was actually resolved in-game — see MoneyRequestPoller. */
    public void reportMoneyRequestResult(String requestId, boolean success, String message) {
        reportMoneyRequestResult(requestId, success, message, null);
    }

    /** Same as above, plus the real current balance — only meaningful for kind "balance_sync". */
    public void reportMoneyRequestResult(String requestId, boolean success, String message, Double balance) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        if (balance != null) body.addProperty("balance", balance);
        send("POST", "/plugin/money-requests/" + encode(requestId) + "/result", body);
    }

    /**
     * Reports played seconds since the last report — powers the site's
     * "salary" claim (PlaytimeAccount.unclaimedSeconds). secondsPlayed is a
     * delta, not a running total: a missed/duplicate report only shifts the
     * claimable amount, never desyncs it. See PlaytimeReporter.
     */
    /**
     * Reports chunk activity points for the site's /stats heatmap. points are
     * raw block coordinates (not chunk/cell coords) — the backend buckets them
     * into 16-block cells itself. Max 500 per call (backend-enforced); batch
     * larger sets yourself. See HeatmapReporter.
     */
    public void reportActivity(String world, java.util.List<int[]> points) {
        if (points.isEmpty()) return;
        JsonObject body = new JsonObject();
        body.addProperty("world", world);
        JsonArray arr = new JsonArray();
        for (int[] p : points) {
            JsonObject point = new JsonObject();
            point.addProperty("x", p[0]);
            point.addProperty("z", p[1]);
            arr.add(point);
        }
        body.add("points", arr);
        send("POST", "/plugin/stats/activity", body);
    }

    /** A pending upgrade-grant request from the site (see UpgradeGrantPoller, routes/upgrades.ts + routes/countries.ts). */
    public record PendingUpgradeGrantRequest(
            String id, String targetType, String countryName, String username,
            List<String> grantBases, List<String> revokeBases, int level, boolean enabled) {}

    /** Blocking GET — called from UpgradeGrantPoller's own scheduler thread, mirrors fetchPendingZoneRequests. */
    public List<PendingUpgradeGrantRequest> fetchPendingUpgradeGrants() {
        List<PendingUpgradeGrantRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/upgrade-grants/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/upgrade-grants/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingUpgradeGrantRequest(
                        o.get("id").getAsString(),
                        o.get("targetType").getAsString(),
                        nullableString(o, "countryName"),
                        nullableString(o, "username"),
                        toStringList(o.getAsJsonArray("grantBases")),
                        toStringList(o.getAsJsonArray("revokeBases")),
                        o.get("level").getAsInt(),
                        o.get("enabled").getAsBoolean()));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingUpgradeGrants failed: " + e);
        }
        return out;
    }

    private static List<String> toStringList(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) out.add(el.getAsString());
        return out;
    }

    /** Reports how an upgrade-grant request was resolved — see UpgradeGrantPoller. */
    public void reportUpgradeGrantResult(String requestId, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        send("POST", "/plugin/upgrade-grants/" + encode(requestId) + "/result", body);
    }

    /** A pending country-create request from the site (see CountryCreateRequestPoller, routes/countries.ts). */
    public record PendingCountryCreateRequest(String id, String countryName, String founderUsername) {}

    /** Blocking GET — called from CountryCreateRequestPoller's own scheduler thread. */
    public List<PendingCountryCreateRequest> fetchPendingCountryCreateRequests() {
        List<PendingCountryCreateRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/country-create-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/country-create-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingCountryCreateRequest(
                        o.get("id").getAsString(), o.get("countryName").getAsString(), o.get("founderUsername").getAsString()));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingCountryCreateRequests failed: " + e);
        }
        return out;
    }

    /** Reports how a country-create request was resolved — see CountryCreateRequestPoller. */
    public void reportCountryCreateRequestResult(String requestId, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        send("POST", "/plugin/country-create-requests/" + encode(requestId) + "/result", body);
    }

    /** Fire-and-forget batch sync — see PlanStatsReporter. body = {"players": [{username, playtimeSeconds, mobKills, deaths}, ...]}. */
    public void reportPlanStatsSync(JsonObject body) {
        send("POST", "/plugin/stats/plan-sync", body);
    }

    // ===== Country-delete / country-sync job-queues (GH #10 — see
    // CountryDeleteRequestPoller/CountrySyncRequestPoller). Same pattern as
    // country-create above, one pair of methods each.

    /** A pending country-delete request (see CountryDeleteRequestPoller). */
    public record PendingCountryDeleteRequest(String id, String countryName) {}

    public List<PendingCountryDeleteRequest> fetchPendingCountryDeleteRequests() {
        List<PendingCountryDeleteRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/country-delete-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/country-delete-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingCountryDeleteRequest(o.get("id").getAsString(), o.get("countryName").getAsString()));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingCountryDeleteRequests failed: " + e);
        }
        return out;
    }

    public void reportCountryDeleteRequestResult(String requestId, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        send("POST", "/plugin/country-delete-requests/" + encode(requestId) + "/result", body);
    }

    /** GH #10 round 3 — a pending country-member-leave request (see CountryMemberLeaveRequestPoller). */
    public record PendingCountryMemberLeaveRequest(String id, String username) {}

    public List<PendingCountryMemberLeaveRequest> fetchPendingCountryMemberLeaveRequests() {
        List<PendingCountryMemberLeaveRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/country-member-leave-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/country-member-leave-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingCountryMemberLeaveRequest(o.get("id").getAsString(), o.get("username").getAsString()));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingCountryMemberLeaveRequests failed: " + e);
        }
        return out;
    }

    public void reportCountryMemberLeaveRequestResult(String requestId, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        send("POST", "/plugin/country-member-leave-requests/" + encode(requestId) + "/result", body);
    }

    /** A pending country-sync request — groups is the raw JSON array from CountrySyncRequest.groupsJson (see schema.prisma). */
    public record PendingCountrySyncRequest(String id, String countryName, JsonArray groups) {}

    public List<PendingCountrySyncRequest> fetchPendingCountrySyncRequests() {
        List<PendingCountrySyncRequest> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/country-sync-requests/pending"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/country-sync-requests/pending -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray requests = body.getAsJsonArray("requests");
            for (JsonElement el : requests) {
                JsonObject o = el.getAsJsonObject();
                out.add(new PendingCountrySyncRequest(
                        o.get("id").getAsString(), o.get("countryName").getAsString(), o.getAsJsonArray("groups")));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchPendingCountrySyncRequests failed: " + e);
        }
        return out;
    }

    public void reportCountrySyncRequestResult(String requestId, boolean success, String message) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        if (message != null) body.addProperty("message", message);
        send("POST", "/plugin/country-sync-requests/" + encode(requestId) + "/result", body);
    }

    // ===== military-diplomacy-design.md §2.2.1/§14/§13 Фаза 4 (see
    // routes/plugin.ts in the farlandsconnect repo, "/plugin/military/*") =====

    /** kind: "pvp_kill" | "pvp_damage" — fire-and-forget, silently no-ops server-side if either player has no country. */
    public void reportMilitaryIncident(String kind, String attackerUsername, String victimUsername) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", kind);
        body.addProperty("attackerUsername", attackerUsername);
        body.addProperty("victimUsername", victimUsername);
        send("POST", "/plugin/military/incident", body);
    }

    /** targetCountryName = the COUNTRY zone's owning country (whose territory was trespassed). */
    public void reportBorderViolation(String intruderUsername, String targetCountryName) {
        JsonObject body = new JsonObject();
        body.addProperty("intruderUsername", intruderUsername);
        body.addProperty("targetCountryName", targetCountryName);
        send("POST", "/plugin/military/border-violation", body);
    }

    /** zoneMarkerId = the MILITARY zone's ZoneInfo.getMarkerID(); attackerCountryName = whoever gets War Score credit. */
    public void reportMilitaryNeutralize(String zoneMarkerId, String attackerCountryName) {
        JsonObject body = new JsonObject();
        body.addProperty("zoneMarkerId", zoneMarkerId);
        body.addProperty("attackerCountryName", attackerCountryName);
        send("POST", "/plugin/military/neutralize", body);
    }

    /** military-diplomacy-design.md §4.1 п.3, GH#24 п.1/вопрос №15 — see MilitaryReconMinigame. qualityBonusPercent is 0-5. */
    public void reportReconQuality(String sessionId, double qualityBonusPercent) {
        JsonObject body = new JsonObject();
        body.addProperty("sessionId", sessionId);
        body.addProperty("qualityBonusPercent", qualityBonusPercent);
        send("POST", "/plugin/military/recon-quality", body);
    }

    /** infra/military-diplomacy-design.md §16/§13 Фаза 5 — body = {"presences": [{username, zoneMarkerId, x, z}, ...]}, see FrontierPresenceReporter. */
    public void reportFrontierPresence(JsonArray presences) {
        JsonObject body = new JsonObject();
        body.add("presences", presences);
        send("POST", "/plugin/military/frontier-presence", body);
    }

    public record WarPair(String countryA, String countryB) {}

    /** Blocking GET — called from WarStatusCache's own scheduler thread (mirrors fetchPendingCountryCreateRequests). Country NAMES, not site cuids — see routes/plugin.ts. */
    public List<WarPair> fetchActiveWars() {
        List<WarPair> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/military/active-wars"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/military/active-wars -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray wars = body.getAsJsonArray("wars");
            for (JsonElement el : wars) {
                JsonObject o = el.getAsJsonObject();
                out.add(new WarPair(o.get("countryA").getAsString(), o.get("countryB").getAsString()));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchActiveWars failed: " + e);
        }
        return out;
    }

    public record ZoneEffectiveness(String markerId, int percent) {}

    /**
     * GH#32 (раунд 10, фидбек 2026-08-18) — "снижается эффективность сразу и
     * в игру". Blocking GET — called from MilitaryEffectivenessCache's own
     * scheduler thread, mirrors fetchActiveWars exactly. percent is 0-100
     * (100 = no captured frontier sector overlaps this zone right now); see
     * farlandsconnect's lib/militaryEffectiveness.ts for how it's computed.
     */
    public List<ZoneEffectiveness> fetchMilitaryEffectiveness() {
        List<ZoneEffectiveness> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/military/effectiveness"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/military/effectiveness -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray entries = body.getAsJsonArray("effectiveness");
            for (JsonElement el : entries) {
                JsonObject o = el.getAsJsonObject();
                out.add(new ZoneEffectiveness(o.get("markerId").getAsString(), o.get("percent").getAsInt()));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchMilitaryEffectiveness failed: " + e);
        }
        return out;
    }

    // GH #27 "Географические объекты" — infra/geographic-landmarks-design.md
    // §8 п.4. Website-authoritative, this is a pure read poll (see
    // LandmarkSyncService), same shape as fetchActiveWars above.
    public record LandmarkDto(
            String id, String world, String category, String markerKind,
            Double px, Double py, Double pz,
            List<double[]> points, List<List<double[]>> extraShapes,
            String officialName
    ) {}

    public List<LandmarkDto> fetchLandmarks() {
        List<LandmarkDto> out = new ArrayList<>();
        if (!isEnabled()) return out;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/plugin/landmarks"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.warning("[FarLandsApi] GET /plugin/landmarks -> HTTP " + response.statusCode());
                return out;
            }

            JsonObject body = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray arr = body.getAsJsonArray("landmarks");
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString();
                String world = o.get("world").getAsString();
                String category = o.get("category").getAsString();
                String markerKind = o.get("markerKind").getAsString();

                Double px = null, py = null, pz = null;
                if (o.has("point") && !o.get("point").isJsonNull()) {
                    JsonObject p = o.getAsJsonObject("point");
                    px = p.get("x").getAsDouble();
                    py = p.get("y").getAsDouble();
                    pz = p.get("z").getAsDouble();
                }

                List<double[]> points = new ArrayList<>();
                if (o.has("points") && !o.get("points").isJsonNull()) {
                    for (JsonElement pe : o.getAsJsonArray("points")) {
                        JsonObject pp = pe.getAsJsonObject();
                        points.add(new double[]{pp.get("x").getAsDouble(), pp.get("z").getAsDouble()});
                    }
                }

                List<List<double[]>> extraShapes = new ArrayList<>();
                if (o.has("extraShapes") && !o.get("extraShapes").isJsonNull()) {
                    for (JsonElement shapeEl : o.getAsJsonArray("extraShapes")) {
                        List<double[]> shape = new ArrayList<>();
                        for (JsonElement pe : shapeEl.getAsJsonArray()) {
                            JsonObject pp = pe.getAsJsonObject();
                            shape.add(new double[]{pp.get("x").getAsDouble(), pp.get("z").getAsDouble()});
                        }
                        extraShapes.add(shape);
                    }
                }

                String officialName = (o.has("officialName") && !o.get("officialName").isJsonNull())
                        ? o.get("officialName").getAsString() : null;

                out.add(new LandmarkDto(id, world, category, markerKind, px, py, pz, points, extraShapes, officialName));
            }
        } catch (Exception e) {
            log.warning("[FarLandsApi] fetchLandmarks failed: " + e);
        }
        return out;
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void send(String method, String path, JsonObject body) {
        if (!isEnabled()) return;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        // Logging only in the callback — no Bukkit API touched, so this can safely
        // run on the HTTP client's own thread instead of needing the main thread.
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        log.warning("[FarLandsApi] " + method + " " + path + " failed: " + error);
                        return;
                    }
                    if (response.statusCode() >= 300) {
                        log.warning("[FarLandsApi] " + method + " " + path + " -> HTTP " + response.statusCode()
                                + " " + response.body());
                    }
                });
    }
}
