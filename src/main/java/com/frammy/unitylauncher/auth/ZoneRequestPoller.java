package com.frammy.unitylauncher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import com.frammy.unitylauncher.zones.web.ZoneWebRequestHandler;
import com.frammy.unitylauncher.zones.web.ZoneWebRequestService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Executes zone-request "jobs" created by the site (see routes/zones.ts
 * in the farlandsconnect repo) — real zone/country state only exists in this
 * plugin's ZoneManager, so the site can't settle these on its own. Same shape
 * as MoneyRequestPoller: the queue lives on the backend (Postgres, polled
 * over HTTP via FarLandsApiClient) instead of the MySQL zone_requests table
 * ZoneWebRequestService already polls directly — this is the HTTP-queue
 * counterpart the comment in MoneyRequestPoller referred to. Both queues
 * feed the exact same ZoneManager.handle(...)/handleWebRequest(...), so no
 * new game logic was needed here, only transport.
 *
 * See infra/game-integration-architecture.md in the farlandsconnect repo.
 */
public class ZoneRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final ZoneManager zoneManager;
    private final Logger log;

    public ZoneRequestPoller(JavaPlugin plugin, FarLandsApiClient api, ZoneManager zoneManager, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.zoneManager = zoneManager;
        this.log = log;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second. No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, periodTicks, periodTicks);
    }

    private void pollOnce() {
        List<FarLandsApiClient.PendingZoneRequest> batch = api.fetchPendingZoneRequests();
        for (FarLandsApiClient.PendingZoneRequest req : batch) {
            // ZoneManager touches Bukkit/world state directly, so — same as the
            // MySQL-direct ZoneWebRequestService — every request runs on the main thread.
            Bukkit.getScheduler().runTask(plugin, () -> process(req));
        }
    }

    private void process(FarLandsApiClient.PendingZoneRequest req) {
        try {
            if ("zones_sync".equals(req.action())) {
                processZonesSync(req);
                return;
            }
            if ("country_activity_sync".equals(req.action())) {
                processCountryActivitySync(req);
                return;
            }
            if ("zone_quota_sync".equals(req.action())) {
                processZoneQuotaSync(req);
                return;
            }
            processMutation(req);
        } catch (Exception e) {
            log.warning("[ZoneRequestPoller] processing " + req.id() + " (" + req.action() + ") failed: " + e);
            api.reportZoneRequestResult(req.id(), false, "internal_error", null);
        }
    }

    /**
     * Read-only — reports the requesting player's OWN zones (ownership is
     * always by player, never by country — see ZoneInfo.owner) so the site
     * can catch drift. Matches old/zoneManager.html's myOwnedZones(): a
     * simple owner-name filter, not a country-membership one.
     */
    private void processZonesSync(FarLandsApiClient.PendingZoneRequest req) {
        String ownerUsername = (req.payload() != null && req.payload().has("ownerUsername"))
                ? req.payload().get("ownerUsername").getAsString()
                : null;
        if (ownerUsername == null) {
            api.reportZoneRequestResult(req.id(), false, "missing_owner_username", null);
            return;
        }

        JsonArray snapshot = new JsonArray();
        for (ZoneInfo z : zoneManager.getZones()) {
            if (z.getOwner() == null || !z.getOwner().equalsIgnoreCase(ownerUsername)) continue;
            snapshot.add(zoneToJson(z));
        }
        api.reportZoneRequestResult(req.id(), true, null, null, snapshot);
    }

    /**
     * Read-only — reports the accumulated chunk-activity tax due for a
     * country's current weekly billing period (CountryInfo.WeeklyTaxDue,
     * see ZoneActivityCalculations/WeeklyCountryInvoiceService). Old never
     * displayed this on the site; the site wants to show it as an info
     * panel now, so this is a new sync action (not one of the 10 mutation
     * actions ZoneManager.handle already supports).
     */
    private void processCountryActivitySync(FarLandsApiClient.PendingZoneRequest req) {
        String countryName = (req.payload() != null && req.payload().has("countryName"))
                ? req.payload().get("countryName").getAsString()
                : null;
        if (countryName == null) {
            api.reportZoneRequestResult(req.id(), false, "missing_country_name", null);
            return;
        }

        double weeklyTaxDue = zoneManager.ul.countryRegistryJdbc.getWeeklyTaxDue(countryName);

        JsonObject result = new JsonObject();
        result.addProperty("weeklyTaxDue", weeklyTaxDue);
        JsonArray wrapper = new JsonArray();
        wrapper.add(result);
        api.reportZoneRequestResult(req.id(), true, null, null, wrapper);
    }

    /**
     * Read-only — reports how many more zones of each upgradeable type
     * (see PLOT_UPGRADE_TARGETS in old/zoneManager.html) the country can
     * still create right now, per ZoneQuotaService's LuckPerms-permission
     * quota (unity.zone.<type>.<N> on the country's group). The site shows
     * this as "(N мест)" in the upgrade-type dropdown instead of letting the
     * player pick a type that's actually full and only finding out on submit.
     */
    private void processZoneQuotaSync(FarLandsApiClient.PendingZoneRequest req) {
        String countryName = (req.payload() != null && req.payload().has("countryName"))
                ? req.payload().get("countryName").getAsString()
                : null;
        if (countryName == null) {
            api.reportZoneRequestResult(req.id(), false, "missing_country_name", null);
            return;
        }

        var quotaService = zoneManager.getQuotaService();
        JsonObject result = new JsonObject();
        for (ZoneType t : new ZoneType[] {
                ZoneType.BANK, ZoneType.HOSPITAL, ZoneType.INDUSTRIAL, ZoneType.PARK,
                ZoneType.CHURCH, ZoneType.LIBRARY, ZoneType.GREENHOUSE, ZoneType.MILITARY,
        }) {
            result.addProperty(t.name(), quotaService.getAvailableCountryQuota(countryName, t));
        }
        JsonArray wrapper = new JsonArray();
        wrapper.add(result);
        api.reportZoneRequestResult(req.id(), true, null, null, wrapper);
    }

    private JsonObject zoneToJson(ZoneInfo z) {
        JsonObject o = new JsonObject();
        o.addProperty("markerId", z.getMarkerID());
        o.addProperty("name", z.getName());
        o.addProperty("world", z.getWorld() != null ? z.getWorld().getName() : null);
        o.addProperty("zoneType", z.getType().name());
        o.addProperty("color", z.getFillColor() != null ? String.format("#%06X", z.getFillColor().asRGB() & 0xFFFFFF) : null);
        o.addProperty("countryName", z.getCountryName());

        // Weekly upkeep billing state — old's zoneDetailHtml showed these as
        // "Активность (накоплено)" and "След. списание" on each zone card
        // (see old/zoneManager.html). Same accrual ZoneActivityCalculations
        // already tracks per-zone; nothing new to compute here.
        o.addProperty("dueCost", z.getDueSinceLastBill(LocalDate.now()));
        o.addProperty("nextBillingDate", z.getNextBillingDate().toString());

        // Remaining cooldown (ms, 0 = available now) on territory changes
        // (COUNTRY/COLONY only) and name/color changes (any zone) — lets the
        // site grey out those buttons up front instead of the player only
        // finding out from a rejected request. See ZoneManager.
        o.addProperty("cornersCooldownMs", zoneManager.cornersCooldownRemainingMs(z));
        o.addProperty("nameColorCooldownMs", zoneManager.nameColorCooldownRemainingMs(z));

        JsonArray points = new JsonArray();
        for (Location loc : z.getCorners()) {
            JsonObject p = new JsonObject();
            p.addProperty("x", loc.getX());
            p.addProperty("z", loc.getZ());
            points.add(p);
        }
        o.add("points", points);

        // getCorners() only ever returns shape 0 ("points" above) — a
        // multi-polygon zone (e.g. a country claim added via "Добавить
        // фигуру") has additional disjoint shapes that were previously never
        // exposed on this read path, so the site's Postgres mirror (and
        // everything drawn from it — Zone Manager, the heatmap zones
        // overlay) only ever showed the first shape even though BlueMap,
        // reading the plugin's own MySQL zones_view directly, showed all of
        // them correctly.
        JsonArray extraShapes = new JsonArray();
        List<List<Location>> shapes = z.getShapes();
        for (int i = 1; i < shapes.size(); i++) {
            JsonArray shapePoints = new JsonArray();
            for (Location loc : shapes.get(i)) {
                JsonObject p = new JsonObject();
                p.addProperty("x", loc.getX());
                p.addProperty("z", loc.getZ());
                shapePoints.add(p);
            }
            extraShapes.add(shapePoints);
        }
        o.add("extraShapes", extraShapes);

        JsonArray members = new JsonArray();
        for (String m : z.getMembers()) members.add(m);
        o.add("members", members);

        // military-diplomacy-design.md §4.1/§14.2, GH#24 п.2-3 — только
        // осмысленно у type == MILITARY, но поле шлём всегда (null у
        // остальных типов — как и с остальными military-only полями ниже).
        // militarySpecialization уже учитывает окно переключения — во время
        // него specService.current() отдаёт null, то есть сайт видит
        // "временно без специализации", ровно как это отражается в игре.
        var specService = com.frammy.unitylauncher.UnityLauncher.getInstance().militarySpecializationService;
        var currentSpec = specService.current(z);
        o.addProperty("militarySpecialization", currentSpec != null ? currentSpec.name() : null);
        o.addProperty("militarySpecializationSwitching", z.isSpecializationSwitching());
        o.addProperty("militaryAnchorPresent", z.getMilitaryAnchorLocation() != null);

        return o;
    }

    /** CREATE/UPDATE_CORNERS/UPDATE_NAME/UPDATE_COLOR/DELETE/UPGRADE_TYPE/ADD_SHAPE/ADD_MEMBER/REMOVE_MEMBER/TRANSFER_OWNERSHIP. */
    private void processMutation(FarLandsApiClient.PendingZoneRequest req) {
        ZoneWebRequestService.Action action;
        try {
            action = ZoneWebRequestService.Action.valueOf(req.action());
        } catch (IllegalArgumentException ex) {
            api.reportZoneRequestResult(req.id(), false, "unknown_action", null);
            return;
        }

        var player = Bukkit.getOfflinePlayer(req.username());
        if (player.getName() == null) {
            api.reportZoneRequestResult(req.id(), false, "user_not_found", null);
            return;
        }
        UUID playerUuid = player.getUniqueId();

        JsonObject payload = req.payload() != null ? req.payload() : new JsonObject();
        ZoneWebRequestService.ZoneWebRequest webRequest = new ZoneWebRequestService.ZoneWebRequest(
                0L, playerUuid, action, req.markerId(), req.zoneType(), req.zoneName(), req.worldName(), payload);

        ZoneWebRequestHandler.Result result;
        try {
            result = zoneManager.handle(webRequest);
        } catch (Throwable t) {
            result = ZoneWebRequestHandler.Result.error("internal_error: " + t.getMessage());
        }

        api.reportZoneRequestResult(req.id(), result.success(), result.message(), result.markerId());
    }
}
