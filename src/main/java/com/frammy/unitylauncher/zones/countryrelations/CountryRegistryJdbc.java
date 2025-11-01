package com.frammy.unitylauncher.zones.countryrelations;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * CountryRegistryJdbc
 *
 * Источник истины: таблица Countries.
 *
 * Колонки:
 *   Name        (PK)
 *   Players     (JSON)   -> {"frammy":"0","Steve":"2"}  // ник -> roleId
 *   Permissions (JSON)   -> роли, среди них Name="Leader", поле ID = id этой роли
 *
 * Мы кэшируем:
 *   playerToCountry[playerLower]   = countryName
 *   countryToLeader[countryLower]  = leaderPlayerLower
 *
 * Кэш ленивый с TTL.
 */
public class CountryRegistryJdbc {

    private final Map<String, String> playerToCountry = new ConcurrentHashMap<>();
    private final Map<String, String> countryToLeader = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;

    private static final long CACHE_TTL_MS = 5_000;
    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile boolean cacheLoadedOnce = false;

    public CountryRegistryJdbc(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // какой у игрока roleId в своей стране
    private final Map<String, Integer> playerToRoleId = new ConcurrentHashMap<>();

    // по стране -> карта roleId -> RoleInfo (Prefix и всё, что нам пригодится)
    private final Map<String, Map<Integer, RoleInfo>> countryRoleMeta = new ConcurrentHashMap<>();

    /**
     * @param prefix как "§o§d❉Президент" или "&f"
     */ // простая структура одной роли
        public record RoleInfo(int id, String name, String prefix) {
    }

    /* ===================== ПУБЛИЧНОЕ API ===================== */

    /** Получить страну игрока или null. */
    public String getCountryOfPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        refreshCacheIfExpired();
        return playerToCountry.get(playerName.toLowerCase(Locale.ROOT));
    }

    /** Совместимость со старым кодом. */
    public String getCountryCachedOrSchedule(String playerName) {
        return getCountryOfPlayer(playerName);
    }

    /** true, если игрок является лидером своей страны. */
    public boolean isCountryLeaderCached(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String country = getCountryOfPlayer(playerName);
        if (country == null) return false;

        String leader = getLeaderOfCountry(country);
        return leader != null && leader.equalsIgnoreCase(playerName);
    }

    /** Имя лидера страны (ник), либо null. */
    public String getLeaderOfCountry(String countryName) {
        if (countryName == null || countryName.isBlank()) return null;
        refreshCacheIfExpired();
        return countryToLeader.get(countryName.toLowerCase(Locale.ROOT));
    }

    /**
     * Удалить страну внутри переданной транзакции (conn autocommit=false снаружи).
     * После удаления чистим локальный кэш.
     */
    public void deleteCountryTx(Connection conn, String countryName) throws SQLException {
        if (conn == null) throw new SQLException("Connection is null");
        if (countryName == null || countryName.isBlank()) return;

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM Countries WHERE Name = ?")) {
            ps.setString(1, countryName);
            ps.executeUpdate();
        }

        // выбрасываем страну из кэша
        String lower = countryName.toLowerCase(Locale.ROOT);
        countryToLeader.remove(lower);
        playerToCountry.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(countryName));

        touchCacheNow();
    }

    /**
     * Асинхронное удаление страны (без общей транзакции с чем-то ещё).
     * DBConnect() выполняется в async, кэш чистится потом в main-потоке.
     */
    public void deleteCountryAsync(String countryName) {
        if (countryName == null || countryName.isBlank()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection con = DBConnect()) {
                if (con == null) {
                    logDb("deleteCountryAsync", "DBConnect()==null");
                    return;
                }
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM Countries WHERE Name = ?")) {
                    ps.setString(1, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("deleteCountryAsync", t);
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                String lower = countryName.toLowerCase(Locale.ROOT);
                countryToLeader.remove(lower);
                playerToCountry.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(countryName));
                touchCacheNow();
            });
        });
    }

    /**
     * Гарантировать стартовый лимит банкоматов для страны.
     * Таблица atm_quota(country PK, quota INT) предполагается.
     */
    public void ensureInitialAtmAllowance(String countryName, int initialCount) {
        if (countryName == null || countryName.isBlank()) return;
        if (initialCount <= 0) return;

        String sql = """
                INSERT INTO atm_quota (country, quota)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE quota = GREATEST(quota, VALUES(quota))
                """;

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDb("ensureInitialAtmAllowance", "DBConnect()==null");
                return;
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, countryName);
                ps.setInt(2, initialCount);
                ps.executeUpdate();
            }
        } catch (Throwable t) {
            logDb("ensureInitialAtmAllowance", t);
        }
    }

    /** ID роли игрока внутри своей страны, или null если не нашли / нет страны. */
    public Integer getPlayerRoleId(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        refreshCacheIfExpired();
        return playerToRoleId.get(playerName.toLowerCase(Locale.ROOT));
    }

    /** Префикс роли игрока (например "§o§d❉Президент"), или null если нет. */
    public String getPlayerRolePrefix(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        refreshCacheIfExpired();

        String countryName = playerToCountry.get(playerName.toLowerCase(Locale.ROOT));
        if (countryName == null || countryName.isBlank()) return null;

        Integer rid = playerToRoleId.get(playerName.toLowerCase(Locale.ROOT));
        if (rid == null) return null;

        Map<Integer, RoleInfo> roles = countryRoleMeta.get(countryName.toLowerCase(Locale.ROOT));
        if (roles == null) return null;

        RoleInfo ri = roles.get(rid);
        if (ri == null) return null;

        String raw = ri.prefix;
        if (raw == null || raw.isBlank()) return null;
        return raw;
    }

    /* ===================== КЭШ СТРАН ===================== */

    private void refreshCacheIfExpired() {
        Instant now = Instant.now();
        if (cacheLoadedOnce && Duration.between(lastRefresh, now).toMillis() < CACHE_TTL_MS) {
            return;
        }

        // временные мапы, которые мы соберём заново
        Map<String, String> newPlayerToCountry = new HashMap<>();
        Map<String, Integer> newPlayerToRoleId = new HashMap<>();
        Map<String, String> newCountryToLeader = new HashMap<>();
        Map<String, Map<Integer, RoleInfo>> newCountryRoleMeta = new HashMap<>();

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDb("refreshCacheIfExpired", "DBConnect()==null");
                return;
            }

            String sql = "SELECT Name, Players, Permissions FROM Countries";
            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String countryName = rs.getString("Name");
                    String playersJson = rs.getString("Players");
                    String permsJson   = rs.getString("Permissions");

                    if (countryName == null || countryName.isBlank()) continue;

                    String countryLower = countryName.toLowerCase(Locale.ROOT);

                    // --- 1. распарсили роли этой страны
                    Map<Integer, RoleInfo> rolesMap = parseRoles(permsJson);

                    // если в этой конкретной итерации роли пустые,
                    // но мы уже ранее в ЭТОМ refresh собрали непустые роли для той же страны,
                    // то не затираем хорошее пустым мусором.
                    // *** anti-overwrite logic:
                    if (rolesMap.isEmpty() && newCountryRoleMeta.containsKey(countryLower)
                            && newCountryRoleMeta.get(countryLower) != null
                            && !newCountryRoleMeta.get(countryLower).isEmpty()) {
                        // оставляем старое
                        rolesMap = newCountryRoleMeta.get(countryLower);
                    }

                    newCountryRoleMeta.put(countryLower, rolesMap);

                    int leaderRoleId = findLeaderRoleIdFromRoles(rolesMap);
                    String leaderFound = null;

                    // --- 2. распарсили игроков
                    Map<String, Integer> playersMap = parsePlayersRoleMap(playersJson);
                    for (Map.Entry<String, Integer> e : playersMap.entrySet()) {
                        String playerLower = e.getKey();
                        Integer roleId = e.getValue();
                        if (playerLower == null) continue;

                        newPlayerToCountry.put(playerLower, countryName);

                        if (roleId != null) {
                            newPlayerToRoleId.put(playerLower, roleId);
                        }

                        if (leaderRoleId != 0 && roleId != null
                                && roleId == leaderRoleId
                                && leaderFound == null) {
                            leaderFound = playerLower;
                        }
                    }

                    if (leaderFound != null && !leaderFound.isBlank()) {
                        newCountryToLeader.put(countryLower, leaderFound);
                    }
                }
            }
        } catch (Throwable t) {
            logDb("refreshCacheIfExpired", t);
            return;
        }

        // записываем временные мапы в поля класса
        playerToCountry.clear();
        playerToCountry.putAll(newPlayerToCountry);

        playerToRoleId.clear();
        playerToRoleId.putAll(newPlayerToRoleId);

        countryToLeader.clear();
        countryToLeader.putAll(newCountryToLeader);

        countryRoleMeta.clear();
        countryRoleMeta.putAll(newCountryRoleMeta);

        cacheLoadedOnce = true;
        lastRefresh = Instant.now();
    }

    private void touchCacheNow() {
        lastRefresh = Instant.now();
        cacheLoadedOnce = true;
    }

    /* ===================== JSON helpers ===================== */

    // Разбираем столбец Players -> map(lowerNick -> roleId)
    private static Map<String, Integer> parsePlayersRoleMap(String playersJson) {
        Map<String, Integer> out = new HashMap<>();
        if (playersJson == null || playersJson.isBlank()) return out;
        try {
            JsonObject obj = JsonParser.parseString(playersJson).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String rawName = e.getKey();
                if (rawName == null || rawName.isBlank()) continue;
                String lower = rawName.toLowerCase(Locale.ROOT);

                Integer roleId = null;
                JsonElement val = e.getValue();
                if (val.isJsonPrimitive()) {
                    try {
                        if (val.getAsJsonPrimitive().isNumber()) {
                            roleId = val.getAsInt();
                        } else if (val.getAsJsonPrimitive().isString()) {
                            roleId = Integer.parseInt(val.getAsString().trim());
                        }
                    } catch (Exception ignored) {}
                }
                out.put(lower, roleId);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    // Разобрать Permissions JSON страны в карту roleId -> RoleInfo
    private static Map<Integer, RoleInfo> parseRoles(String permsJsonRaw) {
        Map<Integer, RoleInfo> out = new HashMap<>();
        if (permsJsonRaw == null || permsJsonRaw.isBlank()) return out;
        try {
            JsonElement root = JsonParser.parseString(permsJsonRaw);
            if (root.isJsonPrimitive() && root.getAsJsonPrimitive().isString()) {
                String inner = root.getAsString();
                root = JsonParser.parseString(inner);
            }
            fillRolesFromRoot(out, root);
            if (out.isEmpty()) {
                Bukkit.getLogger().warning("[CountryRegistryJdbc] parseRoles: empty after parse, raw=" +
                        (permsJsonRaw.length()>128 ? permsJsonRaw.substring(0,128)+"..." : permsJsonRaw));
            }
        } catch (Throwable ignored) {}
        return out;
    }


    // попытка извлечь роли из уже распарсенного JsonElement root
    private static void fillRolesFromRoot(Map<Integer, RoleInfo> out, JsonElement root) {
        if (root == null) return;

        // Формат A: корень — массив ролей
        if (root.isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                RoleInfo ri = roleFromJson(e.getAsJsonObject());
                if (ri != null) out.put(ri.id, ri);
            }
            return;
        }

        // Формат B: корень — объект
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();

            // B1: obj.roles — массив
            if (obj.has("roles") && obj.get("roles").isJsonArray()) {
                for (JsonElement e : obj.get("roles").getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    RoleInfo ri = roleFromJson(e.getAsJsonObject());
                    if (ri != null) out.put(ri.id, ri);
                }
                return;
            }

            // B2: obj.roles — объект вида "0":{...}, "1":{...}
            if (obj.has("roles") && obj.get("roles").isJsonObject()) {
                JsonObject rolesObj = obj.getAsJsonObject("roles");
                for (Map.Entry<String, JsonElement> entry : rolesObj.entrySet()) {
                    JsonElement val = entry.getValue();
                    if (!val.isJsonObject()) continue;
                    RoleInfo ri = roleFromJson(val.getAsJsonObject());
                    if (ri != null) out.put(ri.id, ri);
                }
                return;
            }

            // B3: сам obj похож на мапу ролей (ключи "0","1","Leader", etc.)
            boolean lookedLikeRoleMap = false;
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                JsonElement val = entry.getValue();
                if (!val.isJsonObject()) continue;
                RoleInfo ri = roleFromJson(val.getAsJsonObject());
                if (ri != null) {
                    out.put(ri.id, ri);
                    lookedLikeRoleMap = true;
                }
            }
            if (lookedLikeRoleMap) {
                return;
            }

            // B4: fallback: obj сам может быть одной ролью
            RoleInfo single = roleFromJson(obj);
            if (single != null) {
                out.put(single.id, single);
            }
        }
    }

    private static RoleInfo roleFromJson(JsonObject o) {
        if (o == null) return null;
        Integer id = getInt(o, "ID");
        String name = getString(o, "Name");
        String prefix = getString(o, "Prefix");
        if (id == null) return null;
        return new RoleInfo(id, name, prefix);
    }

    private static int findLeaderRoleIdFromRoles(Map<Integer, RoleInfo> rolesMap) {
        if (rolesMap == null || rolesMap.isEmpty()) return 0;
        for (RoleInfo ri : rolesMap.values()) {
            if (ri.name != null && ri.name.equalsIgnoreCase("Leader")) {
                return ri.id;
            }
        }
        return 0;
    }

    private static Integer getInt(JsonObject o, String key) {
        if (o == null || key == null) return null;
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            JsonElement el = o.get(key);
            if (el.isJsonPrimitive()) {
                if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
                if (el.getAsJsonPrimitive().isString()) return Integer.parseInt(el.getAsString().trim());
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String getString(JsonObject o, String key) {
        if (o == null || key == null) return null;
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        try {
            return o.get(key).getAsString();
        } catch (Exception ignored) {}
        return null;
    }

    /* ===================== LOG ===================== */

    private static void logDb(String where, Throwable t) {
        Bukkit.getLogger().severe("[CountryRegistryJdbc] DB error in " + where + ": " + t);
    }

    private static void logDb(String where, String msg) {
        Bukkit.getLogger().severe("[CountryRegistryJdbc] " + where + ": " + msg);
    }
}
