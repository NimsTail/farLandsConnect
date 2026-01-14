package com.frammy.unitylauncher;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Properties;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public final class DailyDealService {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Europe/Riga");
    private static final String FILE_NAME = "daily_deal_state.properties";
    private static final String KEY_LAST_DATE = "lastResetDate"; // yyyy-MM-dd

    private final JavaPlugin plugin;
    private final File stateFile;

    public DailyDealService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** Вызывать на старте плагина. Сам решит, надо ли сбрасывать. */
    public void runOnStartup() {
        LocalDate today = LocalDate.now(SERVER_ZONE);
        LocalDate last = readLastDateOrNull();

        // первый старт (файла нет) — просто записываем дату и не трогаем БД
        if (last == null) {
            writeLastDate(today);
            plugin.getLogger().info("[DailyDeal] init lastResetDate=" + today);
            return;
        }

        if (today.isEqual(last)) {
            plugin.getLogger().info("[DailyDeal] no reset needed (today=" + today + ")");
            return;
        }

        plugin.getLogger().warning("[DailyDeal] day changed " + last + " -> " + today
                + ", resetting Users.GeneralData.dayDealCode=\"0\" and dayDealRerollsLeft=2");

        // делаем апдейт асинхронно, чтобы не блокировать старт
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final String sql = """
                UPDATE Users
                SET GeneralData = JSON_SET(
                    COALESCE(GeneralData, JSON_OBJECT()),
                    '$.dayDealCode', '0',
                    '$.dayDealRerollsLeft', 2
                )
                """;

            try (var con = DBConnect()) {
                if (con == null) {
                    plugin.getLogger().severe("[DailyDeal] DBConnect()==null, reset skipped");
                    return;
                }
                try (var ps = con.prepareStatement(sql)) {
                    int rows = ps.executeUpdate();
                    plugin.getLogger().info("[DailyDeal] reset done, rows updated=" + rows);
                }
            } catch (Throwable t) {
                plugin.getLogger().severe("[DailyDeal] reset failed: " + t);
                return;
            }

            // фиксируем новую дату только если SQL реально прошёл
            writeLastDate(today);
        });
    }

    private LocalDate readLastDateOrNull() {
        try {
            if (!stateFile.exists()) return null;
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(stateFile)) {
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String s = p.getProperty(KEY_LAST_DATE, "").trim();
            if (s.isEmpty()) return null;
            return LocalDate.parse(s);
        } catch (Throwable t) {
            plugin.getLogger().warning("[DailyDeal] failed to read state file: " + t);
            return null;
        }
    }

    private void writeLastDate(LocalDate date) {
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            Properties p = new Properties();
            p.setProperty(KEY_LAST_DATE, date.toString());
            try (FileOutputStream out = new FileOutputStream(stateFile)) {
                p.store(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8),
                        "UnityLauncher DailyDeal reset state");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[DailyDeal] failed to write state file: " + t);
        }
    }
}
