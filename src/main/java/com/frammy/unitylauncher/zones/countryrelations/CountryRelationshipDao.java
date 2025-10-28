package com.frammy.unitylauncher.zones.countryrelations;

import com.google.gson.*;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class CountryRelationshipDao {

    /* ======================
       Публичные методы
       ====================== */

    /** Вернёт список стран или пустой список при ошибке. */
    public List<String> listAllCountries() {
        final String sql = "SELECT `Name` FROM `Countries`";
        List<String> out = new ArrayList<>();

        try (Connection con = DBConnect()) {
            if (con == null) {
                warnOnce("listAllCountries: DBConnect() == null");
                return out;
            }

            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            logSql("listAllCountries", e);
        } catch (Throwable t) {
            logOther("listAllCountries", t);
        }
        return out;
    }

    /** Прочитать JSON отношений страны -> Map<Страна, Статус>. Пустая мапа при отсутствии/ошибке. */
    public Map<String, String> loadRelationsFor(String countryName) {
        final Map<String, String> out = new HashMap<>();
        if (countryName == null || countryName.isEmpty()) return out;

        final String sql = "SELECT `Relationship` FROM `Countries` WHERE `Name` = ?";

        try (Connection con = DBConnect()) {
            if (con == null) {
                warnOnce("loadRelationsFor: DBConnect() == null");
                return out;
            }

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, countryName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return out;
                    String json = rs.getString(1);
                    return parseJson(json);
                }
            }
        } catch (SQLException e) {
            logSql("loadRelationsFor(" + countryName + ")", e);
            return out;
        } catch (Throwable t) {
            logOther("loadRelationsFor(" + countryName + ")", t);
            return out;
        }
    }

    /** Сохраняет отношения страны. Делает UPSERT и гарантирует значения для всех NOT NULL полей. */
    public void saveRelationsFor(String countryName, Map<String, String> map) {
        if (countryName == null || countryName.isEmpty()) return;
        final String json = toJson(map);

        // Вариант 1: попытаться UPSERT-ом. Важно: указать все NOT NULL без дефолтов.
        final String upsert =
                "INSERT INTO `Countries` " +
                        "(`Name`, `Relationship`, `Players`, `Permissions`, `Upgrades`, `CountryInfo`, `isVerified`) " +
                        "VALUES (?, ?, JSON_ARRAY(), JSON_OBJECT(), '{}', JSON_OBJECT(), 0) " +
                        "ON DUPLICATE KEY UPDATE `Relationship` = VALUES(`Relationship`)";

        try (Connection con = DBConnect()) {
            if (con == null) {
                warnOnce("saveRelationsFor: DBConnect() == null");
                return;
            }
            try (PreparedStatement ps = con.prepareStatement(upsert)) {
                ps.setString(1, countryName);
                ps.setString(2, json);
                ps.executeUpdate();
            }
            return;
        } catch (SQLException e) {
            logSql("saveRelationsFor(UPSERT " + countryName + ")", e);
        } catch (Throwable t) {
            logOther("saveRelationsFor(UPSERT " + countryName + ")", t);
        }

        // Вариант 2 (fallback): UPDATE -> если 0 строк, то INSERT с полным набором полей.
        final String upd = "UPDATE `Countries` SET `Relationship` = ? WHERE `Name` = ?";
        final String ins =
                "INSERT INTO `Countries` " +
                        "(`Name`, `Relationship`, `Players`, `Permissions`, `Upgrades`, `CountryInfo`, `isVerified`) " +
                        "VALUES (?, ?, JSON_ARRAY(), JSON_OBJECT(), '{}', JSON_OBJECT(), 0)";

        try (Connection con = DBConnect()) {
            if (con == null) {
                warnOnce("saveRelationsFor(fallback): DBConnect() == null");
                return;
            }
            boolean oldAuto = con.getAutoCommit();
            con.setAutoCommit(false);
            int updated;
            try (PreparedStatement psUpd = con.prepareStatement(upd)) {
                psUpd.setString(1, json);
                psUpd.setString(2, countryName);
                updated = psUpd.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement psIns = con.prepareStatement(ins)) {
                    psIns.setString(1, countryName);
                    psIns.setString(2, json);
                    psIns.executeUpdate();
                }
            }
            con.commit();
            con.setAutoCommit(oldAuto);
        } catch (SQLException e) {
            logSql("saveRelationsFor(fallback " + countryName + ")", e);
        } catch (Throwable t) {
            logOther("saveRelationsFor(fallback " + countryName + ")", t);
        }
    }

    public void deleteByCountryTx(Connection conn, String countryName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM CountryRelations WHERE country = ?")) {
            ps.setString(1, countryName);
            ps.executeUpdate();
        }
    }

    /* ======================
       Внутренние утилиты
       ====================== */

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Map<String, String> parseJson(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("relations") || !root.get("relations").isJsonObject()) return out;
            JsonObject rel = root.getAsJsonObject("relations");
            for (Map.Entry<String, JsonElement> e : rel.entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String toJson(Map<String, String> map) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject rel = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            rel.addProperty(e.getKey(), e.getValue());
        }
        root.add("relations", rel);
        return GSON.toJson(root);
    }

    private static void logSql(String where, SQLException e) {
        Bukkit.getLogger().severe("[UnityLauncher] SQL error in " + where + ": " + e.getMessage());
        SQLException se = e;
        while (se != null) {
            Bukkit.getLogger().severe("  SQLState=" + se.getSQLState() + " ErrorCode=" + se.getErrorCode()
                    + " Message=" + se.getMessage());
            se = se.getNextException();
        }
    }

    private static void logOther(String where, Throwable t) {
        Bukkit.getLogger().severe("[UnityLauncher] DB error in " + where + ": " + t);
    }

    private static void warnOnce(String msg) {
        Bukkit.getLogger().warning("[UnityLauncher] " + msg);
    }
}
