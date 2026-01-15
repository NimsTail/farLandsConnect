package com.frammy.unitylauncher.advs;
import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;


import javax.annotation.Nullable;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public final class FramesDao {
    private FramesDao() {}

    public static void addUserToFrame(int frameId, String nick) {
        try (Connection con = UnityLauncher.DBConnect()) {
            if (con == null) return;

            con.setAutoCommit(false);

            String selectSql = "SELECT Users FROM Frames WHERE FrameID = ? FOR UPDATE";
            String updateSql = "UPDATE Frames SET Users = ? WHERE FrameID = ?";

            String current;

            try (PreparedStatement ps = con.prepareStatement(selectSql)) {
                ps.setInt(1, frameId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        con.rollback();
                        return;
                    }
                    current = rs.getString(1);
                }
            }

            // распарсили CSV (любой формат: "admin," / "admin, NimsTail_" / " admin ,")
            Set<String> users = new LinkedHashSet<>();
            if (current != null && !current.isBlank()) {
                for (String part : current.split(",")) {
                    String u = part.trim();
                    if (!u.isEmpty()) users.add(u);
                }
            }

            // уже есть — ничего не делаем
            if (users.contains(nick)) {
                con.rollback();
                return;
            }

            users.add(nick);

            String joined = String.join(", ", users); // если хочешь без пробела: ","
            try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                ps.setString(1, joined);
                ps.setInt(2, frameId);
                ps.executeUpdate();
            }

            con.commit();
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[UnityLauncher] FramesDao.addUserToFrame error: " + t.getMessage());
            // если хочешь: UnityLauncher.onError("DBError", null);
        }
    }
}
