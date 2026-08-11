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
                case "purchase" -> processPurchase(req);
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
        if (!cmds.applyMoneyDelta(req.fromUsername(), -debit, "Перевод игроку " + req.toUsername())) {
            api.reportMoneyRequestResult(req.id(), false, "insufficient_funds");
            return;
        }
        if (!cmds.applyMoneyDelta(req.toUsername(), req.amount(), "Перевод от " + req.fromUsername())) {
            cmds.applyMoneyDelta(req.fromUsername(), debit, "Возврат: перевод игроку " + req.toUsername() + " не прошёл"); // best-effort refund
            api.reportMoneyRequestResult(req.id(), false, "recipient_not_found");
            return;
        }
        api.reportMoneyRequestResult(req.id(), true, null);
    }

    /**
     * Marketplace/real-estate purchases (farlandsconnect GH #17 point 1) —
     * the site already records its own marketplace_purchase/
     * property_purchase Transaction row for this (see routes/marketplace.ts,
     * realEstate.ts's recordSettledTransaction call), so both legs move real
     * money silently (mirror=false) — a plugin_deposit/plugin_withdrawal
     * echo would just double the ledger entry.
     */
    private void processPurchase(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.fromUsername() == null || req.toUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        double debit = req.debitAmount() != null ? req.debitAmount() : req.amount();

        UnityCommands cmds = UnityCommands.getInstance();
        if (!cmds.applyMoneyDelta(req.fromUsername(), -debit, null, false)) {
            api.reportMoneyRequestResult(req.id(), false, "insufficient_funds");
            return;
        }
        if (!cmds.applyMoneyDelta(req.toUsername(), req.amount(), null, false)) {
            cmds.applyMoneyDelta(req.fromUsername(), debit, null, false); // best-effort refund
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
        String payee = req.toUsername();
        String payDesc = payee != null ? ("Оплата счёта игроку " + payee) : "Оплата счёта";
        if (!cmds.applyMoneyDelta(req.fromUsername(), -req.amount(), payDesc)) {
            api.reportMoneyRequestResult(req.id(), false, "insufficient_funds");
            return;
        }
        // Server-issued invoices have no payee — the payer's debit is a pure sink.
        if (payee != null && !cmds.applyMoneyDelta(payee, req.amount(), "Оплата счёта от " + req.fromUsername())) {
            cmds.applyMoneyDelta(req.fromUsername(), req.amount(), "Возврат: счёт не оплачен"); // best-effort refund
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
        boolean credited = UnityCommands.getInstance().applyMoneyDelta(req.toUsername(), req.amount(), "Зарплата за игру");
        api.reportMoneyRequestResult(req.id(), credited, credited ? null : "user_not_found");
    }
}
