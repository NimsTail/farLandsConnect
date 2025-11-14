package com.frammy.unitylauncher;

import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkActivityHeatmapExporter;
import com.frammy.unitylauncher.upgrades.UpgradesListener;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Unity implements CommandExecutor {

    private final UnityLauncher plugin = JavaPlugin.getPlugin(UnityLauncher.class);

    private final HelpCommandManager helpManager;
    private final WebSocketManager webSocketManager;
    private final ActivityTracker tracker;
    private final ZoneManager zoneManager;

    public Unity(HelpCommandManager helpManager,
                 WebSocketManager webSocketManager,
                 ActivityTracker tracker,
                 ZoneManager zoneManager) {
        this.helpManager = helpManager;
        this.webSocketManager = webSocketManager;
        this.tracker = tracker;
        this.zoneManager = zoneManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        String cmd = command.getName().toLowerCase(Locale.ROOT);

        // ===================== /login =====================
        if (cmd.equals("login") || cmd.equals("l")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Только игроки могут войти.");
                return true;
            }
            if (args.length < 1) {
                p.sendMessage(ChatColor.YELLOW + "Используй: /login <пароль>");
                return true;
            }

            String password = args[0]; // НЕ логируем
            UnityLauncher plugin = UnityLauncher.getInstance();
            var auth         = plugin.getAuthService();
            var authListener = plugin.getAuthListener();
            var limiter      = plugin.getLoginLimiter();

            String ip  = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : null;
            String key = p.getUniqueId() + "|" + (ip == null ? "" : ip);

            long waitSec = limiter.checkAllowed(key);
            if (waitSec > 0) {
                p.sendMessage(ChatColor.RED + "Слишком много неверных попыток. Подожди " + waitSec + " сек.");
                return true;
            }

            if (auth.checkPassword(p.getName(), password.toCharArray())) {
                auth.markSession(p.getName(), ip);
                limiter.reset(key);
                authListener.completeLogin(p);
            } else {
                limiter.registerFailure(key);
                p.sendMessage(ChatColor.RED + "Неверный пароль!");
            }
            return true;
        }

        // ===================== /register =====================
        if (cmd.equals("register") || cmd.equals("reg")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "Только игроки могут регистрироваться.");
                return true;
            }
            if (args.length < 1) {
                p.sendMessage(ChatColor.YELLOW + "Используй: /register <пароль>");
                return true;
            }

            String password = args[0];
            UnityLauncher plugin = UnityLauncher.getInstance();
            var auth         = plugin.getAuthService();
            var authListener = plugin.getAuthListener();

            if (auth.isRegistered(p.getName())) {
                p.sendMessage(ChatColor.RED + "Ты уже зарегистрирован. Используй /login.");
                return true;
            }
            String err = com.frammy.unitylauncher.auth.PasswordPolicy.validate(password);
            if (err != null) {
                p.sendMessage(ChatColor.RED + err);
                return true;
            }
            if (auth.setNewPassword(p.getName(), password.toCharArray())) {
                authListener.completeRegister(p);
            } else {
                p.sendMessage(ChatColor.RED + "Ошибка регистрации. Попробуй позже.");
            }
            return true;
        }

        // ===================== /ul reload =====================
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            boolean allowed = !(sender instanceof Player)
                    || sender.isOp()
                    || sender.hasPermission("unitylauncher.reload");

            if (!allowed) {
                sender.sendMessage(ChatColor.RED + "Нет прав: unitylauncher.reload");
                return true;
            }

            // 1) перезагрузить config.yml
            plugin.reloadConfig();

            // 2) перезагрузить фразы join/quit/advancement
            ServerMessagesListener msgListener = plugin.getServerMessagesListener();
            if (msgListener != null) {
                ServerMessagesListener.reloadMessages();
            } else {
                sender.sendMessage(ChatColor.RED + "ServerMessagesListener не инициализирован. Проверь onEnable().");
            }

            // 3) перезагрузить апгрейды и зоны
            UpgradesListener.reload(plugin);
            plugin.getZoneManager().loadZonesFromConfig();

            if (plugin.getSignManager() != null) {
                plugin.getZoneManager().scheduleSignOwnershipRecalc(plugin.getSignManager(), 200);
            }

            sender.sendMessage(ChatColor.GREEN + "UnityLauncher: конфиг, сообщения, апгрейды и зоны перезагружены.");
            return true;
        }

        // дальше — только игрок
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
            return true;
        }

        // ===================== /ul (без аргументов) =====================
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Выбери категорию команды:\n");
            for (String cat : plugin.commandCategories) {
                Component clickableCategory = Component.text("[> " + cat)
                        .color(NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand("/ul help " + cat))
                        .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы показать команды категории")));
                p.sendMessage(clickableCategory);
            }
            return true;
        }

        // ===================== /ul zone ... =====================
        if (args.length >= 2 && args[0].equalsIgnoreCase("zone")) {
            String[] zoneArgs = Arrays.copyOfRange(args, 1, args.length);
            zoneManager.handleCommand(p, zoneArgs);
            return true;
        }

        // ===================== /ul <sub> (1 аргумент) =====================
        if (args.length == 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            switch (sub) {
                case "expo": {
                    // тепловая карта активности чанков
                    ActivityWeights weights = new ActivityWeights();
                    ChunkActivityHeatmapExporter.exportHeatmapToBlueMapLayer(
                            tracker.getChunkStatsMap(),
                            p.getLocation().getWorld().getName(),
                            weights
                    );
                    return true;
                }

                case "fsnap": {
                    // форсируем биллинг зон
                    plugin.zoneActivityCalculations.snapshotAndMaybeBillAllZones();
                    return true;
                }

                case "blist": {
                    // список зон по очереди биллинга
                    LocalDate today = LocalDate.now(zoneManager.zoneId);
                    p.sendMessage(ChatColor.GOLD + "Биллинг зон (сегодня: " + today + "):");
                    zoneManager.zoneList.values().stream()
                            .sorted(Comparator.comparing(ZoneInfo::getNextBillingDate))
                            .forEach(z -> {
                                LocalDate nextDate = z.getNextBillingDate();
                                double due         = z.getDueSinceLastBill(today);
                                int days           = z.getDueDaysCount(today);
                                p.sendMessage(
                                        ChatColor.YELLOW + z.getName() +
                                                ChatColor.GRAY + " | владелец: " + ChatColor.WHITE + z.getOwner() +
                                                ChatColor.GRAY + " | след. платёж: " + ChatColor.AQUA + nextDate +
                                                ChatColor.GRAY + " | долг: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", due) +
                                                ChatColor.GRAY + " (" + days + " дн.)"
                                );
                            });
                    return true;
                }

                case "balance":
                case "bal": {
                    double balance = UnityCommands.getInstance().getMoney(sender);
                    sender.sendMessage("Ваш баланс: " + ChatColor.GREEN + balance + ChatColor.RESET + "!");
                    return true;
                }

                case "country": {
                    UnityCommands.getInstance().getCountry(sender);
                    return true;
                }

                case "change": {
                    // подсказка по смене пароля (реальная смена — при 3 аргументах)
                    sender.sendMessage("Введи старый, а затем желаемый пароль!");
                    return true;
                }

                case "notifications": {
                    UnityCommands.getInstance().getNotifications(sender);
                    return true;
                }

                default:
                    return true;
            }
        }

        // ===================== /ul <sub> ... (2 аргумента) =====================
        if (args.length == 2) {
            String sub0 = args[0].toLowerCase(Locale.ROOT);

            switch (sub0) {
                case "help": {
                    String category = args[1];
                    if (category.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Необходимо указать название категории.");
                        return true;
                    }

                    if (plugin.commandCategories.contains(category)) {
                        sender.sendMessage(ChatColor.YELLOW + "Команды в категории \"" + category + "\":");
                        List<HelpCommandManager.HelpCommand> categoryCommands =
                                helpManager.getCommandsByCategory(category);
                        for (HelpCommandManager.HelpCommand cmdh : categoryCommands) {
                            p.sendMessage(cmdh.toComponent());
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "Категория \"" + category + "\" не найдена.");
                    }
                    return true;
                }

                case "fpslink": {
                    String link = args[1];
                    if (webSocketManager != null && webSocketManager.isPlayerConnected(p.getName())) {
                        webSocketManager.sendMessageToPlayer(p.getName(), link);
                        sender.sendMessage("§7Ссылка открыта в приложении.");
                    } else {
                        sender.sendMessage("§cОшибка: приложение не подключено.");
                        if (webSocketManager != null) {
                            webSocketManager.tryForceConnect(p);
                        }
                    }
                    return true;
                }

                case "notifications": {
                    if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off")) {
                        UnityCommands.getInstance().toggleNotifications(sender, args[1]);
                    } else {
                        sender.sendMessage("Используй /ul notifications ON или /ul notifications OFF");
                    }
                    return true;
                }

                default:
                    return true;
            }
        }

        // ===================== /ul <sub> ... (3 аргумента) =====================
        if (args.length == 3) {
            String sub0 = args[0].toLowerCase(Locale.ROOT);

            if (sub0.equals("change")) {// /ul change <old> <new>
                UnityCommands.getInstance().changePass(sender, args[1], args[2]);
                return true;
            }
            return true;
        }

        // прочие конфигурации аргументов пока не используются
        return true;
    }
}
