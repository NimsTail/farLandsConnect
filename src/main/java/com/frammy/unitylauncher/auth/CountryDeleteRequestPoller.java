package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityLauncher;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Executes country-delete "jobs" from the site (GH #10 — see
 * CountryDeleteRequest/routes/countries.ts's self-leave-as-sole-leader
 * handler, farlandsconnect repo). A country disbanded on the site used to
 * just vanish from Postgres — its own MySQL Countries row and LuckPerms
 * group ("country_<Id>") stayed behind forever, since nothing ever told the
 * plugin. Mirrors CountryCreateRequestPoller's shape exactly, opposite
 * direction.
 */
public class CountryDeleteRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;

    public CountryDeleteRequestPoller(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
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
        List<FarLandsApiClient.PendingCountryDeleteRequest> batch = api.fetchPendingCountryDeleteRequests();
        for (FarLandsApiClient.PendingCountryDeleteRequest req : batch) {
            process(req);
        }
    }

    private void process(FarLandsApiClient.PendingCountryDeleteRequest req) {
        try {
            // Resolve the plugin-side numeric Id (needed for the LuckPerms group
            // name) BEFORE deleting — deleteCountryTx's own cache eviction would
            // otherwise race this lookup on the next line.
            Integer countryId = UnityLauncher.getInstance().countryRegistryJdbc.getCountryId(req.countryName());

            try (Connection con = UnityLauncher.DBConnect()) {
                if (con == null) {
                    api.reportCountryDeleteRequestResult(req.id(), false, "db_error");
                    return;
                }
                con.setAutoCommit(false);
                try {
                    UnityLauncher.getInstance().countryRegistryJdbc.deleteCountryTx(con, req.countryName());
                    con.commit();
                } catch (Exception e) {
                    con.rollback();
                    throw e;
                }
            }

            if (countryId != null) {
                deleteLuckPermsGroup(countryId);
            }

            api.reportCountryDeleteRequestResult(req.id(), true, null);
        } catch (Exception e) {
            log.warning("[CountryDeleteRequestPoller] processing " + req.id() + " failed: " + e);
            api.reportCountryDeleteRequestResult(req.id(), false, "internal_error");
        }
    }

    private void deleteLuckPermsGroup(int countryId) {
        LuckPerms lp;
        try {
            lp = LuckPermsProvider.get();
        } catch (Throwable t) {
            log.warning("[CountryDeleteRequestPoller] LuckPerms unavailable, skipping group deletion for country_" + countryId);
            return;
        }

        String groupName = "country_" + countryId;
        var group = lp.getGroupManager().getGroup(groupName);
        if (group != null) {
            lp.getGroupManager().deleteGroup(group).join();
        }
    }
}
