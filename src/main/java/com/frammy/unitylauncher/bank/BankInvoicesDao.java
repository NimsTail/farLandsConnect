package com.frammy.unitylauncher.bank;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class BankInvoicesDao {

    private final JavaPlugin plugin;

    public BankInvoicesDao(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void createToUserAsync(int fromUserId, int toUserId, double amount, String description, Instant dueAt) {
        if (amount <= 0) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                INSERT INTO bank_invoices
                (from_user_id, to_user_id, to_country, amount, description, from_server, non_rejectable, status, due_at)
                VALUES (?, ?, NULL, ?, ?, 1, 0, 'pending', ?)
                """;
            try (Connection con = DBConnect()) {
                if (con == null) return;
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, fromUserId);
                    ps.setInt(2, toUserId);
                    ps.setDouble(3, round2(amount));
                    ps.setString(4, safeDesc(description));
                    ps.setTimestamp(5, dueAt == null ? null : Timestamp.from(dueAt));
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[BankInvoicesDao] createToUserAsync error: " + t);
            }
        });
    }

    public void createToCountryAsync(int fromUserId, String toCountry, double amount, String description, Instant dueAt) {
        if (toCountry == null || toCountry.isBlank()) return;
        if (amount <= 0) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                INSERT INTO bank_invoices
                (from_user_id, to_user_id, to_country, amount, description, from_server, non_rejectable, status, due_at)
                VALUES (?, NULL, ?, ?, ?, 1, 0, 'pending', ?)
                """;
            try (Connection con = DBConnect()) {
                if (con == null) return;
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setInt(1, fromUserId);
                    ps.setString(2, toCountry);
                    ps.setDouble(3, round2(amount));
                    ps.setString(4, safeDesc(description));
                    ps.setTimestamp(5, dueAt == null ? null : Timestamp.from(dueAt));
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[BankInvoicesDao] createToCountryAsync error: " + t);
            }
        });
    }

    public void insertCountryInvoiceTx(Connection con,
                                       int fromUserId,
                                       String toCountry,
                                       double amount,
                                       String description,
                                       Instant dueAt,
                                       String periodKey,
                                       boolean nonRejectable) throws SQLException {

        String sql = """
        INSERT INTO bank_invoices
          (from_user_id, to_user_id, to_country, amount, description, from_server, non_rejectable, status, due_at, period_key)
        VALUES
          (?, NULL, ?, ?, ?, 1, ?, 'pending', ?, ?)
        """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, fromUserId);
            ps.setString(2, toCountry);
            ps.setBigDecimal(3, java.math.BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP));
            ps.setString(4, description == null ? "" : description);
            ps.setInt(5, nonRejectable ? 1 : 0);
            ps.setTimestamp(6, java.sql.Timestamp.from(dueAt));
            ps.setString(7, periodKey);
            ps.executeUpdate();
        }
    }
    public void createToCountryAsync(int fromUserId,
                                     String toCountry,
                                     double amount,
                                     String description,
                                     java.time.Instant dueAt,
                                     String periodKey,          // может быть null если без апгрейда БД
                                     boolean nonRejectable) {

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
            INSERT INTO bank_invoices
              (from_user_id, to_user_id, to_country, amount, description, from_server, non_rejectable, status, due_at, period_key)
            VALUES
              (?, NULL, ?, ?, ?, 1, ?, 'pending', ?, ?)
            """;

            try (Connection con = DBConnect();
                 PreparedStatement ps = con.prepareStatement(sql)) {

                ps.setInt(1, fromUserId);
                ps.setString(2, toCountry);
                ps.setBigDecimal(3, new java.math.BigDecimal(String.format(java.util.Locale.ROOT, "%.2f", amount)));
                ps.setString(4, description == null ? "" : description);
                ps.setInt(5, nonRejectable ? 1 : 0);
                ps.setTimestamp(6, java.sql.Timestamp.from(dueAt));
                ps.setString(7, periodKey); // если колонки нет — убери её из SQL и этот setString
                ps.executeUpdate();

            } catch (java.sql.SQLIntegrityConstraintViolationException dup) {
                // если включил uniq_country_period — дубль недели просто игнорируем
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[BankInvoicesDao] createToCountryAsync error: " + t);
            }
        });
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String safeDesc(String s) {
        if (s == null) return "";
        if (s.length() <= 255) return s;
        return s.substring(0, 255);
    }
}
