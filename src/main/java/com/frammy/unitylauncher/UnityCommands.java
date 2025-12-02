package com.frammy.unitylauncher;

import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * Обновлённый UnityCommands:
 * - Источник истины по странам/ролям — CountryRegistryJdbc.
 * - Баланс игрока: Users.GeneralData.money (JSON).
 * - Баланс страны: Countries.CountryInfo.Money (JSON).
 * - Все SQL через PreparedStatement.
 * - Легаси-команды переведены в безопасные заглушки (можно удалить позже).
 */
public class UnityCommands {
    private static UnityCommands instance;
    public static UnityCommands getInstance() {
        if (instance == null) instance = new UnityCommands();
        return instance;
    }

    /** Проекцию используем минимальную: страна + деньги игрока. */
    public static class PlayerData {
        public String countryName;
        public double money;
    }

    private CountryRegistryJdbc registry() {
        UnityLauncher ul = UnityLauncher.getInstance();
        return (ul != null) ? ul.countryRegistryJdbc : null;
    }

    /* ===================== КЭШ Users.* JSON (4 колонки) ===================== */

    private static final long PLAYER_CACHE_TTL_MS = 60_000; // 60 сек
    private final ConcurrentHashMap<String, CachedPlayerRow> playerCache = new ConcurrentHashMap<>();

    private record CachedPlayerRow(JsonObject customization,
                                   JsonObject social,
                                   JsonObject general,
                                   JsonObject stats,
                                   long loadedAt) {
        boolean isExpired(long nowMs) { return nowMs - loadedAt > PLAYER_CACHE_TTL_MS; }
    }

    private static JsonObject safeParseObject(String json) {
        try {
            if (json == null || json.isEmpty()) return new JsonObject();
            JsonElement el = JsonParser.parseString(json);
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private CachedPlayerRow loadPlayerRowFromDB(String playerName) throws Exception {
        try (Connection con = DBConnect()) {
            if (con == null) return null;
            String sql = """
                    SELECT CustomizationData, SocialData, GeneralData, StatsData
                    FROM Users WHERE Name = ? LIMIT 1
                    """;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, playerName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    JsonObject cust = safeParseObject(rs.getString("CustomizationData"));
                    JsonObject soc  = safeParseObject(rs.getString("SocialData"));
                    JsonObject gen  = safeParseObject(rs.getString("GeneralData"));
                    JsonObject st   = safeParseObject(rs.getString("StatsData"));
                    return new CachedPlayerRow(cust, soc, gen, st, System.currentTimeMillis());
                }
            }
        }
    }

    private CachedPlayerRow getOrLoadCachedPlayer(String playerName) {
        long now = System.currentTimeMillis();
        CachedPlayerRow row = playerCache.get(playerName);
        if (row != null && !row.isExpired(now)) return row;
        try {
            CachedPlayerRow fresh = loadPlayerRowFromDB(playerName);
            if (fresh != null) {
                playerCache.put(playerName, fresh);
                return fresh;
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] loadPlayerRowFromDB: " + e);
        }
        return row; // возможно протухший, но лучше чем ничего
    }

    private void upsertCacheAfterUpdate(String playerName, String column, JsonObject newWholeColumn) {
        long now = System.currentTimeMillis();
        CachedPlayerRow prev = playerCache.get(playerName);
        if (prev == null) {
            JsonObject cust = new JsonObject(), soc = new JsonObject(), gen = new JsonObject(), st = new JsonObject();
            switch (column) {
                case "CustomizationData" -> cust = newWholeColumn;
                case "SocialData"        -> soc  = newWholeColumn;
                case "GeneralData"       -> gen  = newWholeColumn;
                case "StatsData"         -> st   = newWholeColumn;
            }
            playerCache.put(playerName, new CachedPlayerRow(cust, soc, gen, st, now));
            return;
        }
        JsonObject cust = prev.customization.deepCopy();
        JsonObject soc  = prev.social.deepCopy();
        JsonObject gen  = prev.general.deepCopy();
        JsonObject st   = prev.stats.deepCopy();
        switch (column) {
            case "CustomizationData" -> cust = newWholeColumn;
            case "SocialData"        -> soc  = newWholeColumn;
            case "GeneralData"       -> gen  = newWholeColumn;
            case "StatsData"         -> st   = newWholeColumn;
        }
        playerCache.put(playerName, new CachedPlayerRow(cust, soc, gen, st, now));
    }

    /* ===================== Публичные утилиты JSON Users ===================== */

    public void mergeAndUpdatePlayerData(String playerName, String column, Map<String, Object> updates) {
        if (playerName == null || playerName.isBlank()) return;
        if (updates == null || updates.isEmpty()) return;

        // Разрешаем только эти 4 JSON-колонки:
        final java.util.List<String> valid = java.util.List.of("CustomizationData","SocialData","GeneralData","StatsData");
        if (!valid.contains(column)) {
            Bukkit.getLogger().warning("[UnityCommands] mergeAndUpdatePlayerData: bad column " + column);
            return;
        }

        try (Connection con = DBConnect()) {
            if (con == null) return;

            // 1) Берём текущий JSON: сначала из кэша (если не просрочен), иначе из БД
            JsonObject current;
            CachedPlayerRow cached = playerCache.get(playerName);
            if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
                current = switch (column) {
                    case "CustomizationData" -> cached.customization.deepCopy();
                    case "SocialData"        -> cached.social.deepCopy();
                    case "GeneralData"       -> cached.general.deepCopy();
                    case "StatsData"         -> cached.stats.deepCopy();
                    default -> new JsonObject();
                };
            } else {
                String select = "SELECT " + column + " FROM Users WHERE Name = ? LIMIT 1;";
                try (PreparedStatement ps = con.prepareStatement(select)) {
                    ps.setString(1, playerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        current = rs.next() ? safeParseObject(rs.getString(1)) : new JsonObject();
                    }
                }
            }

            // 2) Накатываем патч
            Gson gson = new Gson();
            for (var e : updates.entrySet()) {
                String key = e.getKey();
                Object val = e.getValue();
                if (val == null) {
                    // семантика "удалить ключ", если передали null
                    current.remove(key);
                } else {
                    current.add(key, gson.toJsonTree(val));
                }
            }

            // 3) Обновляем БД
            String update = "UPDATE Users SET " + column + " = ? WHERE Name = ?;";
            try (PreparedStatement ps = con.prepareStatement(update)) {
                ps.setString(1, current.toString());
                ps.setString(2, playerName);
                ps.executeUpdate();
            }

            // 4) Освежаем кэш
            upsertCacheAfterUpdate(playerName, column, current);

        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] mergeAndUpdatePlayerData failed: " + e);
        }
    }

    /** Асинхронно достаёт PlayerData (countryName из SocialData, money из GeneralData). */
    public void getPlayerInfo(String playerName, Consumer<PlayerData> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(UnityLauncher.getInstance(), () -> {
            try {
                CachedPlayerRow row = getOrLoadCachedPlayer(playerName);
                if (row == null) { callback.accept(null); return; }
                PlayerData pd = new PlayerData();
                // money
                if (row.general.has("money") && row.general.get("money").isJsonPrimitive()) {
                    try { pd.money = row.general.get("money").getAsDouble(); } catch (Exception ignored) {}
                }
                // countryName — сначала SocialData, если пусто — спросим у CountryRegistryJdbc
                String c = null;
                if (row.social.has("countryName")) c = safeGetString(row.social.get("countryName"));
                CountryRegistryJdbc reg = registry();
                if (c == null && reg != null) c = reg.getCountryOfPlayer(playerName);
                pd.countryName = c;
                callback.accept(pd);
            } catch (Exception e) {
                e.printStackTrace();
                callback.accept(null);
            }
        });
    }

    private static String safeGetString(JsonElement el) {
        try { return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null; }
        catch (Exception ignored) { return null; }
    }

    /* ===================== Команды (новая логика) ===================== */

    /**
     * /bal — читает JSON-баланс и печатает его.
     */
    public void getMoney(@NotNull CommandSender sender) {
        double bal = 0.0;
        try (Connection con = DBConnect()) {
            if (con == null) { sender.sendMessage(ChatColor.RED + "База недоступна."); return; }
            String sql = "SELECT JSON_EXTRACT(GeneralData,'$.money') AS m FROM Users WHERE Name=? LIMIT 1";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, sender.getName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String s = rs.getString("m");
                        Double v = parseJsonNumber(s);
                        if (v != null) bal = v;
                    }
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] getMoney: " + e);
        }
        sender.sendMessage("Ваш баланс: " + ChatColor.GREEN + String.format("%.2f", bal) + ChatColor.RESET + "Ⓕ");
    }

    /** /country — по новой логике: страна из CountryRegistryJdbc, лидер оттуда, казна из JSON. */
    public void getCountry(@NotNull CommandSender sender) {
        String me = sender.getName();
        CountryRegistryJdbc reg = registry();
        if (reg == null) {
            sender.sendMessage(ChatColor.RED + "Система стран недоступна.");
            return;
        }

        String country = reg.getCountryOfPlayer(me);
        if (country == null || country.isBlank()) {
            sender.sendMessage(ChatColor.RED + "Вы не состоите ни в одной стране.");
            return;
        }

        String leader = reg.getLeaderOfCountry(country);
        String leaderDisplay = "—";
        if (leader != null && !leader.isBlank()) {
            // Попробуем достать нормальный ник с правильным регистром
            var op = Bukkit.getOfflinePlayer(leader);
            if (op.getName() != null) {
                leaderDisplay = op.getName();
            } else {
                leaderDisplay = leader;
            }
        }

        Double money = 0.0;
        try (Connection con = DBConnect()) {
            if (con != null) {
                String sql = "SELECT JSON_EXTRACT(CountryInfo,'$.Money') AS m FROM Countries WHERE Name=? LIMIT 1";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, country);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) money = parseJsonNumber(rs.getString("m"));
                    }
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] getCountry: " + e);
        }

        sender.sendMessage(ChatColor.DARK_GREEN + "=== Информация о государстве ===");
        sender.sendMessage("Государство: " + ChatColor.GOLD + country + ChatColor.RESET);
        sender.sendMessage("Лидер: " + ChatColor.AQUA + leaderDisplay);
        sender.sendMessage("Казна: " + ChatColor.GREEN + String.format("%.2f", (money != null ? money : 0.0)) + ChatColor.RESET + "Ⓕ");
    }

    // ==== AUTH: /ul login <pass>  и  /ul reg <pass> ====

    // Анти-брутфорс по UUID+IP
    private static final int MAX_LOGIN_FAILS = 5;
    private static final long LOGIN_BLOCK_MS = 30_000L; // 30 секунд
    private static final ConcurrentHashMap<String, LoginFailState> LOGIN_FAILS = new ConcurrentHashMap<>();

    private static final class LoginFailState {
        int attempts;
        long blockedUntilMs;
    }

    /** Общая проверка качества пароля по нашим правилам. */
    private static boolean validatePasswordPolicy(String pass, CommandSender sender) {
        String what = "Новый пароль";
        if (pass == null || pass.isBlank()) {
            sender.sendMessage(ChatColor.RED + what + " не может быть пустым.");
            return false;
        }

        int len = pass.length();
        if (len < 8 || len > 128) {
            sender.sendMessage(ChatColor.RED + what + " должен быть от 8 до 128 символов.");
            return false;
        }

        byte[] bytes = pass.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512) {
            sender.sendMessage(ChatColor.RED + what + " слишком длинный по байтам (UTF-8 > 512). Укороти его.");
            return false;
        }

        for (int i = 0; i < pass.length(); i++) {
            char c = pass.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                sender.sendMessage(ChatColor.RED + what + " не должен содержать пробелы или управляющие символы.");
                return false;
            }
        }
        return true;
    }

    public void login(@NotNull CommandSender sender, String pass) {
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
            return;
        }
        if (pass == null || pass.isBlank()) {
            sender.sendMessage(ChatColor.YELLOW + "Использование: /ul login <пароль>");
            return;
        }

        var auth = UnityLauncher.getInstance().getAuthService();
        var listener = UnityLauncher.getInstance().getAuthListener();
        if (auth == null) {
            sender.sendMessage(ChatColor.RED + "Auth-сервис недоступен.");
            return;
        }

        // уже авторизован?
        if (listener != null && listener.isAuthenticated(p)) {
            sender.sendMessage(ChatColor.GREEN + "Ты уже вошёл.");
            return;
        }

        // rate-limit по UUID+IP
        String ip = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : "unknown";
        String key = p.getUniqueId() + "|" + ip;
        long now = System.currentTimeMillis();
        LoginFailState state = LOGIN_FAILS.get(key);
        if (state != null && now < state.blockedUntilMs) {
            long leftSec = (state.blockedUntilMs - now + 999) / 1000;
            sender.sendMessage(ChatColor.RED + "Слишком много неудачных попыток. Попробуй через " +
                    ChatColor.YELLOW + leftSec + ChatColor.RED + " сек.");
            return;
        }

        // зарегистрирован ли игрок?
        boolean hasPass = auth.isRegisteredFast(p.getName());
        if (!hasPass) {
            sender.sendMessage(ChatColor.RED + "Ты не зарегистрирован. Используй /ul reg <пароль>.");
            return;
        }

        // проверяем пароль
        boolean ok = auth.checkPassword(p.getName(), pass.toCharArray());
        if (!ok) {
            // учёт ошибки + блокировки
            LoginFailState s = (state != null) ? state : new LoginFailState();
            s.attempts++;
            if (s.attempts >= MAX_LOGIN_FAILS) {
                s.blockedUntilMs = now + LOGIN_BLOCK_MS;
                s.attempts = 0;
                sender.sendMessage(ChatColor.RED + "Неверный пароль. Логин временно заблокирован на " +
                        (LOGIN_BLOCK_MS / 1000) + " секунд.");
            } else {
                sender.sendMessage(ChatColor.RED + "Неверный пароль. Осталось попыток: " +
                        ChatColor.YELLOW + (MAX_LOGIN_FAILS - s.attempts));
            }
            LOGIN_FAILS.put(key, s);
            return;
        }

        // успех — снимаем блокировки
        LOGIN_FAILS.remove(key);

        // отметим сессию
        auth.markSession(p.getName(), ip);
        auth.updateCacheAfterSession(p.getName(), System.currentTimeMillis(), ip);

        // снимем все блокировки/боссбар
        if (listener != null) listener.completeLogin(p);
    }

    public void register(@NotNull CommandSender sender, String pass) {
        if (!(sender instanceof org.bukkit.entity.Player p)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
            return;
        }
        if (!validatePasswordPolicy(pass, sender)) {
            sender.sendMessage(ChatColor.GRAY + "Использование: /ul reg <пароль>");
            return;
        }

        var auth = UnityLauncher.getInstance().getAuthService();
        var listener = UnityLauncher.getInstance().getAuthListener();
        if (auth == null) {
            sender.sendMessage(ChatColor.RED + "Auth-сервис недоступен.");
            return;
        }

        // уже есть пароль?
        if (auth.isRegisteredFast(p.getName())) {
            sender.sendMessage(ChatColor.RED + "Ты уже зарегистрирован. Используй /ul login <пароль>.");
            return;
        }

        // пишем пароль в БД
        boolean saved = auth.setNewPassword(p.getName(), pass.toCharArray());
        if (!saved) {
            sender.sendMessage(ChatColor.RED + "Ошибка при сохранении пароля.");
            return;
        }
        // обновим кэш после записи пароля
        var rec = auth.getCached(p.getName().toLowerCase(java.util.Locale.ROOT));
        if (rec == null || rec.phcHash() == null) {
            auth.updateCacheAfterPasswordSet(p.getName(), "<set>");
        }

        // сразу активируем сессию
        String ip = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : "unknown";
        auth.markSession(p.getName(), ip);
        auth.updateCacheAfterSession(p.getName(), System.currentTimeMillis(), ip);

        if (listener != null) listener.completeRegister(p);
    }

    /* ===================== Заглушки (временно отключено / легаси) ===================== */

    public void getNotifications(@NotNull CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Система уведомлений временно отключена.");
    }

    public void toggleNotifications(@NotNull CommandSender sender, String toggle) {
        sender.sendMessage(ChatColor.YELLOW + "Система уведомлений временно отключена.");
    }

    public void createOrder(String sellerName, String customerName, String spriteName,
                            Double price, Integer quantity, org.bukkit.Location location,
                            Map<org.bukkit.enchantments.Enchantment, Integer> enchantments) {
        Bukkit.getLogger().info("[UnityCommands] Orders временно отключены.");
    }

    public void dayDeal(@NotNull CommandSender sender, String code) {
        sender.sendMessage(ChatColor.YELLOW + "Ежедневные задания временно отключены.");
    }

    public void getGroups(@NotNull CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Группы страны управляются через LuckPerms/JSON. Команда устарела.");
    }

    public void setGroup(@NotNull CommandSender sender, String nickname, String group) {
        sender.sendMessage(ChatColor.YELLOW + "Назначение групп через эту команду отключено. Используйте актуальные механики.");
    }

    public void setPrefix(@NotNull CommandSender sender, String group, String prefix) {
        sender.sendMessage(ChatColor.YELLOW + "Префиксы управляются через текущую систему ролей. Команда устарела.");
    }

    public void changePass(@NotNull CommandSender sender, String oldPass, String newPass) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Только для игроков.");
            return;
        }
        var auth = UnityLauncher.getInstance().getAuthService();
        if (auth == null) {
            sender.sendMessage(ChatColor.RED + "Auth недоступен.");
            return;
        }

        if (oldPass == null || oldPass.isBlank()) {
            sender.sendMessage(ChatColor.RED + "Не указан старый пароль. Используй: /ul change <старый_пароль> <новый_пароль>");
            return;
        }

        if (!validatePasswordPolicy(newPass, sender)) {
            sender.sendMessage(ChatColor.GRAY + "Использование: /ul change <старый_пароль> <новый_пароль>");
            return;
        }

        if (oldPass.equals(newPass)) {
            sender.sendMessage(ChatColor.RED + "Новый пароль совпадает со старым. Смысла менять нет.");
            return;
        }

        boolean ok = auth.checkPassword(p.getName(), oldPass.toCharArray());
        if (!ok) {
            sender.sendMessage(ChatColor.RED + "Старый пароль неверен.");
            return;
        }
        boolean upd = auth.setNewPassword(p.getName(), newPass.toCharArray());
        if (!upd) {
            sender.sendMessage(ChatColor.RED + "Не удалось сохранить новый пароль.");
            return;
        }
        String ip = (p.getAddress()!=null) ? p.getAddress().getAddress().getHostAddress() : "unknown";
        auth.markSession(p.getName(), ip);
        sender.sendMessage(ChatColor.GREEN + "Пароль изменён.");
    }

    public void setFrame(@NotNull CommandSender sender, String nickname, String frameID) {
        sender.sendMessage(ChatColor.YELLOW + "Frames не используются.");
    }

    public void getTop(@NotNull CommandSender sender, String category) {
        sender.sendMessage(ChatColor.YELLOW + "Таблица лидеров временно отключена.");
    }

    /* ===================== Внутренние JSON/SQL утилиты ===================== */

    private static class UserJsonRow { Double money; }
    private static UserJsonRow lockAndReadUserMoney(Connection con, String player) throws Exception {
        String sql = "SELECT JSON_EXTRACT(GeneralData,'$.money') AS m FROM Users WHERE Name=? FOR UPDATE";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                UserJsonRow row = new UserJsonRow();
                row.money = parseJsonNumber(rs.getString("m"));
                return row;
            }
        }
    }

    private static void writeUserMoney(Connection con, String player, double newMoney) throws Exception {
        String up = "UPDATE Users SET GeneralData = JSON_SET(GeneralData, '$.money', ?) WHERE Name=?";
        try (PreparedStatement ps = con.prepareStatement(up)) {
            ps.setDouble(1, newMoney);
            ps.setString(2, player);
            ps.executeUpdate();
        }
    }

    private static void writeCountryMoney(Connection con, String countryName, double newMoney) throws Exception {
        String up = "UPDATE Countries SET CountryInfo = JSON_SET(CountryInfo, '$.Money', ?) WHERE Name=?";
        try (PreparedStatement ps = con.prepareStatement(up)) {
            ps.setDouble(1, newMoney);
            ps.setString(2, countryName);
            ps.executeUpdate();
        }
    }

    private static Double lockAndReadCountryMoney(Connection con, String countryName) throws Exception {
        String sql = "SELECT JSON_EXTRACT(CountryInfo,'$.Money') AS m FROM Countries WHERE Name=? FOR UPDATE";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, countryName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return parseJsonNumber(rs.getString("m"));
            }
        }
    }

    private static Double parseJsonNumber(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        if ("null".equalsIgnoreCase(s) || s.isEmpty()) return null;
        try { return Double.valueOf(s); } catch (Exception ignore) { return null; }
    }

    private static String parseJsonString(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        if ("null".equalsIgnoreCase(s) || s.isEmpty()) return null;
        return s;
    }

    /**
     * Площадь многоугольника в плоскости XZ (в блоках^2).
     * - Использует координаты X и Z из Bukkit Location, Y игнорируется.
     * - Если последняя точка совпадает с первой — дублированную последнюю убираем.
     * - Удаляем подряд идущие дубликаты точек.
     * - Возвращает абсолютное значение площади (ориентация контура не важна).
     */
    public static double calculateSurfaceArea(java.util.List<org.bukkit.Location> points) {
        if (points == null) return 0.0;

        // 1) скопируем и почистим «на лету»
        java.util.ArrayList<org.bukkit.Location> pts = new java.util.ArrayList<>();
        org.bukkit.Location prev = null;
        for (org.bukkit.Location l : points) {
            if (l == null) continue;
            if (prev != null && l.getBlockX() == prev.getBlockX() && l.getBlockZ() == prev.getBlockZ()) {
                // подряд дубликат — пропускаем
                continue;
            }
            pts.add(l);
            prev = l;
        }
        // если последняя точка замыкает контур — удалим её как дубликат первой
        if (pts.size() >= 2) {
            org.bukkit.Location first = pts.getFirst();
            org.bukkit.Location last  = pts.getLast();
            if (first.getBlockX() == last.getBlockX() && first.getBlockZ() == last.getBlockZ()) {
                pts.removeLast();
            }
        }

        if (pts.size() < 3) return 0.0;

        // 2) шнурковая формула в XZ
        double sum = 0.0;
        final int n = pts.size();
        for (int i = 0; i < n; i++) {
            org.bukkit.Location a = pts.get(i);
            org.bukkit.Location b = pts.get((i + 1) % n);
            double ax = a.getX(), az = a.getZ();
            double bx = b.getX(), bz = b.getZ();
            sum += (ax * bz) - (az * bx);
        }

        double area = Math.abs(sum) * 0.5;

        // по желанию округлим до двух знаков (как было раньше)
        return Math.round(area * 100.0) / 100.0;
    }

    // ==== JSON getters (с кэшем Users) ====
    public Map<String, Object> getJsonFieldValues(String table,
                                                  String jsonColumn,
                                                  String keyColumn,
                                                  String keyValue,
                                                  java.util.List<String> keys) {
        Map<String, Object> resultMap = new java.util.HashMap<>();
        if (table == null || jsonColumn == null || keyColumn == null || keyValue == null || keys == null || keys.isEmpty())
            return resultMap;

        // Оптимизация: если читаем Users по Name — попробуем из кэша
        boolean canUseCache = "Users".equalsIgnoreCase(table) && "Name".equalsIgnoreCase(keyColumn);
        if (canUseCache) {
            CachedPlayerRow row = getOrLoadCachedPlayer(keyValue);
            if (row != null) {
                com.google.gson.JsonObject col = switch (jsonColumn) {
                    case "CustomizationData" -> row.customization;
                    case "SocialData"        -> row.social;
                    case "GeneralData"       -> row.general;
                    case "StatsData"         -> row.stats;
                    default -> null;
                };
                if (col != null) {
                    for (String k : keys) {
                        if (col.has(k)) resultMap.put(k, jsonElementToJava(col.get(k)));
                    }
                    return resultMap;
                }
            }
            // промах — пойдём в БД
        }

        try (java.sql.Connection con = DBConnect()) {
            if (con == null) return resultMap;
            String sql = "SELECT " + jsonColumn + " FROM " + table + " WHERE " + keyColumn + " = ? LIMIT 1;";
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, keyValue);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String json = rs.getString(1);
                        var obj = safeParseObject(new com.google.gson.JsonParser(), json);
                        for (String k : keys) {
                            if (obj.has(k)) resultMap.put(k, jsonElementToJava(obj.get(k)));
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[UnityCommands] getJsonFieldValues error: " + e.getMessage());
            e.printStackTrace();
        }
        return resultMap;
    }

    // ==== Shop spots (Users.GeneralData.shopSpots) ====
    public int getShops(@NotNull CommandSender sender) {
        // сначала пробуем из кэша
        CachedPlayerRow cached = getOrLoadCachedPlayer(sender.getName());
        if (cached != null && cached.general != null && cached.general.has("shopSpots")) {
            try { return cached.general.get("shopSpots").getAsInt(); } catch (Exception ignored) {}
        }

        // иначе — быстро из БД JSON_EXTRACT
        try (Connection con = DBConnect()) {
            if (con == null) return 0;
            String q = "SELECT JSON_EXTRACT(GeneralData,'$.shopSpots') FROM Users WHERE Name=? LIMIT 1;";
            try (PreparedStatement ps = con.prepareStatement(q)) {
                ps.setString(1, sender.getName());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String raw = rs.getString(1);
                        if (raw == null || "null".equalsIgnoreCase(raw)) return 0;
                        // может прийти как число или строка
                        try { return Integer.parseInt(raw.replace("\"","").trim()); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[UnityCommands] getShops error: " + e.getMessage());
        }
        return 0;
    }

    public void setShops(@NotNull CommandSender sender, int shopCount) {
        if (shopCount < 0) shopCount = 0;
        try (Connection con = DBConnect()) {
            if (con == null) return;
            String up = "UPDATE Users SET GeneralData = JSON_SET(GeneralData, '$.shopSpots', ?) WHERE Name=?;";
            try (PreparedStatement ps = con.prepareStatement(up)) {
                ps.setInt(1, shopCount);
                ps.setString(2, sender.getName());
                ps.executeUpdate();
            }
            // обновим кэш
            CachedPlayerRow cached = getOrLoadCachedPlayer(sender.getName());
            if (cached != null && cached.general != null) {
                com.google.gson.JsonObject newGen = cached.general.deepCopy();
                newGen.addProperty("shopSpots", shopCount);
                upsertCacheAfterUpdate(sender.getName(), "GeneralData", newGen);
            }
        } catch (Exception e) {
            System.err.println("[UnityCommands] setShops error: " + e.getMessage());
        }
    }

    /** Безопасно конвертирует JsonElement в обычный Java Object (Map/List/String/Number/Boolean/null). */
    private static Object jsonElementToJava(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            if (p.isString()) return p.getAsString();
            return p.toString();
        }
        if (el.isJsonObject()) {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            for (var e : el.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), jsonElementToJava(e.getValue()));
            }
            return map;
        }
        if (el.isJsonArray()) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (var e : el.getAsJsonArray()) list.add(jsonElementToJava(e));
            return list;
        }
        return el.toString();
    }

    /** Безопасно парсит JSON-строку в JsonObject, возвращает пустой при ошибке. */
    private static com.google.gson.JsonObject safeParseObject(com.google.gson.JsonParser parser, String json) {
        if (json == null || json.isBlank()) return new com.google.gson.JsonObject();
        try {
            com.google.gson.JsonElement root = parser.parse(json);
            if (root != null && root.isJsonObject()) {
                return root.getAsJsonObject();
            }
        } catch (Exception ignored) {}
        return new com.google.gson.JsonObject();
    }

}
