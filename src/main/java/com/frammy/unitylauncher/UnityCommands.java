package com.frammy.unitylauncher;

import com.frammy.unitylauncher.upgrades.core.UpgradesManager;
import com.frammy.unitylauncher.upgrades.impl.EducationUpgrade;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
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
    private static final UnityCommands INSTANCE = new UnityCommands();
    public static UnityCommands getInstance() { return INSTANCE; }


    /** Проекцию используем минимальную: страна + деньги игрока. */
    public static class PlayerData {
        public int userId;
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
        boolean isFresh(long nowMs) { return nowMs - loadedAt <= PLAYER_CACHE_TTL_MS; }
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

    private Integer getUserIdByName(Connection con, String name) throws Exception {
        try (PreparedStatement ps = con.prepareStatement("SELECT ID FROM Users WHERE Name=? LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private CachedPlayerRow getOrLoadCachedPlayer(String playerName) {
        long now = System.currentTimeMillis();
        CachedPlayerRow row = playerCache.get(playerName);
        if (row != null && row.isFresh(now)) return row;
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

    /** Сбросить кэш конкретного игрока (следующий getOrLoadCachedPlayer полезет в БД). */
    public void invalidatePlayerCache(String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        playerCache.remove(playerName);
    }

    /** Сбросить кэш и подгрузить свежие данные асинхронно (best-effort). */
    public void refreshPlayerCacheAsync(String playerName) {
        if (playerName == null || playerName.isBlank()) return;

        invalidatePlayerCache(playerName);

        UnityLauncher ul = UnityLauncher.getInstance();
        if (ul == null) return;

        Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
            try {
                CachedPlayerRow fresh = loadPlayerRowFromDB(playerName);
                if (fresh != null) playerCache.put(playerName, fresh);
            } catch (Exception e) {
                Bukkit.getLogger().warning("[UnityCommands] refreshPlayerCacheAsync failed: " + e);
            }
        });
    }

    /* ===================== Публичные утилиты JSON Users ===================== */

    public void mergeAndUpdatePlayerData(String playerName, String column, Map<String, Object> updates) {
        if (playerName == null || playerName.isBlank()) return;
        if (updates == null || updates.isEmpty()) return;

        final java.util.List<String> valid = java.util.List.of(
                "CustomizationData", "SocialData", "GeneralData", "StatsData"
        );
        if (!valid.contains(column)) {
            Bukkit.getLogger().warning("[UnityCommands] mergeAndUpdatePlayerData: bad column " + column);
            return;
        }

        // строим: UPDATE Users SET col = JSON_SET(JSON_REMOVE(col,'$.a','$.b'), '$.c', CAST(? AS JSON), '$.d', CAST(? AS JSON)) WHERE Name=?
        java.util.List<String> removePaths = new java.util.ArrayList<>();
        java.util.List<String> setPaths = new java.util.ArrayList<>();
        java.util.List<Object> setValues = new java.util.ArrayList<>();

        Gson gson = new Gson();
        for (var e : updates.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;

            String path = "$." + key; // ключи у тебя простые (без точек). Если будут сложные — надо экранировать.
            Object val = e.getValue();
            if (val == null) {
                removePaths.add(path);
            } else {
                setPaths.add(path);
                // значение как JSON-строка, которую MySQL распарсит через CAST(? AS JSON)
                setValues.add(gson.toJsonTree(val).toString());
            }
        }

        if (removePaths.isEmpty() && setPaths.isEmpty()) return;

        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE Users SET ").append(column).append(" = ");

        // JSON_REMOVE если есть удаления
        if (!removePaths.isEmpty()) {
            sql.append("JSON_REMOVE(").append(column);
            for (String ignored : removePaths) {
                sql.append(", ?");
            }
            sql.append(")");
        } else {
            sql.append(column);
        }

        // JSON_SET если есть установки
        if (!setPaths.isEmpty()) {
            sql.insert(0, "UPDATE Users SET " + column + " = JSON_SET(");
            sql.append(", "); // между base и первым path
            // если был JSON_REMOVE — он уже сформирован как выражение. если нет — base = column
            // На этом этапе sql выглядит либо "UPDATE ... JSON_SET(GeneralData = JSON_REMOVE(...), " — поэтому делаем проще:
            // Пересоберём аккуратно ниже.
            sql.setLength(0);

            String baseExpr;
            if (!removePaths.isEmpty()) {
                baseExpr = "JSON_REMOVE(" + column +
                        ", ?".repeat(removePaths.size()) +
                        ")";
            } else {
                baseExpr = column;
            }

            sql.append("UPDATE Users SET ").append(column).append(" = JSON_SET(").append(baseExpr);
            sql.repeat(", ?, CAST(? AS JSON)", setPaths.size());
            sql.append(") WHERE Name = ?");
        } else {
            // только удаления
            sql.append(" WHERE Name = ?");
        }

        try (Connection con = DBConnect()) {
            if (con == null) return;

            try (PreparedStatement ps = con.prepareStatement(sql.toString())) {
                int idx = 1;

                // параметры для JSON_REMOVE
                for (String p : removePaths) ps.setString(idx++, p);

                // параметры для JSON_SET: path + json-string
                for (int i = 0; i < setPaths.size(); i++) {
                    ps.setString(idx++, setPaths.get(i));
                    ps.setString(idx++, (String) setValues.get(i));
                }

                ps.setString(idx, playerName);
                ps.executeUpdate();
            }

            // кэш после атомарного UPDATE: лучше просто refresh (иначе можно не идеально смёрджить в памяти)
            refreshPlayerCacheAsync(playerName);

        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] mergeAndUpdatePlayerData failed: " + e);
        }
    }

    /** Асинхронно достаёт PlayerData (countryName из SocialData, money из GeneralData). */
    public void getPlayerInfo(String playerName, Consumer<PlayerData> callback) {
        UnityLauncher ul = UnityLauncher.getInstance();
        if (ul == null) { callback.accept(null); return; }

        Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
            PlayerData pd = null;
            try {
                CachedPlayerRow row = getOrLoadCachedPlayer(playerName);
                if (row != null) {
                    pd = new PlayerData();

                    if (row.general.has("money") && row.general.get("money").isJsonPrimitive()) {
                        try { pd.money = row.general.get("money").getAsDouble(); } catch (Exception ignored) {}
                    }

                    try (Connection con = DBConnect()) {
                        if (con != null) {
                            Integer id = getUserIdByName(con, playerName);
                            pd.userId = (id != null ? id : 0);
                        }
                    }

                    String c = null;
                    if (row.social.has("countryName")) c = safeGetString(row.social.get("countryName"));
                    CountryRegistryJdbc reg = registry();
                    if ((c == null || c.isBlank()) && reg != null) c = reg.getCountryOfPlayer(playerName);
                    pd.countryName = (c != null && !c.isBlank()) ? c : null;
                }
            } catch (Exception e) {
                Bukkit.getLogger().warning("[UnityCommands] getPlayerInfo failed: " + e);
            }

            PlayerData finalPd = pd;
            Bukkit.getScheduler().runTask(ul, () -> callback.accept(finalPd));
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

    // ===================== DAYDEAL =====================

    private record DayDealParsed(
            String raw,
            boolean generated,
            boolean completed,
            String code,
            String itemName,
            int amount,
            double pricePerOne
    ) {}

    private DayDealParsed parseDayDeal(String raw) {
        if (raw == null) raw = "0";
        raw = raw.trim();

        // "0" - не сгенерировался
        if (raw.equals("0") || raw.isEmpty()) {
            return new DayDealParsed("0", false, false, "0", null, 0, 0.0);
        }

        // Форматы:
        // "96900;Brown Banner;15;2,14;0" - сгенерирован, не выполнен
        // "0;Brown Banner;15;2,14;0" - выполнен
        String[] parts = raw.split(";", -1);
        if (parts.length < 4) {
            // мусор/неожиданный формат
            return new DayDealParsed(raw, false, false, "0", null, 0, 0.0);
        }

        String code = parts[0].trim();
        boolean completed = code.equals("0");

        String itemName = parts[1].trim();
        int amount = 0;
        try { amount = Integer.parseInt(parts[2].trim()); } catch (Exception ignored) {}

        double pricePerOne = 0.0;
        String priceStr = parts[3].trim().replace(",", "."); // у тебя десятичная запятая
        try { pricePerOne = Double.parseDouble(priceStr); } catch (Exception ignored) {}

        boolean generated = true;

        return new DayDealParsed(raw, generated, completed, code, itemName, amount, pricePerOne);
    }

    private String getDayDealRawFromCacheOrDB(String playerName) {
        CachedPlayerRow row = getOrLoadCachedPlayer(playerName);
        if (row == null) return "0";
        if (!row.general.has("dayDealCode")) return "0";
        try {
            return safeGetString(row.general.get("dayDealCode"));
        } catch (Exception ignored) {
            return "0";
        }
    }

    public void dayDealInfo(@NotNull CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Только для игроков.");
            return;
        }

        String raw = getDayDealRawFromCacheOrDB(p.getName());
        DayDealParsed dd = parseDayDeal(raw);

        if (!dd.generated) {
            p.sendMessage(ChatColor.YELLOW + "Дейлик ещё не сгенерирован. Найди его в лаунчере!");
            return;
        }

        if (dd.completed) {
            double baseTotal = dd.amount * dd.pricePerOne;
            double total = applyEducationBonusMaybe(p, baseTotal);
            p.sendMessage(ChatColor.GREEN + "Дейлик уже выполнен.");
            p.sendMessage(ChatColor.GRAY + "Задание было: " + ChatColor.WHITE + dd.itemName +
                    ChatColor.GRAY + " x" + ChatColor.WHITE + dd.amount);
            p.sendMessage(ChatColor.GRAY + "Награда была: " + ChatColor.GOLD +
                    String.format(java.util.Locale.US, "%.2f", total) + ChatColor.GRAY + " Ⓕ");
            return;
        }

        double baseTotal = dd.amount * dd.pricePerOne;
        double total = applyEducationBonusMaybe(p, baseTotal);

        p.sendMessage(ChatColor.DARK_AQUA + "=== DayDeal ===");
        p.sendMessage(ChatColor.GRAY + "Собери: " + ChatColor.WHITE + dd.itemName +
                ChatColor.GRAY + " x" + ChatColor.WHITE + dd.amount);
        p.sendMessage(ChatColor.GRAY + "Награда: " + ChatColor.GOLD +
                String.format(java.util.Locale.US, "%.2f", total) + ChatColor.GRAY + " Ⓕ");
        TextComponent text = new TextComponent(
                ChatColor.GRAY + "Чтобы сдать, держи предмет в руке и нажми: "
        );

        TextComponent cmd = new TextComponent(ChatColor.YELLOW + "/ul daydeal complete");
        cmd.setClickEvent(new ClickEvent(
                ClickEvent.Action.RUN_COMMAND,
                "/ul daydeal complete"
        ));
        cmd.setHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Нажать, чтобы выполнить команду").create()
        ));

        text.addExtra(cmd);

        p.spigot().sendMessage(text);

    }

    public void dayDealComplete(@NotNull CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Только для игроков.");
            return;
        }

        String raw = getDayDealRawFromCacheOrDB(p.getName());
        DayDealParsed dd = parseDayDeal(raw);

        if (!dd.generated) {
            p.sendMessage(ChatColor.RED + "Дейлик ещё не сгенерирован.");
            return;
        }
        if (dd.completed) {
            p.sendMessage(ChatColor.YELLOW + "Дейлик уже выполнен.");
            return;
        }
        if (dd.amount <= 0 || dd.itemName == null || dd.itemName.isBlank()) {
            p.sendMessage(ChatColor.RED + "Дейлик повреждён (неверные данные).");
            return;
        }

        org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(ChatColor.RED + "Возьми нужный предмет в ведущую руку.");
            return;
        }

        // Не пытаемся угадывать Material по "Brown Banner" — это ненадёжно.
        // Но можем сделать минимальную проверку по displayName, если он задан:
        // Если displayName совпадает — хорошо; если не задан — не мешаем.
        boolean nameOk = true;
        try {
            var meta = hand.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String dn = org.bukkit.ChatColor.stripColor(meta.getDisplayName());
                if (!dn.isBlank()) {
                    nameOk = dn.equalsIgnoreCase(dd.itemName);
                }
            }
        } catch (Exception ignored) {}

        if (!nameOk) {
            p.sendMessage(ChatColor.RED + "В руке должен быть предмет: " + ChatColor.WHITE + dd.itemName);
            return;
        }

        int have = hand.getAmount();
        if (have < dd.amount) {
            p.sendMessage(ChatColor.RED + "Недостаточно предметов в руке. Нужно: " +
                    ChatColor.WHITE + dd.amount + ChatColor.RED + ", у тебя: " + ChatColor.WHITE + have);
            return;
        }

        // Списываем из ведущей руки ровно нужное количество
        if (have == dd.amount) {
            p.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(have - dd.amount);
            p.getInventory().setItemInMainHand(hand);
        }

        // Помечаем как выполненный: "0;item;amount;price;rerolls"
        // Рероллы не трогаем — просто сохраняем хвост, если он есть
        String[] parts = dd.raw.split(";", -1);
        String rerollsPart = (parts.length >= 5) ? parts[4] : ""; // не показываем, но сохраняем
        String newRaw = "0;" + dd.itemName + ";" + dd.amount + ";" +
                // сохраняем price как было, но нормально: с запятой можно оставить
                parts[3] + (parts.length >= 5 ? (";" + rerollsPart) : "");

        // Запись в GeneralData.dayDealCode + обновление кэша через mergeAndUpdatePlayerData
        mergeAndUpdatePlayerData(p.getName(), "GeneralData", java.util.Map.of("dayDealCode", newRaw));

        double baseTotal = dd.amount * dd.pricePerOne;
        double total = applyEducationBonusMaybe(p, baseTotal);

        boolean paid = addMoneyToPlayer(p.getName(), total);

        if (!paid) {
            p.sendMessage(ChatColor.RED + "Не удалось начислить деньги (БД недоступна).");
            p.sendMessage(ChatColor.GRAY + "DayDeal (для компенсации):");
            p.sendMessage(ChatColor.GRAY + "Предмет: " + ChatColor.WHITE + dd.itemName);
            p.sendMessage(ChatColor.GRAY + "Количество: " + ChatColor.WHITE + dd.amount);
            p.sendMessage(ChatColor.GRAY + "Цена за штуку: " + ChatColor.WHITE +
                    String.format(java.util.Locale.US, "%.2f", dd.pricePerOne));
            p.sendMessage(ChatColor.GRAY + "Итого: " + ChatColor.GOLD +
                    String.format(java.util.Locale.US, "%.2f", total) + ChatColor.GRAY + " Ⓕ");
            p.sendMessage(ChatColor.DARK_GRAY + "Скриншотни это сообщение и отправь администратору.");

        } else {
            p.sendMessage(ChatColor.GREEN + "Дейлик выполнен!");
            p.sendMessage(ChatColor.GRAY + "Получено: " + ChatColor.GOLD +
                    String.format(java.util.Locale.US, "%.2f", total) + ChatColor.GRAY + " Ⓕ");
        }
    }

    private double applyEducationBonusMaybe(Player p, double baseAmount) {
        if (p == null) return baseAmount;
        if (!(baseAmount > 0.0)) return baseAmount;

        UnityLauncher ul = UnityLauncher.getInstance();
        if (ul == null) return baseAmount;

        UpgradesManager um = ul.getUpgradesManager();
        if (um == null) return baseAmount;

        // core.enabled=false -> ctx будет null, enabled список пустой, метод вернёт null
        EducationUpgrade edu = um.getEnabled(EducationUpgrade.class);
        if (edu == null) return baseAmount;

        return edu.applyEducationBonus(p, baseAmount);
    }

    public boolean applyMoneyDelta(@NotNull String playerName, double delta) {
        if (playerName.isBlank()) return false;
        if (Math.abs(delta) < 0.0000001) return true;

        try (Connection con = DBConnect()) {
            if (con == null) return false;

            con.setAutoCommit(false);
            try {
                UserJsonRow row = lockAndReadUserMoney(con, playerName);
                if (row == null) {
                    con.rollback();
                    return false;
                }

                double cur = (row.money != null) ? row.money : 0.0;
                double next = cur + delta;

                // не даём уйти в минус
                if (next < -1e-9) {
                    con.rollback();
                    return false;
                }
                if (next < 0) next = 0;

                writeUserMoney(con, playerName, next);
                con.commit();

                // sync cache
                CachedPlayerRow cached = getOrLoadCachedPlayer(playerName);
                if (cached != null && cached.general != null) {
                    JsonObject newGen = cached.general.deepCopy();
                    newGen.addProperty("money", next);
                    upsertCacheAfterUpdate(playerName, "GeneralData", newGen);
                }

                return true;

            } catch (Exception e) {
                try { con.rollback(); } catch (Exception ignored) {}
                Bukkit.getLogger().warning("[UnityCommands] applyMoneyDelta failed: " + e);
                return false;
            } finally {
                try { con.setAutoCommit(true); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] applyMoneyDelta outer failed: " + e);
            return false;
        }
    }

    public boolean addMoneyToPlayer(@NotNull String playerName, double delta) {
        if (!(delta > 0.0)) return false;
        return applyMoneyDelta(playerName, delta);
    }

    // ==== AUTH ====

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

    /* ===================== Заглушки (временно отключено / легаси) ===================== */

    public void getNotifications(@NotNull CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Система уведомлений временно отключена.");
    }

    public void toggleNotifications(@NotNull CommandSender sender, String toggle) {
        sender.sendMessage(ChatColor.YELLOW + "Система уведомлений временно отключена.");
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

    private static Double parseJsonNumber(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        if ("null".equalsIgnoreCase(s) || s.isEmpty()) return null;
        try { return Double.valueOf(s); } catch (Exception ignore) { return null; }
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
        if (keyValue == null || keyValue.isBlank() || keys == null || keys.isEmpty()) return resultMap;

        // Жёстко разрешаем только Users + Name + 4 json-колонки
        if (!"Users".equalsIgnoreCase(table)) return resultMap;
        if (!"Name".equalsIgnoreCase(keyColumn)) return resultMap;

        final java.util.List<String> validCols = java.util.List.of(
                "CustomizationData", "SocialData", "GeneralData", "StatsData"
        );
        if (!validCols.contains(jsonColumn)) return resultMap;

        // сначала кэш
        CachedPlayerRow row = getOrLoadCachedPlayer(keyValue);
        if (row != null) {
            JsonObject col = switch (jsonColumn) {
                case "CustomizationData" -> row.customization;
                case "SocialData"        -> row.social;
                case "GeneralData"       -> row.general;
                case "StatsData"         -> row.stats;
                default -> null;
            };
            if (col != null) {
                for (String k : keys) if (col.has(k)) resultMap.put(k, jsonElementToJava(col.get(k)));
                return resultMap;
            }
        }

        // fallback в БД (идентификаторы безопасны, потому что whitelist)
        try (Connection con = DBConnect()) {
            if (con == null) return resultMap;

            String sql = "SELECT " + jsonColumn + " FROM Users WHERE Name = ? LIMIT 1;";
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, keyValue);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        JsonObject obj = safeParseObject(rs.getString(1));
                        for (String k : keys) if (obj.has(k)) resultMap.put(k, jsonElementToJava(obj.get(k)));
                    }
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[UnityCommands] getJsonFieldValues error: " + e);
        }
        return resultMap;
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

}
