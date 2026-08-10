package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Executes upgrade-grant "jobs" created by the site (see
 * UpgradeGrantRequest/routes/upgrades.ts + routes/countries.ts in the
 * farlandsconnect repo) — a purchase/toggle/choice write to
 * PlayerUpgrade/CountryUpgrade is already the source of truth for ownership
 * on the site, but the actual in-game effect only exists once the matching
 * LuckPerms permission nodes are granted (see UpgradeCondition.countryMaxLevel,
 * which reads "unity.<feature>.<level>" nodes off the country's LuckPerms
 * group). This poller is the transport for that — same shape as
 * MoneyRequestPoller/ZoneRequestPoller.
 *
 * Declarative, not incremental: each request carries the FULL desired state
 * for a given upgrade's permission bases (grantBases at the given level/value,
 * revokeBases fully cleared), so a missed or duplicated request can't leave
 * stale nodes behind — we always clear-then-reapply rather than track deltas.
 *
 * LuckPerms' API is thread-safe and doesn't touch Bukkit/world state, so
 * (unlike ZoneRequestPoller) this runs entirely on the polling thread — no
 * need to hop to the main thread.
 *
 * See infra/game-integration-architecture.md in the farlandsconnect repo.
 */
public class UpgradeGrantPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;

    public UpgradeGrantPoller(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
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
        List<FarLandsApiClient.PendingUpgradeGrantRequest> batch = api.fetchPendingUpgradeGrants();
        for (FarLandsApiClient.PendingUpgradeGrantRequest req : batch) {
            process(req);
        }
    }

    private void process(FarLandsApiClient.PendingUpgradeGrantRequest req) {
        try {
            if ("country".equals(req.targetType())) {
                processCountry(req);
            } else if ("player".equals(req.targetType())) {
                processPlayer(req);
            } else {
                api.reportUpgradeGrantResult(req.id(), false, "unknown_target_type");
            }
        } catch (Exception e) {
            log.warning("[UpgradeGrantPoller] processing " + req.id() + " failed: " + e);
            api.reportUpgradeGrantResult(req.id(), false, "internal_error");
        }
    }

    private LuckPerms lp() {
        try {
            return LuckPermsProvider.get();
        } catch (Throwable t) {
            return null;
        }
    }

    private void processCountry(FarLandsApiClient.PendingUpgradeGrantRequest req) {
        if (req.countryName() == null || req.countryName().isBlank()) {
            api.reportUpgradeGrantResult(req.id(), false, "missing_country_name");
            return;
        }
        LuckPerms lp = lp();
        if (lp == null) {
            api.reportUpgradeGrantResult(req.id(), false, "luckperms_unavailable");
            return;
        }

        String canonicalId = UpgradeCondition.resolveCountryGroupId(req.countryName());
        if (canonicalId == null) {
            api.reportUpgradeGrantResult(req.id(), false, "country_not_found");
            return;
        }

        String lpGroupName = "country_" + canonicalId;
        Group group = lp.getGroupManager().getGroup(lpGroupName);
        if (group == null) {
            // Lazily create the group — old addCountry.php created it on country
            // creation; the site's own country creation doesn't reach the plugin
            // at all (Country lives purely in Postgres), so this is the first
            // point a country's LuckPerms group is actually needed.
            group = lp.getGroupManager().createAndLoadGroup(lpGroupName).join();
        }

        applyBases(group.data(), req.grantBases(), req.revokeBases(), req.level(), req.enabled());
        lp.getGroupManager().saveGroup(group).join();

        UpgradeCondition.invalidateCountryNodeCache(canonicalId);
        api.reportUpgradeGrantResult(req.id(), true, null);
    }

    private void processPlayer(FarLandsApiClient.PendingUpgradeGrantRequest req) {
        if (req.username() == null || req.username().isBlank()) {
            api.reportUpgradeGrantResult(req.id(), false, "missing_username");
            return;
        }
        LuckPerms lp = lp();
        if (lp == null) {
            api.reportUpgradeGrantResult(req.id(), false, "luckperms_unavailable");
            return;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(req.username());
        if (offline.getName() == null) {
            api.reportUpgradeGrantResult(req.id(), false, "user_not_found");
            return;
        }
        UUID uuid = offline.getUniqueId();

        User user;
        try {
            user = lp.getUserManager().loadUser(uuid).join();
        } catch (Exception e) {
            api.reportUpgradeGrantResult(req.id(), false, "user_load_failed");
            return;
        }

        applyBases(user.data(), req.grantBases(), req.revokeBases(), req.level(), req.enabled());
        lp.getUserManager().saveUser(user).join();

        // Personal-tree effects aren't consumed by any UpgradeCondition check
        // yet (upgrades_player.json is effectively empty in practice) — no
        // per-user node cache exists to invalidate, unlike the country path.
        api.reportUpgradeGrantResult(req.id(), true, null);
    }

    /**
     * Clears every existing node under each revokeBases prefix, then does the
     * same for each grantBases prefix before adding "base.<level>" with
     * value=enabled — this is what actually turns the *purchased* level into
     * the *effective* one (see UpgradeCondition.countryMaxLevel/groupHasNode).
     */
    private void applyBases(NodeMap data, List<String> grantBases, List<String> revokeBases, int level, boolean enabled) {
        for (String base : revokeBases) {
            data.clear(n -> n instanceof PermissionNode pn && pn.getKey().startsWith(base + "."));
        }
        for (String base : grantBases) {
            data.clear(n -> n instanceof PermissionNode pn && pn.getKey().startsWith(base + "."));
            if (level > 0) {
                Node node = PermissionNode.builder(base + "." + level).value(enabled).build();
                data.add(node);
            }
        }
    }
}
