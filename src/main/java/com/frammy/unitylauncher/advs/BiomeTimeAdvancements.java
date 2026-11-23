package com.frammy.unitylauncher.advs;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_1_1;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
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

public class BiomeTimeAdvancements implements Listener, Runnable {

    private final UnityLauncher plugin;
    private final Ach1_1_1 ach;

    // 5 Minecraft-дней = 5 * 20 минут = 100 минут = 6000 секунд
    private static final int REQUIRED_SECONDS = 6000;
    private static final int OUT_RESET_SECONDS = 10;

    private final NamespacedKey timeKey;
    private final NamespacedKey outKey;
    private final NamespacedKey doneKey;

    private BukkitTask task;
    private final Set<UUID> online = new HashSet<>();

    public BiomeTimeAdvancements(UnityLauncher plugin, Ach1_1_1 ach) {
        this.plugin = plugin;
        this.ach = ach;
        this.timeKey = new NamespacedKey(plugin, "ach1_1_1_cherry_time_s");
        this.outKey  = new NamespacedKey(plugin, "ach1_1_1_out_s");
        this.doneKey = new NamespacedKey(plugin, "ach1_1_1_done");
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this, 20L, 20L); // раз в 1 секунду
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        online.clear();
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
            if (pdc.has(doneKey, PersistentDataType.BYTE)) continue; // уже выполнено

            // --- Проверяем биом ---
            boolean inCherry = isCherryBiome(p);

            int time = pdc.getOrDefault(timeKey, PersistentDataType.INTEGER, 0);
            int out  = pdc.getOrDefault(outKey,  PersistentDataType.INTEGER, 0);

            if (inCherry) {
                time += 1;
                out = 0;
            } else {
                out += 1;
                if (out > OUT_RESET_SECONDS) {
                    // сброс прогресса
                    time = 0;
                    out = 0;
                }
            }

            pdc.set(timeKey, PersistentDataType.INTEGER, time);
            pdc.set(outKey,  PersistentDataType.INTEGER, out);

            if (time >= REQUIRED_SECONDS) {
                ach.grant(p); // ✅ выдаём достижение -> вызовется giveReward()
                pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
    }

    private boolean isCherryBiome(Player p) {
        // В 1.20+ биом называется CHERRY_GROVE
        // Если у тебя другая версия/название — скажи, подстрою.
        try {
            World w = p.getWorld();
            return w.getBiome(p.getLocation()) == org.bukkit.block.Biome.CHERRY_GROVE;
        } catch (Throwable ignored) {
            // fallback для старых API:
            return p.getLocation().getBlock().getBiome() == org.bukkit.block.Biome.CHERRY_GROVE;
        }
    }
}

