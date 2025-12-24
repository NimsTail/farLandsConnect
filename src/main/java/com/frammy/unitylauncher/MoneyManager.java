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
    private final List<Integer> centDenominations   = Arrays.asList(50, 20, 10, 5, 2, 1); // в центах

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

    public MoneyManager(File pluginDataFolder, String pluginName) {
        var pl  = UnityLauncher.getInstance();
        var cfg = pl.getConfig();
        String path = "economy.money.secret";
        String s = cfg.getString(path);
        if (s == null || s.isBlank()) {
            s = java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "");
            cfg.set(path, s);
            pl.saveConfig();
        }
        this.moneySecret = s;
    }

    /* ===================== ПУБЛИЧНЫЕ ОПЕРАЦИИ ===================== */

    /**
     * Выдать игроку наличку с его банковского счёта.
     * Счёт уменьшается, в инвентарь кладём купюры/монеты.
     */
    public void giveMoney(Player player, double amount) {
        long totalCents = toCents(amount);
        if (totalCents <= 0) {
            player.sendMessage(ChatColor.RED + "Сумма должна быть больше нуля.");
            return;
        }

        UnityCommands.getInstance().getPlayerInfo(player.getName(), data -> {
            if (data == null) {
                new BukkitRunnable() {
                    @Override public void run() {
                        player.sendMessage(ChatColor.RED + "Данные не найдены.");
                    }
                }.runTask(UnityLauncher.getInstance());
                return;
            }

            long balanceCents = toCents(data.money);
            if (balanceCents < totalCents) {
                new BukkitRunnable() {
                    @Override public void run() {
                        player.sendMessage(ChatColor.RED + "Недостаточно средств. Баланс: " +
                                ChatColor.YELLOW + round2(data.money) + ChatColor.RED + " Ⓕ.");
                    }
                }.runTask(UnityLauncher.getInstance());
                return;
            }

            List<ItemStack> cashItems = buildCashItemsFromCents(totalCents);

            new BukkitRunnable() {
                @Override public void run() {
                    addItemsOrDrop(player, cashItems);

                    // 3) Списываем баланс АСИНХРОННО
                    new BukkitRunnable() {
                        @Override public void run() {
                            double newBalance = centsToDouble(balanceCents - totalCents);
                            Map<String, Object> updates = new HashMap<>();
                            updates.put("money", newBalance);
                            UnityCommands.getInstance().mergeAndUpdatePlayerData(
                                    player.getName(), "GeneralData", updates
                            );
                        }
                    }.runTaskAsynchronously(UnityLauncher.getInstance());

                    player.sendMessage(ChatColor.GRAY + "С твоего счёта снято " +
                            ChatColor.YELLOW + round2(amount) + ChatColor.GRAY + " Ⓕ наличными.");
                }
            }.runTask(UnityLauncher.getInstance());
        });
    }

    /**
     * Игрок кладёт наличку на счёт или в казну страны.
     * Всегда забирает РОВНО amount. Остаток возвращает сдачей.
     */
    public void takeMoney(Player player, double amount, boolean toCountry) {
        if (player == null || amount <= 0) return;

        long amountCents = toCents(amount);
        if (amountCents <= 0) return;

        Inventory inv = player.getInventory();
        long totalCents = getInventoryCashCents(inv);

        if (totalCents < amountCents) {
            player.sendMessage(ChatColor.RED + "Недостаточно наличных в инвентаре.");
            return;
        }

        // Забираем всю наличку, чтобы потом выдать сдачу
        long collectedCents = collectAndClearCash(inv);
        long remainingCents = collectedCents - amountCents;
        if (remainingCents < 0) remainingCents = 0;

        // Выдаём сдачу
        List<ItemStack> change = buildCashItemsFromCents(remainingCents);
        addItemsOrDrop(player, change);

        double deposited = centsToDouble(amountCents);

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
                    double newBalance = round2(data.money + deposited);
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("money", newBalance);
                    UnityCommands.getInstance().mergeAndUpdatePlayerData(player.getName(), "GeneralData", updates);
                    player.sendMessage(ChatColor.GRAY + "На твой счёт зачислено " + ChatColor.YELLOW + deposited + ChatColor.GRAY + " Ⓕ.");
                }}.runTaskAsynchronously(UnityLauncher.getInstance());
            });
        } else {
            // ===== ВЗНОС В КАЗНУ СТРАНЫ =====
            UnityCommands.getInstance().getPlayerInfo(player.getName(), data -> {
                if (data == null || data.countryName == null || data.countryName.isBlank()) {
                    new BukkitRunnable(){ @Override public void run(){
                        player.sendMessage(ChatColor.RED + "Ты не состоишь в стране — взнос невозможен.");
                    }}.runTask(UnityLauncher.getInstance());
                    return;
                }
                String country = data.countryName;

                new BukkitRunnable(){ @Override public void run(){
                    // 1) Зачисляем в казну страны (атомарно в БД)
                    UnityLauncher.getInstance()
                            .getCountryRegistryJdbc() // <- см. ниже как добавить геттер
                            .addCountryMoney(country, deposited);

                    // 2) Сообщение игроку
                    new BukkitRunnable(){ @Override public void run(){
                        player.sendMessage(ChatColor.GRAY + "В казну страны [" + ChatColor.YELLOW + country
                                + ChatColor.GRAY + "] внесено " + ChatColor.YELLOW + round2(deposited)
                                + ChatColor.GRAY + " Ⓕ.");
                    }}.runTask(UnityLauncher.getInstance());

                }}.runTaskAsynchronously(UnityLauncher.getInstance());

            });
        }
    }

    /**
     * Списывает деньги со счёта игрока (банковский баланс, НЕ наличка).
     * Используется в авто-биллинге зон.
     */
    public void withdraw(String playerName, double amount) {
        if (playerName == null || playerName.isBlank()) {
            Bukkit.getLogger().warning("[MoneyManager] withdraw: пустое имя игрока");
            return;
        }

        long amountCents = toCents(amount);
        if (amountCents <= 0) return;

        UnityCommands.getInstance().getPlayerInfo(playerName, data -> {
            if (data == null) {
                Bukkit.getLogger().warning("[MoneyManager] withdraw: данные игрока '" + playerName + "' не найдены");
                return;
            }

            long curCents = toCents(data.money);
            if (curCents < amountCents) {
                Bukkit.getLogger().info("[MoneyManager] withdraw: у '" + playerName + "' мало денег: "
                        + centsToDouble(curCents) + " Ⓕ, нужно " + centsToDouble(amountCents) + " Ⓕ");
                return;
            }

            long newCents = curCents - amountCents;

            new BukkitRunnable() {
                @Override
                public void run() {
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("money", centsToDouble(newCents));
                    UnityCommands.getInstance()
                            .mergeAndUpdatePlayerData(playerName, "GeneralData", upd);
                }
            }.runTaskAsynchronously(UnityLauncher.getInstance());
        });
    }

    /**
     * Подсчитывает сумму наличных в инвентаре игрока.
     */
    public double getInventoryCash(Player player) {
        if (player == null) return 0.0;
        long cents = getInventoryCashCents(player.getInventory());
        return centsToDouble(cents);
    }

    /**
     * Пытается списать указанную сумму наличными из инвентаря игрока.
     * Если денег недостаточно — вообще ничего не трогает и возвращает false.
     * Если удалось — забирает РОВНО amount и возвращает сдачу купюрами.
     */
    public boolean spendCash(Player player, double amount) {
        if (player == null || amount <= 0) return false;

        long amountCents = toCents(amount);
        if (amountCents <= 0) return false;

        Inventory inv = player.getInventory();
        long totalCents = getInventoryCashCents(inv);
        if (totalCents < amountCents) {
            return false;
        }

        long collected = collectAndClearCash(inv);
        long remaining = collected - amountCents;
        if (remaining < 0) remaining = 0;

        List<ItemStack> change = buildCashItemsFromCents(remaining);
        addItemsOrDrop(player, change);

        return true;
    }

    /* ===================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ ЦЕНТОВ ===================== */

    private long toCents(double value) {
        return Math.round(value * 100.0);
    }

    private double centsToDouble(long cents) {
        return ((double) cents) / 100.0;
    }

    private long getMoneyCents(ItemStack item) {
        double v = getMoneyValue(item);
        if (v <= 0) return 0;
        return toCents(v);
    }

    private long getInventoryCashCents(Inventory inv) {
        long sum = 0L;
        for (ItemStack stack : inv.getStorageContents()) {
            if (!verifyMoney(stack)) continue;
            long unit = getMoneyCents(stack);
            if (unit <= 0) continue;
            sum += unit * stack.getAmount();
        }
        return sum;
    }

    /**
     * Забирает все валидные деньги из инвентаря, возвращает суммарную сумму в центах.
     */
    private long collectAndClearCash(Inventory inv) {
        long total = 0L;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (!verifyMoney(stack)) continue;
            long unit = getMoneyCents(stack);
            if (unit <= 0) continue;
            total += unit * stack.getAmount();
            inv.setItem(i, null);
        }
        return total;
    }

    /**
     * Разбивает сумму в центах на купюры/монеты по заранее заданным номиналам.
     */
    private List<ItemStack> buildCashItemsFromCents(long totalCents) {
        List<ItemStack> list = new ArrayList<>();
        if (totalCents <= 0) return list;

        long dollars = totalCents / 100;
        int cents     = (int) (totalCents % 100);

        // Купюры
        for (int d : dollarDenominations) {
            while (dollars >= d) {
                list.add(createMoneyItem(Material.PRISMARINE_SHARD, d, "Ⓕ"));
                dollars -= d;
            }
        }

        // Монеты
        for (int c : centDenominations) {
            while (cents >= c) {
                list.add(createMoneyItem(
                        Material.PRISMARINE_CRYSTALS,
                        c / 100.0,
                        "ⓒ"
                ));
                cents -= c;
            }
        }

        return list;
    }

    /**
     * Кладёт предметы в инвентарь, остатки дропает у ног.
     */
    private void addItemsOrDrop(Player player, List<ItemStack> items) {
        Map<Integer, ItemStack> leftoversAll = new HashMap<>();
        for (ItemStack it : items) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(it);
            if (!leftovers.isEmpty()) leftoversAll.putAll(leftovers);
        }
        if (!leftoversAll.isEmpty()) {
            leftoversAll.values().forEach(l ->
                    player.getWorld().dropItemNaturally(player.getLocation(), l)
            );
        }
    }

    /* ===================== СОЗДАНИЕ / ПРОВЕРКА КУПЮР ===================== */

    private ItemStack createMoneyItem(Material material, double value, String symbol) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // Для доллара показываем целое, для монет — количество центов
            String displayValue = symbol.equals("Ⓕ")
                    ? String.valueOf((int) value)
                    : String.valueOf((int) Math.round(value * 100.0));

            meta.setDisplayName(ChatColor.GREEN + symbol + "   " +
                    ChatColor.RESET + displayValue);

            if (material == Material.PRISMARINE_SHARD)    meta.setCustomModelData(10010); // «доллары»
            if (material == Material.PRISMARINE_CRYSTALS) meta.setCustomModelData(10011); // «центы»

            var pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_VER,   PersistentDataType.INTEGER, 1);
            pdc.set(KEY_VALUE, PersistentDataType.DOUBLE,  round2(value));
            String sig = hmac(signPayload(round2(value), 1, material));
            if (sig != null) pdc.set(KEY_SIG, PersistentDataType.STRING, sig);

            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                    org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private double getMoneyValue(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0.0;
        if (!verifyMoney(item)) return 0.0;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0.0;
        var pdc = meta.getPersistentDataContainer();

        Double v = pdc.get(KEY_VALUE, PersistentDataType.DOUBLE);
        if (v != null && v > 0.0) {
            return round2(v);
        }
        return 0.0;
    }

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

        if (ver != null && ver == 1 && val != null && val > 0 && sig != null && !sig.isBlank()) {
            String expect = hmac(signPayload(val, ver, mat));
            return expect != null && expect.equals(sig);
        }
        return false;
    }

    public boolean isMoneyItem(ItemStack item) {
        return verifyMoney(item);
    }

    /* ===================== ПРОЧЕЕ ===================== */

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

    private String hmac(String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(
                    moneySecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(key);
            byte[] raw = mac.doFinal(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String signPayload(double value, int ver, Material mat) {
        String v = String.format(java.util.Locale.ROOT, "%.2f", value);
        return v + "|" + ver + "|" + mat.name();
    }

    public void tryWithdraw(String playerName, double amount, java.util.function.Consumer<Boolean> cb) {
        if (playerName == null || playerName.isBlank() || amount <= 0) {
            if (cb != null) cb.accept(false);
            return;
        }

        long amountCents = toCents(amount);
        if (amountCents <= 0) { if (cb != null) cb.accept(false); return; }

        UnityCommands.getInstance().getPlayerInfo(playerName, data -> {
            if (data == null) { if (cb != null) cb.accept(false); return; }

            long curCents = toCents(data.money);
            if (curCents < amountCents) {
                if (cb != null) cb.accept(false);
                return;
            }

            long newCents = curCents - amountCents;

            new BukkitRunnable() {
                @Override public void run() {
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("money", centsToDouble(newCents));
                    UnityCommands.getInstance().mergeAndUpdatePlayerData(playerName, "GeneralData", upd);
                    if (cb != null) cb.accept(true);
                }
            }.runTaskAsynchronously(UnityLauncher.getInstance());
        });
    }

}
