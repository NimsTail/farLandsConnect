package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthBossbarManager {

    // сколько секунд даём на вход/регистрацию
    public static final int TIMEOUT_SECONDS = 60;

    private final UnityLauncher plugin;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> leftSec = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();

    public AuthBossbarManager(UnityLauncher plugin) {
        this.plugin = plugin;
    }

    /** Запустить персональный таймер для игрока. */
    public void startTimer(Player p, boolean isRegister) {
        stopTimer(p); // на всякий

        BossBar bar = Bukkit.createBossBar(
                title(isRegister, TIMEOUT_SECONDS),
                BarColor.RED,
                BarStyle.SEGMENTED_10
        );
        bar.setProgress(1.0);
        bar.addPlayer(p);

        bars.put(p.getUniqueId(), bar);
        leftSec.put(p.getUniqueId(), TIMEOUT_SECONDS);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Integer rem = leftSec.get(p.getUniqueId());
            if (rem == null) return;

            // уже авторизован? (AuthListener помечает)
            if (plugin.getAuthListener() != null && plugin.getAuthListener().isAuthenticated(p)) {
                stopTimer(p);
                return;
            }

            int next = rem - 1;
            if (next <= 0) {
                stopTimer(p);
                p.kickPlayer(ChatColor.RED + "Время на авторизацию истекло. Повторите вход.");
                return;
            }

            leftSec.put(p.getUniqueId(), next);
            BossBar b = bars.get(p.getUniqueId());
            if (b != null) {
                b.setProgress(Math.max(0, Math.min(1, next / (double) TIMEOUT_SECONDS)));
                b.setTitle(title(isRegister, next));
            }
        }, 20L, 20L); // ежесекундно

        tasks.put(p.getUniqueId(), task);
    }

    /** Остановить и убрать боссбар. */
    public void stopTimer(Player p) {
        UUID id = p.getUniqueId();
        BukkitTask t = tasks.remove(id);
        if (t != null) t.cancel();

        BossBar bar = bars.remove(id);
        if (bar != null) {
            bar.removeAll();
        }
        leftSec.remove(id);
    }

    private static String title(boolean isRegister, int secLeft) {
        String action = isRegister ? "Регистрация" : "Вход";
        return ChatColor.YELLOW + action + ChatColor.GRAY + " • Осталось " +
                ChatColor.GOLD + secLeft + ChatColor.GRAY + " c";
    }
}
