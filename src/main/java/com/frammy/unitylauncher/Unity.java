package com.frammy.unitylauncher;

import com.flowpowered.math.vector.Vector3d;
import com.frammy.unitylauncher.bluemap.BlueMapIntegration;
import com.frammy.unitylauncher.signs.SignManager;
import com.mysql.cj.util.StringUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public class Unity implements CommandExecutor {
    //UnityLauncher unityLauncher = UnityLauncher.getInstance();
    UnityLauncher unityLauncher = JavaPlugin.getPlugin(UnityLauncher.class);
    public String shopID;
    public static List<Vector3d> convertLocationListToVector3dList(List<Location> locations) {
        return locations.stream()
                .map(loc -> new Vector3d(loc.getX(), loc.getY(), loc.getZ())) // Преобразуем каждую Location в Vector3d
                .collect(Collectors.toList()); // Собираем результат в новый List
    }

    private final HelpCommandManager helpManager;
    private BlueMapIntegration blueMapIntegration;
    private SignManager signManager;
    private final WebSocketManager webSocketManager;
    // Конструктор принимает HelpCommandManager
    public Unity(HelpCommandManager helpManager, WebSocketManager webSocketManager) {
        this.helpManager = helpManager;
        this.webSocketManager = webSocketManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {

        if (sender instanceof Player) {
            Player p = (Player) sender;
            if (args.length == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Выбери категорию команды:\n");
                for (String s : unityLauncher.commandCategories) {
                    Component clickableCategory = Component.text( "[> " + s)
                            .color(NamedTextColor.GRAY)
                            .clickEvent(ClickEvent.runCommand("/ul help " + s))
                            .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы показать команды категории")));

                    p.sendMessage(clickableCategory);

                }

                return false;
            }

            if (args.length >= 2 && args[0].equalsIgnoreCase("zone")) {
                // Убираем "zone" и передаём оставшиеся аргументы
                String[] zoneArgs = Arrays.copyOfRange(args, 1, args.length);

                UnityLauncher plugin = (UnityLauncher) Bukkit.getPluginManager().getPlugin("unityLauncher");
                if (plugin != null) {
                    plugin.getZoneManager().handleCommand(p, zoneArgs);
                }
                return true;
            }

            if (args.length == 1) {
                switch (args[0].toLowerCase()) {
                    case "rcode":
                        UnityCommands.getInstance().rCode(sender);
                        return false;
                    case "balance":
                    case "bal":
                        UnityCommands.getInstance().getMoney(sender);
                        return false;
                    case "country":
                        UnityCommands.getInstance().getCountry(sender);
                        return false;
                    case "top":
                        sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<balance/playtime/events>" + ChatColor.RESET + "!");
                        return false;
                    case "change":
                        sender.sendMessage("Введи старый, а затем желаемый пароль!");
                        return false;
                    case "notifications":
                        UnityCommands.getInstance().getNotifications(sender);
                        return false;
                    case "pay":
                        sender.sendMessage("Введи ник игрока и сумму, которую вы хотите перечислить.");
                        return false;
                    case "countrybalance":
                    case "cb":
                        sender.sendMessage("Введи действие и сумму денег.");
                        return false;
                    case "daydeal":
                        sender.sendMessage("Введи код.");
                        return false;
                    case "group":
                        sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
                        return false;
                }
                return false;
            }
            if (args.length == 2) {
                switch (args[0]) {
                    case "help":
                        String category = args[1];
                        if (!StringUtils.isNullOrEmpty(category)) {
                            // Проверяем, существует ли категория
                            if (unityLauncher.commandCategories.contains(category)) {
                                sender.sendMessage(ChatColor.YELLOW + "Команды в категории \"" + category + "\":");

                                List<HelpCommandManager.HelpCommand> categoryCommands = helpManager.getCommandsByCategory(category);
                                for (HelpCommandManager.HelpCommand cmd : categoryCommands) {
                                    p.sendMessage(cmd.toComponent());
                                }

                            } else {
                                sender.sendMessage(ChatColor.RED + "Категория \"" + category + "\" не найдена.");
                            }
                        } else {
                            sender.sendMessage(ChatColor.RED + "Необходимо указать название категории.");
                        }
                        break;
                    case "shop":
                        switch (args[1]) {
                            case "addcorner":
                                Player player = (Player) sender;
                                if (unityLauncher.awaitingCorrectCommand.contains(player)) {
                                    if (!blueMapIntegration.markerPoints.containsKey(player)) {
                                        blueMapIntegration.markerPoints.put(player, new ArrayList<>());
                                    }
                                    blueMapIntegration.markerPoints.get(player).add(player.getLocation());
                                    if (calculateSurfaceArea(blueMapIntegration.markerPoints.get(player)) > 500) {
                                        blueMapIntegration.markerPoints.get(player).remove(player.getLocation());
                                        player.sendMessage(ChatColor.RED + "Слишком дохуя площади. Удали часть точек.");
                                    } else {
                                        sender.sendMessage(ChatColor.GRAY + "Текущая площадь магазина: " + calculateSurfaceArea(blueMapIntegration.markerPoints.get(player)) + " блоков.");
                                    }
                                } else {
                                    sender.sendMessage(ChatColor.RED + "Тебе сперва необходимо создать магазин!");
                                }
                                break;
                            case "removecorner":
                                if (blueMapIntegration.markerPoints.get((Player) sender) == null) {
                                    sender.sendMessage(ChatColor.RED + "Создание магазина либо уже было завершено, либо не было ещё начато!");
                                } else {
                                    if (blueMapIntegration.markerPoints.get((Player) sender).size() != 0) {
                                        sender.sendMessage(ChatColor.GRAY + "Последняя точка (" +
                                                blueMapIntegration.markerPoints.get((Player) sender).get(
                                                        blueMapIntegration.markerPoints.get((Player) sender).size() - 1
                                                ).toString() + ") удалена.");

                                        List<Location> list = blueMapIntegration.markerPoints.get((Player) sender);
                                        list.remove(list.size() - 1);
                                    } else {
                                        sender.sendMessage(ChatColor.RED + "Все точки уже удалены!");
                                    }


                                }

                                    break;
                            case "build":
                                if (blueMapIntegration.markerPoints.get((Player) sender) != null) {
                                    if (blueMapIntegration.markerPoints.get((Player) sender).size() >= 3) {
                                        UnityCommands.getInstance().setShops(sender,UnityCommands.getInstance().getShops(sender) - 1);
                                        blueMapIntegration.addBlueMapMarker(shopID, ((Player) sender).getLocation(), "shops", "Магазины", "extrude", convertLocationListToVector3dList(blueMapIntegration.markerPoints.get((Player) sender)), (Player)sender);
                                    } else {
                                        sender.sendMessage(ChatColor.RED + "Необходимы минимум 3 точки для маркера магазина!");
                                    }
                                } else {
                                    sender.sendMessage(ChatColor.RED + "Создание магазина либо уже было завершено, либо не было ещё начато!");
                                }


                                break;

                        }
                        return false;
                    case "change":
                        sender.sendMessage("Введи старый, а затем желаемый пароль!");
                        return false;
                    case "fpslink":
                        String message = args[1];
                        if (webSocketManager != null && webSocketManager.isPlayerConnected(p.getName())) {
                            webSocketManager.sendMessageToPlayer(p.getName(), message);
                            sender.sendMessage("§7Ссылка открыта в приложении.");
                        } else {
                            sender.sendMessage("§cОшибка: приложение не подключено.");
                            webSocketManager.tryForceConnect(p);
                        }
                        return false;
                    case "top":
                        String s = args[1].substring(0, 1).toUpperCase() + args[1].substring(1).toLowerCase();
                        if (s.equals("Playtime") || s.equals("Balance") || s.equals("Events"))
                            UnityCommands.getInstance().getTop(sender, s);
                        return false;
                    case "notifications":
                        if (args[1].equals("on") || args[1].equals("off")) UnityCommands.getInstance().toggleNotifications(sender, args[1]);
                        return false;
                    case "pay":
                        sender.sendMessage("Введи ник игрока и сумму, которую вы хочешь перечислить.");
                        return false;
                    case "countrybalance":
                    case "cb":
                        sender.sendMessage("Введи действие и сумму, которую вы хочешь перечислить.");
                        return false;
                    case "daydeal":
                        UnityCommands.getInstance().dayDeal(sender, args[1]);
                        return false;
                    case "group":
                        switch (args[1]) {
                            case "list":
                                UnityCommands.getInstance().getGroups(sender);
                                break;
                            case "set":
                                sender.sendMessage("Введи ник игрока и группу, в которую хотите его поместить (только для владельцев стран)");
                                break;
                            case "prefix":
                                sender.sendMessage("Введи группу и префикс, который хотите ей присвоить (без пробелов)");
                                break;
                            default:
                                sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
                                break;
                        }
                        return false;
                }
            }
            if (args.length == 3) {


                if (args[0].equalsIgnoreCase("shop") && args[1].equalsIgnoreCase("create")) {
                    if (args[2].isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "Необходимо указать название магазина!");
                    } else {
                        if (UnityCommands.getInstance().getShops(sender) != 0) {
                            if (!signManager.hasPlayerShopBrand(args[2], sender.getName())) {
                                shopID = args[2];
                                Component message = Component.text("Укажите границы магазина ", NamedTextColor.GRAY)
                                        .append(Component.text(shopID, NamedTextColor.YELLOW))
                                        .append(Component.text(" используя команду: ", NamedTextColor.GRAY));

                                Component commandText = Component.text("/ul shop addcorner", NamedTextColor.YELLOW)
                                        .clickEvent(ClickEvent.suggestCommand("/ul shop addcorner")) // Вставляет команду в чат
                                        .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы вставить команду в чат", NamedTextColor.WHITE))); // Подсказка при наведении
                                message = message.append(commandText);
                                sender.sendMessage(message);

                                unityLauncher.addPlayerToWaitList((Player) sender);
                            } else {
                                sender.sendMessage(ChatColor.RED + "Магазин с таким названием уже существует!");
                            }
                            return true;
                        }
                    }



                }
                if (args[0].equals("shop") && args[1].equals("destroy")) {

                }
                if (args[0].equals("change")) {
                    UnityCommands.getInstance().changePass(sender, args[1], args[2]);
                    return false;
                }
                if (args[0].equals("pay")) {
                    try {
                        double sendMoney = Double.parseDouble(args[2]);
                        UnityCommands.getInstance().    pay(sender, args[1], sendMoney, -1);
                    } catch (Exception e) {
                        sender.sendMessage("Введите ник игрока и сумму, которую вы хотите перечислить.");
                    }
                    return false;
                }
                if (args[0].equals("countrybalance") || args[0].equals("cb")) {
                    try {
                        if (args[1].equals("add") || args[1].equals("withdraw")) {
                            double sendMoney = Double.parseDouble(args[2]);
                            switch (args[1]) {
                                case "add":
                                    UnityCommands.getInstance().CountryMoney(sender, true, sendMoney);
                                    break;
                                case "withdraw":
                                    UnityCommands.getInstance().CountryMoney(sender, false, sendMoney);
                                    break;
                            }
                        } else sender.sendMessage("Введите действие и сумму денег.");
                    } catch (Exception e) {
                        sender.sendMessage("Введите действие и сумму денег.");
                    }
                    return false;
                }
                if (args[0].equals("group")) {
                    switch (args[1]) {
                        case "set":
                            sender.sendMessage("Введи ник игрока и группу, в которую хотите его поместить (только для владельцев стран)");
                            break;
                        case "prefix":
                            sender.sendMessage("Введи группу и префикс, который хотите ей присвоить (без пробелов)");
                            break;
                        default:
                            sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
                            break;
                    }
                    return false;
                }
                return false;
            }


            if (args.length == 4 && args[0].equals("group")) {
                switch (args[1]) {
                    case "set":
                        UnityCommands.getInstance().setGroup(sender, args[2], args[3]);
                        break;
                    case "prefix":
                        UnityCommands.getInstance().setPrefix(sender, args[2], args[3]);
                        break;
                    default:
                        sender.sendMessage("Введи категорию " + ChatColor.GREEN + "<list/set/prefix>" + ChatColor.RESET + "!");
                        break;
                }
            }
        }
        return false;
    }
}
