package com.frammy.unitylauncher.zones.countryrelations;

import com.google.gson.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.UUID;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * Регистр стран игроков с агрессивным кэшированием и фоновой подкачкой.
 * - Все публичные get* читают ТОЛЬКО из кэша и НЕ ходят в БД.
 * - Новые имена добавляются в pending, фоновая задача (~10с) тянет батчем.
 * - Дополнительно: кэш лидеров стран (по Countries.Players + Countries.Permissions).
 */
public class CountryRegistryJdbc {

    /** имя игрока (lowercase) -> страна (display name из БД) */
    private final Map<String, String> countryByPlayer = new ConcurrentHashMap<>();
    /** страна (lowercase) -> ник лидера (как в БД, case-sensitive) */
    private final Map<String, String> leaderByCountry = new ConcurrentHashMap<>();

    /** имена, которые надо подтянуть/освежить при ближайшем батче */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private volatile int taskId = -1;
    private JavaPlugin pluginRef; // для runTask(...)
    private static final long REFRESH_PERIOD_TICKS = 20L * 10; // ~10 сек
    private static final int BATCH_SIZE = 128;
    private static final long DB_ERROR_LOG_COOLDOWN_MS = 5000;
    private static final AtomicLong LAST_DB_ERROR_LOG = new AtomicLong(0);

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /* ===================== ПУБЛИЧНОЕ API (только кэш) ===================== */

    /** Вернёт страну из кэша или null. */
    public String getCountryCached(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;
        return countryByPlayer.get(playerName.toLowerCase(Locale.ROOT));
    }

    /** Вернёт ник лидера страны (кэш) или null. */
    public String getCountryLeaderCached(String countryName) {
        if (countryName == null || countryName.isEmpty()) return null;
        return leaderByCountry.get(countryName.toLowerCase(Locale.ROOT));
    }

    /** Быстрая проверка: является ли игрок лидером своей страны (по кэшу). */
    public boolean isCountryLeaderCached(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String country = getCountryCached(playerName);
        if (country == null || country.isBlank()) return false;
        String leader = getCountryLeaderCached(country);
        return leader != null && leader.equalsIgnoreCase(playerName);
    }

    /**
     * Асинхронная обёртка: вернёт текущее кэш-значение в основном треде,
     * а имя поставит в очередь на подкачку (если кэша нет/устарел).
     */
    public void getCountryByPlayerNameAsync(String playerName, Consumer<String> callback) {
        if (playerName == null || callback == null) return;
        ensureScheduledRefresh(playerName); // попросим фон подкачать
        String cached = getCountryCached(playerName);
        if (pluginRef != null) {
            Bukkit.getScheduler().runTask(pluginRef, () -> callback.accept(cached));
        } else {
            callback.accept(cached);
        }
    }

    /** Удобно по UUID: узнаём ник, дальше — как обычно. */
    public void getCountryOfAsync(UUID playerId, Consumer<String> callback) {
        if (callback == null) return;
        Player p = (playerId != null) ? Bukkit.getPlayer(playerId) : null;
        if (p == null) {
            if (pluginRef != null) Bukkit.getScheduler().runTask(pluginRef, () -> callback.accept(null));
            else callback.accept(null);
            return;
        }
        getCountryByPlayerNameAsync(p.getName(), callback);
    }

    /** Гарантируем подкачку игрока в ближайшем батче. */
    public void ensureScheduledRefresh(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        pending.add(playerName.toLowerCase(Locale.ROOT));
        // чтение из кэша для разминки
        countryByPlayer.get(playerName.toLowerCase(Locale.ROOT));
    }

    /** Массово добавить игроков на ближайшую подкачку. */
    public void ensureScheduledRefresh(Collection<String> playerNames) {
        if (playerNames == null) return;
        for (String n : playerNames) {
            if (n != null && !n.isEmpty()) pending.add(n.toLowerCase(Locale.ROOT));
        }
    }

    /* ===================== ЖИЗНЕННЫЙ ЦИКЛ ===================== */

    public void start(JavaPlugin plugin) {
        if (taskId != -1) return;
        this.pluginRef = plugin;
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshTick, 40L, REFRESH_PERIOD_TICKS).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /* ===================== ФОНОВЫЙ ТИК ===================== */

    private void refreshTick() {
        try {
            Set<String> batchKeys = drainPendingUpTo();
            if (batchKeys.isEmpty()) return;
            fetchCountriesBatch(new ArrayList<>(batchKeys));
        } catch (Throwable t) {
            logDbOnce("refreshTick", t);
        }
    }

    private Set<String> drainPendingUpTo() {
        Set<String> out = new LinkedHashSet<>();
        Iterator<String> it = pending.iterator();
        while (it.hasNext() && out.size() < 512) {
            String k = it.next(); it.remove(); out.add(k);
        }
        return out;
    }

    /**
     * Тянем Users.GeneralData.batch -> получаем страну каждого игрока,
     * затем ОДНИМ батчем тянем лидеров по Countries (Players + Permissions).
     */
    private void fetchCountriesBatch(List<String> keys) {
        if (keys.isEmpty()) return;
        for (int i = 0; i < keys.size(); i += BATCH_SIZE) {
            List<String> chunk = keys.subList(i, Math.min(i + BATCH_SIZE, keys.size()));
            Set<String> countries = queryUsersAndUpdateCountryCache(chunk);
            if (!countries.isEmpty()) {
                fetchLeadersForCountries(new ArrayList<>(countries));
            }
        }
    }

    /** Запрос Users.Name IN (...) → обновление countryByPlayer, возврат найденных стран. */
    private Set<String> queryUsersAndUpdateCountryCache(List<String> namesChunk) {
        Set<String> countriesSeen = new HashSet<>();
        if (namesChunk.isEmpty()) return countriesSeen;

        String placeholders = String.join(",", Collections.nCopies(namesChunk.size(), "?"));
        String sql = "SELECT Name, GeneralData FROM Users WHERE Name IN (" + placeholders + ")";

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDbOnce("DBConnect()==null", null);
                pending.addAll(namesChunk);
                return countriesSeen;
            }

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                int idx = 1;
                for (String n : namesChunk) ps.setString(idx++, n);
                try (ResultSet rs = ps.executeQuery()) {
                    Set<String> seenPlayers = new HashSet<>();
                    while (rs.next()) {
                        String name = rs.getString("Name");
                        String json = rs.getString("GeneralData");
                        String country = parseCountrySafe(json);

                        String key = (name != null) ? name.toLowerCase(Locale.ROOT) : null;
                        if (key != null) {
                            seenPlayers.add(key);
                            if (country != null && !country.isBlank()) {
                                countryByPlayer.put(key, country);
                                countriesSeen.add(country.toLowerCase(Locale.ROOT));
                            } else {
                                countryByPlayer.remove(key);
                            }
                        }
                    }
                    // кого не нашли — чистим
                    for (String requested : namesChunk) {
                        String key = requested.toLowerCase(Locale.ROOT);
                        if (!seenPlayers.contains(key)) countryByPlayer.remove(key);
                    }
                }
            }
        } catch (Throwable t) {
            logDbOnce("queryUsersAndUpdateCountryCache", t);
            pending.addAll(namesChunk);
        }
        return countriesSeen;
    }

    /**
     * Батч-запрос лидеров: SELECT Name, Players, Permissions FROM Countries WHERE Name IN (...)
     * Парсим Permissions → находим ID роли "Leader"; в Players ищем ник с этим ID.
     */
    private void fetchLeadersForCountries(List<String> countriesChunk) {
        if (countriesChunk.isEmpty()) return;

        for (int i = 0; i < countriesChunk.size(); i += BATCH_SIZE) {
            List<String> chunk = countriesChunk.subList(i, Math.min(i + BATCH_SIZE, countriesChunk.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            String sql = "SELECT Name, Players, Permissions FROM Countries WHERE Name IN (" + placeholders + ")";

            try (Connection con = DBConnect()) {
                if (con == null) {
                    logDbOnce("DBConnect()==null(fetchLeaders)", null);
                    continue;
                }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    int idx = 1;
                    for (String c : chunk) ps.setString(idx++, c);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String name = rs.getString("Name");
                            String playersJson = rs.getString("Players");
                            String permsJson   = rs.getString("Permissions");

                            int leaderRoleId = extractLeaderRoleId(permsJson);
                            String leader = extractLeaderName(playersJson, leaderRoleId);

                            String key = (name != null) ? name.toLowerCase(Locale.ROOT) : null;
                            if (key != null) {
                                if (leader != null && !leader.isBlank()) {
                                    leaderByCountry.put(key, leader);
                                } else {
                                    leaderByCountry.remove(key);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                logDbOnce("fetchLeadersForCountries", t);
            }
        }
    }

    /* ===================== ПАРСИНГ JSON ===================== */

    private static String parseCountrySafe(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (!obj.has("countryName") || obj.get("countryName").isJsonNull()) return null;
            String country = obj.get("countryName").getAsString();
            return (country != null && !country.isBlank()) ? country : null;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Находит ID роли "Leader" в Permissions. Если не нашли — 0. */
    private static int extractLeaderRoleId(String permissionsJson) {
        if (permissionsJson == null || permissionsJson.isEmpty()) return 0;
        try {
            JsonElement el = JsonParser.parseString(permissionsJson);
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement e : arr) {
                    if (!e.isJsonObject()) continue;
                    JsonObject o = e.getAsJsonObject();
                    String name = o.has("Name") && !o.get("Name").isJsonNull() ? o.get("Name").getAsString() : null;
                    if (name != null && name.equalsIgnoreCase("Leader")) {
                        if (o.has("ID") && !o.get("ID").isJsonNull()) {
                            try { return o.get("ID").getAsInt(); } catch (Exception ignored) {}
                        }
                        break;
                    }
                }
            } else if (el.isJsonObject()) {
                // fallback на случай JSON-объекта
                JsonObject root = el.getAsJsonObject();
                JsonArray roles = root.has("roles") && root.get("roles").isJsonArray() ? root.get("roles").getAsJsonArray() : null;
                if (roles != null) {
                    for (JsonElement r : roles) {
                        if (!r.isJsonObject()) continue;
                        JsonObject o = r.getAsJsonObject();
                        String name = o.has("Name") ? o.get("Name").getAsString() : null;
                        if (name != null && name.equalsIgnoreCase("Leader")) {
                            if (o.has("ID")) {
                                try { return o.get("ID").getAsInt(); } catch (Exception ignored) {}
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0; // по умолчанию (как в примере)
    }

    /** Находит ник с ролью leaderRoleId в Players JSON. Возвращает первый найденный. */
    private static String extractLeaderName(String playersJson, int leaderRoleId) {
        if (playersJson == null || playersJson.isEmpty()) return null;
        try {
            JsonObject obj = JsonParser.parseString(playersJson).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                JsonElement val = e.getValue();
                Integer role = null;
                try {
                    if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isNumber()) {
                        role = val.getAsInt();
                    } else if (val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                        role = Integer.parseInt(val.getAsString());
                    }
                } catch (Exception ignored) {}
                if (role != null && role == leaderRoleId) {
                    return e.getKey(); // ник
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /* ===================== ЛОГИ ===================== */

    private static void logDbOnce(String where, Throwable t) {
        long now = System.currentTimeMillis();
        long last = LAST_DB_ERROR_LOG.get();
        if (now - last < DB_ERROR_LOG_COOLDOWN_MS) return;
        if (!LAST_DB_ERROR_LOG.compareAndSet(last, now)) return;

        if (t == null) {
            Bukkit.getLogger().severe("[UnityLauncher] БД недоступна (" + where + ")");
        } else {
            Bukkit.getLogger().severe("[UnityLauncher] Ошибка БД (" + where + "): " + t.getMessage());
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            Bukkit.getLogger().severe(sw.toString());
        }
    }
}
