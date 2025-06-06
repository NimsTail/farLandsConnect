package com.frammy.unitylauncher;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MoneyManager implements Listener {
    private final File issuedMoneyFile;
    private final YamlConfiguration issuedMoneyConfig;
    private final List<Integer> dollarDenominations = Arrays.asList(1000, 500, 200, 100, 50, 20, 10, 5, 2, 1);
    private final List<Integer> centDenominations = Arrays.asList(50, 20, 10, 5, 2, 1); // В центах
    private final NamespacedKey idKey;
    private final HashMap<String, List<String>> chestMoneyMap = new HashMap<>();

    public MoneyManager(File pluginDataFolder, String pluginName) {
        issuedMoneyFile = new File(pluginDataFolder, "issued_money.yml");
        if (!issuedMoneyFile.exists()) {
            try {
                issuedMoneyFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        issuedMoneyConfig = YamlConfiguration.loadConfiguration(issuedMoneyFile);
        idKey = new NamespacedKey(pluginName, "money_id");
    }

    public void giveMoney(Player player, double amount) {
        int totalCents = (int) Math.round(amount * 100);
        int dollars = totalCents / 100;
        int cents = totalCents % 100;

        List<ItemStack> itemsToGive = new ArrayList<>();

        for (int denomination : dollarDenominations) {
            while (dollars >= denomination) {
                ItemStack moneyItem = createMoneyItem(Material.PRISMARINE_SHARD, denomination, "Dollar");
                itemsToGive.add(moneyItem);
                registerMoney(player, moneyItem, denomination, "Dollar");
                dollars -= denomination;
            }
        }

        for (int denomination : centDenominations) {
            while (cents >= denomination) {
                ItemStack moneyItem = createMoneyItem(Material.PRISMARINE_CRYSTALS, denomination / 100.0, "Cent");
                itemsToGive.add(moneyItem);
                registerMoney(player, moneyItem, denomination / 100.0, "Cent");
                cents -= denomination;
            }
        }

        for (ItemStack item : itemsToGive) {
            player.getInventory().addItem(item);
        }

       // player.sendMessage("Вы получили " + totalCents / 100 + " долларов и " + totalCents % 100 + " центов.");
    }

    public boolean takeMoney(Player player, double amount) {
        int totalCents = (int) Math.round(amount * 100); // Сумма в центах
        int remainingCents = totalCents;

        Map<Integer, List<ItemStack>> moneyMap = new TreeMap<>(Collections.reverseOrder());
        int totalInventoryCents = 0;

        // Сканируем инвентарь игрока
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() == Material.AIR) continue;

            if (isMoney(item)) {
                double itemValue = extractMoneyValue(item);
                int itemCents = (int) Math.round(itemValue * 100);
                totalInventoryCents += itemCents * item.getAmount();

                moneyMap.putIfAbsent(itemCents, new ArrayList<>());
                moneyMap.get(itemCents).add(item);
            }
        }

        // Проверяем, достаточно ли средств
        if (totalInventoryCents < totalCents) {
            player.sendMessage("Недостаточно средств для выполнения операции.");
            return false;
        }

        List<ItemStack> itemsToRemove = new ArrayList<>();
        List<ItemStack> changeToGive = new ArrayList<>();

        // Забираем деньги из инвентаря
        for (Map.Entry<Integer, List<ItemStack>> entry : moneyMap.entrySet()) {
            int denominationCents = entry.getKey();
            List<ItemStack> stacks = entry.getValue();

            for (ItemStack stack : stacks) {
                while (remainingCents > 0 && stack.getAmount() > 0) {
                    if (remainingCents >= denominationCents) {
                        // Забираем купюру и уменьшаем оставшуюся сумму
                        remainingCents -= denominationCents;
                        removeMoney(player, stack); // Удаляем купюру из сохранений
                        stack.setAmount(stack.getAmount() - 1);
                        if (stack.getAmount() == 0) itemsToRemove.add(stack);
                    } else {
                        // Если купюра больше, чем нужно, используем её для сдачи
                        int changeCents = denominationCents - remainingCents;
                        remainingCents = 0;
                        removeMoney(player, stack); // Удаляем купюру из сохранений
                        stack.setAmount(stack.getAmount() - 1);
                        if (stack.getAmount() == 0) itemsToRemove.add(stack);

                        // Рассчитываем сдачу
                        for (int denom : dollarDenominations) {
                            while (changeCents >= denom * 100) {
                                changeCents -= denom * 100;
                                ItemStack changeItem = createMoneyItem(Material.PRISMARINE_SHARD, denom, "Dollar");
                                changeToGive.add(changeItem);
                            }
                        }
                        for (int denom : centDenominations) {
                            while (changeCents >= denom) {
                                changeCents -= denom;
                                ItemStack changeItem = createMoneyItem(Material.PRISMARINE_CRYSTALS, denom / 100.0, "Cent");
                                changeToGive.add(changeItem);
                            }
                        }

                        // Если сдачу невозможно выдать, отменяем операцию
                        if (changeCents > 0) {
                            player.sendMessage("Ошибка: сдачу невозможно выдать. Обратитесь к администратору.");
                            return false;
                        }
                    }
                }

                if (remainingCents <= 0) break;
            }

            if (remainingCents <= 0) break;
        }

        // Удаляем купюры из инвентаря
        for (ItemStack item : itemsToRemove) {
            player.getInventory().remove(item);
        }

        // Регистрируем и выдаём сдачу
        for (ItemStack item : changeToGive) {
            player.getInventory().addItem(item);
            double value = extractMoneyValue(item);
            String type = item.getType() == Material.PRISMARINE_SHARD ? "Dollar" : "Cent";
            registerMoney(player, item, value, type); // Регистрируем сдачу
        }

        // Проверяем, остались ли неоплаченные средства
        if (remainingCents > 0) {
            player.sendMessage("Ошибка: не удалось снять полную сумму. Обратитесь к администратору.");
            return false;
        }

       // player.sendMessage("С вашего счета снято " + amount + " долларов.");
        return true;
    }


    private void removeMoney(Player player, ItemStack item) {
        String playerId = player.getUniqueId().toString();
        String displayName = item.getItemMeta().getDisplayName();
        String path = playerId + "." + displayName;

        if (issuedMoneyConfig.contains(path)) {
            List<String> moneyIds = issuedMoneyConfig.getStringList(path);
            if (!moneyIds.isEmpty()) {
                moneyIds.remove(0); // Удаляем первый ID из списка
                if (moneyIds.isEmpty()) {
                    issuedMoneyConfig.set(path, null); // Удаляем путь, если список пуст
                } else {
                    issuedMoneyConfig.set(path, moneyIds);
                }
                saveIssuedMoney();
            }
        }
    }

    private ItemStack createMoneyItem(Material material, double value, String type) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String displayValue;
            if (type.equals("Dollar")) {
                displayValue = String.valueOf((int) value); // Отображаем целое число для долларов
            } else {
                displayValue = String.valueOf((int) (value * 100)); // Для центов отображаем в сотых
            }
            meta.setDisplayName(type + " " + displayValue);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void registerMoney(Player player, ItemStack item, double value, String type) {
        String uniqueID = UUID.randomUUID().toString();
        String playerId = player.getUniqueId().toString();
        issuedMoneyConfig.set(playerId + ".Nickname", player.getName());

        String path = playerId + "." + type + " " + (int) value; // Преобразуем value в целое число для соответствия.
        List<String> moneyIds = issuedMoneyConfig.getStringList(path); // Загружаем список или создаём новый.
        moneyIds.add(uniqueID); // Добавляем новый ID.
        issuedMoneyConfig.set(path, moneyIds); // Обновляем путь с новым списком.
        saveIssuedMoney();
    }

    private boolean isMoney(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;

            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return false;

            String displayName = meta.getDisplayName();
            return displayName.toLowerCase().contains("dollar") || displayName.toLowerCase().contains("cent");


    }

    private double extractMoneyValue(ItemStack item) {
        if (item == null || !isMoney(item)) return 0.0;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.getDisplayName() == null) return 0.0;

        String displayName = meta.getDisplayName();
        try {
            String[] parts = displayName.split(" ");
            double value = Double.parseDouble(parts[1]);
            return parts[0].equals("Dollar") ? value : value / 100; // Доллары целые, центы делятся на 100
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void saveIssuedMoney() {
        try {
            issuedMoneyConfig.save(issuedMoneyFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        ItemStack item = event.getItemDrop().getItemStack();
        Player player = event.getPlayer();

        if (isMoney(item)) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                PersistentDataContainer container = meta.getPersistentDataContainer();
                String uniqueID = UUID.randomUUID().toString();
                container.set(idKey, PersistentDataType.STRING, uniqueID);
                item.setItemMeta(meta);

                // Удаляем купюру из сохранения игрока
                String playerId = player.getUniqueId().toString();
                String displayName = item.getItemMeta().getDisplayName();
                String path = playerId + "." + displayName;

                if (issuedMoneyConfig.contains(path)) {
                    List<String> moneyIds = issuedMoneyConfig.getStringList(path);
                    if (!moneyIds.isEmpty()) {
                        moneyIds.remove(0); // Удаляем первый ID из списка
                        if (moneyIds.isEmpty()) {
                            issuedMoneyConfig.set(path, null); // Удаляем путь, если список пуст
                        } else {
                            issuedMoneyConfig.set(path, moneyIds);
                        }
                        saveIssuedMoney();
                    }
                }
            }
        }
    }

    @EventHandler
    public void onItemPickup(PlayerPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        Player player = event.getPlayer();

        if (isMoney(item)) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                PersistentDataContainer container = meta.getPersistentDataContainer();

                if (container.has(idKey, PersistentDataType.STRING)) {
                    String uniqueID = container.get(idKey, PersistentDataType.STRING);
                    container.remove(idKey);
                    item.setItemMeta(meta);

                    // Добавляем купюру в сохранение игрока
                    String playerId = player.getUniqueId().toString();
                    String displayName = item.getItemMeta().getDisplayName();
                    String path = playerId + "." + displayName;

                    List<String> moneyIds = issuedMoneyConfig.getStringList(path);
                    moneyIds.add(uniqueID);
                    issuedMoneyConfig.set(path, moneyIds);
                    saveIssuedMoney();
                }
            }
        }
    }
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == null || event.getWhoClicked() == null) return;

        Inventory clickedInventory = event.getClickedInventory();
        InventoryAction action = event.getAction();

        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        Player player = (Player) event.getWhoClicked();
        System.out.println("CURSOR " + cursorItem);
        System.out.println("CURRENT " + currentItem);

        Inventory topInventory = event.getView().getTopInventory();
        Inventory bottomInventory = event.getView().getBottomInventory();

        if (topInventory.getType() == InventoryType.CHEST) {
            Block block = topInventory.getLocation().getBlock();
            if (block.getState() instanceof Chest) {
                Chest chest = (Chest) block.getState();

                if (event.isShiftClick()) {
                    // Проверка, откуда предмет перемещается
                    if (clickedInventory == bottomInventory) {
                        // Перемещение из инвентаря игрока в сундук
                        handleMoneyTransferToChest(currentItem, chest, player);
                    } else if (clickedInventory == topInventory) {
                        // Перемещение из сундука в инвентарь игрока
                        handleMoneyTransferFromChest(currentItem, chest, player);
                    }
                } else {
                    player.sendMessage("Взаимодействие с сундуком. Действие: " + action.name());

                    switch (action) {
                        case PLACE_ONE:
                        case PLACE_ALL:
                        case PLACE_SOME:
                            handleMoneyTransferToChest(getEffectiveItem(currentItem, cursorItem), chest, player);
                            break;
                        case PICKUP_ONE:
                        case PICKUP_ALL:
                        case PICKUP_SOME:
                        case PICKUP_HALF:
                            handleMoneyTransferFromChest(getEffectiveItem(currentItem, cursorItem), chest, player);
                            break;
                        default:
                            player.sendMessage("Необработанное действие: " + action.name());
                            break;
                    }
                }
            }
        }
    }

    private ItemStack getEffectiveItem(ItemStack currentItem, ItemStack cursorItem) {
        return (currentItem == null || currentItem.getType() == Material.AIR) ? cursorItem : currentItem;
    }

    /* @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getType() == InventoryType.CHEST) {
            Block block = event.getInventory().getLocation().getBlock();
            if (block.getState() instanceof Chest) {
                Chest chest = (Chest) block.getState();

                // Обновляем данные сундука, перебирая содержимое
                String chestKey = getChestKey(chest.getLocation());
                issuedMoneyConfig.set("chests." + chestKey, null); // Удаляем старую запись

                for (ItemStack item : chest.getBlockInventory().getContents()) {
                    if (item != null && isMoney(item)) {
                        String itemDisplayName = item.getItemMeta().getDisplayName();
                        List<String> chestMoneyIds = issuedMoneyConfig.getStringList("chests." + chestKey + "." + itemDisplayName);

                        ItemMeta meta = item.getItemMeta();
                        if (meta != null) {
                            PersistentDataContainer container = meta.getPersistentDataContainer();
                            if (container.has(idKey, PersistentDataType.STRING)) {
                                String moneyId = container.get(idKey, PersistentDataType.STRING);
                                chestMoneyIds.add(moneyId);
                                issuedMoneyConfig.set("chests." + chestKey + "." + itemDisplayName, chestMoneyIds);
                            }
                        }
                    }
                }

                saveIssuedMoney();
            }
        }
    }*/
    // Метод обработки переноса денег в сундук
    private void handleMoneyTransferToChest(ItemStack item, Chest chest, Player player) {
        if (isMoney(item)) {
            player.sendMessage("Переносим деньги в сундук...");
            String playerPath = player.getUniqueId().toString() + "." + item.getItemMeta().getDisplayName();
            List<String> playerMoneyIds = issuedMoneyConfig.getStringList(playerPath);

            if (!playerMoneyIds.isEmpty()) {
                String moneyId = playerMoneyIds.remove(0);
                issuedMoneyConfig.set(playerPath, playerMoneyIds.isEmpty() ? null : playerMoneyIds);

                String chestKey = getChestKey(chest.getLocation());
                String itemDisplayName = item.getItemMeta().getDisplayName();
                List<String> chestMoneyIds = issuedMoneyConfig.getStringList("chests." + chestKey + "." + itemDisplayName);
                chestMoneyIds.add(moneyId);

                issuedMoneyConfig.set("chests." + chestKey + "." + itemDisplayName, chestMoneyIds);
                saveIssuedMoney();

                player.sendMessage("Деньги перенесены. ID: " + moneyId);
            } else {
                player.sendMessage("Ошибка: У вас нет таких денег.");
            }
        } else {
            player.sendMessage("Ошибка: Это не деньги.");
        }
    }
    // Метод обработки переноса денег из сундука
    private void handleMoneyTransferFromChest(ItemStack item, Chest chest, Player player) {
        if (isMoney(item)) {
            player.sendMessage("Забираем деньги из сундука...");
            String chestKey = getChestKey(chest.getLocation());
            String itemDisplayName = item.getItemMeta().getDisplayName();
            List<String> chestMoneyIds = issuedMoneyConfig.getStringList("chests." + chestKey + "." + itemDisplayName);

            if (chestMoneyIds != null && !chestMoneyIds.isEmpty()) {
                String moneyId = chestMoneyIds.remove(0);
                issuedMoneyConfig.set("chests." + chestKey + "." + itemDisplayName,
                        chestMoneyIds.isEmpty() ? null : chestMoneyIds);

                String playerPath = player.getUniqueId().toString() + "." + itemDisplayName;
                List<String> playerMoneyIds = issuedMoneyConfig.getStringList(playerPath);
                playerMoneyIds.add(moneyId);
                issuedMoneyConfig.set(playerPath, playerMoneyIds);
                saveIssuedMoney();

                player.sendMessage("Деньги возвращены. ID: " + moneyId);
            } else {
                player.sendMessage("Ошибка: В сундуке нет таких денег.");
            }
        } else {
            player.sendMessage("Ошибка: Это не деньги.");
        }
    }
    // Обработка добавления денег в сундук через воронку
    @EventHandler
    public void onHopperTransfer(InventoryMoveItemEvent event) {
        if (event.getDestination().getType() == InventoryType.CHEST) {
            Chest chest = (Chest) event.getDestination().getLocation().getBlock().getState();
            ItemStack item = event.getItem();

            if (isMoney(item)) {
                // Извлекаем ID из NBT-тега предмета
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    PersistentDataContainer container = meta.getPersistentDataContainer();
                    if (container.has(idKey, PersistentDataType.STRING)) {
                        String moneyId = container.get(idKey, PersistentDataType.STRING);

                        // Добавляем ID в структуру сундука
                        String chestKey = getChestKey(chest.getLocation());
                        chestMoneyMap.computeIfAbsent(chestKey, k -> new ArrayList<>()).add(moneyId);

                        // Сохраняем изменения
                        saveIssuedMoney();
                    }
                }
            }
        }
    }

    private void saveChestMoney(Chest chest) {
        String chestKey = getChestKey(chest.getLocation());
        issuedMoneyConfig.set("chests." + chestKey, null); // Удаляем старую запись

        if (chestMoneyMap.containsKey(chestKey)) {
            for (Map.Entry<String, List<String>> entry : chestMoneyMap.entrySet()) {
                String moneyType = entry.getKey();
                List<String> moneyIds = entry.getValue();

                if (!moneyIds.isEmpty()) {
                    issuedMoneyConfig.set("chests." + chestKey + "." + moneyType, moneyIds);
                }
            }
        }

        saveIssuedMoney();
    }

    private String getChestKey(Location location) {
        return location.getWorld().getName() + "_" + location.getBlockX() + "_" + location.getBlockY() + "_" + location.getBlockZ();
    }
}


