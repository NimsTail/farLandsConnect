package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.bank.BankInvoicesDao;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.*;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.Map;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public final class WeeklyCountryInvoiceService {

    private final UnityLauncher plugin;
    private final CountryRegistryJdbc reg;
    private final BankInvoicesDao invoicesDao;
    private final ZoneId zoneId;
    private final int serverUserId;

    public WeeklyCountryInvoiceService(UnityLauncher plugin,
                                       CountryRegistryJdbc reg,
                                       BankInvoicesDao invoicesDao,
                                       ZoneId zoneId,
                                       int serverUserId) {
        this.plugin = plugin;
        this.reg = reg;
        this.invoicesDao = invoicesDao;
        this.zoneId = zoneId;
        this.serverUserId = serverUserId;
    }

    public void start() {
        long delay = ticksUntilNextRun();
        long week = 20L * 60L * 60L * 24L * 7L;

        Bukkit.getScheduler().runTaskTimer(plugin, () ->
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::runWeeklyBillingAsync),
                delay,
                week
        );
    }

    private long ticksUntilNextRun() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);

        // Понедельник 00:10
        ZonedDateTime next = now.with(java.time.DayOfWeek.MONDAY)
                .withHour(0).withMinute(10).withSecond(0).withNano(0);

        if (!next.isAfter(now)) next = next.plusWeeks(1);

        long diffMs = Duration.between(now, next).toMillis();
        return Math.max(1L, diffMs / 50L);
    }

    private void runWeeklyBillingAsync() {
        Map<String, Double> all = reg.getAllWeeklyTaxDue();
        if (all.isEmpty()) return;

        LocalDate today = LocalDate.now(zoneId);
        String periodKey = isoWeekKey(today);

        for (Map.Entry<String, Double> e : all.entrySet()) {
            String countryLower = e.getKey();
            double due = round2(e.getValue());
            if (due <= 0.0) continue;

            String display = reg.getCountryDisplayName(countryLower);
            if (display == null || display.isBlank()) continue;

            String desc = "Еженедельный налог (активность чанков) [" + periodKey + "]";
            Instant dueAt = Instant.now().plus(Duration.ofDays(7));

            billOneCountryTx(display, due, desc, dueAt, periodKey);
        }

        // мягко пометим кэш как “обновить”
        Bukkit.getScheduler().runTask(plugin, reg::getAllCountryNames);
    }

    private void billOneCountryTx(String countryNameDisplay,
                                  double amount,
                                  String description,
                                  Instant dueAt,
                                  String periodKey) {

        try (Connection con = DBConnect()) {
            if (con == null) {
                plugin.getLogger().severe("[WeeklyInvoice] DBConnect()==null");
                return;
            }

            con.setAutoCommit(false);

            try {
                // 1) вставляем инвойс (уникальность держит (to_country, period_key))
                invoicesDao.insertCountryInvoiceTx(
                        con,
                        serverUserId,
                        countryNameDisplay,
                        amount,
                        description,
                        dueAt,
                        periodKey,
                        true
                );

                // 2) обнуляем WeeklyTaxDue в той же транзакции
                resetWeeklyTaxDueTx(con, countryNameDisplay);

                con.commit();
                plugin.getLogger().info("[WeeklyInvoice] created invoice for " + countryNameDisplay
                        + " amount=" + amount + " period=" + periodKey);

            } catch (java.sql.SQLIntegrityConstraintViolationException dup) {
                // инвойс уже существует за эту неделю -> ничего не трогаем
                try { con.rollback(); } catch (Exception ignore) {}
                plugin.getLogger().warning("[WeeklyInvoice] skipped duplicate for "
                        + countryNameDisplay + " period=" + periodKey);

            } catch (Throwable t) {
                try { con.rollback(); } catch (Exception ignore) {}
                plugin.getLogger().severe("[WeeklyInvoice] failed for " + countryNameDisplay + ": " + t);
            } finally {
                try { con.setAutoCommit(true); } catch (Exception ignore) {}
            }

        } catch (Throwable t) {
            plugin.getLogger().severe("[WeeklyInvoice] connection error: " + t);
        }
    }

    private static void resetWeeklyTaxDueTx(Connection con, String countryName) throws Exception {
        final String sql = """
            UPDATE Countries
            SET CountryInfo = JSON_SET(
                COALESCE(CountryInfo, JSON_OBJECT()),
                '$.WeeklyTaxDue',
                0
            )
            WHERE Name = ?
            """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, countryName);
            ps.executeUpdate();
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String isoWeekKey(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        int week = date.get(wf.weekOfWeekBasedYear());
        int year = date.get(wf.weekBasedYear());
        return String.format(Locale.ROOT, "%dW%02d", year, week);
    }
}