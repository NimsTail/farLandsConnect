package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Generic persistent "accumulate N seconds while a condition holds" tracker
 * with a short grace period so a brief interruption (lag, stepping out for
 * a couple seconds) doesn't wipe multi-day progress — same shape as the
 * original BiomeTimeAdvancements (Ach1_1_1), generalized so new "N часов/
 * дней" achievements don't each need their own copy.
 */
public class PersistentTimeConditionListener implements Listener, Runnable {

    private static final int OUT_RESET_SECONDS = 10;

    private final UnityLauncher plugin;
    private final Predicate<Player> condition;
    private final FrameRewardAdvancement advancement;
    private final int requiredSeconds;

    private final NamespacedKey timeKey;
    private final NamespacedKey outKey;
    private final NamespacedKey doneKey;

    private BukkitTask task;
    private final Set<UUID> online = new HashSet<>();

    public PersistentTimeConditionListener(UnityLauncher plugin, String keyPrefix, Predicate<Player> condition,
                                            FrameRewardAdvancement advancement, int requiredSeconds) {
        this.plugin = plugin;
        this.condition = condition;
        this.advancement = advancement;
        this.requiredSeconds = requiredSeconds;
        this.timeKey = new NamespacedKey(plugin, keyPrefix + "_time_s");
        this.outKey = new NamespacedKey(plugin, keyPrefix + "_out_s");
        this.doneKey = new NamespacedKey(plugin, keyPrefix + "_done");
    }

    public void start() {
        if (task != null) return;
        for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 20L); // раз в секунду
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        online.add(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        online.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public void run() {
        for (UUID uuid : online) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            PersistentDataContainer pdc = p.getPersistentDataContainer();
            if (pdc.has(doneKey, PersistentDataType.BYTE)) continue;

            boolean ok = condition.test(p);

            int time = pdc.getOrDefault(timeKey, PersistentDataType.INTEGER, 0);
            int out = pdc.getOrDefault(outKey, PersistentDataType.INTEGER, 0);

            if (ok) {
                time += 1;
                out = 0;
            } else {
                out += 1;
                if (out > OUT_RESET_SECONDS) {
                    time = 0;
                    out = 0;
                }
            }

            pdc.set(timeKey, PersistentDataType.INTEGER, time);
            pdc.set(outKey, PersistentDataType.INTEGER, out);

            if (time >= requiredSeconds) {
                advancement.grant(p);
                pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
    }
}
