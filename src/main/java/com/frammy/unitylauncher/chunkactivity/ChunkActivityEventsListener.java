package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

/**
 * Сбор сырых метрик активности по чанкам:
 *  - изменение блоков
 *  - дроп предметов
 *  - спавн мобов
 *  - тиковая нагрузка (воронки / печки / редстоун)
 *  - активность игроков
 *
 * Все данные складываются в ActivityTracker → дальше используются ZoneActivityCalculations.
 */
public class ChunkActivityEventsListener implements Listener {

    private final UnityLauncher plugin;
    private final ActivityTracker tracker;

    // простой антиспам, чтобы не считать каждое смещение на 1 блок как отдельный ивент
    private final Set<String> recentlyMoved = new HashSet<>();

    public ChunkActivityEventsListener(UnityLauncher plugin, ActivityTracker tracker) {
        this.plugin = plugin;
        this.tracker = tracker;
    }

    /* ===================== ВСПОМОГАТЕЛЬНО ===================== */

    private static Chunk chunkOf(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getChunk();
    }

    /* ===================== ITEM DROPS ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item entity = e.getEntity();
        Location loc = entity.getLocation();
        Chunk ch = chunkOf(loc);
        if (ch == null) return;

        ItemStack stack = entity.getItemStack();
        int amount = (stack == null) ? 0 : stack.getAmount();
        if (amount <= 0) return;

        tracker.incItemDrops(ch, amount);
    }

    /* ===================== MOBS / ENTITY COUNT ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        Creature mob = (Creature) e.getEntity();
        Chunk ch = chunkOf(mob.getLocation());
        if (ch == null) return;

        // пока просто считаем каждый спавн как +1
        tracker.incEntitySpawns(ch, 1);
    }

    /* ===================== TICK LOAD ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryMove(InventoryMoveItemEvent e) {
        InventoryHolder holder = e.getInitiator().getHolder();
        if (holder == null) return;

        Location loc = null;
        try {
            if (holder instanceof org.bukkit.block.BlockState bs) {
                loc = bs.getLocation();
            } else if (holder instanceof Entity ent) {
                loc = ent.getLocation();
            }
        } catch (Throwable ignored) {}

        Chunk ch = chunkOf(loc);
        if (ch == null) return;

        // одна операция воронки = условно 1 единица нагрузки
        tracker.incTickLoad(ch, 1.0);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFurnaceBurn(FurnaceBurnEvent e) {
        Chunk ch = chunkOf(e.getBlock().getLocation());
        if (ch == null) return;

        // разжигание печки — чуть меньше, чем воронка
        tracker.incTickLoad(ch, 0.5);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent e) {
        if (e.getOldCurrent() == e.getNewCurrent()) return;

        Chunk ch = chunkOf(e.getBlock().getLocation());
        if (ch == null) return;

        // любой "щелчок" редстоуна — маленькая, но частая нагрузка
        tracker.incTickLoad(ch, 0.2);
    }

    /* ===================== PLAYER ACTIVITY ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent e) {
        // уже есть onPlayerMove в UnityLauncher — мы не ломаем его:
        // он слушает с дефолтным приоритетом, а мы — MONITOR и ничего не отменяем.

        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return;
        }

        Player p = e.getPlayer();
        Chunk ch = chunkOf(e.getTo());
        if (ch == null) return;

        String key = p.getName().toLowerCase();
        if (!recentlyMoved.add(key)) return; // уже считали за последний тик/сек

        // Сбросим флаг через 1 секунду, чтобы не заспамить
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> recentlyMoved.remove(key), 20L);

        tracker.recordPlayerActivity(ch, p.getName(), 1.0);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Location loc = (e.getClickedBlock() != null)
                ? e.getClickedBlock().getLocation()
                : p.getLocation();
        Chunk ch = chunkOf(loc);
        if (ch == null) return;

        tracker.recordPlayerActivity(ch, p.getName(), 0.5);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Chunk ch = chunkOf(p.getLocation());
        if (ch == null) return;

        // Выход игрока — ещё один "сигнал", что в этом чанке была жизнь
        tracker.recordPlayerActivity(ch, p.getName(), 0.2);
    }
}
