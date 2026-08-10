package com.frammy.unitylauncher;

import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkActivityHeatmapExporter;
import com.frammy.unitylauncher.chunkactivity.ZonesEconomyConfig;
import com.frammy.unitylauncher.signs.features.trash.TrashSellConfig;
import com.frammy.unitylauncher.upgrades.core.Upgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradesManager;
import com.frammy.unitylauncher.upgrades.impl.SafeDepositUpgrade;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

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
                             @NotNull String @NonNull [] args)
    {

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
                auth.updateCacheAfterSession(p.getName(), System.currentTimeMillis(), ip);
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

            // если строка уже есть в Users/пароль уже задан — считаем, что имя занято
            if (auth.isRegistered(p.getName())) {
                p.sendMessage(ChatColor.RED + "Ты уже зарегистрирован. Используй /login.");
                return true;
            }

            String err = com.frammy.unitylauncher.auth.PasswordPolicy.validate(password);
            if (err != null) {
                p.sendMessage(ChatColor.RED + err);
                return true;
            }

            // СОЗДАЁМ ЗАПИСЬ как в PHP: INSERT Users(Name, Password, CustomizationData, SocialData, GeneralData, StatsData)
            boolean created = auth.registerNewUser(p.getName(), password.toCharArray(), "0");
            if (!created) {
                p.sendMessage(ChatColor.RED + "Ошибка регистрации (возможно имя уже существует). Попробуй позже.");
                return true;
            }

            String ip = (p.getAddress() != null) ? p.getAddress().getAddress().getHostAddress() : "unknown";
            auth.markSession(p.getName(), ip);
            auth.updateCacheAfterSession(p.getName(), System.currentTimeMillis(), ip);

            authListener.completeRegister(p);
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
            TrashSellConfig.load(plugin);

            // 2) перезагрузить фразы join/quit/advancement
            ServerMessagesListener msgListener = plugin.getServerMessagesListener();
            if (msgListener != null) {
                ServerMessagesListener.reloadMessages();
            } else {
                sender.sendMessage(ChatColor.RED + "ServerMessagesListener не инициализирован. Проверь onEnable().");
            }

            // 3) перезагрузить апгрейды и зоны
            UpgradesManager um = plugin.getUpgradesManager();
            if (um != null) {
                um.reload();
            } else {
                sender.sendMessage(ChatColor.RED + "UpgradesManager не инициализирован. Проверь onEnable().");
            }

            plugin.getZoneManager().loadZonesFromConfig();

            if (plugin.getSignManager() != null) {
                plugin.getZoneManager().scheduleSignOwnershipRecalc(plugin.getSignManager(), 200);
            }

            // 4) zones-economy.yml (отдельный файл)
            ZonesEconomyConfig.load(plugin);

            sender.sendMessage(ChatColor.GREEN + "UnityLauncher: конфиг, сообщения, апгрейды и зоны перезагружены.");
            return true;
        }

        // ===================== /ul money give <player> <amount> =====================
        // Admin utility — no in-game shop/zone flow grants money outside a country
        // context yet, so this is also the only way to test the site's balance
        // mirror without one. Goes through the same applyMoneyDelta as every other
        // real economy action, so it mirrors to the site exactly like a real one.
        if (args.length >= 2 && args[0].equalsIgnoreCase("money") && args[1].equalsIgnoreCase("give")) {
            boolean allowed = !(sender instanceof Player) || sender.isOp() || sender.hasPermission("unitylauncher.money.give");
            if (!allowed) {
                sender.sendMessage(ChatColor.RED + "Нет прав: unitylauncher.money.give");
                return true;
            }
            if (args.length != 4) {
                sender.sendMessage(ChatColor.YELLOW + "Используй: /ul money give <ник> <сумма>");
                return true;
            }

            String targetName = args[2];
            double amount;
            try {
                amount = Double.parseDouble(args[3].replace(',', '.'));
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Сумма должна быть числом.");
                return true;
            }
            if (!(amount > 0)) {
                sender.sendMessage(ChatColor.RED + "Сумма должна быть положительной.");
                return true;
            }

            boolean ok = UnityCommands.getInstance().applyMoneyDelta(targetName, amount);
            sender.sendMessage(ok
                    ? ChatColor.GREEN + "Начислено " + amount + " игроку " + targetName + "."
                    : ChatColor.RED + "Не удалось — игрок '" + targetName + "' не найден в базе.");
            return true;
        }

        // ===================== /ul frame give <player> <frameId> =====================
        // Рамки "за победу в событии" / "за особые достижения" (Алмазная id 5,
        // Незеритовая id 13, infra/frames-catalog.md) не имеют автоматического
        // условия — по решению, выдаются админом вручную этой командой.
        // Идёт тем же путём, что и достижения (FramesDao -> POST
        // /plugin/.../frames/:frameId/grant), так что сайт увидит рамку сразу.
        if (args.length >= 2 && args[0].equalsIgnoreCase("frame") && args[1].equalsIgnoreCase("give")) {
            boolean allowed = !(sender instanceof Player) || sender.isOp() || sender.hasPermission("unitylauncher.frame.give");
            if (!allowed) {
                sender.sendMessage(ChatColor.RED + "Нет прав: unitylauncher.frame.give");
                return true;
            }
            if (args.length != 4) {
                sender.sendMessage(ChatColor.YELLOW + "Используй: /ul frame give <ник> <id рамки>");
                return true;
            }

            String targetName = args[2];
            int frameId;
            try {
                frameId = Integer.parseInt(args[3]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "ID рамки должен быть числом.");
                return true;
            }
            if (frameId <= 0) {
                sender.sendMessage(ChatColor.RED + "ID рамки должен быть положительным.");
                return true;
            }

            com.frammy.unitylauncher.advs.FramesDao.addUserToFrame(frameId, targetName);
            sender.sendMessage(ChatColor.GREEN + "Рамка #" + frameId + " отправлена игроку " + targetName + " (запрос на сайт ушёл, но проверить владение можно только на самом сайте — команда не подтверждает, что ник существует).");
            return true;
        }

        // дальше — только игрок
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам.");
            return true;
        }

        // ===================== /ul (без аргументов) =====================
        if (args.length == 0) {
            sendRootUlHelp(p);
            return true;
        }

        // ===================== /ul zone ... =====================
        if (args.length >= 2 && args[0].equalsIgnoreCase("zone")) {
            String[] zoneArgs = Arrays.copyOfRange(args, 1, args.length);
            zoneManager.handleCommand(p, zoneArgs);
            return true;
        }

        // ===================== /ul trash confirm|cancel =====================
        if (args[0].equalsIgnoreCase("trash")) {
            var sm = plugin.getSignManager();
            if (sm == null) {
                p.sendMessage(ChatColor.RED + "SignManager не инициализирован.");
                return true;
            }

            var tc = sm.getTrashController();

            if (args.length < 2) {
                p.sendMessage(ChatColor.YELLOW + "Используй: /ul trash confirm или /ul trash cancel");
                return true;
            }

            String sub = args[1].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "confirm" -> tc.handleTrashConfirm(p);
                case "cancel" -> tc.handleTrashCancel(p);
                default -> p.sendMessage(ChatColor.YELLOW + "Используй: /ul trash confirm или /ul trash cancel");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("shop")) {
            var sm = plugin.getSignManager();
            var sc = sm.getShopController();
            if (args.length >= 2 && args[1].equalsIgnoreCase("buy")) {
                sc.confirmPendingBuy(p);
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("cancel")) {
                sc.cancelPendingBuy(p);
                return true;
            }

            p.sendMessage(ChatColor.GRAY + "Используй кнопки в чате: [КУПИТЬ] / [ОТМЕНА]");
            return true;
        }

        // ===================== /ul safe create =====================
        if (args[0].equalsIgnoreCase("safe")) {

            String sub = (args.length >= 2) ? args[1].toLowerCase(Locale.ROOT) : "";

            if (sub.equals("create")) {
                SafeDepositUpgrade safe = getEnabledUpgrade(p, SafeDepositUpgrade.class,
                        "Сейфы сейчас отключены (апгрейд не активен).");
                if (safe == null) return true;

                ItemStack key = p.getInventory().getItemInMainHand();
                if (key.getType().isAir()) {
                    p.sendMessage(ChatColor.RED + "Возьми предмет-ключ в основную руку.");
                    return true;
                }

                safe.beginCreateSafe(p, key);
                return true;
            }

            p.sendMessage(ChatColor.YELLOW + "Используй: /ul safe create");
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
                    UnityCommands.getInstance().getMoney(sender);
                    return true;
                }

                case "country": {
                    UnityCommands.getInstance().getCountry(sender);
                    return true;
                }

                case "change": {
                    // Тупо подсказка — реальная смена пароля только при 3 аргументах
                    sender.sendMessage(ChatColor.YELLOW + "Используй: /ul change <старый_пароль> <новый_пароль>");
                    return true;
                }

                case "notifications": {
                    UnityCommands.getInstance().getNotifications(sender);
                    return true;
                }

                case "help": {
                    // /ul help → тот же вывод, что и просто /ul
                    sendRootUlHelp(p);
                    return true;
                }

                case "daydeal": {
                    UnityCommands.getInstance().dayDealInfo(sender);
                    return true;
                }

                case "relations": {
                    sender.sendMessage(ChatColor.YELLOW + "Система отношений между странами появится в следующих обновлениях.");
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

                case "change": {
                    // /ul change <old>  — нет нового пароля
                    sender.sendMessage(ChatColor.RED + "Не указан новый пароль. Используй: /ul change <старый_пароль> <новый_пароль>");
                    return true;
                }

                case "daydeal": {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    if (sub.equals("info")) {
                        UnityCommands.getInstance().dayDealInfo(sender);
                        return true;
                    }
                    if (sub.equals("complete")) {
                        UnityCommands.getInstance().dayDealComplete(sender);
                        return true;
                    }
                    sender.sendMessage(ChatColor.RED + "Используй: /ul daydeal info или /ul daydeal complete");
                    return true;
                }

                default:
                    return true;
            }
        }

        // ===================== /ul <sub> ... (3 аргумента) =====================
        if (args.length == 3) {
            String sub0 = args[0].toLowerCase(Locale.ROOT);

            if (sub0.equals("change")) { // /ul change <old> <new>
                String oldPass = args[1];
                String newPass = args[2];

                if (newPass.isBlank()) {
                    sender.sendMessage(ChatColor.RED + "Не указан новый пароль. Используй: /ul change <старый_пароль> <новый_пароль>");
                    return true;
                }

                if (oldPass.equals(newPass)) {
                    sender.sendMessage(ChatColor.RED + "Новый пароль совпадает со старым. Смена пароля не имеет смысла.");
                    return true;
                }

                UnityCommands.getInstance().changePass(sender, oldPass, newPass);
                return true;
            }
            return true;
        }

        // прочие конфигурации аргументов пока не используются
        return true;
    }

    /**
     * Общий вывод "корневой" помощи /ul и /ul help
     */
    private void sendRootUlHelp(Player p) {
        p.sendMessage(ChatColor.YELLOW + "Выбери категорию команды:\n");
        for (String cat : plugin.commandCategories) {
            Component clickableCategory = Component.text("[> " + cat)
                    .color(NamedTextColor.GRAY)
                    .clickEvent(ClickEvent.runCommand("/ul help " + cat))
                    .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы показать команды категории")));
            p.sendMessage(clickableCategory);
        }
    }

    private <T extends Upgrade> T getEnabledUpgrade(Player p, Class<T> clazz, String errIfDisabled) {
        UpgradesManager um = plugin.getUpgradesManager();
        if (um == null) {
            p.sendMessage(ChatColor.RED + "UpgradesManager не инициализирован.");
            return null;
        }

        T u = um.getEnabled(clazz);
        if (u == null) {
            p.sendMessage(ChatColor.RED + errIfDisabled);
            return null;
        }
        return u;
    }
}
