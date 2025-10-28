package com.frammy.unitylauncher;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.frammy.unitylauncher.UnityLauncher.onError;
import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class UnityCommands {
    private static UnityCommands instance;

    public static UnityCommands getInstance() {
        if (instance == null) {
            instance = new UnityCommands();
        }
        return instance;
    }

    public static class PlayerData {
        public String countryName;
        public double money;
    }

// ==== BEGIN: in-memory cache for Users table (all JSON columns in one shot) ====

    private static final long PLAYER_CACHE_TTL_MS = 60_000; // 60 сек. поправь под себя

    private final java.util.concurrent.ConcurrentHashMap<String, CachedPlayerRow> playerCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedPlayerRow(JsonObject customization, JsonObject social, JsonObject general, JsonObject stats, long loadedAt) {

        // упрощаем сигнатуру: TTL берём из внешней константы
            boolean isExpired(long nowMs) {
                return nowMs - loadedAt > PLAYER_CACHE_TTL_MS;
            }
        }

    private static com.google.gson.JsonObject safeParseObject(com.google.gson.JsonParser parser, String json) {
        try {
            if (json == null || json.isEmpty()) return new com.google.gson.JsonObject();
            var el = parser.parse(json);
            return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : new com.google.gson.JsonObject();
        } catch (Exception ignored) {
            return new com.google.gson.JsonObject();
        }
    }

    // Преобразование JsonElement -> Java Object (без Streams.parse)
    private static Object jsonElementToJava(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            var p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber())  return p.getAsNumber();
            if (p.isString())  return p.getAsString();
            return p.getAsString();
        }
        if (el.isJsonArray()) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (var e : el.getAsJsonArray()) out.add(jsonElementToJava(e));
            return out;
        }
        if (el.isJsonObject()) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            for (var e : el.getAsJsonObject().entrySet()) {
                map.put(e.getKey(), jsonElementToJava(e.getValue()));
            }
            return map;
        }
        return null;
    }

    private CachedPlayerRow loadPlayerRowFromDB(String playerName) throws Exception {
        try (java.sql.Connection con = DBConnect()) {
            if (con == null) return null;

            String sql = "SELECT CustomizationData, SocialData, GeneralData, StatsData " +
                    "FROM Users WHERE Name = ? LIMIT 1";
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, playerName);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;

                    com.google.gson.JsonParser parser = new com.google.gson.JsonParser();

                    var cust = safeParseObject(parser, rs.getString("CustomizationData"));
                    var soc  = safeParseObject(parser, rs.getString("SocialData"));
                    var gen  = safeParseObject(parser, rs.getString("GeneralData"));
                    var st   = safeParseObject(parser, rs.getString("StatsData"));

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
            System.err.println("[UnityCommands] loadPlayerRowFromDB error: " + e.getMessage());
            e.printStackTrace();
        }
        return row; // вернём (возможно, протухший) как best-effort
    }

    private void upsertCacheAfterUpdate(String playerName, String column, com.google.gson.JsonObject newWholeColumn) {
        CachedPlayerRow prev = playerCache.get(playerName);
        long now = System.currentTimeMillis();
        if (prev == null) {
            com.google.gson.JsonObject cust = new com.google.gson.JsonObject();
            com.google.gson.JsonObject soc  = new com.google.gson.JsonObject();
            com.google.gson.JsonObject gen  = new com.google.gson.JsonObject();
            com.google.gson.JsonObject st   = new com.google.gson.JsonObject();

            switch (column) {
                case "CustomizationData" -> cust = newWholeColumn;
                case "SocialData"        -> soc  = newWholeColumn;
                case "GeneralData"       -> gen  = newWholeColumn;
                case "StatsData"         -> st   = newWholeColumn;
            }
            playerCache.put(playerName, new CachedPlayerRow(cust, soc, gen, st, now));
            return;
        }

        var cust = prev.customization.deepCopy();
        var soc  = prev.social.deepCopy();
        var gen  = prev.general.deepCopy();
        var st   = prev.stats.deepCopy();

        switch (column) {
            case "CustomizationData" -> cust = newWholeColumn;
            case "SocialData"        -> soc  = newWholeColumn;
            case "GeneralData"       -> gen  = newWholeColumn;
            case "StatsData"         -> st   = newWholeColumn;
        }
        playerCache.put(playerName, new CachedPlayerRow(cust, soc, gen, st, now));
    }

// ==== END: cache ====

    public Map<String, Object> getJsonFieldValues(String table,
                                                  String jsonColumn,
                                                  String keyColumn,
                                                  String keyValue,
                                                  java.util.List<String> keys) {
        Map<String, Object> resultMap = new java.util.HashMap<>();

        boolean canUseCache = "Users".equalsIgnoreCase(table) && "Name".equalsIgnoreCase(keyColumn);
        if (canUseCache) {
            CachedPlayerRow row = getOrLoadCachedPlayer(keyValue);
            if (row != null) {
                com.google.gson.JsonObject col;
                switch (jsonColumn) {
                    case "CustomizationData" -> col = row.customization;
                    case "SocialData"        -> col = row.social;
                    case "GeneralData"       -> col = row.general;
                    case "StatsData"         -> col = row.stats;
                    default -> col = null;
                }
                if (col != null) {
                    for (String k : keys) {
                        if (col.has(k)) resultMap.put(k, jsonElementToJava(col.get(k)));
                    }
                    return resultMap;
                }
            }
            // при промахе — пойдём в БД
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
            System.err.println("Ошибка чтения JSON: " + e.getMessage());
            e.printStackTrace();
        }
        return resultMap;
    }

    public void mergeAndUpdatePlayerData(String playerName, String column, java.util.Map<String, Object> updates) {
        java.util.List<String> validColumns = java.util.List.of("CustomizationData", "SocialData", "GeneralData", "StatsData");
        if (!validColumns.contains(column)) {
            System.err.println("Недопустимая колонка: " + column);
            return;
        }

        try (java.sql.Connection con = DBConnect()) {
            if (con == null) return;

            com.google.gson.JsonObject current;
            CachedPlayerRow cached = playerCache.get(playerName);
            if (cached != null && !cached.isExpired(System.currentTimeMillis())) {
                switch (column) {
                    case "CustomizationData" -> current = cached.customization.deepCopy();
                    case "SocialData"        -> current = cached.social.deepCopy();
                    case "GeneralData"       -> current = cached.general.deepCopy();
                    case "StatsData"         -> current = cached.stats.deepCopy();
                    default -> current = new com.google.gson.JsonObject();
                }
            } else {
                String select = "SELECT " + column + " FROM Users WHERE Name = ? LIMIT 1;";
                try (java.sql.PreparedStatement ps = con.prepareStatement(select)) {
                    ps.setString(1, playerName);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            current = safeParseObject(new com.google.gson.JsonParser(), rs.getString(1));
                        } else {
                            current = new com.google.gson.JsonObject();
                        }
                    }
                }
            }

            com.google.gson.Gson gson = new com.google.gson.Gson();
            for (var e : updates.entrySet()) {
                current.add(e.getKey(), gson.toJsonTree(e.getValue()));
            }

            String update = "UPDATE Users SET " + column + " = ? WHERE Name = ?;";
            try (java.sql.PreparedStatement ps = con.prepareStatement(update)) {
                ps.setString(1, current.toString());
                ps.setString(2, playerName);
                ps.executeUpdate();
            }

            upsertCacheAfterUpdate(playerName, column, current);
        } catch (Exception e) {
            System.err.println("Ошибка обновления JSON: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void getGroups(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT PermissionGroups FROM Countries WHERE Players LIKE '%" + sender.getName() + "%';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    if (rs.getString("PermissionGroups").isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране! Будь осторожнее!");
                        return;
                    }
                    String[] list = rs.getString("PermissionGroups").split("¦");
                    StringBuilder groups = new StringBuilder();
                    for (String row : list) {
                        String[] elem = row.split("¶");
                        groups.append(elem[0]).append(" ");
                    }
                    sender.sendMessage("Группы в твоей стране: " + groups);
                } else {
                    sender.sendMessage(ChatColor.RED + "У вас нет групп!");
                    return;
                }
                rs.close();
            } catch (Exception e) {
                onError("getGroup", (Player) sender);
            }
        }
    }

    public void setGroup(@NotNull CommandSender sender, String nickname, String group) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT Mayor, PermissionGroups FROM Countries WHERE Players LIKE '%" + sender.getName() + "%';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    if (rs.getString("Mayor").isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране! Будь осторожнее!");
                        return;
                    } else if (!rs.getString("Mayor").equals(sender.getName())) {
                        sender.sendMessage(ChatColor.RED + "Недостаточно прав!");
                        return;
                    }
                    boolean check = false;
                    String[] list = rs.getString("PermissionGroups").split("¦");
                    for (String row : list) {
                        String[] elem = row.split("¶");
                        if (elem[0].contains(group)) {
                            check = true;
                            break;
                        }
                    }
                    if (!check) return;
                } else {
                    sender.sendMessage(ChatColor.RED + "Вы не в стране!");
                    return;
                }
                rs.close();
                String query2 = "UPDATE Users SET CountryPermissions='" + group + "' WHERE Name='" + nickname + "';";
                Statement st2 = con.createStatement();
                st2.executeUpdate(query2);
            } catch (Exception e) {
                onError("setGroup", (Player) sender);
            }
        }
    }

    public void setPrefix(@NotNull CommandSender sender, String group, String prefix) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT Mayor, PermissionGroups FROM Countries WHERE Players LIKE '%" + sender.getName() + "%';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    if (rs.getString("Mayor").isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране! Будь осторожнее!");
                        return;
                    } else if (!rs.getString("Mayor").equals(sender.getName())) {
                        sender.sendMessage(ChatColor.RED + "Недостаточно прав!");
                        return;
                    }
                    if (rs.getString("PermissionGroups").isEmpty()) return;
                    String[] list = rs.getString("PermissionGroups").split("¦");
                    StringBuilder fin = new StringBuilder();
                    for (String row : list) {
                        String[] elem = row.split("¶");
                        if (elem[0].contains(group)) {
                            elem[1] = prefix;
                            fin.append(elem[0]).append("¶").append(elem[1]).append("¶").append(elem[2]).append("¶").append(elem[3]).append("¦");
                            break;
                        } else {
                            fin.append(row).append("¦");
                        }
                    }
                    fin = new StringBuilder(fin.substring(0, fin.length() - 1));
                    String query2 = "UPDATE Countries SET PermissionGroups='" + fin + "' WHERE Name='" + sender.getName() + "';";
                    Statement st2 = con.createStatement();
                    st2.executeUpdate(query2);
                } else {
                    sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране!");
                    return;
                }
                rs.close();
            } catch (Exception e) {
                onError("setPrefix", (Player) sender);
            }
        }
    }

    public void dayDeal(@NotNull CommandSender sender, String code) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT DayDealCode, Money FROM Users WHERE Name='" + sender.getName() + "';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    Bukkit.getConsoleSender().sendMessage(rs.getString("DayDealCode"));
                    if (rs.getString("DayDealCode").equals("0")) {
                        sender.sendMessage(ChatColor.RED + "Задание ещё не было получено. Для этого зайди в FarLands клиент.");
                        return;
                    }
                    String[] tableCode = rs.getString("DayDealCode").split(";");
                    if (tableCode[0].equals(code)) {
                        Player p = (Player) sender;
                        Bukkit.getConsoleSender().sendMessage(p.getInventory().getItemInMainHand().getType().toString());
                        if (p.getInventory().getItemInMainHand().getAmount() >= Integer.parseInt(tableCode[2]) &&
                                p.getInventory().getItemInMainHand().getType().toString().contains(tableCode[1].replaceAll(" ", "_").toUpperCase())) {
                            p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - Integer.parseInt(tableCode[2]));
                        } else {
                            sender.sendMessage(ChatColor.RED + "Предметы не совпадают!");
                            return;
                        }
                        double money = Double.parseDouble(tableCode[3].replaceAll(",",".")) * Integer.parseInt(tableCode[2]) + rs.getInt("Money");
                        String updCode = "0;" + tableCode[1] + ";" + tableCode[2] + ";" + tableCode[3];
                        String query2 = "Update Users SET DayDealCode='" + updCode + "', Money=" + money + " WHERE Name='" + sender.getName() + "';";
                        Statement st2 = con.createStatement();
                        st2.executeUpdate(query2);
                        sender.sendMessage(ChatColor.GREEN + "Вы выполнили задание!" + ChatColor.RESET + " Возвращайтесь завтра.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Такого кода не существует!");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Вас нет в базе!");
                    return;
                }
                rs.close();
            } catch (Exception e) {
                onError("dayDeal", (Player) sender);
            }
        }
    }

    public void CountryMoney(@NotNull CommandSender sender, boolean action, double money) {
        if (money <= 0) {
            sender.sendMessage(ChatColor.RED + "Не стоит!");
            return;
        }
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT Mayor, Money FROM Countries WHERE Players LIKE '%" + sender.getName() + "%';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                double CountryMoney;
                boolean isMayor;
                if (rs.next()) {
                    CountryMoney = rs.getDouble("Money");
                    isMayor = rs.getString("Mayor").equals(sender.getName());
                } else {
                    sender.sendMessage(ChatColor.RED + "Вы не в стране!");
                    return;
                }
                rs.close();
                String query2 = "SELECT Money FROM Users WHERE Name='" + sender.getName() + "';";
                Statement st2 = con.createStatement();
                ResultSet rs2 = st2.executeQuery(query2);
                double SenderMoney;
                if (rs2.next())
                    SenderMoney = rs2.getDouble("Money");
                else {
                    sender.sendMessage(ChatColor.RED + "Вы не в базе!");
                    return;
                }
                rs2.close();
                if (action) {
                    if (SenderMoney < money) {
                        sender.sendMessage(ChatColor.RED + "Недостаточно средств!");
                        return;
                    }
                    CountryMoney+=money;
                    SenderMoney-=money;
                } else {
                    if (isMayor) {
                        if (CountryMoney < money) {
                            sender.sendMessage(ChatColor.RED + "Недостаточно средств!");
                            return;
                        }
                    } else sender.sendMessage(ChatColor.RED + "Недостаточно полномочий!");
                    CountryMoney-=money;
                    SenderMoney+=money;
                }
                String query3 = "UPDATE Users SET Money=" + SenderMoney +" WHERE Name='" + sender.getName() + "';";
                Statement st3 = con.createStatement();
                st3.executeUpdate(query3);

                String query4 = "UPDATE Countries SET Money=" + CountryMoney + " WHERE Players LIKE '%" + sender.getName() + "%';";
                Statement st4 = con.createStatement();
                st4.executeUpdate(query4);
                if (action)
                    sender.sendMessage("Деньги были зачислены");
                else
                    sender.sendMessage("Деньги были выведены");
            } catch (Exception e) {
                onError("money", (Player) sender);
            }
        }
    }

    public void changePass(@NotNull CommandSender sender, String old, String password) {
        Connection con = DBConnect();
        if (con != null && password != null) {
            try {
                String querySelect = "SELECT Name, Password FROM Users WHERE Name='" + sender.getName() + "';";
                Statement stSelect = con.createStatement();
                ResultSet rs = stSelect.executeQuery(querySelect);
                if (rs.next()) {
                    if (!Objects.equals(old, rs.getString("Password"))) {
                        sender.sendMessage(ChatColor.RED + "Неверный текущий пароль");
                        return;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Сначала зарегистрируйтесь в FarLands клиенте");
                    return;
                }
                rs.close();
                String queryUpdate = "UPDATE Users SET Password = '"+password+"' WHERE Name = '"+sender.getName()+"';";
                Statement st = con.createStatement();
                st.executeUpdate(queryUpdate);
                sender.sendMessage("Пароль успешно изменен");
            } catch (Exception e) {
                onError("passCh", (Player) sender);
            }
        }
    }

    public Double getMoney(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT Money FROM Users WHERE Name='" + sender.getName() + "';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                Double result = null;

                if (rs.next()) {
                    String money = rs.getString(1);
                    if (money == null || money.equals("0")) {
                        sender.sendMessage(ChatColor.RED + "Твои карманы пусты, странник!");
                    } else {
                        sender.sendMessage("У тебя " + ChatColor.GREEN + money + ChatColor.RESET + " шекелей!");
                        result = Double.valueOf(money);
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Вас нет в базе!");
                }

                rs.close();
                st.close();
                con.close();

                return result;
            } catch (Exception e) {
                onError("getMoney", (Player) sender);
            }
        }
        return null;
    }

    public void setShops(@NotNull CommandSender sender, int shopCount) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "UPDATE Users SET AvailableShopPlaces='"+ shopCount +"' WHERE Name='"+sender.getName()+"';";
                Statement st = con.createStatement();
                st.executeUpdate(query);

            } catch (Exception e) {
                onError("getShops", (Player) sender);
            }
        }
    }

    public int getShops(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT AvailableShopPlaces FROM Users WHERE Name='"+sender.getName()+"';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    String shopPlaces = rs.getString(1);
                    if (shopPlaces == null || shopPlaces.equals("0")) {
                        sender.sendMessage(ChatColor.RED + "Твой лимит точек продажи истрачен. Чтобы создать магазин, необходимо приобрести лицензию или закрыть предыдущий.");
                        return 0;
                    } else {
                        rs.close();
                        return Integer.parseInt(shopPlaces);
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Чел..а тебя то нет в базе!");
                    rs.close();
                    return 0;
                }
            } catch (Exception e) {
                onError("getShops", (Player) sender);
            }
        }
        return 0;
    }

    public void getTop(@NotNull CommandSender sender, String category) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String order = "";
                if (category.equalsIgnoreCase("Balance") || category.equalsIgnoreCase("Bal")) {
                    category = "Money";
                    order = "Money";
                }
                if (category.equalsIgnoreCase("Playtime")) {
                    category = "AllPlayTime, PlayTime";
                    order = "AllPlayTime";
                }
                if (category.equalsIgnoreCase("Events")) {
                    order = category;
                }
                String query = "SELECT Name, " + category + " FROM Users ORDER BY " + order + " DESC LIMIT 5;";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (category.equals("Money"))  sender.sendMessage(ChatColor.BOLD + "Топ миллиардеров:");
                if (category.equals("Events"))  sender.sendMessage(ChatColor.BOLD + "Топ победителей эвентов:");
                if (category.equals("AllPlayTime, PlayTime"))  sender.sendMessage(ChatColor.BOLD + "Топ задротов:");

                TableGenerator tg = new TableGenerator(TableGenerator.Alignment.RIGHT, TableGenerator.Alignment.LEFT, TableGenerator.Alignment.LEFT);
                int i = 1;
                while (rs.next())
                    if (rs.getString(1) != null) {
                        double filler;
                        if (category.equalsIgnoreCase("AllPlayTime, PlayTime")) {
                            filler = rs.getInt("Playtime") + rs.getInt("AllPlayTime");
                        } else {
                            filler = rs.getDouble(category);
                        }
                        tg.addRow(String.valueOf(i++), rs.getString(1), String.valueOf(filler));
                    }
                rs.close();
                for (String line : tg.generate(TableGenerator.Receiver.CLIENT, true, true))
                    sender.sendMessage(line);
            } catch (Exception e) {
                onError("getTop", (Player) sender);
            }
        }
    }

    public void getCountry(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT * FROM Countries WHERE Players LIKE '%" + sender.getName() + "%';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    if (rs.getString("Name").isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране! Будь осторожнее!");
                        return;
                    } else {
                        String country = rs.getString("Name");
                        sender.sendMessage("Ты живешь в: " + ChatColor.GREEN + country + ChatColor.RESET);
                        sender.sendMessage("Президент: " + ChatColor.GREEN + rs.getString("Mayor") + ChatColor.RESET);
                        sender.sendMessage("Капитал: " + ChatColor.GREEN + rs.getInt("Money") + ChatColor.RESET);
                        sender.sendMessage("Жители: " + ChatColor.GREEN + rs.getString("Players").substring(0, rs.getString("Players").length() - 2) + ChatColor.RESET);
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране!");
                    return;
                }
                rs.close();
            } catch (Exception e) {
                onError("getCountry", (Player) sender);
            }
        }
    }

    public void rCode(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT regCode FROM Users WHERE Name='" + sender.getName() + "';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                boolean isEmpty = true;
                if (rs.next()) {
                    isEmpty = false;
                    String rCode = rs.getString(1);
                    if (rCode.equals("0")) {
                        sender.sendMessage(ChatColor.RED + "Ты уже зарегистрирован!");
                    } else sender.sendMessage("Твой код регистрации: " + ChatColor.GREEN + rCode);
                } else {
                    sender.sendMessage(ChatColor.RED + "Тебя нет в базе данных");
                }
                if (isEmpty) sender.sendMessage(ChatColor.RED + "Сначала зарегистрируйся в лаунчере!");
                rs.close();
            } catch (Exception e) {
                onError("rCode", (Player) sender);
            }
        }
    }

    public void getNotifications(@NotNull CommandSender sender) {
        getNotified(sender);
    }

    public void getPlayerInfo(String playerName, java.util.function.Consumer<PlayerData> callback) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(UnityLauncher.getInstance(), () -> {
            try {
                CachedPlayerRow row = getOrLoadCachedPlayer(playerName);
                if (row == null) { callback.accept(null); return; }

                PlayerData pd = new PlayerData();

                // money из GeneralData
                if (row.general.has("money") && row.general.get("money").isJsonPrimitive()) {
                    try { pd.money = row.general.get("money").getAsDouble(); } catch (Exception ignored) {}
                }

                // countryName из SocialData (несколько возможных ключей, чтобы не отвалиться)
                String c = null;
                if (row.social.has("countryName")) c = safeGetString(row.social.get("countryName"));
                if (c == null && row.social.has("country")) c = safeGetString(row.social.get("country"));
                if (c == null && row.social.has("nation"))  c = safeGetString(row.social.get("nation"));
                pd.countryName = c;

                callback.accept(pd);
            } catch (Exception e) {
                e.printStackTrace();
                callback.accept(null);
            }
        });
    }

    private static String safeGetString(com.google.gson.JsonElement el) {
        try { return el != null && el.isJsonPrimitive() ? el.getAsString() : null; }
        catch (Exception ignored) { return null; }
    }

    public static void getNotified(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT * FROM Notifications;";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                boolean isEmpty = true;
                while (rs.next()) {
                    switch (rs.getString("Reason")) {
                        case "Defence":
                            if (rs.getString("Receiver").contains(sender.getName())) {
                                sender.sendMessage("★ Вы будете атакованы игроком " + rs.getString("Addresser") + " в " + rs.getString("Time"));
                                isEmpty = false;
                            }
                            break;
                        case "Attack":
                            if (rs.getString("Addresser").contains(sender.getName())) {
                                sender.sendMessage("★ Мы планируем атаковать " + rs.getString("Receiver") + " в " + rs.getString("Time"));
                                isEmpty = false;
                            }
                            break;
                        case "Sold":
                            if (rs.getString("Receiver").contains(sender.getName())) {
                                sender.sendMessage("★ Ваш предмет был продан");
                                isEmpty = false;
                            }
                            break;
                    }
                    if (rs.getString("Reason").contains("CountryInvite") && sender.getName().equals(rs.getString("Receiver"))) {
                        if (rs.getString("Reason").length() > 15)
                            sender.sendMessage("★ Вы были приглашены игроком " + rs.getString("Addresser") + " в страну " + rs.getString("Reason").substring(14));
                        isEmpty = false;
                    }
                }
                if (isEmpty) sender.sendMessage("★ У вас нет уведомлений! ★");
                rs.close();
            } catch (Exception e) {
                onError("notifications", (Player) sender);
            }
        }
    }

    public void toggleNotifications(@NotNull CommandSender sender, String toggle) {
        int n = 0;
        if (toggle.equals("on")) n = 1;
        Connection con = DBConnect();
        if (con != null) {
            try {
                String queryUpdate = "UPDATE Users SET NotificationToggle = '" + n + "' WHERE Name = '" + sender.getName() + "';";
                Statement st = con.createStatement();
                st.executeUpdate(queryUpdate);
                if (n == 1) sender.sendMessage("Уведомления при входе включены!");
                else sender.sendMessage("Уведомления при входе отключены!");
            } catch (Exception e) {
                onError("notificationsToggle", (Player) sender);
            }
        }
    }

    public void createOrder(String sellerName, String customerName, String spriteName, Double price, Integer quantity, Location location, Map<Enchantment, Integer> enchantments /*, String customerName, ..., другие параметры */) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                // Пример с одной колонкой Seller — добавь остальные по аналогии
                String insertQuery = "INSERT INTO Orders (Seller, Customer, SpriteName, Price, Quantity, Description, IsFinished, Enchantments) VALUES (?,?,?,?,?,?,?,?);";

                PreparedStatement st = con.prepareStatement(insertQuery);
                st.setString(1, sellerName); // Заполняем колонку Seller
                st.setString(2, customerName);
                st.setString(3, spriteName);
                st.setString(4, price.toString());
                st.setString(5, quantity.toString());
                st.setString(6, "Покупка в магазине (" + location.getX() + ", " + location.getY() + ", " + location.getZ() + ")");
                st.setString(7, "Yes");
                st.setString(8, ""); // СЮДА ЗАЧАРОВАНИЯ НУЖНО
                int rowsAffected = st.executeUpdate();
                if (rowsAffected > 0) {
                    System.out.println("Новый заказ успешно создан!");
                } else {
                    System.err.println("Не удалось создать заказ.");
                }

                st.close();
                con.close();
            } catch (Exception e) {
                System.err.println("Ошибка при создании заказа: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void pay(@NotNull CommandSender sender, String receiver, double money, double customFee) {
        if (sender.getName().equals(receiver)) {
            sender.sendMessage(ChatColor.RED + "Отправлять деньги себе запрещено.");
            return;
        }
        Connection con = DBConnect();
        if (money <= 0) {
            sender.sendMessage(ChatColor.RED + "Самый умный?");
            return;
        }
        if (con != null) {
            try {
                String query = "SELECT Name, Money FROM Users WHERE Name='" + sender.getName() + "' OR Name='" + receiver + "';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                double SenderMoney = -1.0, ReceiverMoney = -1.0;
                while (rs.next()) {
                    if (rs.getString("Name").equals(sender.getName())) SenderMoney = rs.getDouble("Money");
                    if (rs.getString("Name").equals(receiver)) ReceiverMoney = rs.getDouble("Money");
                }
                rs.close();
                if (ReceiverMoney == -1) {
                    sender.sendMessage(ChatColor.RED + "Такого игрока не существует.");
                    return;
                } else if (SenderMoney < money) {
                    sender.sendMessage(ChatColor.RED + "На твоём счету недостаточно денег.");
                    return;
                }
                else {
                    String query2 = "SELECT TransferFee FROM Countries WHERE Name='" + sender.getName() + "' OR Name='" + receiver + "';";
                    Statement st2 = con.createStatement();
                    ResultSet rs2 = st2.executeQuery(query2);
                    double fee;
                    if (customFee == -1) {
                        if (rs2.next()) {
                            fee = 1 - rs2.getInt("TransferFee");
                        } else {
                            fee = 0.92;
                        }
                    } else {
                        fee = 1 - customFee;
                    }

                    SenderMoney -= money;
                    ReceiverMoney += (money * fee);
                    String query3 = "UPDATE Users SET Money=" + SenderMoney + " WHERE Name='" + sender.getName() + "';";
                    Statement st3 = con.createStatement();
                    st3.executeUpdate(query3);
                    String query4 = "UPDATE Users SET Money=" + ReceiverMoney + " WHERE Name='" + receiver + "';";
                    Statement st4 = con.createStatement();
                    st4.executeUpdate(query4);
                }
                Player receiverPlayer = Bukkit.getServer().getPlayer(receiver);
                if (receiverPlayer != null) {
                    receiverPlayer.sendMessage("Получено " + money + " от игрока " + sender + ".");
                }
                sender.sendMessage("Вы отправили " + money + "F игроку " +  receiver + ".");
            } catch (Exception e) {
                onError("pay", (Player) sender);
            }
        }
    }

    public void setFrame(@NotNull CommandSender sender, String nickname, String FrameID) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT Users FROM Frames WHERE FrameID ='" + FrameID + "';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                String users = "";
                if (rs.next()) {
                    users = rs.getString("Users");
                    if (rs.getString("Users").isEmpty()) return;
                }
                rs.close();
                String query2 = "UPDATE Frames SET Users='" + users + nickname + ",' WHERE FrameID='" + FrameID + "';";
                Statement st2 = con.createStatement();
                st2.executeUpdate(query2);
            } catch (Exception e) {
                onError("setGroup", (Player) sender);
            }
        }
    }

    public static double calculateSurfaceArea(List<Location> points) {
        if (points.size() < 3) return 0;

        double area = 0;
        int n = points.size();

        // Применяем формулу площади многоугольника (формула "Шу")
        for (int i = 0; i < n; i++) {
            Location current = points.get(i);
            Location next = points.get((i + 1) % n); // Следующая точка, для замыкания контура

            area += current.getX() * next.getZ() - current.getZ() * next.getX();
        }

        return Math.round(Math.abs(area / 2.0) * 100.0) / 100.0; // Площадь должна быть положительной
    }

    public boolean hasPermissionContaining(Player player, String permission) {
        for (PermissionAttachmentInfo permInfo : player.getEffectivePermissions()) {
            if (permInfo.getPermission().contains(permission)) {
                return true; // Игрок имеет право, содержащее "head"
            }
        }
        return false; // Игрок не имеет права, содержащего "head"
    }
}