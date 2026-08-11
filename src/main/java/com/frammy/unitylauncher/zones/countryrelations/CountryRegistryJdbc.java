package com.frammy.unitylauncher.zones.countryrelations;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
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

    private final java.util.concurrent.atomic.AtomicBoolean refreshInFlight = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Map<String, String> playerToCountry = new ConcurrentHashMap<>();
    private final Map<String, String> countryToLeader = new ConcurrentHashMap<>();
    // lowerCountry -> display name as stored in DB (original casing)
    private final Map<String, String> countryLowerToDisplay = new ConcurrentHashMap<>();
    // lowerCountry -> Countries.Id (стабильный ключ LuckPerms-группы страны — не зависит от транслитерации имени)
    private final Map<String, Integer> countryLowerToId = new ConcurrentHashMap<>();
    // Countries.Id -> display name — reverse of countryLowerToId. Needed
    // because playerCountryCanonical()/UpgradeCondition store the numeric
    // Countries.Id as the "canonical" country everywhere permission checks
    // touch it (see its own comment), but that leaves nothing that can turn
    // that Id back into a name for display (see getCountryNameById / ATM
    // "Инфо" screen, which used to print the raw Id).
    private final Map<Integer, String> countryIdToDisplay = new ConcurrentHashMap<>();
    private final Map<String, Double> countryToTransferFeePct = new ConcurrentHashMap<>();

    private final Map<String, Double> countryMoney = new ConcurrentHashMap<>();
    private final Map<String, Double> countryWeeklyTaxDue = new ConcurrentHashMap<>();
    private final Map<String, Double> countryArea = new ConcurrentHashMap<>();
    // общестрановой лимит площади под участки (PLOT), настраивается лидером на country.html:
    // mode "none" (по умолчанию, не ограничено) | "percent" (доля от countryArea) | "blocks" (абсолютное число)
    private final Map<String, String> countryPlotCapMode = new ConcurrentHashMap<>();
    private final Map<String, Double> countryPlotCapValue = new ConcurrentHashMap<>();

    private final JavaPlugin plugin;

    private static final long CACHE_TTL_MS = 5_000;
    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile boolean cacheLoadedOnce = false;

    private static final java.util.concurrent.atomic.AtomicBoolean ATM_SCHEMA_CHECKED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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
        public record RoleInfo(int id, String name, String prefix, int index, boolean buildZones, int zoneLimit) {}

    /** Комиссия страны (проценты, напр. 1.25 = 1.25%). Если нет — вернёт fallbackPct. */
    public double getCountryTransferFeePctOr(String countryName, double fallbackPct) {
        if (countryName == null || countryName.isBlank()) return fallbackPct;
        refreshCacheIfExpired();
        return countryToTransferFeePct.getOrDefault(countryName.toLowerCase(Locale.ROOT), fallbackPct);
    }

    /** Площадь территории страны (CountryInfo.Area, блоков²) — 0, если неизвестна. */
    public double getCountryArea(String countryName) {
        if (countryName == null || countryName.isBlank()) return 0;
        refreshCacheIfExpired();
        return countryArea.getOrDefault(countryName.toLowerCase(Locale.ROOT), 0.0);
    }

    /** Режим общестранового лимита площади под участки: "none" | "percent" | "blocks". */
    public String getPlotAreaCapMode(String countryName) {
        if (countryName == null || countryName.isBlank()) return "none";
        refreshCacheIfExpired();
        return countryPlotCapMode.getOrDefault(countryName.toLowerCase(Locale.ROOT), "none");
    }

    /** Значение лимита: проценты (0..100) при mode="percent", либо блоки² при mode="blocks". */
    public double getPlotAreaCapValue(String countryName) {
        if (countryName == null || countryName.isBlank()) return 0;
        refreshCacheIfExpired();
        return countryPlotCapValue.getOrDefault(countryName.toLowerCase(Locale.ROOT), 0.0);
    }

    /* ===================== ПУБЛИЧНОЕ API ===================== */

    /** Список всех стран (displayName из БД). */
    public List<String> getAllCountryNames() {
        refreshCacheIfExpired();
        List<String> out = new ArrayList<>(countryLowerToDisplay.values());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /**
     * Стабильный ID страны (Countries.Id) — используется как ключ LuckPerms-группы страны
     * ВМЕСТО транслитерированного имени, чтобы имя LP-группы не зависело от алфавита/регистра
     * названия и не могло коллизировать между двумя разными странами.
     * Возвращает null, если страны с таким именем сейчас нет (например, только что расформирована).
     */
    public Integer getCountryId(String countryName) {
        if (countryName == null || countryName.isBlank()) return null;
        refreshCacheIfExpired();
        return countryLowerToId.get(countryName.trim().toLowerCase(Locale.ROOT));
    }

    /** Накопленный недельный налог (CountryInfo.WeeklyTaxDue), если нет — 0.0. */
    public double getWeeklyTaxDue(String countryName) {
        if (countryName == null || countryName.isBlank()) return 0.0;
        refreshCacheIfExpired();
        String key = countryName.toLowerCase(Locale.ROOT);
        return countryWeeklyTaxDue.getOrDefault(key, 0.0);
    }

    /** Снимок по всем странам: lowerName -> WeeklyTaxDue. */
    public Map<String, Double> getAllWeeklyTaxDue() {
        refreshCacheIfExpired();
        return new HashMap<>(countryWeeklyTaxDue);
    }

    /** Прибавить delta к WeeklyTaxDue (может быть отрицательным). */
    public void addWeeklyTaxDue(String countryName, double delta) {
        if (countryName == null || countryName.isBlank()) return;
        if (delta == 0.0) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                UPDATE Countries
                SET CountryInfo = JSON_SET(
                    COALESCE(CountryInfo, JSON_OBJECT()),
                    '$.WeeklyTaxDue',
                    COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(CountryInfo,'$.WeeklyTaxDue')) AS DECIMAL(20,3)), 0) + ?
                )
                WHERE Name = ?
                """;
            try (Connection con = DBConnect()) {
                if (con == null) { logDb("addWeeklyTaxDue"); return; }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setDouble(1, delta);
                    ps.setString(2, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("addWeeklyTaxDue", t);
                return;
            }

            Bukkit.getScheduler().runTask(plugin, this::touchCacheNow);
        });
    }

    public String getCountryDisplayName(String countryNameOrLower) {
        if (countryNameOrLower == null || countryNameOrLower.isBlank()) return null;
        refreshCacheIfExpired();
        String lower = countryNameOrLower.toLowerCase(Locale.ROOT);
        return countryLowerToDisplay.getOrDefault(lower, countryNameOrLower);
    }

    /**
     * Reverse of getCountryId(String) — turns the numeric Countries.Id
     * (the "canonical" country identifier permission checks use, see
     * UpgradeCondition.playerCountryCanonical/resolveCountryGroupId) back
     * into a display-ready name. Returns null if that Id no longer exists
     * (country deleted/расформирована).
     */
    public String getCountryNameById(Integer countryId) {
        if (countryId == null) return null;
        refreshCacheIfExpired();
        return countryIdToDisplay.get(countryId);
    }

    /**
     * Best-effort display name for whatever's stored as a sign/zone's
     * "canonical" country — accepts either the numeric Countries.Id (what
     * playerCountryCanonical/resolveCountryGroupId actually store) or an
     * already-plain name, and always returns something printable.
     */
    public String getCountryDisplayNameForCanonical(String canonicalOrName) {
        if (canonicalOrName == null || canonicalOrName.isBlank()) return null;
        try {
            String byId = getCountryNameById(Integer.valueOf(canonicalOrName));
            if (byId != null) return byId;
        } catch (NumberFormatException ignored) {
            // не число — значит это уже (предположительно) обычное имя
        }
        return getCountryDisplayName(canonicalOrName);
    }

    /** Обнулить WeeklyTaxDue для страны (при выставлении недельного счёта/списании). */
    public void resetWeeklyTaxDue(String countryName) {
        if (countryName == null || countryName.isBlank()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                UPDATE Countries
                SET CountryInfo = JSON_SET(
                    COALESCE(CountryInfo, JSON_OBJECT()),
                    '$.WeeklyTaxDue',
                    0
                )
                WHERE Name = ?
                """;
            try (Connection con = DBConnect()) {
                if (con == null) { logDb("resetWeeklyTaxDue"); return; }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("resetWeeklyTaxDue", t);
                return;
            }

            Bukkit.getScheduler().runTask(plugin, this::touchCacheNow);
        });
    }


    /** Баланс казны страны (из CountryInfo.Money), если нет — 0.0. */
    public double getCountryMoney(String countryName) {
        if (countryName == null || countryName.isBlank()) return 0.0;
        refreshCacheIfExpired();
        String key = countryName.toLowerCase(Locale.ROOT);
        return countryMoney.getOrDefault(key, 0.0);
    }

    /**
     * Добавить к балансу страны delta (может быть отрицательным).
     * Работает асинхронно и атомарно на уровне БД.
     */
    public void addCountryMoney(String countryName, double delta) {
        if (countryName == null || countryName.isBlank()) return;
        if (delta == 0.0) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // MariaDB не поддерживает CAST(x AS JSON) (нет типа JSON — только
            // LONGTEXT + CHECK(json_valid()), см. тот же коммент в
            // UnityCommands.mergeAndUpdatePlayerData) — с ним запрос падал с
            // SQLSyntaxErrorException прямо тут. Числовой результат сам по
            // себе JSON_SET понимает без кастов — DECIMAL достаточно.
            final String sql = """
                UPDATE Countries
                SET CountryInfo = JSON_SET(
                    COALESCE(CountryInfo, JSON_OBJECT()),
                    '$.Money',
                    COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(CountryInfo,'$.Money')) AS DECIMAL(20,3)), 0) + ?
                )
                WHERE Name = ?
                """;
            try (Connection con = DBConnect()) {
                if (con == null) { logDb("addCountryMoney"); return; }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setDouble(1, delta);
                    ps.setString(2, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("addCountryMoney", t);
                return;
            }

            // мягко обновим кэш в main-потоке, чтобы не ждать TTL
            Bukkit.getScheduler().runTask(plugin, this::touchCacheNow);
        });
    }

    public boolean addCountryMoneySync(String countryName, double delta) {
        if (countryName == null || countryName.isBlank()) return false;
        if (delta == 0.0) return true;

        // см. комментарий в addCountryMoney() выше — CAST(x AS JSON) не
        // поддерживается MariaDB, роняло запрос с синтаксической ошибкой.
        final String sql = """
        UPDATE Countries
        SET CountryInfo = JSON_SET(
            COALESCE(CountryInfo, JSON_OBJECT()),
            '$.Money',
            COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(CountryInfo,'$.Money')) AS DECIMAL(20,3)), 0) + ?
        )
        WHERE Name = ?
        """;

        try (Connection con = DBConnect()) {
            if (con == null) { logDb("addCountryMoneySync"); return false; }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setDouble(1, delta);
                ps.setString(2, countryName);
                int updated = ps.executeUpdate();
                if (updated <= 0) return false;
            }
        } catch (Throwable t) {
            logDb("addCountryMoneySync", t);
            return false;
        }

        // кэш обновляем аккуратно: пометим “грязным”, чтобы подхватилось TTL-рефрешем
        Bukkit.getScheduler().runTask(plugin, this::touchCacheNow);
        return true;
    }

    /** Получить страну игрока или null. */
    public String getCountryOfPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        refreshCacheIfExpired();
        return playerToCountry.get(playerName.toLowerCase(Locale.ROOT));
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
        countryToTransferFeePct.remove(lower);
        playerToCountry.entrySet().removeIf(e -> e.getValue().equalsIgnoreCase(countryName));

        touchCacheNow();
    }

    /**
     * Гарантировать стартовый лимит банкоматов для страны.
     * Таблица atm_quota(country PK, quota INT) создаётся автоматически, если её нет.
     */
    public void ensureInitialAtmAllowance(String countryName, int initialCount) {
        if (countryName == null || countryName.isBlank()) return;
        if (initialCount <= 0) return;

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDb("ensureInitialAtmAllowance");
                return;
            }

            // === гарантия существования таблицы и колонок ===
            ensureAtmQuotaSchema(con);

            // === обычная логика UPSERT ===
            final String sql = """
                INSERT INTO atm_quota (country, quota)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE quota = GREATEST(quota, VALUES(quota))
                """;

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, countryName.toLowerCase(Locale.ROOT));
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

    /** Право роли игрока строить участки (PLOT) на территории своей страны + лимит участков на игрока этой роли. */
    public record ZoneBuildPermission(boolean allowed, int limit) {
        public static final ZoneBuildPermission DENIED = new ZoneBuildPermission(false, 0);
    }

    /** Право+лимит строительства участков для роли ИГРОКА В ЕГО СОБСТВЕННОЙ стране (не для произвольной страны). */
    public ZoneBuildPermission getPlayerZoneBuildPermission(String playerName) {
        if (playerName == null || playerName.isBlank()) return ZoneBuildPermission.DENIED;
        refreshCacheIfExpired();

        String countryName = playerToCountry.get(playerName.toLowerCase(Locale.ROOT));
        if (countryName == null || countryName.isBlank()) return ZoneBuildPermission.DENIED;

        Integer rid = playerToRoleId.get(playerName.toLowerCase(Locale.ROOT));
        if (rid == null) return ZoneBuildPermission.DENIED;

        Map<Integer, RoleInfo> roles = countryRoleMeta.get(countryName.toLowerCase(Locale.ROOT));
        if (roles == null) return ZoneBuildPermission.DENIED;

        RoleInfo ri = roles.get(rid);
        if (ri == null || !ri.buildZones()) return ZoneBuildPermission.DENIED;
        return new ZoneBuildPermission(true, ri.zoneLimit());
    }

    /* ===================== КЭШ СТРАН ===================== */

    /**
     * Принудительный СИНХРОННЫЙ (блокирующий) рефреш кэша, игнорируя TTL.
     * Только для редких одноразовых действий, где нельзя ждать асинхронного
     * обновления — например, разметка территории Государства сразу после
     * создания страны через сайт (страна только что появилась в БД, а
     * обычный refreshCacheIfExpired() на главном потоке вернул бы старый
     * кэш и запустил обновление в фоне, не дождавшись его).
     */
    /** True once a refresh has actually succeeded at least once — false right after boot until the first successful DB round-trip, and stays false if every attempt so far has failed. Callers that would otherwise treat "not found in cache" as authoritative (e.g. ZoneManager's orphan-country cleanup, which DELETES data on a negative) must check this first. */
    public boolean isCacheLoaded() {
        return cacheLoadedOnce;
    }

    public void forceRefreshBlocking() {
        refreshCacheNowBlocking_();
    }

    private void refreshCacheIfExpired() {
        Instant now = Instant.now();
        if (cacheLoadedOnce && Duration.between(lastRefresh, now).toMillis() < CACHE_TTL_MS) {
            return;
        }

        // не блокируем main-thread
        if (Bukkit.isPrimaryThread()) {
            if (refreshInFlight.compareAndSet(false, true)) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        refreshCacheNowBlocking_();
                    } finally {
                        refreshInFlight.set(false);
                    }
                });
            }
            return; // отдаем старые значения, обновление придёт чуть позже
        }

        // если мы уже в async — можно обновить прямо сейчас
        refreshCacheNowBlocking_();
    }

    private void refreshCacheNowBlocking_() {
        // временные мапы, которые мы соберём заново
        Map<String, String> newPlayerToCountry = new HashMap<>();
        Map<String, Integer> newPlayerToRoleId = new HashMap<>();
        Map<String, String> newCountryToLeader = new HashMap<>();
        Map<String, Map<Integer, RoleInfo>> newCountryRoleMeta = new HashMap<>();
        Map<String, Double> newCountryToTransferFeePct = new HashMap<>();

        Map<String, Double> newCountryMoney = new HashMap<>();
        Map<String, Double> newCountryWeeklyTaxDue = new HashMap<>();
        Map<String, String> newCountryLowerToDisplay = new HashMap<>();
        Map<String, Integer> newCountryLowerToId = new HashMap<>();
        Map<Integer, String> newCountryIdToDisplay = new HashMap<>();
        Map<String, Double> newCountryArea = new HashMap<>();
        Map<String, String> newCountryPlotCapMode = new HashMap<>();
        Map<String, Double> newCountryPlotCapValue = new HashMap<>();

        try (Connection con = DBConnect()) {
            if (con == null) {
                logDb("refreshCacheIfExpired");
                return;
            }

            String sql = "SELECT Id, Name, Players, Permissions, CountryInfo FROM Countries";
            try (PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    String countryName = rs.getString("Name");
                    String playersJson = rs.getString("Players");
                    String permsJson   = rs.getString("Permissions");

                    if (countryName == null || countryName.isBlank()) continue;

                    String countryLower = countryName.toLowerCase(Locale.ROOT);
                    newCountryLowerToDisplay.put(countryLower, countryName);
                    int countryId = rs.getInt("Id");
                    newCountryLowerToId.put(countryLower, countryId);
                    newCountryIdToDisplay.put(countryId, countryName);

                    // --- 1. распарсили роли этой страны
                    Map<Integer, RoleInfo> rolesMap = parseRoles(permsJson);

                    // anti-overwrite: если текущий JSON пустой, а ранее в этом refresh уже были роли — оставляем старое
                    if (rolesMap.isEmpty() && newCountryRoleMeta.containsKey(countryLower)
                            && newCountryRoleMeta.get(countryLower) != null
                            && !newCountryRoleMeta.get(countryLower).isEmpty()) {
                        rolesMap = newCountryRoleMeta.get(countryLower);
                    }

                    newCountryRoleMeta.put(countryLower, rolesMap);

                    Integer leaderRoleId = findLeaderRoleIdFromRoles(rolesMap); // <- теперь Integer
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

                        if (roleId != null && Objects.equals(roleId, leaderRoleId) && leaderFound == null) {
                            leaderFound = playerLower;
                        }
                    }

                    if (leaderFound != null && !leaderFound.isBlank()) {
                        newCountryToLeader.put(countryLower, leaderFound);
                    }

                    // --- 3. CountryInfo: TransferFee, Money, WeeklyTaxDue
                    String countryInfo = rs.getString("CountryInfo");
                    try {
                        if (countryInfo != null && !countryInfo.isBlank()) {
                            JsonElement ciRoot = JsonParser.parseString(countryInfo);
                            if (ciRoot.isJsonObject()) {
                                JsonObject ci = ciRoot.getAsJsonObject();

                                // TransferFee
                                if (ci.has("TransferFee") && !ci.get("TransferFee").isJsonNull()) {
                                    Double tf = null;
                                    try {
                                        JsonElement tfEl = ci.get("TransferFee");
                                        if (tfEl.isJsonPrimitive() && tfEl.getAsJsonPrimitive().isNumber()) {
                                            tf = tfEl.getAsDouble();
                                        } else if (tfEl.isJsonPrimitive() && tfEl.getAsJsonPrimitive().isString()) {
                                            tf = Double.parseDouble(tfEl.getAsString().trim());
                                        }
                                    } catch (Exception ignored) {}
                                    if (tf != null) newCountryToTransferFeePct.put(countryLower, tf);
                                }

                                // Money
                                if (ci.has("Money") && !ci.get("Money").isJsonNull()) {
                                    Double moneyVal = null;
                                    try {
                                        JsonElement mEl = ci.get("Money");
                                        if (mEl.isJsonPrimitive() && mEl.getAsJsonPrimitive().isNumber()) {
                                            moneyVal = mEl.getAsDouble();
                                        } else if (mEl.isJsonPrimitive() && mEl.getAsJsonPrimitive().isString()) {
                                            moneyVal = Double.parseDouble(mEl.getAsString().trim());
                                        }
                                    } catch (Exception ignored) {}
                                    if (moneyVal != null) newCountryMoney.put(countryLower, moneyVal);
                                }

                                // WeeklyTaxDue
                                if (ci.has("WeeklyTaxDue") && !ci.get("WeeklyTaxDue").isJsonNull()) {
                                    Double taxVal = null;
                                    try {
                                        JsonElement tEl = ci.get("WeeklyTaxDue");
                                        if (tEl.isJsonPrimitive() && tEl.getAsJsonPrimitive().isNumber()) {
                                            taxVal = tEl.getAsDouble();
                                        } else if (tEl.isJsonPrimitive() && tEl.getAsJsonPrimitive().isString()) {
                                            taxVal = Double.parseDouble(tEl.getAsString().trim());
                                        }
                                    } catch (Exception ignored) {}
                                    if (taxVal != null) newCountryWeeklyTaxDue.put(countryLower, taxVal);
                                }

                                // Area (площадь территории страны, блоков²)
                                if (ci.has("Area") && !ci.get("Area").isJsonNull()) {
                                    Double areaVal = null;
                                    try {
                                        JsonElement aEl = ci.get("Area");
                                        if (aEl.isJsonPrimitive() && aEl.getAsJsonPrimitive().isNumber()) {
                                            areaVal = aEl.getAsDouble();
                                        } else if (aEl.isJsonPrimitive() && aEl.getAsJsonPrimitive().isString()) {
                                            areaVal = Double.parseDouble(aEl.getAsString().trim());
                                        }
                                    } catch (Exception ignored) {}
                                    if (areaVal != null) newCountryArea.put(countryLower, areaVal);
                                }

                                // PlotAreaCapMode/PlotAreaCapValue — общестрановой лимит площади под
                                // участки (PLOT), настраивается лидером на country.html
                                if (ci.has("PlotAreaCapMode") && !ci.get("PlotAreaCapMode").isJsonNull()) {
                                    try {
                                        String modeVal = ci.get("PlotAreaCapMode").getAsString().trim().toLowerCase(Locale.ROOT);
                                        if (modeVal.equals("percent") || modeVal.equals("blocks") || modeVal.equals("none")) {
                                            newCountryPlotCapMode.put(countryLower, modeVal);
                                        }
                                    } catch (Exception ignored) {}
                                }
                                if (ci.has("PlotAreaCapValue") && !ci.get("PlotAreaCapValue").isJsonNull()) {
                                    Double capVal = null;
                                    try {
                                        JsonElement cEl = ci.get("PlotAreaCapValue");
                                        if (cEl.isJsonPrimitive() && cEl.getAsJsonPrimitive().isNumber()) {
                                            capVal = cEl.getAsDouble();
                                        } else if (cEl.isJsonPrimitive() && cEl.getAsJsonPrimitive().isString()) {
                                            capVal = Double.parseDouble(cEl.getAsString().trim());
                                        }
                                    } catch (Exception ignored) {}
                                    if (capVal != null && capVal >= 0) newCountryPlotCapValue.put(countryLower, capVal);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
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

        countryToTransferFeePct.clear();
        countryToTransferFeePct.putAll(newCountryToTransferFeePct);

        countryMoney.clear();
        countryMoney.putAll(newCountryMoney);

        countryLowerToDisplay.clear();
        countryLowerToDisplay.putAll(newCountryLowerToDisplay);

        countryLowerToId.clear();
        countryLowerToId.putAll(newCountryLowerToId);

        countryIdToDisplay.clear();
        countryIdToDisplay.putAll(newCountryIdToDisplay);

        countryWeeklyTaxDue.clear();
        countryWeeklyTaxDue.putAll(newCountryWeeklyTaxDue);

        countryArea.clear();
        countryArea.putAll(newCountryArea);

        countryPlotCapMode.clear();
        countryPlotCapMode.putAll(newCountryPlotCapMode);

        countryPlotCapValue.clear();
        countryPlotCapValue.putAll(newCountryPlotCapValue);

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

    // где-нибудь рядом с полями класса:
    private static final Set<String> WARNED_EMPTY_PERMS = ConcurrentHashMap.newKeySet();

    // замени метод parseRoles целиком:
    private static Map<Integer, RoleInfo> parseRoles(String permsJsonRaw) {
        Map<Integer, RoleInfo> out = new HashMap<>();
        if (permsJsonRaw == null) return out;

        String trimmed = permsJsonRaw.trim();
        // Нормальные «пустые» варианты — не логируем
        if (trimmed.isEmpty() || "{}".equals(trimmed) || "[]".equals(trimmed)) return out;

        try {
            JsonElement root = JsonParser.parseString(permsJsonRaw);
            if (root.isJsonPrimitive() && root.getAsJsonPrimitive().isString()) {
                String inner = root.getAsString();
                if (inner != null) {
                    String innerTrim = inner.trim();
                    if (innerTrim.isEmpty() || "{}".equals(innerTrim) || "[]".equals(innerTrim)) {
                        return out; // тоже валидная пустота — без варна
                    }
                    root = JsonParser.parseString(inner);
                }
            }
            fillRolesFromRoot(out, root);

            // Если реально не смогли извлечь ничего — варним только один раз на такой payload
            if (out.isEmpty()) {
                String key = Integer.toHexString(trimmed.hashCode());
                if (WARNED_EMPTY_PERMS.add(key)) {
                    Bukkit.getLogger().warning("[CountryRegistryJdbc] parseRoles: empty after parse, raw="
                            + (trimmed.length() > 128 ? trimmed.substring(0, 128) + "..." : trimmed));
                }
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
        Integer id = getInt(o);
        String name = getString(o, "Name");
        String prefix = getString(o, "Prefix");

        int idx = 0;
        try {
            if (o.has("Index") && !o.get("Index").isJsonNull()) {
                JsonElement el = o.get("Index");
                if (el.isJsonPrimitive()) {
                    if (el.getAsJsonPrimitive().isNumber()) idx = el.getAsInt();
                    else if (el.getAsJsonPrimitive().isString()) idx = Integer.parseInt(el.getAsString().trim());
                }
            }
        } catch (Exception ignored) {}

        // Permissions.buildZones (bool, вложенный объект, как invite/players/settings/upgrades/permissions)
        // — право строить личные участки (PLOT) на территории страны
        boolean buildZones = false;
        try {
            if (o.has("Permissions") && o.get("Permissions").isJsonObject()) {
                JsonObject perms = o.getAsJsonObject("Permissions");
                if (perms.has("buildZones") && !perms.get("buildZones").isJsonNull()) {
                    JsonElement el = perms.get("buildZones");
                    if (el.isJsonPrimitive()) {
                        var prim = el.getAsJsonPrimitive();
                        buildZones = prim.isBoolean() ? prim.getAsBoolean() : Boolean.parseBoolean(prim.getAsString());
                    }
                }
            }
        } catch (Exception ignored) {}

        // ZoneLimit — верхнеуровневое поле роли (не внутри Permissions, т.к. это число, а не флаг)
        // — максимум участков (PLOT) на игрока этой роли внутри территории страны
        int zoneLimit = 0;
        try {
            if (o.has("ZoneLimit") && !o.get("ZoneLimit").isJsonNull()) {
                JsonElement el = o.get("ZoneLimit");
                if (el.isJsonPrimitive()) {
                    if (el.getAsJsonPrimitive().isNumber()) zoneLimit = el.getAsInt();
                    else if (el.getAsJsonPrimitive().isString()) zoneLimit = Integer.parseInt(el.getAsString().trim());
                }
            }
        } catch (Exception ignored) {}

        if (id == null) return null;
        return new RoleInfo(id, name, prefix, idx, buildZones, zoneLimit);
    }

    public int getPlayerRoleIndex(String playerName) {
        if (playerName == null || playerName.isBlank()) return 0;
        refreshCacheIfExpired();

        String pLower = playerName.toLowerCase(Locale.ROOT);
        String country = playerToCountry.get(pLower);
        if (country == null) return 0;

        Integer rid = playerToRoleId.get(pLower);
        if (rid == null) return 0;

        Map<Integer, RoleInfo> roles = countryRoleMeta.get(country.toLowerCase(Locale.ROOT));
        if (roles == null) return 0;

        RoleInfo ri = roles.get(rid);
        return (ri == null) ? 0 : ri.index();
    }

    private static Integer findLeaderRoleIdFromRoles(Map<Integer, RoleInfo> rolesMap) {
        if (rolesMap == null || rolesMap.isEmpty()) return null;
        for (RoleInfo ri : rolesMap.values()) {
            if (ri.name != null && ri.name.equalsIgnoreCase("Leader")) {
                return ri.id; // может быть 0 — это ок!
            }
        }
        return null;
    }

    private static Integer getInt(JsonObject o) {
        if (o == null) return null;
        if (!o.has("ID") || o.get("ID").isJsonNull()) return null;
        try {
            JsonElement el = o.get("ID");
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

    /** Обновить только площадь в CountryInfo, деньги не трогаем.
     *  Если Money отсутствует — создадим его = 0 (идемпотентно).
     */
    public void setCountryAreaPreserveMoney(String countryName, double area) {
        if (countryName == null || countryName.isBlank()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
            UPDATE Countries
            SET CountryInfo = JSON_SET(
                COALESCE(CountryInfo, JSON_OBJECT()),
                '$.Area',  ?,
                '$.Money', COALESCE(JSON_EXTRACT(CountryInfo,'$.Money'), 0)
            )
            WHERE Name = ?
            """;
            try (Connection con = DBConnect()) {
                if (con == null) { logDb("setCountryAreaPreserveMoney"); return; }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setDouble(1, area);
                    ps.setString(2, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("setCountryAreaPreserveMoney", t);
            }
        });
    }

    /** Добавить к площади страны deltaArea (например, при создании колонии). Инициализирует поле, если его не было. */
    public void addCountryArea(String countryName, double deltaArea) {
        if (countryName == null || countryName.isBlank() || deltaArea == 0.0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                UPDATE Countries
                SET CountryInfo = JSON_SET(
                    COALESCE(CountryInfo, JSON_OBJECT()),
                    '$.Area',
                    COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(CountryInfo,'$.Area')) AS DECIMAL(20,3)), 0) + ?,
                    '$.Money',
                    COALESCE(JSON_EXTRACT(CountryInfo,'$.Money'), 0)
                )
                WHERE Name = ?
                """;
            try (Connection con = DBConnect()) {
                if (con == null) { logDb("addCountryArea"); return; }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setDouble(1, deltaArea);
                    ps.setString(2, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("addCountryArea", t);
            }
        });
    }

    /**
     * Прибавить delta к полю CountryInfo.Taxes (может быть отрицательным).
     * Работает атомарно на уровне БД.
     *
     * Пример JSON после нескольких вызовов:
     *   {
     *     "Money": 123.45,
     *     "WeeklyTaxDue": 456.78,
     *     "Taxes": 890.12
     *   }
     */
    public void addCountryTaxes(String countryName, double delta) {
        if (countryName == null || countryName.isBlank()) return;
        if (delta == 0.0) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                UPDATE Countries
                SET CountryInfo = JSON_SET(
                    COALESCE(CountryInfo, JSON_OBJECT()),
                    '$.Taxes',
                    COALESCE(
                        CAST(JSON_UNQUOTE(JSON_EXTRACT(CountryInfo,'$.Taxes')) AS DECIMAL(20,3)),
                        0
                    ) + ?
                )
                WHERE Name = ?
                """;
            try (Connection con = DBConnect()) {
                if (con == null) {
                    logDb("addCountryTaxes");
                    return;
                }
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setDouble(1, delta);
                    ps.setString(2, countryName);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                logDb("addCountryTaxes", t);
            }
        });
    }

    /** Проверяет и создаёт таблицу atm_quota + недостающие колонки. */
    private void ensureAtmQuotaSchema(Connection c) throws SQLException {
        // чтобы не выполнять проверку тысячи раз
        if (ATM_SCHEMA_CHECKED.get()) return;

        // 1. создаём таблицу если отсутствует
        try (Statement st = c.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS `atm_quota` (" +
                            " `country` VARCHAR(64) NOT NULL," +
                            " `quota`   INT NOT NULL DEFAULT 0," +
                            " `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                            " PRIMARY KEY (`country`)" +
                            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
            );
        }

        // 2. проверяем существующие колонки
        Set<String> cols = new HashSet<>();
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(null, null, "atm_quota", "%")) {
            while (rs.next()) {
                cols.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }

        try (Statement st = c.createStatement()) {
            if (!cols.contains("country")) {
                st.executeUpdate("ALTER TABLE `atm_quota` ADD COLUMN `country` VARCHAR(64) NOT NULL DEFAULT ''");
            }
            if (!cols.contains("quota")) {
                st.executeUpdate("ALTER TABLE `atm_quota` ADD COLUMN `quota` INT NOT NULL DEFAULT 0");
            }
            if (!cols.contains("updated_at")) {
                st.executeUpdate(
                        "ALTER TABLE `atm_quota` ADD COLUMN `updated_at` TIMESTAMP " +
                                "NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"
                );
            }
        }

        ATM_SCHEMA_CHECKED.set(true);
    }

    public boolean countryExistsCached(String countryName) {
        if (countryName == null || countryName.isBlank()) return false;
        refreshCacheIfExpired();
        String key = countryName.toLowerCase(Locale.ROOT);
        // достаточно проверять по любому кэшу страны
        return countryMoney.containsKey(key) || countryToLeader.containsKey(key);
    }

    /* ===================== LOG ===================== */

    private static void logDb(String where, Throwable t) {
        Bukkit.getLogger().severe("[CountryRegistryJdbc] DB error in " + where + ": " + t);
    }

    private static void logDb(String where) {
        Bukkit.getLogger().severe("[CountryRegistryJdbc] " + where + ": " + "DBConnect()==null");
    }
}
