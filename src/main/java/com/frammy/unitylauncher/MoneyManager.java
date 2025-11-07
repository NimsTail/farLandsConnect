package com.frammy.unitylauncher;

import org.bukkit.*;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.util.*;

public class MoneyManager implements Listener {

    private final List<Integer> dollarDenominations = Arrays.asList(1000, 500, 200, 100, 50, 20, 10, 5, 2, 1);
    private final List<Integer> centDenominations = Arrays.asList(50, 20, 10, 5, 2, 1); // В центах
    private static final NamespacedKey PDC_MONEY_VALUE =
            new NamespacedKey(UnityLauncher.getInstance(), "money.cents"); // int: сумма в центрах
    private static final NamespacedKey PDC_MONEY_KIND  =
            new NamespacedKey(UnityLauncher.getInstance(), "money.kind");  // "coin" | "note"
    private static final Set<Material> MONEY_MATERIALS = Set.of(
            Material.PRISMARINE_SHARD,      // купюры (доллары)
            Material.PRISMARINE_CRYSTALS    // монеты (центы)
    );
    // PDC-ключи
    private final NamespacedKey KEY_VALUE  = new NamespacedKey(UnityLauncher.getInstance(), "money.value");
    private final NamespacedKey KEY_SIG    = new NamespacedKey(UnityLauncher.getInstance(), "money.sig");
    private final NamespacedKey KEY_VER    = new NamespacedKey(UnityLauncher.getInstance(), "money.ver");

    // Секрет для подписи
    private final String moneySecret;

    private ItemStack createMoneyItem(Material mat, int cents, String glyph) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int abs = Math.abs(cents);
            int dollars = abs / 100;
            int justCents = abs % 100;
            String display = (justCents == 0) ? String.valueOf(dollars) : (dollars + "." + String.format(java.util.Locale.ROOT, "%02d", justCents));

            meta.setDisplayName(ChatColor.GREEN + glyph + "   " + ChatColor.RESET + display);
            // ставим защищающие метки
            meta.getPersistentDataContainer().set(PDC_MONEY_VALUE, PersistentDataType.INTEGER, cents);
            meta.getPersistentDataContainer().set(PDC_MONEY_KIND,  PersistentDataType.STRING, (cents % 100 == 0) ? "note" : "coin");
            // при желании — диапазон CMD, чтобы текстурпак отличал деньги
            meta.setCustomModelData(10000 + (cents % 100 == 0 ? 1 : 2));
            item.setItemMeta(meta);
        }
        return item;
    }

    public void giveMoney(Player player, double amount) {
        int totalCents = (int) Math.round(amount * 100.0);

        UnityCommands.getInstance().getPlayerInfo(player.getName(), data -> {
            if (data == null) {
                new BukkitRunnable(){ @Override public void run(){
                    player.sendMessage(ChatColor.RED + "Данные не найдены.");
                }}.runTask(UnityLauncher.getInstance());
                return;
            }
            if (Math.round(data.money * 100.0) < totalCents) {
                new BukkitRunnable(){ @Override public void run(){
                    player.sendMessage(ChatColor.RED + "Недостаточно средств. Баланс: " + ChatColor.YELLOW + data.money + ChatColor.RED + " Ⓕ.");
                }}.runTask(UnityLauncher.getInstance());
                return;
            }

            // 1) Сформируем стэк предметов (SYNC-логика выполнения addItem — только в main)
            List<ItemStack> toGive = new ArrayList<>();
            int dollars = totalCents / 100, cents = totalCents % 100;

            for (int d : dollarDenominations) {
                while (dollars >= d) {
                    toGive.add(createMoneyItem(Material.PRISMARINE_SHARD, d * 100, "Ⓕ"));
                    dollars -= d;
                }
            }
            for (int c : centDenominations) {
                while (cents >= c) {
                    toGive.add(createMoneyItem(Material.PRISMARINE_CRYSTALS, c, "ⓒ"));
                    cents -= c;
                }
            }

            // 2) Попробуем выдать на главном потоке
            new BukkitRunnable(){ @Override public void run(){
                Map<Integer, ItemStack> leftoversAll = new HashMap<>();
                for (ItemStack it : toGive) {
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(it);
                    if (!leftovers.isEmpty()) leftoversAll.putAll(leftovers);
                }
                // Если что-то не влезло — дропнем у ног
                if (!leftoversAll.isEmpty()) {
                    leftoversAll.values().forEach(l -> player.getWorld().dropItemNaturally(player.getLocation(), l));
                }

                // 3) Списываем баланс АСИНХРОННО
                new BukkitRunnable(){ @Override public void run(){
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("money", round2(data.money - amount));
                    UnityCommands.getInstance().mergeAndUpdatePlayerData(player.getName(), "GeneralData", updates);
                }}.runTaskAsynchronously(UnityLauncher.getInstance());

                player.sendMessage(ChatColor.GRAY + "С твоего счёта снято " + ChatColor.YELLOW + round2(amount) + ChatColor.GRAY + " Ⓕ.");
            }}.runTask(UnityLauncher.getInstance());
        });
    }

    private double getMoneyValue(ItemStack item) {
        if (!verifyMoney(item)) return 0.0;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0.0;
        Double v = meta.getPersistentDataContainer().get(KEY_VALUE, PersistentDataType.DOUBLE);
        return (v == null) ? 0.0 : round2(v);
    }

    public void takeMoney(Player player, double amount, boolean toCountry) {
        if (player == null || amount <= 0) return;

        double remaining = round2(amount);

        // Списываем ТОЛЬКО предметную валюту из инвентаря по слотам.
        Inventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents(); // индексный доступ

        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (!verifyMoney(stack)) continue;

            double unit = getMoneyValue(stack); // номинал одной штуки
            if (unit <= 0) continue;

            int canTakeByValue = (int) Math.floor((remaining + 1e-9) / unit);
            if (canTakeByValue <= 0) continue;

            int take = Math.min(canTakeByValue, stack.getAmount());
            if (take <= 0) continue;

            int newAmount = stack.getAmount() - take;
            remaining = round2(remaining - unit * take);

            if (newAmount <= 0) {
                inv.setItem(slot, null);
            } else {
                ItemStack copy = stack.clone();
                copy.setAmount(newAmount);
                inv.setItem(slot, copy);
            }
        }

        double deposited = round2(amount - remaining);

        if (deposited <= 0.0) {
            player.sendMessage(ChatColor.RED + "Недостаточно наличных в инвентаре.");
            return;
        }

        if (!toCountry) {
            // ===== ВЗНОС НА ЛИЧНЫЙ СЧЁТ ИГРОКА =====
            UnityCommands.getInstance().getPlayerInfo(player.getName(), data -> {
                if (data == null) {
                    new BukkitRunnable(){ @Override public void run(){
                        player.sendMessage(ChatColor.RED + "Данные игрока не найдены.");
                    }}.runTask(UnityLauncher.getInstance());
                    return;
                }
                new BukkitRunnable(){ @Override public void run(){
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("money", round2(data.money + deposited));
                    UnityCommands.getInstance().mergeAndUpdatePlayerData(player.getName(), "GeneralData", updates);
                    player.sendMessage(ChatColor.GRAY + "На твой счёт зачислено " + ChatColor.YELLOW + deposited + ChatColor.GRAY + " Ⓕ.");
                }}.runTaskAsynchronously(UnityLauncher.getInstance());
            });
        } else {
            // ===== ВЗНОС В КАЗНУ СТРАНЫ =====
            // Нужен конкретный метод UnityCommands для баланса страны.
            // Отправляю понятное сообщение, чтобы не «жечь» деньги в никуда.
            UnityCommands.getInstance().getPlayerInfo(player.getName(), data -> {
                if (data == null || data.countryName == null || data.countryName.isBlank()) {
                    new BukkitRunnable(){ @Override public void run(){
                        player.sendMessage(ChatColor.RED + "Ты не состоишь в стране — взнос невозможен.");
                    }}.runTask(UnityLauncher.getInstance());
                    return;
                }
                String country = data.countryName;
                // 👉 ДАЙ, пожалуйста, файл/метод для изменения баланса страны.
                // Временная безопасная логика: вернём деньги игроку на счёт, чтобы не потерять их.
                new BukkitRunnable(){ @Override public void run(){
                    Map<String, Object> updates = new HashMap<>();
                    // вернём на баланс игрока, чтобы не потерять средства (пока нет API страны)
                    updates.put("money", round2(data.money + deposited));
                    UnityCommands.getInstance().mergeAndUpdatePlayerData(player.getName(), "GeneralData", updates);
                    player.sendMessage(ChatColor.RED + "Взнос в казну страны [" + country + "] временно не настроен на сервере.");
                    player.sendMessage(ChatColor.GRAY + "Сумма " + ChatColor.YELLOW + deposited + ChatColor.GRAY + " Ⓕ возвращена на твой счёт, чтобы ничего не потерялось.");
                }}.runTaskAsynchronously(UnityLauncher.getInstance());
            });
        }
    }

    private ItemStack createMoneyItem(Material material, double value, String symbol) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + symbol + "   " + ChatColor.RESET + (symbol.equals("Ⓕ") ? (int) value : (int) Math.round(value * 100.0)));

            // Необязательно, но удобно для текстур-пака:
            if (material == Material.PRISMARINE_SHARD)      meta.setCustomModelData(10010); // «доллары»
            if (material == Material.PRISMARINE_CRYSTALS)   meta.setCustomModelData(10011); // «центы»

            var pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_VER,   PersistentDataType.INTEGER, 1);
            pdc.set(KEY_VALUE, PersistentDataType.DOUBLE,  round2(value));
            String sig = hmac(signPayload(round2(value), 1, material));
            if (sig != null) pdc.set(KEY_SIG, PersistentDataType.STRING, sig);

            // Защита от «красивых» правок
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    @EventHandler
    public void onAnvilRename(PrepareAnvilEvent event) {
        AnvilInventory inv = event.getInventory();
        ItemStack base = inv.getItem(0);
        if (base == null) return;
        if (verifyMoney(base)) {
            event.setResult(null);
            HumanEntity he = event.getView().getPlayer();
            if (he instanceof Player p) {
                p.sendMessage(ChatColor.RED + "Купюры нельзя переименовывать или модифицировать.");
            }
        }
    }

    public MoneyManager(File pluginDataFolder, String pluginName) {
        // Гарантируем наличие секрета в config.yml (один раз генерируется)
        var pl = UnityLauncher.getInstance();
        var cfg = pl.getConfig();
        String path = "economy.money.secret";
        String s = cfg.getString(path);
        if (s == null || s.isBlank()) {
            s = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "");
            cfg.set(path, s);
            pl.saveConfig();
        }
        this.moneySecret = s;
    }

    private String hmac(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(moneySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] raw = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Формируем каноническую строку для подписи. Привяжем ещё и материал. */
    private String signPayload(double value, int ver, Material mat) {
        // Жёстко фиксируем локаль/формат
        String v = String.format(java.util.Locale.ROOT, "%.2f", value);
        return v + "|" + ver + "|" + mat.name();
    }

    /** Полная проверка купюры. */
    private boolean verifyMoney(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material mat = item.getType();
        if (!MONEY_MATERIALS.contains(mat)) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        var pdc = meta.getPersistentDataContainer();

        Integer ver = pdc.get(KEY_VER, PersistentDataType.INTEGER);
        Double  val = pdc.get(KEY_VALUE, PersistentDataType.DOUBLE);
        String  sig = pdc.get(KEY_SIG, PersistentDataType.STRING);
        if (ver == null || ver != 1 || val == null || val <= 0 || sig == null || sig.isBlank()) return false;

        String expect = hmac(signPayload(val, ver, mat));
        return expect != null && expect.equals(sig);
    }

}