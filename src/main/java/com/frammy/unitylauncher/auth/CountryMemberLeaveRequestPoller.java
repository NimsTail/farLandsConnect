package com.frammy.unitylauncher.auth;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Executes country-member-leave "jobs" from the site (GH #10 round 3 — see
 * CountryMemberLeaveRequest/routes/countries.ts's kick and self-leave
 * handlers, farlandsconnect repo). A player leaving or getting kicked from a
 * country used to only touch Postgres — their LuckPerms country_<Id> group
 * and country-tagged prefix (see LuckPermsPrefixService) stayed on them
 * indefinitely, until someone happened to run the one-off
 * /ul luckperms cleanup pass. Mirrors CountryDeleteRequestPoller's shape.
 */
public class CountryMemberLeaveRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;

    public CountryMemberLeaveRequestPoller(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
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
        List<FarLandsApiClient.PendingCountryMemberLeaveRequest> batch = api.fetchPendingCountryMemberLeaveRequests();
        for (FarLandsApiClient.PendingCountryMemberLeaveRequest req : batch) {
            process(req);
        }
    }

    private void process(FarLandsApiClient.PendingCountryMemberLeaveRequest req) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(req.username());
            if (op.getName() == null) {
                api.reportCountryMemberLeaveRequestResult(req.id(), false, "player_not_found");
                return;
            }
            UUID uuid = op.getUniqueId();

            LuckPerms lp;
            try {
                lp = LuckPermsProvider.get();
            } catch (Throwable t) {
                api.reportCountryMemberLeaveRequestResult(req.id(), false, "luckperms_unavailable");
                return;
            }

            LuckPermsCountryCleanup.stripPlayerCountryState(lp, uuid);
            api.reportCountryMemberLeaveRequestResult(req.id(), true, null);
        } catch (Exception e) {
            log.warning("[CountryMemberLeaveRequestPoller] processing " + req.id() + " failed: " + e);
            api.reportCountryMemberLeaveRequestResult(req.id(), false, "internal_error");
        }
    }
}
