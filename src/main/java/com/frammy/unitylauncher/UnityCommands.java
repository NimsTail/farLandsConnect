package com.frammy.unitylauncher;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;
import static com.frammy.unitylauncher.UnityLauncher.onError;

public class UnityCommands {
    private static UnityCommands instance;

    public static UnityCommands getInstance() {
        if (instance == null) {
            instance = new UnityCommands();
        }
        return instance;
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
                onError("getGroup", e, (Player) sender);
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
                onError("setGroup", e, (Player) sender);
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
                    if (rs.getString("PermissionGroups").length() == 0) return;
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
                onError("setPrefix", e, (Player) sender);
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
                        sender.sendMessage(ChatColor.RED + "Получи задание!");
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
                        sender.sendMessage(ChatColor.GREEN + "Вы выполнили задание!" + ChatColor.RESET + " Возвращайтесь завтра");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Такого кода не существует!");
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Вас нет в базе!");
                    return;
                }
                rs.close();
            } catch (Exception e) {
                onError("dayDeal", e, (Player) sender);
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
                onError("money", e, (Player) sender);
            }
        }
    }

    public void changePass(@NotNull CommandSender sender, String old, String password) {
        Connection con = DBConnect();
        if (con != null || password != null) {
            try {
                String querySelect = "SELECT Name, Password FROM Users WHERE Name='" + sender.getName() + "';";
                assert con != null;
                Statement stSelect = con.createStatement();
                ResultSet rs = stSelect.executeQuery(querySelect);
                if (rs.next()) {
                    if (!Objects.equals(old, rs.getString("Password"))) {
                        sender.sendMessage(ChatColor.RED + "Неверный текущий пароль");
                        return;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Сначала зарегистрируйтесь в лаунчере");
                    return;
                }
                rs.close();
                String queryUpdate = "UPDATE Users SET Password = '"+password+"' WHERE Name = '"+sender.getName()+"';";
                Statement st = con.createStatement();
                st.executeUpdate(queryUpdate);
                sender.sendMessage("Пароль успешно изменен");
            } catch (Exception e) {
                onError("passCh", e, (Player) sender);
            }
        }
    }

    public void getMoney(@NotNull CommandSender sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT Money FROM Users WHERE Name='"+sender.getName()+"';";
                Statement st = con.createStatement();
                ResultSet rs = st.executeQuery(query);
                if (rs.next()) {
                    String money = rs.getString(1);
                    if (money == null || money.equals("0")) {
                        sender.sendMessage(ChatColor.RED + "Твои карманы пусты, странник!");
                        return;
                    } else
                        sender.sendMessage("У тебя: " + ChatColor.GREEN + money + ChatColor.RESET + " шекелей!");
                } else {
                    sender.sendMessage(ChatColor.RED + "Вас нет в базе!");
                    return;
                }
                rs.close();
            } catch (Exception e) {
                onError("getMoney", e, (Player) sender);
            }
        }
    }

    public void setShops(@NotNull CommandSender sender, int shopCount) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "UPDATE Users SET AvailableShopPlaces='"+ shopCount +"' WHERE Name='"+sender.getName()+"';";
                Statement st = con.createStatement();
                st.executeUpdate(query);

            } catch (Exception e) {
                onError("getShops", e, (Player) sender);
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
                onError("getShops", e, (Player) sender);
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
                onError("getTop", e, (Player) sender);
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
                onError("getCountry", e, (Player) sender);
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
                onError("rCode", e, (Player) sender);
            }
        }
    }

    public void getNotifications(@NotNull CommandSender sender) {
        getNotified(sender);
    }
    public GeneralData getPlayerInfo(Player sender) {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "SELECT GeneralData FROM Users WHERE Name = ?;";
                PreparedStatement st = con.prepareStatement(query);
                st.setString(1, sender.getName());
                ResultSet rs = st.executeQuery();

                if (rs.next()) {
                    String generalDataJson = rs.getString("GeneralData");
                    if (generalDataJson == null || generalDataJson.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "У тебя нет данных GeneralData.");
                        return null;
                    }

                    Gson gson = new Gson();
                    GeneralData data = gson.fromJson(generalDataJson, GeneralData.class);

                    return data;
                } else {
                    sender.sendMessage(ChatColor.RED + "Ты не найден в базе данных.");
                    return null;
                }
            } catch (Exception e) {
                onError("getPlayerInfo", e, (Player) sender);
            }
        }
        return null;
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
                onError("notifications", e, (Player) sender);
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
                onError("notificationsToggle", e, (Player) sender);
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
                onError("pay", e, (Player) sender);
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
                    if (rs.getString("Users").isEmpty()) {
                     //   sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одной стране! Будь осторожнее!");
                        return;
                    }
                }
                rs.close();
                String query2 = "UPDATE Frames SET Users='" + users + nickname + ",' WHERE FrameID='" + FrameID + "';";
                Statement st2 = con.createStatement();
                st2.executeUpdate(query2);
            } catch (Exception e) {
                onError("setGroup", e, (Player) sender);
            }
        }
    }
}
