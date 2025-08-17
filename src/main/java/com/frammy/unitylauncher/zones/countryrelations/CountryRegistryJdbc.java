package com.frammy.unitylauncher.zones.countryrelations;

import com.frammy.unitylauncher.UnityLauncher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class CountryRegistryJdbc {
    private final Map<String, String> cacheByPlayerName = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    /** Синхронный вариант (лучше не вызывать из основного потока) */
    public String getCountryByPlayerName(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;
        String key = playerName.toLowerCase(Locale.ROOT);
        String cached = cacheByPlayerName.get(key);
        if (cached != null) return cached;

        String sql = "SELECT GeneralData FROM Users WHERE Name = ? LIMIT 1";
        try (Connection con = DBConnect();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String json = rs.getString(1);
                if (json == null || json.isEmpty()) return null;

                try {
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    if (!obj.has("countryName") || obj.get("countryName").isJsonNull()) return null;
                    String country = obj.get("countryName").getAsString();
                    if (country != null && !country.isEmpty()) {
                        cacheByPlayerName.put(key, country);
                    }
                    return country;
                } catch (Exception ignored) {
                    return null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** Асинхронный вариант — результат придёт в колбэк */
    public void getCountryByPlayerNameAsync(String playerName, Consumer<String> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(UnityLauncher.getInstance(), () -> {
            String result = getCountryByPlayerName(playerName);
            // Возвращаем в основной поток, чтобы с результатом можно было безопасно работать с Bukkit API
            Bukkit.getScheduler().runTask(UnityLauncher.getInstance(), () -> callback.accept(result));
        });
    }

    /** Асинхронно для UUID */
    public void getCountryOfAsync(UUID playerId, Consumer<String> callback) {
        Player p = Bukkit.getPlayer(playerId);
        if (p == null) {
            callback.accept(null);
            return;
        }
        getCountryByPlayerNameAsync(p.getName(), callback);
    }
}