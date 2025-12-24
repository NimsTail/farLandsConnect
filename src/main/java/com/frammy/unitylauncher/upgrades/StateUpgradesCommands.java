package com.frammy.unitylauncher.upgrades;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Команды для управления государственными апгрейдами
 * /state contract create <описание> <награда>
 * /state contract list
 * /state focus <fish|wood|ore>
 * /state sampler
 * /state happyhour <start> <end> <discount>
 */
public record StateUpgradesCommands(StateUpgradesManager manager) implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Только для игроков!");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "contract" -> handleContract(player, args);
            case "focus" -> handleFocus(player, args);
            case "sampler" -> handleSampler(player);
            case "happyhour" -> handleHappyHour(player, args);
            case "recycle" -> handleRecycle(player);
            default -> sendHelp(player);
        }

        return true;
    }

    private void handleContract(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Использование: /state contract <create|list>");
            return;
        }

        String action = args[1].toLowerCase();
        String country = UpgradeCondition.playerCountryCanonical(player.getName());

        if (country == null) {
            player.sendMessage(ChatColor.RED + "Вы не состоите в стране!");
            return;
        }

        switch (action) {
            case "create" -> {
                if (args.length < 4) {
                    player.sendMessage(ChatColor.RED + "Использование: /state contract create <описание...> <награда>");
                    return;
                }

                // reward — последний аргумент
                double reward;
                try {
                    reward = Double.parseDouble(args[args.length - 1]);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Неверная награда! Последний аргумент должен быть числом.");
                    return;
                }

                // description — всё между args[2]..args[len-2]
                String description = String.join(" ", Arrays.copyOfRange(args, 2, args.length - 1)).trim();
                if (description.isBlank()) {
                    player.sendMessage(ChatColor.RED + "Описание не может быть пустым!");
                    return;
                }

                if (!manager.canCreateContract(country)) {
                    player.sendMessage(ChatColor.RED + "Достигнут лимит активных контрактов или нет апгрейда!");
                    return;
                }

                manager.createContract(country, description, reward);
                player.sendMessage(ChatColor.GREEN + "✓ Контракт создан: " + description + " (" + reward + " Ⓕ)");
            }
            case "list" -> {
                List<StateUpgradesManager.StateContract> contracts = manager.getActiveContracts(country);
                if (contracts.isEmpty()) {
                    player.sendMessage(ChatColor.YELLOW + "Нет активных контрактов");
                    return;
                }

                player.sendMessage(ChatColor.GOLD + "=== Активные контракты ===");
                for (int i = 0; i < contracts.size(); i++) {
                    StateUpgradesManager.StateContract c = contracts.get(i);
                    player.sendMessage(ChatColor.YELLOW + String.valueOf(i + 1) + ". " + c.description() +
                            ChatColor.GRAY + " [" + c.reward() + " Ⓕ]");
                }
            }
            default -> player.sendMessage(ChatColor.RED + "Неизвестное действие: " + action);
        }
    }

    private void handleFocus(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Использование: /state focus <fish|wood|ore>");
            return;
        }

        String country = UpgradeCondition.playerCountryCanonical(player.getName());
        if (country == null) {
            player.sendMessage(ChatColor.RED + "Вы не состоите в стране!");
            return;
        }

        String resourceType = args[1].toLowerCase();
        if (!resourceType.equals("fish") && !resourceType.equals("wood") && !resourceType.equals("ore")) {
            player.sendMessage(ChatColor.RED + "Неверный тип ресурса! Используйте: fish, wood, ore");
            return;
        }

        manager.setResourceFocus(country, resourceType);
        player.sendMessage(ChatColor.GREEN + "✓ Ресурсный фокус установлен: " + resourceType);
        player.sendMessage(ChatColor.GRAY + "Бонус: +8% к " + resourceType + ", -4% к остальным");
    }

    private void handleSampler(Player player) {
        if (!manager.canTakeSample(player)) {
            player.sendMessage(ChatColor.RED + "Образцы можно брать раз в 2 часа!");
            return;
        }

        player.sendMessage(ChatColor.GREEN + "✓ Вы можете взять образец товара!");
        player.sendMessage(ChatColor.GRAY + "Используйте ПКМ по витрине магазина");
    }

    private void handleHappyHour(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + "Использование: /state happyhour <set|remove|here> ...");
            return;
        }

        String sub = args[1].toLowerCase(java.util.Locale.ROOT);

        // here можно разрешить всем
        boolean needsWrite = sub.equals("set") || sub.equals("remove");

        if (needsWrite) {
            if (!manager.canEditHappyHour(player)) { // сделаем в manager (ниже)
                player.sendMessage(ChatColor.RED + "Недостаточно прав в стране для настройки Happy Hour.");
                return;
            }
        }

        int range = manager.getHappyHourTargetRange(); // из конфиг-yml
        org.bukkit.block.Block target = player.getTargetBlockExact(range);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Посмотри на блок (витрину/табличку) в радиусе " + range + " блоков.");
            return;
        }

        switch (sub) {
            case "here" -> {
                var s = manager.getHappyHour(target.getLocation());
                if (s == null) player.sendMessage(ChatColor.YELLOW + "Happy Hour тут не задан.");
                else player.sendMessage(ChatColor.AQUA + "Happy Hour: " + s.startHour() + ":00–" + s.endHour() + ":00, скидка " + s.discount() + "%");
            }
            case "remove" -> {
                boolean removed = manager.removeHappyHour(target.getLocation());
                player.sendMessage(removed ? ChatColor.GREEN + "✓ Happy Hour удалён." : ChatColor.YELLOW + "Тут не было Happy Hour.");
                if (removed) manager.saveDataNow(); // чтобы сразу записать
            }
            case "set" -> {
                if (args.length < 5) {
                    player.sendMessage(ChatColor.RED + "Использование: /state happyhour set <start_hour> <end_hour> <discount>");
                    return;
                }
                try {
                    int startHour = Integer.parseInt(args[2]);
                    int endHour = Integer.parseInt(args[3]);
                    int discount = Integer.parseInt(args[4]);

                    if (startHour < 0 || startHour > 23 || endHour < 0 || endHour > 23) {
                        player.sendMessage(ChatColor.RED + "Часы должны быть от 0 до 23!");
                        return;
                    }
                    if (discount < 1 || discount > 50) {
                        player.sendMessage(ChatColor.RED + "Скидка должна быть от 1% до 50%!");
                        return;
                    }

                    manager.setHappyHour(target.getLocation(), startHour, endHour, discount);
                    manager.saveDataNow();

                    player.sendMessage(ChatColor.GREEN + "✓ Happy Hour установлен: " + startHour + "-" + endHour + " со скидкой " + discount + "%");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Неверные числа!");
                }
            }
            default -> player.sendMessage(ChatColor.RED + "Использование: /state happyhour <set|remove|here>");
        }
    }

    private void handleRecycle(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "Возьмите сломанный инструмент в руку!");
            return;
        }

        ItemStack recycled = manager.recycleItem(player, item);
        if (recycled == null) {
            player.sendMessage(ChatColor.RED + "Этот предмет нельзя переработать или у вас нет апгрейда!");
            return;
        }

        player.getInventory().removeItem(item);
        player.getInventory().addItem(recycled);
    }

    private void sendHelp(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== Государственные апгрейды ===");
        player.sendMessage(ChatColor.YELLOW + "/state contract create <описание> <награда>");
        player.sendMessage(ChatColor.YELLOW + "/state contract list");
        player.sendMessage(ChatColor.YELLOW + "/state focus <fish|wood|ore>");
        player.sendMessage(ChatColor.YELLOW + "/state sampler");
        player.sendMessage(ChatColor.YELLOW + "/state happyhour here");
        player.sendMessage(ChatColor.YELLOW + "/state happyhour set <startHour> <endHour> <discount%>");
        player.sendMessage(ChatColor.YELLOW + "/state happyhour remove");
        player.sendMessage(ChatColor.YELLOW + "/state recycle - переработать сломанный инструмент");
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                               @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("contract", "focus", "sampler", "happyhour", "recycle");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("contract")) {
            return Arrays.asList("create", "list");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("happyhour")) {
            return Arrays.asList("here", "set", "remove");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("focus")) {
            return Arrays.asList("fish", "wood", "ore");
        }

        return new ArrayList<>();
    }
}
