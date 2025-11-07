package com.frammy.unitylauncher;

import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkActivityHeatmapExporter;
import com.frammy.unitylauncher.upgrades.UpgradesListener;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.countryrelations.DiplomacyService;
import com.frammy.unitylauncher.zones.countryrelations.RelationStatus;
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
import java.util.*;

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

        // --- Команды /login, /register (и алиасы) ---
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        if (cmd.equals("login") || cmd.equals("l")) {
            if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Только игроки могут войти."); return true; }
            if (args.length < 1) { p.sendMessage(ChatColor.YELLOW + "Используй: /login <пароль>"); return true; }

            String password = args[0]; // НЕ логируем и не трогаем
            UnityLauncher plugin = UnityLauncher.getInstance();
            var auth = plugin.getAuthService();
            var authListener = plugin.getAuthListener();
            var limiter = plugin.getLoginLimiter();

            String ip = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : null;
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

        if (cmd.equals("register") || cmd.equals("reg")) {
            if (!(sender instanceof Player p)) { sender.sendMessage(ChatColor.RED + "Только игроки могут регистрироваться."); return true; }
            if (args.length < 1) { p.sendMessage(ChatColor.YELLOW + "Используй: /register <пароль>"); return true; }

            String password = args[0];
            UnityLauncher plugin = UnityLauncher.getInstance();
            var auth = plugin.getAuthService();
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


        // --- /ul reload (доступно консоли и игрокам с правом) ---
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            boolean allowed = !(sender instanceof Player) || sender.isOp() || sender.hasPermission("unitylauncher.reload");
            if (!allowed) {
                sender.sendMessage(ChatColor.RED + "Нет прав: unitylauncher.reload");
                return true;
            }
            plugin.reloadConfig();
            UpgradesListener.reload(plugin);
            plugin.getZoneManager().loadZonesFromConfig();

             if (plugin.getSignManager() != null) {
                 plugin.getZoneManager().scheduleSignOwnershipRecalc(plugin.getSignManager(), 200);
             }

            sender.sendMessage(ChatColor.GREEN + "UnityLauncher: конфиг, апгрейды и зоны перезагружены.");
            return true;
        }

        // только игрок
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
            return true;
        }

        // 0 аргументов — показать категории
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

        // подпакет зон: /ul zone ...
        if (args.length >= 2 && args[0].equalsIgnoreCase("zone")) {
            String[] zoneArgs = Arrays.copyOfRange(args, 1, args.length);
            zoneManager.handleCommand(p, zoneArgs);
            return true;
        }

        // ===== /ul relations ... =====
//        if (args[0].equalsIgnoreCase("relations")) {
//            if (args.length == 1) {
//                sender.sendMessage(ChatColor.YELLOW + "/ul relations get \"<ДругаяСтрана>\"");
//                sender.sendMessage(ChatColor.YELLOW + "/ul relations set \"<ДругаяСтрана>\" <HOSTILE|NEUTRAL|FRIENDLY>");
//                return true;
//            }
//
//            String sub = args[1].toLowerCase(Locale.ROOT);
//
//            ParsedArg parsed = parseQuotedArg(args);
//            String otherCountry = parsed.value;
//            if (otherCountry == null || otherCountry.isBlank()) {
//                sender.sendMessage(ChatColor.RED + "Укажи страну. " + ChatColor.GRAY +
//                        "Если название содержит пробел, используй кавычки. Пример: \"Моя Страна\"");
//                return true;
//            }
//
//            // синхронное получение своей страны
//            String myCountry = plugin.countryRegistryJdbc.getCountryOfPlayer(p.getName());
//            if (myCountry == null || myCountry.isBlank()) {
//                sender.sendMessage(ChatColor.RED + "Ты не состоишь ни в одном государстве.");
//                return true;
//            }
//
//            DiplomacyService diplomacy = plugin.diplomacy;
//
//            if (sub.equals("get")) {
//                RelationStatus s = diplomacy.getRelation(myCountry, otherCountry);
//                sender.sendMessage(
//                        ChatColor.GOLD + myCountry + ChatColor.GRAY + " ↔ " +
//                                ChatColor.GOLD + otherCountry + ChatColor.GRAY + " = " +
//                                ChatColor.AQUA + s
//                );
//                return true;
//            }
//
//            if (sub.equals("set")) {
//                // parsed.nextIndex — позиция аргумента после имени страны
//                if (parsed.nextIndex >= args.length) {
//                    sender.sendMessage(ChatColor.RED + "Укажи статус: HOSTILE | NEUTRAL | FRIENDLY");
//                    return true;
//                }
//
//                RelationStatus newStatus = RelationStatus.from(args[parsed.nextIndex]);
//                diplomacy.setRelation(myCountry, otherCountry, newStatus);
//
//                sender.sendMessage(ChatColor.GREEN + "Установлено: " +
//                        myCountry + " ↔ " + otherCountry + " = " + newStatus);
//                return true;
//            }
//
//            sender.sendMessage(ChatColor.RED + "Неизвестная подкоманда relations: " + sub);
//            return true;
//        }

        // ===== Остальные команды =====

        // == 1 аргумент ==
        if (args.length == 1) {
            String sub = args[0].toLowerCase(Locale.ROOT);

            switch (sub) {
                case "expo": {
                    // рендерим heatmap слой активности чанков
                    ActivityWeights weights = new ActivityWeights();
                    ChunkActivityHeatmapExporter.exportHeatmapToBlueMapLayer(
                            tracker.getChunkStatsMap(),
                            p.getLocation().getWorld().getName(),
                            weights
                    );
                    return true;
                }

                case "fsnap": {
                    // форсируем биллинг зон прямо сейчас
                    plugin.zoneActivityCalculations.snapshotAndMaybeBillAllZones();
                    return true;
                }

                case "blist": {
                    LocalDate today = LocalDate.now(zoneManager.zoneId);
                    p.sendMessage(ChatColor.GOLD + "Биллинг зон (сегодня: " + today + "):");
                    zoneManager.zoneList.values().stream()
                            .sorted(Comparator.comparing(ZoneInfo::getNextBillingDate))
                            .forEach(z -> {
                                LocalDate nextDate = z.getNextBillingDate();
                                double due = z.getDueSinceLastBill(today);
                                int days = z.getDueDaysCount(today);
                                p.sendMessage(
                                        ChatColor.YELLOW + z.getName() +
                                                ChatColor.GRAY + " | владелец: " + ChatColor.WHITE + z.getOwner() +
                                                ChatColor.GRAY + " | след. платеж: " + ChatColor.AQUA + nextDate +
                                                ChatColor.GRAY + " | долг: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", due) +
                                                ChatColor.GRAY + " (" + days + " дн.)"
                                );
                            });
                    return true;
                }

//                case "rcode":
//                    UnityCommands.getInstance().rCode(sender);
//                    return true;

                case "balance":
                case "bal": {
                    double balance = UnityCommands.getInstance().getMoney(sender);
                    sender.sendMessage("Ваш баланс: " + ChatColor.GREEN + balance + ChatColor.RESET + "!");
                    return true;
                }

                case "country":
                    UnityCommands.getInstance().getCountry(sender);
                    return true;

//                case "top":
//                    sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<balance/playtime/events>" + ChatColor.RESET + "!");
//                    return true;

                case "change":
                    sender.sendMessage("Введи старый, а затем желаемый пароль!");
                    return true;

                case "notifications":
                    UnityCommands.getInstance().getNotifications(sender);
                    return true;

//                case "pay":
//                    sender.sendMessage("Введи ник игрока и сумму, которую вы хотите перечислить.");
//                    return true;
//
//                case "countrybalance":
//                case "cb":
//                    sender.sendMessage("Введи действие и сумму денег.");
//                    return true;
//
//                case "daydeal":
//                    sender.sendMessage("Введи код.");
//                    return true;
//
//                case "group":
//                    sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
//                    return true;
            }
            return true;
        }

        // == 2 аргумента ==
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

                case "change":
                    sender.sendMessage("Введи старый, а затем желаемый пароль!");
                    return true;

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

//                case "top": {
//                    // нормализуем регистр первой буквы
//                    String cat = args[1].substring(0, 1).toUpperCase(Locale.ROOT)
//                            + args[1].substring(1).toLowerCase(Locale.ROOT);
//                    if (cat.equals("Playtime") || cat.equals("Balance") || cat.equals("Events")) {
//                        UnityCommands.getInstance().getTop(sender, cat);
//                    } else {
//                        sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<balance/playtime/events>" + ChatColor.RESET + "!");
//                    }
//                    return true;
//                }

                case "notifications":
                    if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off")) {
                        UnityCommands.getInstance().toggleNotifications(sender, args[1]);
                    } else {
                        sender.sendMessage("Используй /ul notifications ON или /ul notifications OFF");
                    }
                    return true;

//                case "pay":
//                    sender.sendMessage("Введи ник игрока и сумму, которую ты хочешь перечислить.");
//                    return true;

//                case "countrybalance":
//                case "cb":
//                    sender.sendMessage("Введи действие и сумму, которую ты хочешь перечислить.");
//                    return true;

//                case "daydeal":
//                    UnityCommands.getInstance().dayDeal(sender, args[1]);
//                    return true;

//                case "group": {
//                    String sub1 = args[1].toLowerCase(Locale.ROOT);
//                    switch (sub1) {
//                        case "list":
//                            UnityCommands.getInstance().getGroups(sender);
//                            break;
//                        case "set":
//                            sender.sendMessage("Введи ник игрока и группу, в которую хотите его поместить (только для владельцев стран)");
//                            break;
//                        case "prefix":
//                            sender.sendMessage("Введи группу и префикс, который хотите ей присвоить (без пробелов)");
//                            break;
//                        default:
//                            sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
//                            break;
//                    }
//                    return true;
//                }
            }
        }

        // == 3 аргумента ==
        if (args.length == 3) {
            String sub0 = args[0].toLowerCase(Locale.ROOT);

            switch (sub0) {

                case "change":
                    UnityCommands.getInstance().changePass(sender, args[1], args[2]);
                    return true;

//                case "pay":
//                    try {
//                        double sendMoney = Double.parseDouble(args[2]);
//                        UnityCommands.getInstance().pay(sender, args[1], sendMoney, -1);
//                    } catch (Exception ex) {
//                        sender.sendMessage("Введите ник игрока и сумму, которую вы хотите перечислить.");
//                    }
//                    return true;

//                case "countrybalance":
//                case "cb":
//                    try {
//                        if (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("withdraw")) {
//                            double sendMoney = Double.parseDouble(args[2]);
//                            UnityCommands.getInstance()
//                                    .CountryMoney(sender,
//                                            args[1].equalsIgnoreCase("add"),
//                                            sendMoney);
//                        } else {
//                            sender.sendMessage("Введите действие и сумму денег.");
//                        }
//                    } catch (Exception ex) {
//                        sender.sendMessage("Введите действие и сумму денег.");
//                    }
//                    return true;
//
//                case "group": {
//                    String sub1 = args[1].toLowerCase(Locale.ROOT);
//                    switch (sub1) {
//                        case "set":
//                            sender.sendMessage("Введи ник игрока и группу, в которую хотите его поместить (только для владельцев стран)");
//                            break;
//                        case "prefix":
//                            sender.sendMessage("Введи группу и префикс, который хотите ей присвоить (без пробелов)");
//                            break;
//                        default:
//                            sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
//                            break;
//                    }
//                    return true;
//                }
            }
        }

//        // == 4 аргумента ==  (/ul group set <ник> <группа>) или (/ul group prefix <группа> <префикс>)
//        if (args.length == 4 && args[0].equalsIgnoreCase("group")) {
//            String sub1 = args[1].toLowerCase(Locale.ROOT);
//            switch (sub1) {
//                case "set":
//                    UnityCommands.getInstance().setGroup(sender, args[2], args[3]);
//                    break;
//                case "prefix":
//                    UnityCommands.getInstance().setPrefix(sender, args[2], args[3]);
//                    break;
//                default:
//                    sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
//                    break;
//            }
//            return true;
//        }

        // дальше аргументов больше — пока не документировано
        return true;
    }

    /* ===================== кавычкованный парсер страны для /ul relations ===================== */

    // просто структурка: значение и индекс аргумента после него
    private record ParsedArg(String value, int nextIndex) {}

    /**
     * Парсит один аргумент, начиная с args[2]:
     *
     * Примеры:
     *   /ul relations get Spain
     *   -> value="Spain", nextIndex=3
     *
     *   /ul relations set "New Republic" FRIENDLY
     *   -> value="New Republic", nextIndex=4
     */
    private static ParsedArg parseQuotedArg(String[] args) {
        if (args.length <= 2) {
            return new ParsedArg(null, 2);
        }

        String first = args[2];

        // случай: "Country Name"
        if (first.startsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            boolean closed = false;

            // кавычки в одном аргументе: "USA"
            if (first.endsWith("\"") && first.length() > 1) {
                sb.append(first, 1, first.length() - 1);
                return new ParsedArg(sb.toString(), 3);
            }

            // иначе собираем до закрывающей кавычки
            sb.append(first.substring(1));
            int i = 3;
            while (i < args.length) {
                String cur = args[i];
                if (cur.endsWith("\"")) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(cur, 0, cur.length() - 1);
                    closed = true;
                    i++;
                    break;
                } else {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(cur);
                    i++;
                }
            }

            if (!closed) {
                // не нашли закрывающую кавычку; считаем парс невалидным
                return new ParsedArg(null, args.length);
            }
            return new ParsedArg(sb.toString(), i);
        }

        // без кавычек: просто один аргумент
        return new ParsedArg(first, 3);
    }
}
