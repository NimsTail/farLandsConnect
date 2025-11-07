package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;

import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

/**
 * Хранение и проверка пароля игрока:
 *  - Users.Password  -> строка формата: pbkdf2$<iter>$<saltB64>$<hashB64>
 *  - Users.GeneralData.lastAuthAt (millis) и lastAuthIP для TTL сессии
 */
public class AuthService {
    private final ConcurrentHashMap<String, AuthRecord> authCache = new ConcurrentHashMap<>();
    private final AtomicBoolean preloadDone = new AtomicBoolean(false);

    // 24 часа жива сессия (можно менять)
    public static final long AUTH_TTL_MS = UnityLauncher.getAuthTtlMs();

    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RNG = new SecureRandom();
    private static byte[] PEPPER() { return UnityLauncher.getAuthPepper(); }
    private static int ITER() { return UnityLauncher.getAuthIter(); }
    private static int KEY_LEN() { return UnityLauncher.getAuthKeyLen(); }

    /**
     * @param phcHash          $argon2id$...  (или null, если не зарегистрирован)
     * @param lastAuthAtMs     until, если есть активная сессия
     * @param lastIp           сохранённый IP для сессии (может быть null)
     */
    public record AuthRecord(String phcHash, Long lastAuthAtMs, String lastIp, long loadedAtMs) {
        public AuthRecord withPhc(String newPhc) {
            return new AuthRecord(newPhc, this.lastAuthAtMs, this.lastIp, System.currentTimeMillis());
        }
        public AuthRecord withSession(Long lastAtMs, String ip) {
            return new AuthRecord(this.phcHash, lastAtMs, ip, System.currentTimeMillis());
        }
    }

    private static String lc(String name) { return name == null ? null : name.toLowerCase(Locale.ROOT); }

    // --- encode / verify с pepper ---
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    // === ПУБЛИЧНО ===
    public AuthRecord getCached(String nameLc) {
        return authCache.get(nameLc);
    }

    /** Единичная загрузка одного игрока из БД, если промах по кэшу (используй в onJoin). */
    public AuthRecord loadOneFromDb(String nameLc) {
        try (Connection con = DBConnect()) {
            if (con == null) return null;
            String sql = """
            SELECT Password,
                   JSON_EXTRACT(GeneralData, '$.lastAuthAt') AS lastAuthAt,
                   JSON_UNQUOTE(JSON_EXTRACT(GeneralData, '$.lastAuthIP')) AS lastIp
            FROM Users WHERE Name=? LIMIT 1
        """;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, nameLc);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    String phc  = rs.getString("Password");
                    Long at     = parseJsonLong(rs.getString("lastAuthAt"));
                    String lastIp = parseJsonString(rs.getString("lastIp"));
                    AuthRecord rec = new AuthRecord(phc, at, lastIp, System.currentTimeMillis());
                    authCache.put(nameLc, rec);
                    return rec;
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] loadOneFromDb: " + e.getMessage());
            return null;
        }
    }


    /** Прогрев кэша всех пользователей при старте. Дёргаем из onEnable() асинхронно. */
    public void preloadAllAuth() {
        try (Connection con = DBConnect()) {
            if (con == null) return;
            String sql = """
            SELECT Name,
                   Password,
                   JSON_EXTRACT(GeneralData, '$.lastAuthAt') AS lastAuthAt,
                   JSON_UNQUOTE(JSON_EXTRACT(GeneralData, '$.lastAuthIP')) AS lastIp
            FROM Users
        """;
            int n = 0;
            try (PreparedStatement ps = con.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                long now = System.currentTimeMillis();
                while (rs.next()) {
                    String name = rs.getString("Name");
                    String phc  = rs.getString("Password");
                    Long at = parseJsonLong(rs.getString("lastAuthAt"));
                    String lastIp = parseJsonString(rs.getString("lastIp"));
                    authCache.put(lc(name), new AuthRecord(phc, at, lastIp, now));
                    n++;
                }
            }
            Bukkit.getLogger().info("[Auth] Preload complete: " + n + " users cached.");
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] Preload failed: " + e.getMessage());
        } finally {
            preloadDone.set(true);
        }
    }


    // === WRITE-THROUGH ХУКИ (вот ЭТО — основное, про что ты спрашивал) ===

    /** ВЫЗЫВАТЬ после успешной регистрации или смены пароля (после UPDATE Users SET Password=?). */
    public void updateCacheAfterPasswordSet(String playerName, String phcHash) {
        String key = lc(playerName);
        authCache.compute(key, (k, v) -> (v == null ? new AuthRecord(phcHash, null, null, System.currentTimeMillis())
                : v.withPhc(phcHash)));
    }

    /** ВЫЗЫВАТЬ после успешного логина, когда записали сессию (authUntil, lastAuthIp) в БД. */
    public void updateCacheAfterSession(String playerName, long lastAtMs, String ip) {
        String key = lc(playerName);
        authCache.compute(key, (k, v) ->
                (v == null ? new AuthRecord(null, lastAtMs, ip, System.currentTimeMillis())
                        : v.withSession(lastAtMs, ip))
        );
    }

    private static String encode(char[] password, byte[] userSalt, int iter, int keyLen) throws Exception {
        byte[] saltEff = (PEPPER().length == 0) ? userSalt : concat(userSalt, PEPPER());
        PBEKeySpec spec = new PBEKeySpec(password, saltEff, iter, keyLen);
        byte[] raw = javax.crypto.SecretKeyFactory.getInstance(ALGO).generateSecret(spec).getEncoded();
        return "pbkdf2$" + iter + "$" +
                java.util.Base64.getEncoder().encodeToString(userSalt) + "$" +
                java.util.Base64.getEncoder().encodeToString(raw);
    }

    private static boolean verify(char[] password, String stored) throws Exception {
        if (stored == null || !stored.startsWith("pbkdf2$")) return false;
        String[] parts = stored.split("\\$");
        int iter = Integer.parseInt(parts[1]);
        byte[] userSalt = java.util.Base64.getDecoder().decode(parts[2]);
        String recomputed = encode(password, userSalt, iter, KEY_LEN());
        return java.util.Objects.equals(stored, recomputed);
    }

    /* ---------------- DB: Users.Password ---------------- */

//    public boolean isRegistered(String player) {
//        try (Connection con = DBConnect()) {
//            if (con == null) return false;
//            try (PreparedStatement ps = con.prepareStatement("SELECT Password FROM Users WHERE Name=? LIMIT 1")) {
//                ps.setString(1, player);
//                try (ResultSet rs = ps.executeQuery()) {
//                    if (!rs.next()) return false; // нет вообще записи в Users
//                    String v = rs.getString(1);
//                    return v != null && !v.isBlank();
//                }
//            }
//        } catch (Exception e) {
//            Bukkit.getLogger().warning("[Auth] isRegistered: " + e);
//            return false;
//        }
//    }

    public boolean setNewPassword(String player, char[] newPass) {
        try (Connection con = DBConnect()) {
            if (con == null) return false;
            byte[] userSalt = new byte[16]; RNG.nextBytes(userSalt);
            String enc = encode(newPass, userSalt, ITER(), KEY_LEN());
            try (PreparedStatement ps = con.prepareStatement("UPDATE Users SET Password=? WHERE Name=?")) {
                ps.setString(1, enc);
                ps.setString(2, player);
                boolean ok = ps.executeUpdate() > 0;
                if (ok) updateCacheAfterPasswordSet(player, enc);
                return ok;
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] setNewPassword: " + e);
            return false;
        }
    }

    public boolean checkPassword(String player, char[] password) {
        try (Connection con = DBConnect()) {
            if (con == null) return false;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT Password FROM Users WHERE Name=? LIMIT 1")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String stored = rs.getString(1);
                    if (stored == null || stored.isBlank()) return false;
                    return verify(password, stored);
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] checkPassword: " + e);
            return false;
        }
    }

    /* ---------------- Сессии: Users.GeneralData.{lastAuthAt,lastAuthIP} ---------------- */

    public boolean isSessionValid(String player, String ip) {
        AuthRecord rec = authCache.get(lc(player));
        if (rec != null && rec.lastAuthAtMs != null) {
            long now = System.currentTimeMillis();
            if ((now - rec.lastAuthAtMs) <= AUTH_TTL_MS && (ip == null || ip.equals(rec.lastIp))) {
                return true;
            }
        }
        try (Connection con = DBConnect()) {
            if (con == null) return false;
            String sql = """
                SELECT JSON_EXTRACT(GeneralData,'$.lastAuthAt') AS a,
                       JSON_EXTRACT(GeneralData,'$.lastAuthIP') AS ip
                FROM Users WHERE Name=? LIMIT 1
            """;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    Long at = parseJsonLong(rs.getString("a"));
                    String savedIp = parseJsonString(rs.getString("ip"));
                    if (at == null || savedIp == null) return false;
                    long now = System.currentTimeMillis();
                    return (now - at) <= AUTH_TTL_MS && (ip == null || ip.equals(savedIp));
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] isSessionValid: " + e);
            return false;
        }
    }

    public void markSession(String player, String ip) {
        try (Connection con = DBConnect()) {
            if (con == null) return;
            long now = Instant.now().toEpochMilli();
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE Users SET GeneralData = " +
                            "JSON_SET(JSON_SET(COALESCE(GeneralData, JSON_OBJECT()), '$.lastAuthAt', ?), '$.lastAuthIP', ?) " +
                            "WHERE Name=?")) {
                ps.setLong(1, now);
                ps.setString(2, ip == null ? "" : ip);
                ps.setString(3, player);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] markSession: " + e);
        }
    }

    private static Long parseJsonLong(String raw) {
        if (raw == null || raw.equalsIgnoreCase("null") || raw.isBlank()) return null;
        try { return Long.parseLong(raw.replace("\"","").trim()); } catch (Exception ignored) { return null; }
    }
    private static String parseJsonString(String raw) {
        if (raw == null || raw.equalsIgnoreCase("null") || raw.isBlank()) return null;
        raw = raw.trim();
        if (raw.startsWith("\"") && raw.endsWith("\"")) return raw.substring(1, raw.length()-1);
        return raw;
    }



    /** Быстрая проверка через кэш (без похода в БД). */
    public boolean isRegisteredFast(String player) {
        if (player == null) return false;
        var rec = authCache.get(player.toLowerCase(Locale.ROOT));
        return rec != null && rec.phcHash() != null && !rec.phcHash().isBlank();
    }

    /** Стандартная проверка (сначала cache, потом БД). */
    public boolean isRegistered(String player) {
        // быстрый путь
        if (isRegisteredFast(player)) return true;
        // иначе БД
        try (Connection con = DBConnect()) {
            if (con == null) return false;
            try (PreparedStatement ps = con.prepareStatement("SELECT Password FROM Users WHERE Name=? LIMIT 1")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String v = rs.getString(1);
                    boolean ok = v != null && !v.isBlank();
                    if (ok) {
                        // обновим кэш для последующих быстрых проверок
                        authCache.compute(player.toLowerCase(Locale.ROOT), (k, old) ->
                                old == null ? new AuthRecord(v, null, null, System.currentTimeMillis())
                                        : old.withPhc(v)
                        );
                    }
                    return ok;
                }
            }
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] isRegistered: " + e);
            return false;
        }
    }

}
