package com.frammy.unitylauncher.database;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class DatabaseManager {

    @Nullable
    public static Connection DBConnect() {
        try {
            Class.forName("com.mysql.jdbc.Driver");
            String url = "jdbc:mysql://mysql.apexhosting.gdn:3306/apexMC1473088";
            String username = "apexMC1473088";
            String password = "H#pXkkgG8SbaexeB6azGXMlm";
            return DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            onError("DBError", e, null);
            return null;
        }
    }

    public static void resetDayDealCode() {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "UPDATE Users SET DayDealCode=0 WHERE 1;";
                Statement st = con.createStatement();
                st.executeUpdate(query);
            } catch (Exception ex) {
                onError("NotInBase", ex, null);
            }
        }
    }

    public static void updatePlaytime(Map<String, Long> playTime) {
        Connection con = DBConnect();
        if (con != null) {
            playTime.forEach((key, value) -> {
                try {
                    String query2 = "SELECT Playtime FROM Users WHERE Name='" + key + "';";
                    Statement st2 = con.createStatement();
                    ResultSet rs2 = st2.executeQuery(query2);
                    long sqlTime = 0;
                    if (rs2.next()) {
                        sqlTime = rs2.getInt("Playtime");
                    } else {
                        Bukkit.getConsoleSender().sendMessage("No player " + key + " in database");
                    }
                    sqlTime += value;
                    String query3 = "UPDATE Users SET Playtime=" + sqlTime + " WHERE Name='" + key + "';";
                    Statement st3 = con.createStatement();
                    st3.executeUpdate(query3);
                } catch (Exception e) {
                    onError("", e, null);
                }
            });
        }
    }

    public static void onError(String reason, Exception e, Player p) {
        e.printStackTrace();
        switch (reason) {
            case "NotInBase":
                if (p != null) p.sendMessage("§cВас не существует в базе!");
                break;
            case "SignErr":
                if (p != null) p.sendMessage("§cОшибка при оплате по табличке!");
                break;
            case "DBError":
                if (p != null) p.sendMessage("§cОшибка при соединении с базой!");
                break;
            default:
                break;
        }
    }
}
