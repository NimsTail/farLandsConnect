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

    /** Kinds that actually move money: amount (and debitAmount, if sent) must be a positive number. */
    private static final java.util.Set<String> MONEY_KINDS = java.util.Set.of(
            "transfer", "purchase", "invoice_pay", "salary_claim",
            "country_treasury_deposit", "country_treasury_withdraw", "site_charge", "site_credit");

    private void process(FarLandsApiClient.PendingMoneyRequest req) {
        try {
            if (MONEY_KINDS.contains(req.kind())
                    && (!(req.amount() > 0) || (req.debitAmount() != null && !(req.debitAmount() > 0)))) {
                api.reportMoneyRequestResult(req.id(), false, "invalid_amount");
                return;
            }
            switch (req.kind()) {
                case "transfer" -> processTransfer(req);
                case "purchase" -> processPurchase(req);
                case "invoice_pay" -> processInvoicePay(req);
                case "salary_claim" -> processSalaryClaim(req);
                case "balance_sync" -> processBalanceSync(req);
                case "daydeal_confirm" -> processDaydealConfirm(req);
                case "daydeal_status" -> processDaydealStatus(req);
                case "country_treasury_deposit" -> processTreasuryDeposit(req);
                case "country_treasury_withdraw" -> processTreasuryWithdraw(req);
                case "site_charge" -> processSiteCharge(req);
                case "site_credit" -> processSiteCredit(req);
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

        String err = UnityCommands.getInstance().transferMoney(req.fromUsername(), req.toUsername(), debit, req.amount(),
                "Перевод игроку " + req.toUsername(), "Перевод от " + req.fromUsername(), true);
        api.reportMoneyRequestResult(req.id(), err == null, err);
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

        String err = UnityCommands.getInstance().transferMoney(req.fromUsername(), req.toUsername(), debit, req.amount(),
                null, null, false);
        api.reportMoneyRequestResult(req.id(), err == null, err);
    }

    private void processInvoicePay(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.fromUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }

        // farlandsconnect GH #17 point 2: commission now rides on debitAmount
        // (payer pays amount+commission, recipient still gets exactly
        // amount — same split as processTransfer) instead of always
        // debiting/crediting the bare amount.
        double debit = req.debitAmount() != null ? req.debitAmount() : req.amount();

        UnityCommands cmds = UnityCommands.getInstance();
        String payee = req.toUsername();
        // Server-issued invoices have no payee — the payer's debit is a pure sink.
        if (payee == null) {
            boolean ok = cmds.applyMoneyDelta(req.fromUsername(), -debit, "Оплата счёта");
            api.reportMoneyRequestResult(req.id(), ok, ok ? null : "insufficient_funds");
            return;
        }
        String err = cmds.transferMoney(req.fromUsername(), payee, debit, req.amount(),
                "Оплата счёта игроку " + payee, "Оплата счёта от " + req.fromUsername(), true);
        api.reportMoneyRequestResult(req.id(), err == null, "recipient_not_found".equals(err) ? "payee_not_found" : err);
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

    /**
     * farlandsconnect GH #19 follow-up — пополнение казны страны с сайта:
     * чистый сток (никто в игре не получает эти деньги, казна — виртуальный
     * счётчик в Postgres, не игровой аккаунт), тот же паттерн, что и
     * safewayless счёт в processInvoicePay, только всегда без получателя.
     * До этого сайт трогал только свой локальный Account-зеркало — игрок
     * реально ничего не терял, следующий же balance_sync тихо откатывал
     * "списание" обратно на нетронутый реальный баланс.
     */
    private void processTreasuryDeposit(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.fromUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        boolean debited = UnityCommands.getInstance().applyMoneyDelta(req.fromUsername(), -req.amount(), "Пополнение казны страны");
        api.reportMoneyRequestResult(req.id(), debited, debited ? null : "insufficient_funds");
    }

    /**
     * farlandsconnect GH #19 follow-up — снятие из казны страны на сайте:
     * чистый источник (никто в игре не платит за это — казна отдаёт свои
     * же виртуальные деньги), тот же паттерн, что processSalaryClaim.
     * До этого снятие уменьшало казну на сайте, но игрок реально не получал
     * ничего — следующий же balance_sync стирал фиктивное зачисление.
     */
    private void processTreasuryWithdraw(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.toUsername() == null) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        boolean credited = UnityCommands.getInstance().applyMoneyDelta(req.toUsername(), req.amount(), "Снятие из казны страны");
        api.reportMoneyRequestResult(req.id(), credited, credited ? null : "user_not_found");
    }

    /**
     * Payment for something bought on the site itself (player upgrades,
     * frames, founding a country): a pure sink, nobody in-game receives it.
     * The site records its own ledger row (upgrade_purchase, frame_purchase,
     * country_create — note says what), so the debit is silent (mirror=false).
     * Before this kind existed the site only lowered its local balance mirror
     * and the next balance_sync restored the real balance: those purchases
     * were free (farlandsconnect review C3).
     */
    private void processSiteCharge(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.fromUsername() == null || !(req.amount() > 0)) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        boolean debited = UnityCommands.getInstance().applyMoneyDelta(req.fromUsername(), -req.amount(), null, false);
        api.reportMoneyRequestResult(req.id(), debited, debited ? null : "insufficient_funds");
    }

    /**
     * Money the site pays out to a player (e.g. the partial refund for
     * downgrading an upgrade): a pure source, silent for the same reason as
     * site_charge — the site writes its own ledger row.
     */
    private void processSiteCredit(FarLandsApiClient.PendingMoneyRequest req) {
        if (req.toUsername() == null || !(req.amount() > 0)) {
            api.reportMoneyRequestResult(req.id(), false, "missing_username");
            return;
        }
        boolean credited = UnityCommands.getInstance().applyMoneyDelta(req.toUsername(), req.amount(), null, false);
        api.reportMoneyRequestResult(req.id(), credited, credited ? null : "user_not_found");
    }
}
