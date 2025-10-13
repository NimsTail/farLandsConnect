package com.frammy.unitylauncher.zones.countryrelations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 */
public class CountryRegistryJdbc {

    /** имя игрока (lowercase) -> страна (display name из БД) */
    private final Map<String, String> countryByPlayer = new ConcurrentHashMap<>();
    /** имена, которые надо подтянуть/освежить при ближайшем батче */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    private volatile int taskId = -1;
    private JavaPlugin pluginRef; // <— запоминаем плагин для runTask(...)
    private static final long REFRESH_PERIOD_TICKS = 20L * 10; // ~10 секунд
    private static final int BATCH_SIZE = 128;                 // пачка имён на один запрос
    private static final long DB_ERROR_LOG_COOLDOWN_MS = 5000;
    private static final AtomicLong LAST_DB_ERROR_LOG = new AtomicLong(0);

    /* ===================== ПУБЛИЧНОЕ API (только кэш) ===================== */

    /** Вернёт страну из кэша или null. НЕ делает запрос в БД. */
    public String getCountryCached(String playerName) {
        if (playerName == null || playerName.isEmpty()) return null;
        return countryByPlayer.get(playerName.toLowerCase(Locale.ROOT));
    }

    /**
     * Асинхронная обёртка: вернёт текущее кэш-значение в основном треде,
     * а имя поставит в очередь на подкачку (если кэша нет/устарел).
     * БД не блокируем; свежие данные подтянутся ближайшим батчем (≈10с).
     */
    public void getCountryByPlayerNameAsync(String playerName, Consumer<String> callback) {
        if (playerName == null || callback == null) return;
        ensureScheduledRefresh(playerName); // попросим фон подкачать
        String cached = getCountryCached(playerName);
        // вернуть ответ в основном потоке
        if (pluginRef != null) {
            Bukkit.getScheduler().runTask(pluginRef, () -> callback.accept(cached));
        } else {
            // на всякий случай, если start() ещё не вызывался
            callback.accept(cached);
        }
    }

    /** Удобный вариант для UUID: определяем ник, затем используем async по нику. */
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

    /**
     * Гарантирует, что игрок появится в ближайшем батче обновления.
     * Возвращает текущее кэш-значение (может быть null до подкачки).
     */
    public void ensureScheduledRefresh(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;
        String key = playerName.toLowerCase(Locale.ROOT);
        pending.add(key);
        countryByPlayer.get(key);
    }

    /** Массово добавить игроков на ближайшую подкачку. */
    public void ensureScheduledRefresh(Collection<String> playerNames) {
        if (playerNames == null) return;
        for (String n : playerNames) {
            if (n != null && !n.isEmpty()) pending.add(n.toLowerCase(Locale.ROOT));
        }
    }

    /* ===================== Жизненный цикл / планировщик ===================== */

    /** Запускает фоновое обновление кэша каждые ~10 секунд. */
    public void start(JavaPlugin plugin) {
        if (taskId != -1) return;
        this.pluginRef = plugin; // <— сохранили плагин
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshTick, 40L, REFRESH_PERIOD_TICKS).getTaskId();
    }

    /** Остановить фоновое обновление. */
    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /* ===================== Внутренности ===================== */

    /** Один тик обновления: собираем «кого тянуть» и тянем батчами. */
    private void refreshTick() {
        try {
            Set<String> batchKeys = drainPendingUpTo(); // ограничим разрастание
            if (batchKeys.isEmpty()) return;
            fetchCountriesBatch(new ArrayList<>(batchKeys));
        } catch (Throwable t) {
            logDbOnce("refreshTick", t);
        }
    }

    /** Забирает до N элементов из pending. */
    private Set<String> drainPendingUpTo() {
        Set<String> out = new LinkedHashSet<>();
        Iterator<String> it = pending.iterator();
        while (it.hasNext() && out.size() < 512) {
            String k = it.next();
            it.remove();
            out.add(k);
        }
        return out;
    }

    /** Тянем страны батчами через IN (?, ?, ...). */
    private void fetchCountriesBatch(List<String> keys) {
        if (keys.isEmpty()) return;

        // Разбиваем на чанки
        for (int i = 0; i < keys.size(); i += BATCH_SIZE) {
            List<String> chunk = keys.subList(i, Math.min(i + BATCH_SIZE, keys.size()));
            queryAndUpdateCache(chunk);
        }
    }

    /** Один батч-запрос и обновление кэша. */
    private void queryAndUpdateCache(List<String> namesChunk) {
        if (namesChunk.isEmpty()) return;

        String placeholders = String.join(",", Collections.nCopies(namesChunk.size(), "?"));
        String sql = "SELECT Name, GeneralData FROM Users WHERE Name IN (" + placeholders + ")";

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDbOnce("DBConnect()==null", null);
                pending.addAll(namesChunk);
                return;
            }

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                int idx = 1;
                for (String n : namesChunk) ps.setString(idx++, n);
                try (ResultSet rs = ps.executeQuery()) {
                    Set<String> seen = new HashSet<>();
                    while (rs.next()) {
                        String name = rs.getString("Name");
                        String json = rs.getString("GeneralData");
                        String country = parseCountrySafe(json);

                        String key = (name != null) ? name.toLowerCase(Locale.ROOT) : null;
                        if (key != null) {
                            seen.add(key);
                            if (country != null && !country.isEmpty()) {
                                countryByPlayer.put(key, country);
                            } else {
                                countryByPlayer.remove(key);
                            }
                        }
                    }
                    // кого не нашли в БД — вычищаем из кэша
                    for (String requested : namesChunk) {
                        String key = requested.toLowerCase(Locale.ROOT);
                        if (!seen.contains(key)) countryByPlayer.remove(key);
                    }
                }
            }
        } catch (Throwable t) {
            logDbOnce("queryAndUpdateCache", t);
            pending.addAll(namesChunk);
        }
    }

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
