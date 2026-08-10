package com.frammy.unitylauncher.auth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * Executes password-change "jobs" created by the site (see routes/profile.ts
 * in the farlandsconnect repo, POST /profile/me/password) — AuthService is
 * the real source of truth for login credentials, so the site can't settle
 * these on its own. Same shape as MoneyRequestPoller/ZoneRequestPoller.
 *
 * AuthService.setNewPassword(...) already calls FarLandsApiClient.
 * changePassword(...) on success, which hits the existing
 * POST /plugin/users/:username/password mirror — that's what actually
 * updates the site's own Postgres passwordHash, so this poller only needs
 * to call setNewPassword and report the outcome, no separate mirroring.
 */
public class PasswordChangeRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final AuthService authService;
    private final Logger log;

    public PasswordChangeRequestPoller(JavaPlugin plugin, FarLandsApiClient api, AuthService authService, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.authService = authService;
        this.log = log;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second. No-op if the API bridge is disabled. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, periodTicks, periodTicks);
    }

    private void pollOnce() {
        List<FarLandsApiClient.PendingPasswordRequest> batch = api.fetchPendingPasswordRequests();
        for (FarLandsApiClient.PendingPasswordRequest req : batch) {
            Bukkit.getScheduler().runTask(plugin, () -> process(req));
        }
    }

    private void process(FarLandsApiClient.PendingPasswordRequest req) {
        try {
            if (req.newPassword() == null || req.newPassword().isBlank()) {
                api.reportPasswordRequestResult(req.id(), false, "missing_password");
                return;
            }
            boolean ok = authService.setNewPassword(req.username(), req.newPassword().toCharArray());
            api.reportPasswordRequestResult(req.id(), ok, ok ? null : "user_not_found");
        } catch (Exception e) {
            log.warning("[PasswordChangeRequestPoller] processing " + req.id() + " failed: " + e);
            api.reportPasswordRequestResult(req.id(), false, "internal_error");
        }
    }
}
