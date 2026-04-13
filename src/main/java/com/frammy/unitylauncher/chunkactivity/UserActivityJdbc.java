package com.frammy.unitylauncher.chunkactivity;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * Читает таблицу Users и по GeneralData считает активных игроков по странам.
 * <p>
 * GeneralData пример:
 * {
 * "Name": "frammy",
 * "money": 0,
 * "lastAuthAt": 1763098353665,
 * "countryName": "Nautilus",
 * ...
 * }
 */
public record UserActivityJdbc(JavaPlugin plugin) {

    /**
     * Статистика по стране:
     * totalPlayers  — сколько игроков вообще привязано к стране,
     * activePlayers — сколько из них были активны после minLastAuthAtMs.
     */
    public static final class CountryActivityStats {
        private int totalPlayers;
        private int activePlayers;

        public CountryActivityStats() {
        }

        void incTotal() {
            totalPlayers++;
        }

        void incActive() {
            activePlayers++;
        }

        public int totalPlayers() {
            return totalPlayers;
        }

        public int activePlayers() {
            return activePlayers;
        }
    }


    /**
     * Посчитать по каждой стране:
     *  - сколько игроков всего (totalPlayers),
     *  - сколько из них активны (activePlayers: lastAuthAt >= minLastAuthAtMs).
     *
     * @param minLastAuthAtMs порог "активности" (например, now - 24h)
     * @return map: countryNameLower -> CountryActivityStats
     */
    public Map<String, CountryActivityStats> loadCountryActivityStats(long minLastAuthAtMs) {
        Map<String, CountryActivityStats> out = new HashMap<>();

        final String sql = "SELECT GeneralData FROM Users";

        try (Connection con = DBConnect()) {
            if (con == null) {
                Bukkit.getLogger().severe("[UserActivityJdbc] DBConnect() == null");
                return out;
            }

            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String generalJson = rs.getString(1);
                    if (generalJson == null || generalJson.isBlank()) continue;

                    try {
                        JsonElement root = JsonParser.parseString(generalJson);
                        if (!root.isJsonObject()) continue;
                        JsonObject obj = root.getAsJsonObject();

                        // countryName
                        String country = null;
                        if (obj.has("countryName") && !obj.get("countryName").isJsonNull()) {
                            country = obj.get("countryName").getAsString();
                        }
                        if (country == null || country.isBlank()) continue;
                        String countryLower = country.toLowerCase(Locale.ROOT);

                        CountryActivityStats stats =
                                out.computeIfAbsent(countryLower, k -> new CountryActivityStats());
                        stats.incTotal();

                        // lastAuthAt
                        long lastAuthAt = 0L;
                        if (obj.has("lastAuthAt") && !obj.get("lastAuthAt").isJsonNull()) {
                            JsonElement la = obj.get("lastAuthAt");
                            if (la.isJsonPrimitive()) {
                                try {
                                    if (la.getAsJsonPrimitive().isNumber()) {
                                        lastAuthAt = la.getAsLong();
                                    } else if (la.getAsJsonPrimitive().isString()) {
                                        lastAuthAt = Long.parseLong(la.getAsString().trim());
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        if (lastAuthAt > 0L && lastAuthAt >= minLastAuthAtMs) {
                            stats.incActive();
                        }

                    } catch (Throwable ignored) {
                        // кривую запись просто пропускаем
                    }
                }
            }
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[UserActivityJdbc] SQL/DB error (loadCountryActivityStats): " + t);
        }

        return out;
    }

}
