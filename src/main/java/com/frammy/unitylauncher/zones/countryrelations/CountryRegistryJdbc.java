package com.frammy.unitylauncher.zones.countryrelations;

import com.frammy.unitylauncher.UnityLauncher;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class CountryRegistryJdbc {
    private final Map<String, String> cacheByPlayerName = new ConcurrentHashMap<>();
    private static final Gson GSON = new Gson();

    // чтобы не спамить лог каждую миллисекунду, если БД упала
    private static final AtomicLong LAST_DB_ERROR_LOG = new AtomicLong(0);
    private static final long DB_ERROR_LOG_COOLDOWN_MS = 5000;

    /** Синхронный вариант (лучше не вызывать из основного треда в горячих местах) */
    public String getCountryByPlayerName(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;

        String key = playerName.toLowerCase(Locale.ROOT);
        String cached = cacheByPlayerName.get(key);
        if (cached != null) return cached;

        String sql = "SELECT GeneralData FROM Users WHERE Name = ? LIMIT 1";

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDbUnavailableOnce("getCountryByPlayerName(" + playerName + ")", null);
                return null;
            }

            try (PreparedStatement ps = con.prepareStatement(sql)) {
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
                    } catch (Exception parseErr) {
                        return null; // JSON битый — пропускаем
                    }
                }
            }
        } catch (Exception e) {
            logDbUnavailableOnce("getCountryByPlayerName(" + playerName + ")", e);
            return null;
        }
    }

    /** Асинхронный вариант — результат придёт в колбэк (возвращаемся в основной поток) */
    public void getCountryByPlayerNameAsync(String playerName, Consumer<String> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(UnityLauncher.getInstance(), () -> {
            String result = getCountryByPlayerName(playerName);
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

    /* ===================== helpers ===================== */

    private static void logDbUnavailableOnce(String where, Throwable t) {
        long now = System.currentTimeMillis();
        long last = LAST_DB_ERROR_LOG.get();
        if (now - last >= DB_ERROR_LOG_COOLDOWN_MS && LAST_DB_ERROR_LOG.compareAndSet(last, now)) {
            if (t == null) {
                Bukkit.getLogger().severe("[UnityLauncher] БД недоступна (" + where + ")");
            } else {
                Bukkit.getLogger().severe("[UnityLauncher] Ошибка работы с БД (" + where + "): " + t.getMessage());
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                Bukkit.getLogger().severe(sw.toString());
            }
        }
    }
}
