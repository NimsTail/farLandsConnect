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

    // Best-effort mirror to the new farlandsconnect backend — never authoritative,
    // never blocks local auth. See FarLandsApiClient for details.
    private final FarLandsApiClient api;

    public AuthService(FarLandsApiClient api) {
        this.api = api;
    }

    // 24 часа жива сессия (можно менять)
    public static final long AUTH_TTL_MS = UnityLauncher.getAuthTtlMs();

    private static final String ALGO = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RNG = new SecureRandom();
    private static byte[] PEPPER() { return UnityLauncher.getAuthPepper(); }
    private static int ITER() { return UnityLauncher.getAuthIter(); }
    private static int KEY_LEN() { return UnityLauncher.getAuthKeyLen(); }

    // --- defaults for new Users row ---
    private static final String[] DEFAULT_AVATARS = new String[]{
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414689588490250/5f5d92d771d25e03b63198ea22bbd686.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414689911447724/6c88c0b4f7c40b1eeb90d4d83e66752f.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414690167308420/9d7daf7436190f91a911709a748be1d0.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414690452516924/44c2757a1fccff9d6535d75030113930.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414690712559806/506b170dd98aaf7cb72a34f443c371b9.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414690993582190/b72dd83f24e172804ab7029f014e60b4.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414691362676897/ccb14ab5b74116a50a020c3d03835402.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414691656286208/d0599d30a02f44c8c5fe5d663d88e4c7.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414691975045170/e410361349e7f0ad35ea873439075d8b.jpg",
            "https://cdn.discordapp.com/attachments/447062336042303488/1158414692285419681/e916110647965958dc45006ec2398cec.jpg"
    };

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String pickDefaultAvatar() {
        int idx = RNG.nextInt(DEFAULT_AVATARS.length);
        return DEFAULT_AVATARS[idx];
    }

    /**
     * Регистрация "как в PHP":
     *  - проверяет уникальность (по Name)
     *  - INSERT в Users со всеми дефолтными JSON полями
     *  - Password кладём как pbkdf2$... (НЕ plaintext)
     *
     * @param regCode может быть null/"".
     * @return true если создали новую запись, false если уже существует или ошибка.
     */
    public boolean registerNewUser(String player, char[] password, String regCode) {
        if (player == null || player.isBlank() || password == null) return false;

        try (Connection con = DBConnect()) {
            if (con == null) return false;

            // 1) uniqueness (как в PHP)
            try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM Users WHERE Name=? LIMIT 1")) {
                ps.setString(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return false; // уже есть
                }
            }

            // 2) Password PBKDF2 (то, что ты уже используешь)
            byte[] userSalt = new byte[16];
            RNG.nextBytes(userSalt);
            String enc = encode(password, userSalt, ITER(), KEY_LEN());

            // 3) JSON payloads (как в PHP)
            String avatar = pickDefaultAvatar();
            String nameEsc = jsonEscape(player);
            String regEsc  = jsonEscape(regCode == null ? "" : regCode);
            String avatarEsc = jsonEscape(avatar);

            String customizationJson = "{"
                    + "\"Name\":\"" + nameEsc + "\","
                    + "\"bgURL\":\"\","
                    + "\"frameID\":\"0\","
                    + "\"avatarURL\":\"" + avatarEsc + "\""
                    + "}";

            String socialJson = "{"
                    + "\"Name\":\"" + nameEsc + "\","
                    + "\"vkURL\":\"\","
                    + "\"otherURL\":\"\","
                    + "\"discordID\":\"\","
                    + "\"telegramID\":\"\","
                    + "\"telegramURL\":\"\""
                    + "}";

            String generalJson = "{"
                    + "\"Name\":\"" + nameEsc + "\","
                    + "\"money\":0.0,"
                    + "\"regCode\":\"" + regEsc + "\","
                    + "\"shopSpots\":1,"
                    + "\"countryName\":\"\","
                    + "\"dayDealCode\":\"0\","
                    + "\"notificationToggle\":true"
                    + "}";

            String statsJson = "{"
                    + "\"Name\":\"" + nameEsc + "\","
                    + "\"rating\":\"0;0;0\","
                    + "\"playtime\":0,"
                    + "\"eventsWon\":0,"
                    + "\"totalPlaytime\":0"
                    + "}";

            // 4) INSERT
            String sql = """
    INSERT INTO Users
    (Name, Password, `Last seen`, CustomizationData, SocialData, GeneralData, StatsData, AuthToken)
    VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
""";

            String lastSeen = "REGISTER"; // или "" если хочешь пусто

            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, player);            // Name
                ps.setString(2, enc);               // Password (pbkdf2$...)
                ps.setString(3, lastSeen);          // `Last seen`
                ps.setString(4, customizationJson); // CustomizationData
                ps.setString(5, socialJson);        // SocialData
                ps.setString(6, generalJson);       // GeneralData
                ps.setString(7, statsJson);         // StatsData

                int rows = ps.executeUpdate();
                if (rows <= 0) return false;
            }

            // 5) прогреем кэш (чтобы isRegisteredFast начал работать сразу)
            updateCacheAfterPasswordSet(player, enc);
            // сессия дальше выставится через AuthListener.completeRegister() -> auth.markSession()

            if (api != null) api.upsertUser(player, new String(password));

            return true;
        } catch (Exception e) {
            Bukkit.getLogger().warning("[Auth] registerNewUser: " + e.getMessage());
            return false;
        }
    }

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

    public boolean setNewPassword(String player, char[] newPass) {
        try (Connection con = DBConnect()) {
            if (con == null) return false;
            byte[] userSalt = new byte[16]; RNG.nextBytes(userSalt);
            String enc = encode(newPass, userSalt, ITER(), KEY_LEN());
            try (PreparedStatement ps = con.prepareStatement("UPDATE Users SET Password=? WHERE Name=?")) {
                ps.setString(1, enc);
                ps.setString(2, player);
                boolean ok = ps.executeUpdate() > 0;
                if (ok) {
                    updateCacheAfterPasswordSet(player, enc);
                    if (api != null) api.changePassword(player, new String(newPass));
                }
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
