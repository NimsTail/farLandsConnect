package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class AuthListener implements Listener {

    private final UnityLauncher plugin;
    private final AuthService auth;
    /** Joined, async registration/session check still running: blocked like needLogin. Value = per-join token. */
    private final Map<UUID, Object> pendingCheck = new ConcurrentHashMap<>();
    private final Set<UUID> needLogin = ConcurrentHashMap.newKeySet();
    private final Set<UUID> needRegister = ConcurrentHashMap.newKeySet();
    private final AuthBossbarManager bossbars;
    private final Map<UUID, Long> lastPrompt = new ConcurrentHashMap<>();
    private static final long PROMPT_COOLDOWN_MS = 3000L; // раз в 3 секунды максимум

    public AuthListener(UnityLauncher plugin, AuthService auth, AuthBossbarManager bossbars) {
        this.plugin = plugin;
        this.auth = auth;
        this.bossbars = bossbars;
    }

    public boolean isAuthenticated(Player p) {
        UUID id = p.getUniqueId();
        return !(pendingCheck.containsKey(id) || needLogin.contains(id) || needRegister.contains(id));
    }

    /* --------- JOIN/QUIT --------- */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        String name = p.getName();
        String ip = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : null;

        // Блокируем сразу: проверка ниже идёт в БД асинхронно, и до её ответа
        // игрок не считается вошедшим (isAuthenticated смотрит pendingCheck).
        Object token = new Object();
        pendingCheck.put(id, token);

        UnityLauncher.runDbAsync(() -> {
            boolean hasPass = auth.isRegistered(name);
            boolean sessionOk = hasPass && auth.isSessionValid(name, ip);

            Bukkit.getScheduler().runTask(plugin, () -> {
                // Игрок вышел (или вышел и зашёл заново — там уже свой токен),
                // либо успел войти через /login, пока шла проверка.
                if (!pendingCheck.remove(id, token)) return;

                if (sessionOk) {
                    send(p, ChatColor.GREEN + "Сессия подтверждена — добро пожаловать!");
                    return;
                }

                // нет пароля -> регистрация, иначе логин
                if (!hasPass) {
                    needRegister.add(id);
                    if (bossbars != null) bossbars.startTimer(p, true);
                } else {
                    needLogin.add(id);
                    if (bossbars != null) bossbars.startTimer(p, false);
                }
                promptIfDue(p);
            });
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (bossbars != null) bossbars.stopTimer(e.getPlayer());
        // Only report last-seen for players who actually authenticated this session —
        // an unregistered/never-logged-in player has no matching User row on the backend.
        if (isAuthenticated(e.getPlayer())) {
            UnityLauncher plugin = UnityLauncher.getInstance();
            if (plugin != null && plugin.getFarLandsApi() != null) {
                plugin.getFarLandsApi().lastSeen(e.getPlayer().getName());
            }
        }
        pendingCheck.remove(id);
        needLogin.remove(id);
        needRegister.remove(id);
        lastPrompt.remove(e.getPlayer().getUniqueId());
    }

    /* --------- БЛОКИРОВКИ ПОКА НЕ ВОШЁЛ --------- */

    private boolean blockIfNotAuth(Player p) {
        if (isAuthenticated(p)) return false;
        promptIfDue(p); // мягкое напоминание с троттлингом в action bar
        return true;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getX() == e.getTo().getX() &&
                e.getFrom().getZ() == e.getTo().getZ()) return; // разрешаем поворот без перемещения
        if (blockIfNotAuth(e.getPlayer())) e.setTo(e.getFrom());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && !isAuthenticated(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && !isAuthenticated(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (blockIfNotAuth(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (blockIfNotAuth(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInv(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && blockIfNotAuth(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (blockIfNotAuth(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && blockIfNotAuth(p)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        if (blockIfNotAuth(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent e) {
        if (blockIfNotAuth(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (blockIfNotAuth(e.getPlayer())) e.setCancelled(true);
    }

    private static final java.util.Set<String> PRE_LOGIN_COMMANDS =
            java.util.Set.of("/login", "/l", "/register", "/reg");

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (isAuthenticated(p)) return;

        // Exact command names only: a prefix check also let /lp, /luckperms,
        // /loot etc. through before login (offline-mode server, nick spoofing).
        String cmd = e.getMessage().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (PRE_LOGIN_COMMANDS.contains(cmd)) return;

        e.setCancelled(true);
        blockIfNotAuth(p);
    }

    /* --------- Внешние хуки для UnityCommands --------- */

    /** Вызови это после УСПЕШНОГО логина (когда введён пароль и записана сессия в БД — markSession делает вызывающий, async). Main thread. */
    public void completeLogin(Player p) {
        pendingCheck.remove(p.getUniqueId());
        needLogin.remove(p.getUniqueId());
        needRegister.remove(p.getUniqueId());
        if (bossbars != null) bossbars.stopTimer(p);
        send(p, ChatColor.GREEN + "Ты вошёл. Приятной игры!");
    }

    /** Вызови это после УСПЕШНОЙ регистрации (когда Password и сессия записаны в БД — markSession делает вызывающий, async). Main thread. */
    public void completeRegister(Player p) {
        pendingCheck.remove(p.getUniqueId());
        needLogin.remove(p.getUniqueId());
        needRegister.remove(p.getUniqueId());
        if (bossbars != null) bossbars.stopTimer(p);
        send(p, ChatColor.GREEN + "Пароль установлен. Ты вошёл!");
    }

    private static void send(Player p, String msg) {
        p.sendMessage(msg);
    }

    private void showActionBar(Player p, String msg) {
        p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new net.md_5.bungee.api.chat.TextComponent(msg));
    }

    private void promptIfDue(Player p) {
        UUID id = p.getUniqueId();
        // Пока идёт проверка при входе подсказывать нечего — и не жжём кулдаун,
        // иначе первая подсказка после проверки может проглотиться.
        if (!needLogin.contains(id) && !needRegister.contains(id)) return;
        long now = System.currentTimeMillis();
        long last = lastPrompt.getOrDefault(id, 0L);
        if (now - last < PROMPT_COOLDOWN_MS) return; // ещё рано — не спамим

        lastPrompt.put(id, now);

        if (needLogin.contains(id)) {
            showActionBar(p, ChatColor.YELLOW + "Введи пароль: " + ChatColor.GOLD + "/login <пароль>");
        } else if (needRegister.contains(id)) {
            showActionBar(p, ChatColor.YELLOW + "Зарегистрируй пароль: " + ChatColor.GOLD + "/reg <пароль>");
        }
    }

}
