package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * Менеджер банковских апгрейдов: сейфы, проценты на вклад, комиссии
 */
public class BankUpgradesManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final UpgradesConfig config;

    // Ключи для PDC
    private final NamespacedKey KEY_SAFE_OWNER;
    private final NamespacedKey KEY_SAFE_ID;

    // Хранилище сейфов: Location -> PlayerName
    private final Map<Location, String> safeBoxes = new ConcurrentHashMap<>();

    // Таск начисления процентов
    private BukkitTask interestTask;

    // Кулдаун для предотвращения спама при проверке комиссий
    private final Map<UUID, Long> atmUsageCooldown = new ConcurrentHashMap<>();

    public BankUpgradesManager(UnityLauncher plugin, ZoneManager zoneManager, UpgradesConfig config) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.config = config;

        this.KEY_SAFE_OWNER = new NamespacedKey(plugin, "safe.owner");
        this.KEY_SAFE_ID = new NamespacedKey(plugin, "safe.id");

    }

    public void start() {
        // Загружаем сейфы из файла
        loadSafes();

        // Запускаем таск начисления процентов (раз в час)
        if (interestTask != null) interestTask.cancel();
        interestTask = new BukkitRunnable() {
            @Override
            public void run() {
                processDepositInterest();
            }
        }.runTaskTimer(plugin, 72000L, 72000L);

        if (config.debug) plugin.getLogger().info("[BankUpgrades] Started");
    }

    public void stop() {
        if (interestTask != null) {
            interestTask.cancel();
            interestTask = null;
        }
        saveSafes();
    }

    // =====================================================================
    //  СЕЙФОВЫЕ ЯЧЕЙКИ (Safe Deposit)
    // =====================================================================

    /**
     * Проверить, может ли игрок создать новый сейф
     */
    public boolean canCreateSafe(Player player) {
        String pc = UpgradeCondition.playerCountryCanonical(player.getName());
        if (pc == null || pc.isBlank()) return false;

        // Проверяем апгрейд страны
        if (countryMaxLevel(pc, config.safeDepositPerm, 1) < 1) return false;

        // Считаем сколько у игрока уже сейфов
        int current = countPlayerSafes(player.getName());
        return current < config.safeDepositMaxPerPlayer;
    }

    private int countPlayerSafes(String playerName) {
        return (int) safeBoxes.values().stream()
                .filter(owner -> owner.equalsIgnoreCase(playerName))
                .count();
    }

    /**
     * Попытка создать сейф из сундука
     */
    public boolean tryCreateSafe(Player player, Block block) {
        if (block.getType() != Material.CHEST) return false;

        Location loc = block.getLocation();

        // Проверяем, что мы в банковской зоне
        ZoneInfo zone = zoneManager.getZoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.BANK) {
            player.sendMessage(ChatColor.RED + "Сейфы можно размещать только в банковских зонах!");
            return false;
        }

        // Проверяем права
        if (!canCreateSafe(player)) {
            player.sendMessage(ChatColor.RED + "У вас нет прав на создание сейфа или достигнут лимит!");
            return false;
        }

        // Проверяем, не занят ли уже этот сундук
        if (safeBoxes.containsKey(loc)) {
            player.sendMessage(ChatColor.RED + "Этот сундук уже является сейфом!");
            return false;
        }

        // Создаём сейф
        safeBoxes.put(loc, player.getName());

        // Помечаем сундук в PDC
        Chest chest = (Chest) block.getState();
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        pdc.set(KEY_SAFE_OWNER, PersistentDataType.STRING, player.getName());
        pdc.set(KEY_SAFE_ID, PersistentDataType.STRING, UUID.randomUUID().toString());
        chest.update();

        player.sendMessage(ChatColor.GREEN + "Сейф успешно создан! Только вы можете открыть его.");
        saveSafes();

        if (config.debug) plugin.getLogger().info("[BankUpgrades] Safe created by " + player.getName() + " at " + loc);

        return true;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (!(e.getInventory().getHolder() instanceof Chest chest)) return;

        Location loc = chest.getLocation();
        String owner = safeBoxes.get(loc);

        if (owner == null) return; // не сейф

        // Проверяем права доступа
        if (!owner.equalsIgnoreCase(p.getName()) && !p.isOp()) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Это личный сейф игрока " + owner + "!");
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeBreak(BlockBreakEvent e) {
        Location loc = e.getBlock().getLocation();
        String owner = safeBoxes.get(loc);

        if (owner == null) return;

        Player p = e.getPlayer();
        if (!owner.equalsIgnoreCase(p.getName()) && !p.isOp()) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Вы не можете сломать чужой сейф!");
            return;
        }

        // Удаляем из списка
        safeBoxes.remove(loc);
        p.sendMessage(ChatColor.YELLOW + "Сейф удалён.");
        saveSafes();
    }

    // =====================================================================
    //  ПРОЦЕНТ НА ВКЛАД (Deposit Interest)
    // =====================================================================

    /**
     * Начисление процентов на деньги в сундуках банков
     */
    private void processDepositInterest() {
        if (config.debug) plugin.getLogger().info("[BankUpgrades] Processing deposit interest...");

        int processed = 0;

        // Проходим по всем сейфам и банковским сундукам
        for (Map.Entry<Location, String> entry : safeBoxes.entrySet()) {
            Location loc = entry.getKey();
            String playerName = entry.getValue();

            // Проверяем апгрейд страны
            String pc = UpgradeCondition.playerCountryCanonical(playerName);
            if (pc == null || countryMaxLevel(pc, config.depositInterestPerm, 1) < 1) continue;

            // Проверяем что сундук загружен
            if (!loc.isWorldLoaded() || !loc.getChunk().isLoaded()) continue;

            Block block = loc.getBlock();
            if (block.getType() != Material.CHEST) continue;

            Chest chest = (Chest) block.getState();
            Inventory inv = chest.getInventory();

            // Считаем деньги в сундуке
            double totalMoney = countMoneyInInventory(inv);
            if (totalMoney <= 0) continue;

            // Начисляем процент (daily_percent / 24 за час)
            double hourlyPercent = config.depositInterestDailyPercent / 24.0;
            double interest = totalMoney * (hourlyPercent / 100.0);

            if (interest >= 0.01) { // минимум 1 цент
                // Начисляем на счёт игрока
                addMoneyToPlayerAccount(playerName, interest);
                processed++;

                if (config.debug) {
                    plugin.getLogger().info("[BankUpgrades] Interest: " + playerName +
                            " +" + String.format("%.2f", interest) + " Ⓕ (from " +
                            String.format("%.2f", totalMoney) + " Ⓕ in safe)");
                }
            }
        }

        if (processed > 0) {
            plugin.getLogger().info("[BankUpgrades] Processed interest for " + processed + " safes");
        }
    }

    /**
     * Подсчёт денег в инвентаре (упрощённая версия - считаем emerald блоки как деньги)
     */
    private double countMoneyInInventory(Inventory inv) {
        double total = 0.0;

        for (ItemStack item : inv.getContents()) {
            if (item == null || item.getType().isAir()) continue;

            // Для примера считаем изумрудные блоки как 1000Ⓕ каждый
            // В реальности нужно проверять PDC money.value как в MoneyManager
            if (item.getType() == Material.EMERALD_BLOCK) {
                total += item.getAmount() * 1000.0;
            } else if (item.getType() == Material.EMERALD) {
                total += item.getAmount() * 100.0;
            } else if (item.getType() == Material.PRISMARINE_SHARD) {
                // Проверяем PDC для настоящих денег
                if (item.hasItemMeta()) {
                    var meta = item.getItemMeta();
                    var pdc = meta.getPersistentDataContainer();
                    NamespacedKey keyValue = new NamespacedKey(plugin, "money.value");
                    Integer value = pdc.get(keyValue, PersistentDataType.INTEGER);
                    if (value != null) {
                        total += (value / 100.0) * item.getAmount();
                    }

                }
            }
        }

        return total;
    }

    private void addMoneyToPlayerAccount(String playerName, double amount) {
        new BukkitRunnable() {
            @Override
            public void run() {
                UnityCommands.getInstance().getPlayerInfo(playerName, data -> {
                    if (data != null) {
                        double newBalance = data.money + amount;
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("money", newBalance);
                        UnityCommands.getInstance().mergeAndUpdatePlayerData(
                            playerName, "GeneralData", updates
                        );
                    }
                });
            }
        }.runTaskAsynchronously(plugin);
    }

    // =====================================================================
    //  КОМИССИИ ATM (Network Fees)
    // =====================================================================

    /**
     * Вычислить комиссию для операции ATM
     * @param playerName имя игрока
     * @param location локация ATM
     * @param amount сумма операции
     * @return комиссия в процентах (0.0 = без комиссии, 0.01 = 1%, и т.д.)
     */
    public double calculateAtmFee(String playerName, Location location, double amount) {
        String pc = UpgradeCondition.playerCountryCanonical(playerName);
        if (pc == null || pc.isBlank()) return 0.0;
        if (amount <= 0) return 0.0;

        ZoneInfo zone = zoneManager.getZoneAt(location);

        // 1) В банковской зоне
        if (zone != null && zone.getType() == ZoneType.BANK) {
            boolean hasAtmNetwork = countryMaxLevel(pc, config.atmPermBase, 1) >= 1;

            // Идея: без апгрейда "ATM Network" банк — как чужая сеть (дороже),
            // с апгрейдом — льготная комиссия банка.
            return hasAtmNetwork ? config.atmFeeInBank : config.atmFeeForeign;
        }

        // 2) На своей территории (COUNTRY/COLONY) — проверяем freeTransfer
        if (zone != null && (zone.getType() == ZoneType.COUNTRY || zone.getType() == ZoneType.COLONY)) {
            String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
            if (pc.equals(zoneCountry)) {
                if (countryMaxLevel(pc, config.freeTransferPerm, 1) >= 1) {
                    return 0.0; // free transfer = без комиссии
                }
                return config.atmFeeInCountry;
            }
        }

        // 3) Чужая территория / нейтралка
        return config.atmFeeForeign;
    }

    /**
     * Применить комиссию к операции
     * @return итоговая сумма после комиссии
     */
    public double applyAtmFee(Player player, Location atmLocation, double amount) {
        double feeRate = calculateAtmFee(player.getName(), atmLocation, amount);
        if (feeRate <= 0.0000001) return amount;

        double fee = amount * feeRate;
        double result = amount - fee;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = atmUsageCooldown.get(uuid);

        if (last == null || (now - last) > 5000) {
            if (fee >= 0.01) {
                player.sendMessage(ChatColor.YELLOW + "Комиссия банкомата: " +
                        ChatColor.RED + String.format("%.2f", fee) + " Ⓕ " +
                        ChatColor.GRAY + "(" + String.format("%.1f", feeRate * 100.0) + "%)");
            }
            atmUsageCooldown.put(uuid, now);
        }

        return result;
    }

    // =====================================================================
    //  СОХРАНЕНИЕ/ЗАГРУЗКА
    // =====================================================================

    private void loadSafes() {
        File file = new File(plugin.getDataFolder(), "bank_safes.yml");
        if (!file.exists()) return;

        try {
            org.bukkit.configuration.file.YamlConfiguration config =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);

            org.bukkit.configuration.ConfigurationSection section = config.getConfigurationSection("safes");
            if (section == null) return;

            for (String key : section.getKeys(false)) {
                String world = section.getString(key + ".world");
                int x = section.getInt(key + ".x");
                int y = section.getInt(key + ".y");
                int z = section.getInt(key + ".z");
                String owner = section.getString(key + ".owner");

                if (world != null && owner != null) {
                    World w = Bukkit.getWorld(world);
                    if (w != null) {
                        Location loc = new Location(w, x, y, z);
                        safeBoxes.put(loc, owner);
                    }
                }
            }

            plugin.getLogger().info("[BankUpgrades] Loaded " + safeBoxes.size() + " safe boxes");
        } catch (Exception e) {
            plugin.getLogger().severe("[BankUpgrades] Failed to load safes: " + e.getMessage());
        }
    }

    private void saveSafes() {
        File file = new File(plugin.getDataFolder(), "bank_safes.yml");
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();

        int i = 0;
        for (Map.Entry<Location, String> entry : safeBoxes.entrySet()) {
            Location loc = entry.getKey();
            String owner = entry.getValue();

            String key = "safes.safe_" + i;
            config.set(key + ".world", loc.getWorld().getName());
            config.set(key + ".x", loc.getBlockX());
            config.set(key + ".y", loc.getBlockY());
            config.set(key + ".z", loc.getBlockZ());
            config.set(key + ".owner", owner);

            i++;
        }

        try {
            config.save(file);
        } catch (Exception e) {
            plugin.getLogger().severe("[BankUpgrades] Failed to save safes: " + e.getMessage());
        }
    }

    // =====================================================================
    //  API для других систем
    // =====================================================================

    public boolean isSafe(Location location) {
        return safeBoxes.containsKey(location);
    }

    public String getSafeOwner(Location location) {
        return safeBoxes.get(location);
    }
}
