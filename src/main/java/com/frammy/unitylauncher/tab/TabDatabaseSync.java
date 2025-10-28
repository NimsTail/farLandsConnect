package com.frammy.unitylauncher.tab;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/**
 * Минимальный DAO для работы с таблицами TAB:
 * - tab_groups: определяет свойства группы (tabprefix, tagprefix, ...)
 * - tab_users: перс-оверрайды (используем только при необходимости)
 * <p>
 * НИЧЕГО не кэширует сам: вызывай его из своего кэширующего слоя/тасок.
 *
 * @param server можно оставить null, если нет мульти-серверной схемы
 * @param world  можно оставить null
 */
public record TabDatabaseSync(DataSource ds, String server, String world, Plugin plugin) {

    /* --------------------------------------------------------
       GROUPS
       -------------------------------------------------------- */

    /**
     * Считает все существующие группы TAB.
     */
    public Set<String> loadExistingTabGroups() {
        String sql = "SELECT `group` FROM tab_groups GROUP BY `group`";
        try (Connection c = ds.getConnection();
             PreparedStatement st = c.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            Set<String> out = new HashSet<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[TAB-DB] loadExistingTabGroups: " + e.getMessage());
            return Collections.emptySet();
        }
    }

    // === ASYNC UPSERT игрока в табличку TAB (prefix/suffix) ===
// Требуется MySQL с UNIQUE по uuid в таблице tab_users.
// Если у тебя другое имя таблицы/поля — поправь SQL ниже.
// === ASYNC UPSERT игрока в табличку TAB (prefix/suffix) ===
// Требуется MySQL с UNIQUE по uuid в таблице tab_users.
// Если у тебя другое имя таблицы/поля — поправь SQL ниже.
    public void upsertPlayerPrefixAsync(java.util.UUID uuid, String prefix, String suffix) {
        if (uuid == null) return;
        final String userKey = uuid.toString(); // у тебя в дампе user бывает UUID-строкой

        final String pfx = (prefix != null && !prefix.isBlank()) ? prefix : null;
        final String sfx = (suffix != null && !suffix.isBlank()) ? suffix : null;

        if (pfx == null && sfx == null) {
            clearUserOverrides(uuid);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String deleteSql =
                    "DELETE FROM tab_users " +
                            "WHERE `user`=? " +
                            "  AND `property` IN ('tabprefix','tagprefix','tabsuffix','tagsuffix') " +
                            "  AND ( ( ? IS NULL AND `world` IS NULL ) OR `world`=? ) " +
                            "  AND ( ( ? IS NULL AND `server` IS NULL ) OR `server`=? )";

            String insertSql = "INSERT INTO tab_users(`user`,`property`,`value`,`world`,`server`) VALUES(?,?,?,?,?)";

            try (Connection c = ds.getConnection()) {
                // 1) Сносим старые строки для этого пользователя/мира/сервера
                try (PreparedStatement del = c.prepareStatement(deleteSql)) {
                    del.setString(1, userKey);
                    del.setString(2, world);
                    del.setString(3, world);
                    del.setString(4, server);
                    del.setString(5, server);
                    del.executeUpdate();
                }

                // 2) Вставляем новые значения (по факту — upsert без уникальных ключей)
                try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                    if (pfx != null) {
                        // tab-лист
                        ins.setString(1, userKey);
                        ins.setString(2, "tabprefix");
                        ins.setString(3, pfx);
                        ins.setString(4, world);
                        ins.setString(5, server);
                        ins.addBatch();

                        // над головой
                        ins.setString(1, userKey);
                        ins.setString(2, "tagprefix");
                        ins.setString(3, pfx);
                        ins.setString(4, world);
                        ins.setString(5, server);
                        ins.addBatch();
                    }

                    if (sfx != null) {
                        // tab-лист
                        ins.setString(1, userKey);
                        ins.setString(2, "tabsuffix");
                        ins.setString(3, sfx);
                        ins.setString(4, world);
                        ins.setString(5, server);
                        ins.addBatch();

                        // над головой
                        ins.setString(1, userKey);
                        ins.setString(2, "tagsuffix");
                        ins.setString(3, sfx);
                        ins.setString(4, world);
                        ins.setString(5, server);
                        ins.addBatch();
                    }

                    ins.executeBatch();
                }
            } catch (SQLException e) {
                Bukkit.getLogger().warning("[UnityLauncher] TabDatabaseSync.upsertPlayerPrefixAsync error: " + e.getMessage());
            }
        });
    }

    /* --------------------------------------------------------
       USERS (если вдруг нужны персональные оверрайды TAB)
       -------------------------------------------------------- */

    /**
     * Снять персональные оверрайды TAB у игрока (tab_users).
     */
    public void clearUserOverrides(UUID uuid) {
        String sql = "DELETE FROM tab_users WHERE `user`=?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            Bukkit.getLogger().warning("[TAB-DB] clearUserOverrides: " + e.getMessage());
        }
    }
}
