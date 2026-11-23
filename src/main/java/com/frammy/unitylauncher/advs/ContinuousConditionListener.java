package com.frammy.unitylauncher.advs;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Generic "hold some condition continuously for a full game day" tracker —
 * for achievements like "проносить весь игровой день" where the state has
 * to stay unbroken, not just accumulate. Polls every CHECK_INTERVAL_TICKS;
 * any tick the condition doesn't hold resets that player's streak.
 */
public class ContinuousConditionListener extends BukkitRunnable {

    private static final long CHECK_INTERVAL_TICKS = 100L; // 5 сек
    // "Игровой день" ≈ 20 реальных минут при обычной скорости тиков.
    private static final long REQUIRED_MS = 20L * 60 * 1000;

    private final Predicate<Player> condition;
    private final FrameRewardAdvancement advancement;
    private final Map<UUID, Long> streakStart = new HashMap<>();

    public ContinuousConditionListener(Predicate<Player> condition, FrameRewardAdvancement advancement) {
        this.condition = condition;
        this.advancement = advancement;
    }

    public void start(Plugin plugin) {
        runTaskTimer(plugin, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!condition.test(p)) {
                streakStart.remove(p.getUniqueId());
                continue;
            }
            long start = streakStart.computeIfAbsent(p.getUniqueId(), k -> now);
            if (now - start >= REQUIRED_MS) {
                advancement.grant(p);
                streakStart.remove(p.getUniqueId());
            }
        }
    }
}
