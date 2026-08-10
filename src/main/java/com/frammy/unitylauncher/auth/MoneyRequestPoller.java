package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityCommands;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.logging.Logger;

/**
 * Executes money-request "jobs" created by the site (transfer / invoice-pay /
 * salary-claim, see routes/bank.ts in the farlandsconnect repo) — real money
 * only exists in this plugin's MySQL, so the site can't settle these on its
 * own. Same shape as ZoneWebRequestService, but the queue lives on the
 * backend (Postgres, polled over HTTP via FarLandsApiClient) instead of a
 * MySQL table this plugin owns directly.
 *
 * See infra/game-integration-architecture.md in the farlandsconnect repo.
 */
public class MoneyRequestPoller {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final Logger log;

    public MoneyRequestPoller(JavaPlugin plugin, FarLandsApiClient api, Logger log) {
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
        List<FarLandsApiClient.PendingMoneyRequest> batch = api.fetchPendingMoneyRequests();
        for (FarLandsApiClient.PendingMoneyRequest req : batch) {
            // applyMoneyDelta touches the DB directly (no Bukkit API), but keeping all
            // money movement on the main thread avoids racing other in-game economy
            // actions (shop trades, zone billing) that also go through it.
            Bukkit.getScheduler().runTask(plugin, () -> process(req));
        }
    }

    private void process(FarLandsApiClient.PendingMoneyRequest req) {
        try {
            switch (req.kind()) {
                case "transfer" -> processTransfer(req);
                case "invoice_pay" -> processInvoicePay(req);
                case "salary_claim" -> processSalaryClaim(req);
                case "balance_sync" -> processBalanceSync(req);
                case "daydeal_confirm" -> processDaydealConfirm(req);
                case "daydeal_status" -> processDaydealStatus(req);
                default -> api.reportMoneyRequestResult(req.id(), false, "unknown_kind");
            }
        } catch (Exception e) {
            log.warning("[MoneyRequestPoller] processing " + req.id() + " (" + req.kind() + ") failed: " + e);
            api.reportMoneyRequestResult(req.id(), false, "internal_error");
        }
    }

    /** Read-only — reports the real dayDealCode so the site can catch drift (see UnityCommands.getDayDealRaw). */
    private void processDaydealStatus(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.toUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        String raw = UnityCommands.getInstance().getDayDealRaw(req.toUsername());
        api.reportMoneyRequestResult(req.id(), true, raw);
    }

    /** Writes the site-chosen "Задание дня" card into Users.GeneralData.dayDealCode for real. */
    private void processDaydealConfirm(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.toUsername() == null || req.note() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        boolean ok = UnityCommands.getInstance().setDayDealRaw(req.toUsername(), req.note());
        api.reportMoneyRequestResult(req.id(), ok, ok ? null : "user_not_found");
    }

    private void processTransfer(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.fromUsername() == null || req.toUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        double debit = req.debitAmount() != null ? req.debitAmount() : req.amount();

        UnityCommands cmds = UnityCommands.getInstance();
        if (!cmds.applyMoneyDelta(req.fromUsername(), -debit)) {
            api.reportMoneyRequestResult(req.id(), false, "insufficient_funds");
            return;
        }
        if (!cmds.applyMoneyDelta(req.toUsername(), req.amount())) {
            cmds.applyMoneyDelta(req.fromUsername(), debit); // best-effort refund
            api.reportMoneyRequestResult(req.id(), false, "recipient_not_found");
            return;
        }
        api.reportMoneyRequestResult(req.id(), true, null);
    }

    private void processInvoicePay(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.fromUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }

        UnityCommands cmds = UnityCommands.getInstance();
        if (!cmds.applyMoneyDelta(req.fromUsername(), -req.amount())) {
            api.reportMoneyRequestResult(req.id(), false, "insufficient_funds");
            return;
        }
        // Server-issued invoices have no payee — the payer's debit is a pure sink.
        if (req.toUsername() != null && !cmds.applyMoneyDelta(req.toUsername(), req.amount())) {
            cmds.applyMoneyDelta(req.fromUsername(), req.amount()); // best-effort refund
            api.reportMoneyRequestResult(req.id(), false, "payee_not_found");
            return;
        }
        api.reportMoneyRequestResult(req.id(), true, null);
    }

    /** Read-only — no debit/credit, just reports the real current balance so the site can reconcile. */
    private void processBalanceSync(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.toUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        Double balance = UnityCommands.getInstance().getBalance(req.toUsername());
        if (balance == null) {
            api.reportMoneyRequestResult(req.id(), false, "user_not_found");
            return;
        }
        api.reportMoneyRequestResult(req.id(), true, null, balance);
    }

    private void processSalaryClaim(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.toUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        boolean credited = UnityCommands.getInstance().applyMoneyDelta(req.toUsername(), req.amount());
        api.reportMoneyRequestResult(req.id(), credited, credited ? null : "user_not_found");
    }
}
