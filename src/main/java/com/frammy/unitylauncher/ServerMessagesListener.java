package com.frammy.unitylauncher;

import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.ThunderChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class ServerMessagesListener implements Listener {

    private static UnityLauncher plugin;
    private static final Random RNG = new Random();

    private static boolean DEBUG = false;

    private static boolean JOIN_ENABLED;
    private static boolean QUIT_ENABLED;
    private static boolean ADV_ENABLED;
    private static boolean DEATH_ENABLED;
    private static boolean FIRSTJOIN_ENABLED;
    private static boolean THUNDER_ENABLED;

    private static double THUNDER_CHANCE = 0.01; // 1%

    private static List<String> JOIN_TEMPLATES       = new ArrayList<>();
    private static List<String> QUIT_TEMPLATES       = new ArrayList<>();
    private static List<String> ADV_TEMPLATES        = new ArrayList<>();
    private static List<String> DEATH_TEMPLATES      = new ArrayList<>();
    private static List<String> FIRSTJOIN_TEMPLATES  = new ArrayList<>();
    private static List<String> THUNDER_TEMPLATES    = new ArrayList<>();

    private ServerMessagesListener() {
        // singleton через init()
    }

    /**
     * Вызывается из onEnable().
     */
    public static void init(UnityLauncher pl) {
        plugin = pl;
        reloadMessages(); // прогружаем конфиг + дефолты
        Bukkit.getPluginManager().registerEvents(new ServerMessagesListener(), pl);
        plugin.getLogger().info("[UL] ServerMessagesListener initialized");
    }

    /**
     * Перечитывает фразы из config.yml.
     * Вызывается из /ul reload и из init().
     */
    public static void reloadMessages() {
        if (plugin == null) {
            return;
        }

        FileConfiguration c = plugin.getConfig();

        // --- дефолтный блок, если serverMessages отсутствует полностью ---
        if (!c.isConfigurationSection("serverMessages")) {
            c.set("serverMessages.debug", true);

            c.set("serverMessages.join.enabled", true);
            c.set("serverMessages.join.templates", List.of(
                    "&e%player% &7зашёл на Farlands.",
                    "&e%player% &7решил проверить, жив ли сервер."
            ));

            c.set("serverMessages.quit.enabled", true);
            c.set("serverMessages.quit.templates", List.of(
                    "&e%player% &7вышел с сервера.",
                    "&e%player% &7испарился в неизвестном направлении."
            ));

            c.set("serverMessages.advancement.enabled", true);
            c.set("serverMessages.advancement.templates", List.of(
                    "&6%player% &7получил достижение &e[%title%]&7.",
                    "&6%player% &7открыл новую ачивку &e[%title%]&7!"
            ));

            c.set("serverMessages.death.enabled", true);
            c.set("serverMessages.death.templates", List.of(
                    "&c%player% &7умер. &8(%vanilla%)",
                    "&c%player% &7погиб как герой. &8[%vanilla%]",
                    "&c%player% &7снова проверил физику на прочность. &8(%vanilla%)"
            ));

            c.set("serverMessages.firstJoin.enabled", true);
            c.set("serverMessages.firstJoin.templates", List.of(
                    "&d%player% &7впервые зашёл на &5Farlands&7. Никто ему не объяснил, что тут происходит.",
                    "&dНовый человек: &b%player%&7. Не кусайтесь, игроки.",
                    "&d%player% &7появился здесь впервые. Отменить уже нельзя."
            ));

            c.set("serverMessages.thunder.enabled", true);
            c.set("serverMessages.thunder.chance", 0.01);
            c.set("serverMessages.thunder.templates", List.of(
                    "&9Гроза над Farlands &7ворчит что-то про лаги.",
                    "&9Небо орёт, молнии шлёт. &7Сервер делает вид, что так и должно быть.",
                    "&9Где-то над Farlands грохочет. &7Кто-то явно сейчас строит из ТНТ."
            ));

            plugin.saveConfig();
        }

        DEBUG = c.getBoolean("serverMessages.debug", false);

        JOIN_ENABLED       = getBooleanWithDefault(c, "serverMessages.join.enabled", true);
        QUIT_ENABLED       = getBooleanWithDefault(c, "serverMessages.quit.enabled", true);
        ADV_ENABLED        = getBooleanWithDefault(c, "serverMessages.advancement.enabled", true);
        DEATH_ENABLED      = getBooleanWithDefault(c, "serverMessages.death.enabled", true);
        FIRSTJOIN_ENABLED  = getBooleanWithDefault(c, "serverMessages.firstJoin.enabled", true);
        THUNDER_ENABLED    = getBooleanWithDefault(c, "serverMessages.thunder.enabled", true);

        THUNDER_CHANCE = c.getDouble("serverMessages.thunder.chance", 0.01);

        JOIN_TEMPLATES = nonEmptyOrDefault(
                c.getStringList("serverMessages.join.templates"),
                List.of("&e%player% &7зашёл на сервер.")
        );
        QUIT_TEMPLATES = nonEmptyOrDefault(
                c.getStringList("serverMessages.quit.templates"),
                List.of("&e%player% &7вышел с сервера.")
        );
        ADV_TEMPLATES = nonEmptyOrDefault(
                c.getStringList("serverMessages.advancement.templates"),
                List.of("&6%player% &7получил достижение &e[%title%]&7.")
        );
        DEATH_TEMPLATES = nonEmptyOrDefault(
                c.getStringList("serverMessages.death.templates"),
                List.of("&c%player% &7умер. &8(%vanilla%)")
        );
        FIRSTJOIN_TEMPLATES = nonEmptyOrDefault(
                c.getStringList("serverMessages.firstJoin.templates"),
                List.of("&d%player% &7впервые зашёл на Farlands.")
        );
        THUNDER_TEMPLATES = nonEmptyOrDefault(
                c.getStringList("serverMessages.thunder.templates"),
                List.of("&9Гроза над Farlands &7ворчит что-то про лаги.")
        );

        plugin.saveConfig();

        if (DEBUG) {
            plugin.getLogger().info("[UL] ServerMessagesListener config reloaded:");
            plugin.getLogger().info("  join       enabled=" + JOIN_ENABLED      + " templates=" + JOIN_TEMPLATES.size());
            plugin.getLogger().info("  quit       enabled=" + QUIT_ENABLED      + " templates=" + QUIT_TEMPLATES.size());
            plugin.getLogger().info("  adv        enabled=" + ADV_ENABLED       + " templates=" + ADV_TEMPLATES.size());
            plugin.getLogger().info("  death      enabled=" + DEATH_ENABLED     + " templates=" + DEATH_TEMPLATES.size());
            plugin.getLogger().info("  firstJoin  enabled=" + FIRSTJOIN_ENABLED + " templates=" + FIRSTJOIN_TEMPLATES.size());
            plugin.getLogger().info("  thunder    enabled=" + THUNDER_ENABLED   + " templates=" + THUNDER_TEMPLATES.size()
                    + " chance=" + THUNDER_CHANCE);
        }
    }

    private static boolean getBooleanWithDefault(FileConfiguration c, String path, boolean def) {
        if (!c.contains(path)) {
            c.set(path, def);
        }
        return c.getBoolean(path, def);
    }

    private static List<String> nonEmptyOrDefault(List<String> list, List<String> def) {
        if (list == null || list.isEmpty()) {
            return new ArrayList<>(def);
        }
        return new ArrayList<>(list);
    }

    private static String pickRandom(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(RNG.nextInt(list.size()));
    }

    private static String colorize(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    // базовый вариант — как раньше
    private static void broadcastTemplate(String template, Player player, String title) {
        broadcastTemplate(template, player, title, null);
    }

    // расширенный: поддержка %vanilla% (ориг. текст смерти и т.п.)
    private static void broadcastTemplate(String template, Player player, String title, String vanilla) {
        if (template == null) return;
        if (player == null) return; // на всякий пожарный

        String msg = template;

        // поддерживаем и %player%, и %player_name% (как в PlaceholderAPI)
        msg = msg.replace("%player%", player.getName());
        msg = msg.replace("%player_name%", player.getName());

        if (title != null) {
            msg = msg.replace("%title%", title);
            msg = msg.replace("%advancement%", title);
        }

        if (vanilla != null) {
            msg = msg.replace("%vanilla%", vanilla);
        }

        msg = colorize(msg);
        Bukkit.getServer().broadcastMessage(msg);
    }

    // для грозы и любых глобальных сообщений без конкретного игрока
    private static void broadcastRaw(String template) {
        if (template == null) return;
        Bukkit.getServer().broadcastMessage(colorize(template));
    }

    /* ===================== JOIN / QUIT ===================== */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // убиваем стандартное join-сообщение всегда
        event.setJoinMessage(null);

        Player p = event.getPlayer();

        // Первый вход
        if (!p.hasPlayedBefore() && FIRSTJOIN_ENABLED) {
            String firstTpl = pickRandom(FIRSTJOIN_TEMPLATES);
            if (firstTpl != null) {
                broadcastTemplate(firstTpl, p, null);
            }
            return;
        }

        if (!JOIN_ENABLED) return;

        String tpl = pickRandom(JOIN_TEMPLATES);
        if (tpl != null) {
            broadcastTemplate(tpl, p, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!QUIT_ENABLED) return;

        // убиваем стандартное quit-сообщение
        event.setQuitMessage(null);

        String tpl = pickRandom(QUIT_TEMPLATES);
        if (tpl != null) {
            broadcastTemplate(tpl, event.getPlayer(), null);
        }
    }

    /* ===================== ADVANCEMENTS ===================== */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!ADV_ENABLED) return;

        var adv = event.getAdvancement();
        AdvancementDisplay display = adv.getDisplay(); // ВАЖНО: ИМПОРТ из io.papermc.paper.advancement.*

        if (display == null) {
            if (DEBUG) {
                plugin.getLogger().info("ADV DEBUG: " + adv.getKey() + " has no display (hidden/recipes?)");
            }
            return;
        }

        // Вырезаем скрытые/без-чата ачивки
        if (!display.doesAnnounceToChat() || display.isHidden()) {
            if (DEBUG) {
                plugin.getLogger().info("ADV DEBUG: " + adv.getKey() +
                        " doesAnnounceToChat=" + display.doesAnnounceToChat() +
                        " isHidden=" + display.isHidden() + " -> skip");
            }
            return;
        }

        String titlePlain = PlainTextComponentSerializer.plainText().serialize(display.title());
        // описание пока не используем, но если захочешь — можно увести в %desc%
        // String descPlain  = PlainTextComponentSerializer.plainText().serialize(display.description());

        String tpl = pickRandom(ADV_TEMPLATES);
        if (tpl != null) {
            broadcastTemplate(tpl, event.getPlayer(), titlePlain);
        }
    }

    /* ===================== DEATH MESSAGES ===================== */

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!DEATH_ENABLED) return;

        Player p = event.getEntity();

        // В новых Paper API deathMessage() возвращает Component
        String vanillaPlain = null;
        if (event.deathMessage() != null) {
            vanillaPlain = PlainTextComponentSerializer.plainText().serialize(event.deathMessage());
        }

        // Убираем стандартное серверное сообщение
        event.deathMessage(null);

        String tpl = pickRandom(DEATH_TEMPLATES);
        if (tpl != null) {
            broadcastTemplate(tpl, p, null, vanillaPlain);
        }
    }

    /* ===================== THUNDERSTORM 1% MESSAGE ===================== */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThunderChange(ThunderChangeEvent event) {
        if (!THUNDER_ENABLED) return;

        // Нас интересует момент, когда ГРОЗА включается
        if (!event.toThunderState()) return;

        // На всякий случай убеждаемся, что реально идёт шторм (дождь+гроза)
        if (!event.getWorld().hasStorm()) {
            return;
        }

        // Шанс 1% (или сколько выставлено в конфиге)
        if (RNG.nextDouble() >= THUNDER_CHANCE) return;

        // Берём случайного игрока из онлайна
        var online = Bukkit.getOnlinePlayers();
        if (online.isEmpty()) {
            // Некому быть виноватым — тихо выходим
            return;
        }

        List<Player> players = new ArrayList<>(online);
        Player randomPlayer = players.get(RNG.nextInt(players.size()));

        String tpl = pickRandom(THUNDER_TEMPLATES);
        if (tpl != null) {
            // Здесь можно использовать %player% / %player_name%
            broadcastTemplate(tpl, randomPlayer, null, null);
        }
    }

}
