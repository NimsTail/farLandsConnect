package com.frammy.unitylauncher.signs.features.atm;

import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignState;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.render.SignScrollService;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.upgrades.impl.AtmFeesUpgrade;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.NonNull;

import java.util.*;

public final class AtmController {

    private static final String GUI_TITLE_PREFIX = ChatColor.DARK_GREEN + "ATM";

    private final UnityLauncher plugin;
    private final BlueMapIntegration blueMap;
    private final SignStore store;
    private final SignScrollService scroll;

    private final Map<UUID, Location> openAtmByPlayer = new HashMap<>();
    private final Map<UUID, PendingInput> pending = new HashMap<>();

    public AtmController(UnityLauncher plugin, BlueMapIntegration blueMap, SignStore store, SignScrollService scroll) {
        this.plugin = plugin;
        this.blueMap = blueMap;
        this.store = store;
        this.scroll = scroll;
    }

    // ===== creation =====

    public void onSignCreateATM(SignChangeEvent e,
                                String countryCanonical,
                                int have,
                                int allowed) {

        Player p = e.getPlayer();
        Location loc = SignStore.keyLoc(e.getBlock().getLocation());

        int need = have + 1;
        if (allowed < need) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Нельзя поставить ATM №" + need + " для страны [" + countryCanonical + "]. "
                    + ChatColor.GRAY + "Нужно разрешение! (куплено: " + allowed + ").");
            return;
        }

        // Ставим сразу (не ждём async), потом можно “украсить” названием страны из Users.
        String title = "ATM [" + countryCanonical + "]";
        e.setLine(0, title);
        e.setLine(1, "Коснитесь,");
        e.setLine(2, "чтобы начать");
        e.setLine(3, "");

        Map<Integer, String> scrollLines = new HashMap<>();
        scrollLines.put(0, title);
        Bukkit.getScheduler().runTask(plugin, () ->
                scroll.makeSignScrollingLines(loc, scrollLines, 6, 13)
        );

        String markerID = "marker_" + UUID.randomUUID();

        store.put(loc, new SignVariables(
                p.getName(),
                countryCanonical,
                List.of(title, "Коснитесь,", "чтобы начать", ""),
                List.of(0),
                false,
                false,
                SignCategory.ATM,
                SignState.ATM_MENU,
                markerID
        ));

        blueMap.addBlueMapMarker(markerID, loc, "services", "Сервисы", "point_atm", null, p);

        p.sendMessage(ChatColor.GREEN + "Банкомат установлен." + ChatColor.GRAY + " (" + need + "/" + allowed + ")");

        // Пытаемся заменить canonical на “красивое” имя страны из Users (как в Legacy).
        UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
            if (data == null) return;
            new BukkitRunnable() {
                @Override public void run() {
                    SignVariables sv = store.get(loc);
                    if (sv == null) return;

                    String pretty = data.countryName;
                    if (pretty == null || pretty.isBlank()) return;

                    if (!(loc.getBlock().getState() instanceof Sign sign)) return;

                    String newTitle = "ATM [" + pretty + "]";
                    sign.setLine(0, newTitle);
                    sign.update();

                    // обновим текст в переменных
                    List<String> t = new ArrayList<>(sv.getSignText());
                    while (t.size() < 4) t.add("");
                    t.set(0, newTitle);
                    sv.setSignText(t);

                    Map<Integer, String> sl = new HashMap<>();
                    sl.put(0, newTitle);
                    scroll.makeSignScrollingLines(loc, sl, 6, 13);
                }
            }.runTask(UnityLauncher.getInstance());
        });
    }

    // ===== interact =====

    public void onInteract(PlayerInteractEvent e, SignVariables sv) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // ATM должен работать и с предметом в руке (включая "деньги"),
        // иначе ты отдаёшь событие другим механикам.
        // Жёстко запрещаем стандартное взаимодействие.
        e.setCancelled(true);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        Player p = e.getPlayer();
        Location atmLoc = (e.getClickedBlock() != null)
                ? SignStore.keyLoc(e.getClickedBlock().getLocation())
                : null;
        if (atmLoc == null) return;

        openAtmByPlayer.put(p.getUniqueId(), atmLoc);
        openMainMenu(p, sv);
    }

    // ===== GUI =====

    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof AtmHolder(
                GuiType gui, ActionType action, TargetKind targetKind, String targetValue, int page
        ))) return;

        e.setCancelled(true);

        Location atmLoc = openAtmByPlayer.get(p.getUniqueId());
        if (atmLoc == null) { p.closeInventory(); return; }

        SignVariables sv = store.get(atmLoc);

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= inv.getSize()) return;

        // ===== MAIN =====
        if (gui == GuiType.MAIN) {
            switch (slot) {
                case 10 -> openWithdrawDepositTargetMenu(p, sv, ActionType.WITHDRAW);
                case 12 -> openWithdrawDepositTargetMenu(p, sv, ActionType.DEPOSIT);
                case 14, 16 -> openTransferModeMenu(p, sv);
                case 22 -> showInfo(p, atmLoc, sv);
                case 26 -> p.closeInventory();
            }
            return;
        }

        // ===== WD_TARGET: выбор игрок/страна для withdraw/deposit =====
        if (gui == GuiType.WD_TARGET) {
            if (slot == 11) { // PLAYER
                openAmountMenu(p, sv, action, TargetKind.PLAYER, null);
                return;
            }
            if (slot == 15) { // COUNTRY
                String my = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (my == null || my.isBlank()) { p.sendMessage(ChatColor.RED + "Ты не состоишь в стране."); return; }
                openAmountMenu(p, sv, action, TargetKind.COUNTRY, my);
                return;
            }
            if (slot == 26) { // BACK
                openMainMenu(p, sv);
            }
            return;
        }

        // ===== AMOUNT: пресеты/кастом =====
        if (gui == GuiType.AMOUNT) {
            if (slot == 26) { // BACK
                if (action == ActionType.WITHDRAW || action == ActionType.DEPOSIT) {
                    openWithdrawDepositTargetMenu(p, sv, action);
                } else {
                    openTransferModeMenu(p, sv);
                }
                return;
            }

            if (slot == 22) { // CUSTOM -> chat
                beginAmountChatOnly(p, atmLoc, sv, action, targetKind, targetValue);
                p.closeInventory();
                return;
            }

            Double preset = amountPresetBySlot(slot);
            if (preset == null || preset <= 0) return;

            // выполнить операцию сразу
            runActionWithResolvedTarget(p, atmLoc, action, targetKind, targetValue, preset);
            p.closeInventory();
            return;
        }

        // ===== TRANSFER_MODE: голова/баннер =====
        if (gui == GuiType.TRANSFER_MODE) {
            if (slot == 11) { // HEAD -> pick player
                openPickPlayerMenu(p, sv);
                return;
            }
            if (slot == 15) { // BANNER -> pick country
                openPickCountryMenu(p, sv);
                return;
            }
            if (slot == 26) { // BACK
                openMainMenu(p, sv);
            }
            return;
        }

        // ===== PICK_PLAYER =====
        if (gui == GuiType.PICK_PLAYER) {
            if (slot == 26) { openTransferModeMenu(p, sv); return; }

            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType().isAir()) return;

            String target = null;
            if (it.getItemMeta() instanceof SkullMeta sm) {
                if (sm.getOwningPlayer() != null) target = sm.getOwningPlayer().getName();
            }
            if (target == null || target.isBlank()) return;
            if (target.equalsIgnoreCase(p.getName())) { p.sendMessage(ChatColor.RED + "Нельзя перевести самому себе."); return; }

            openAmountMenu(p, sv, ActionType.TRANSFER_PLAYER, TargetKind.PLAYER, target);
            return;
        }

        // ===== PICK_COUNTRY =====
        if (gui == GuiType.PICK_COUNTRY_LIST) {
            if (slot == 45) { // BACK
                openTransferModeMenu(p, sv);
                return;
            }
            if (slot == 47) { // PREV
                openPickCountryListMenu(p, sv, page - 1);
                return;
            }
            if (slot == 51) { // NEXT
                openPickCountryListMenu(p, sv, page + 1);
                return;
            }
            if (slot == 49) { // CHAT INPUT (страна + сумма)
                beginCountryAndAmountChat(p, atmLoc, sv);
                p.closeInventory();
                return;
            }
            if (slot == 53) { // MY COUNTRY
                String my = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (my == null || my.isBlank()) {
                    p.sendMessage(ChatColor.RED + "Ты не состоишь в стране.");
                    return;
                }
                openAmountMenu(p, sv, ActionType.TRANSFER_COUNTRY, TargetKind.COUNTRY, my);
                return;
            }

            // Клик по стране (0..44)
            if (slot <= 44) {
                ItemStack it = inv.getItem(slot);
                if (it == null || it.getType().isAir()) return;
                ItemMeta meta = it.getItemMeta();
                if (meta == null || !meta.hasDisplayName()) return;

                String picked = ChatColor.stripColor(meta.getDisplayName());
                if (picked.isBlank()) return;

                // проверка существования (на всякий)
                if (!UnityLauncher.getInstance().countryRegistryJdbc.countryExistsCached(picked)) {
                    p.sendMessage(ChatColor.RED + "Страна больше не существует/не найдена.");
                    openPickCountryListMenu(p, sv, page);
                    return;
                }

                openAmountMenu(p, sv, ActionType.TRANSFER_COUNTRY, TargetKind.COUNTRY, picked);
            }
        }

    }

    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof AtmHolder)) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            Inventory top = p.getOpenInventory().getTopInventory();
            InventoryHolder h = top.getHolder();
            if (h instanceof AtmHolder) return;
            openAtmByPlayer.remove(p.getUniqueId());
            // pending не трогаем
        });
    }

    public void onQuit(Player p) {
        if (p == null) return;
        openAtmByPlayer.remove(p.getUniqueId());
        pending.remove(p.getUniqueId());
    }

    private void openMainMenu(Player p, SignVariables sv) {
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";
        Inventory inv = Bukkit.createInventory(new AtmHolder(GuiType.MAIN, null, null, null), 27, GUI_TITLE_PREFIX + " [" + country + "]");

        inv.setItem(10, button(Material.GOLD_INGOT, ChatColor.YELLOW + "Снятие наличных", List.of(
                ChatColor.GRAY + "Счёт игрока или казна страны → наличные",
                ChatColor.DARK_GRAY + "Ввод: источник, сумма"
        )));
        inv.setItem(12, button(Material.EMERALD, ChatColor.GREEN + "Взнос наличных", List.of(
                ChatColor.GRAY + "Наличные → счёт игрока или казна страны",
                ChatColor.DARK_GRAY + "Ввод: получатель, сумма"
        )));
        inv.setItem(14, button(Material.PLAYER_HEAD, ChatColor.AQUA + "Перевод игроку", List.of(
                ChatColor.GRAY + "Счёт игрока → игрок",
                ChatColor.DARK_GRAY + "Ввод: ник, сумма"
        )));
        inv.setItem(16, button(Material.BOOK, ChatColor.AQUA + "Перевод стране", List.of(
                ChatColor.GRAY + "Счёт игрока → казна страны",
                ChatColor.DARK_GRAY + "Ввод: страна, сумма"
        )));
        inv.setItem(22, button(Material.PAPER, ChatColor.YELLOW + "Информация", List.of(
                ChatColor.GRAY + "Комиссия и владелец ATM"
        )));
        inv.setItem(26, button(Material.BARRIER, ChatColor.RED + "Закрыть", List.of()));

        p.openInventory(inv);
    }

    private void openWithdrawDepositTargetMenu(Player p, SignVariables sv, ActionType action) {
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";
        String title = GUI_TITLE_PREFIX + " [" + country + "] → " +
                (action == ActionType.WITHDRAW ? "Снятие" : "Взнос");

        Inventory inv = Bukkit.createInventory(new AtmHolder(GuiType.WD_TARGET, action, null, null), 27, ChatColor.DARK_GREEN + title);

        inv.setItem(11, button(Material.PLAYER_HEAD, ChatColor.YELLOW + "Игрок", List.of(ChatColor.GRAY + "Операция со счётом игрока")));
        inv.setItem(15, button(Material.BOOK, ChatColor.AQUA + "Страна", List.of(ChatColor.GRAY + "Операция с казной страны")));
        inv.setItem(26, button(Material.BARRIER, ChatColor.RED + "Назад", List.of()));

        p.openInventory(inv);
    }

    private void openAmountMenu(Player p, SignVariables sv, ActionType action, TargetKind kind, String targetValue) {
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";
        String t = (action == ActionType.WITHDRAW ? "Снятие" :
                action == ActionType.DEPOSIT ? "Взнос" :
                        action == ActionType.TRANSFER_PLAYER ? "Перевод игроку" :
                                "Перевод стране");

        String who = (kind == TargetKind.PLAYER ? "Игрок" : "Страна");
        if (targetValue != null && !targetValue.isBlank()) who += ": " + targetValue;

        Inventory inv = Bukkit.createInventory(new AtmHolder(GuiType.AMOUNT, action, kind, targetValue), 27,
                ChatColor.DARK_GREEN + "ATM [" + country + "] → " + t);

        // Пресеты сумм (слоты подобраны так, чтобы красиво легло)
        setAmountButton(inv, 10, 50);
        setAmountButton(inv, 11, 100);
        setAmountButton(inv, 12, 250);
        setAmountButton(inv, 13, 500);
        setAmountButton(inv, 14, 1000);
        setAmountButton(inv, 15, 2500);
        setAmountButton(inv, 16, 5000);

        inv.setItem(22, button(Material.OAK_SIGN, ChatColor.YELLOW + "Своя сумма", List.of(
                ChatColor.GRAY + "После выбора напиши число в чат"
        )));
        inv.setItem(26, button(Material.BARRIER, ChatColor.RED + "Назад", List.of(ChatColor.GRAY + who)));

        p.openInventory(inv);
    }

    private void setAmountButton(Inventory inv, int slot, int amount) {
        inv.setItem(slot, button(Material.GOLD_NUGGET, ChatColor.YELLOW + String.valueOf(amount) + " Ⓕ",
                List.of(ChatColor.GRAY + "Нажми, чтобы выбрать")));
    }

    private Double amountPresetBySlot(int slot) {
        return switch (slot) {
            case 10 -> 50.0;
            case 11 -> 100.0;
            case 12 -> 250.0;
            case 13 -> 500.0;
            case 14 -> 1000.0;
            case 15 -> 2500.0;
            case 16 -> 5000.0;
            default -> null;
        };
    }

    private void openTransferModeMenu(Player p, SignVariables sv) {
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";
        Inventory inv = Bukkit.createInventory(new AtmHolder(GuiType.TRANSFER_MODE, null, null, null), 27,
                ChatColor.DARK_GREEN + "ATM [" + country + "] → Перевод");

        inv.setItem(11, button(Material.PLAYER_HEAD, ChatColor.AQUA + "Голова", List.of(
                ChatColor.GRAY + "Перевод игроку",
                ChatColor.DARK_GRAY + "Выбор игрока через головы"
        )));
        inv.setItem(15, button(Material.WHITE_BANNER, ChatColor.AQUA + "Баннер", List.of(
                ChatColor.GRAY + "Перевод стране",
                ChatColor.DARK_GRAY + "Выбор страны через баннер"
        )));
        inv.setItem(26, button(Material.BARRIER, ChatColor.RED + "Назад", List.of()));

        p.openInventory(inv);
    }

    private void openPickPlayerMenu(Player p, SignVariables sv) {
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";
        Inventory inv = Bukkit.createInventory(new AtmHolder(GuiType.PICK_PLAYER, null, null, null), 54,
                ChatColor.DARK_GREEN + "ATM [" + country + "] → Игрок");

        int slot = 0;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            if (pl == null) continue;
            if (pl.getUniqueId().equals(p.getUniqueId())) continue;
            if (slot >= 45) break; // оставим низ под кнопки

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            if (meta != null) {
                meta.setOwningPlayer(pl);
                meta.setDisplayName(ChatColor.YELLOW + pl.getName());
                meta.setLore(List.of(ChatColor.GRAY + "Нажми чтобы выбрать"));
                head.setItemMeta(meta);
            }
            inv.setItem(slot++, head);
        }

        inv.setItem(26, button(Material.BARRIER, ChatColor.RED + "Назад", List.of()));
        p.openInventory(inv);
    }

    private void openPickCountryMenu(Player p, SignVariables sv) {
        openPickCountryListMenu(p, sv, 0);
    }

    private void openPickCountryListMenu(Player p, SignVariables sv, int page) {
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";

        List<String> countries = UnityLauncher.getInstance().countryRegistryJdbc.getAllCountryNames();

        final int pageSize = 45; // 0..44
        int maxPage = (countries.isEmpty() ? 0 : (countries.size() - 1) / pageSize);
        if (page < 0) page = 0;
        if (page > maxPage) page = maxPage;

        Inventory inv = Bukkit.createInventory(
                new AtmHolder(GuiType.PICK_COUNTRY_LIST, null, TargetKind.COUNTRY, null, page),
                54,
                ChatColor.DARK_GREEN + "ATM [" + country + "] → Страна (" + (page + 1) + "/" + (maxPage + 1) + ")"
        );

        int start = page * pageSize;
        int end = Math.min(countries.size(), start + pageSize);

        int slot = 0;
        for (int i = start; i < end; i++) {
            String cName = countries.get(i);

            double money = UnityLauncher.getInstance().countryRegistryJdbc.getCountryMoney(cName);
            double feePct = UnityLauncher.getInstance().countryRegistryJdbc.getCountryTransferFeePctOr(cName, 0.0);

            ItemStack icon = getIcon(cName, money, feePct);

            inv.setItem(slot++, icon);
        }

        // Нижняя панель (45..53)
        inv.setItem(45, button(Material.BARRIER, ChatColor.RED + "Назад", List.of()));
        inv.setItem(47, button(Material.ARROW, ChatColor.YELLOW + "Пред.", List.of(ChatColor.GRAY + "Предыдущая страница")));
        inv.setItem(49, button(Material.OAK_SIGN, ChatColor.AQUA + "Ввести страну в чат", List.of(ChatColor.GRAY + "Формат: <страна> <сумма>")));
        inv.setItem(51, button(Material.ARROW, ChatColor.YELLOW + "След.", List.of(ChatColor.GRAY + "Следующая страница")));

        // Быстрый “моя страна”
        inv.setItem(53, button(Material.WHITE_BANNER, ChatColor.YELLOW + "Моя страна", List.of(ChatColor.GRAY + "Сразу выбрать твою страну")));

        p.openInventory(inv);
    }

    private @NonNull ItemStack getIcon(String cName, double money, double feePct) {
        ItemStack icon = new ItemStack(Material.WHITE_BANNER);
        ItemMeta meta = icon.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + cName);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Казна: " + ChatColor.YELLOW + round2(money) + " Ⓕ");
            if (feePct > 0) lore.add(ChatColor.GRAY + "Комиссия страны: " + ChatColor.RED + round2(feePct) + "%");
            lore.add(ChatColor.DARK_GRAY + "Нажми, чтобы выбрать");
            meta.setLore(lore);
            icon.setItemMeta(meta);
        }
        return icon;
    }

    private static ItemStack button(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * @param targetValue ник игрока или страна (если уже выбрано)
     */
    public record AtmHolder(GuiType gui, ActionType action, TargetKind targetKind, String targetValue, int page)
            implements InventoryHolder {
        private AtmHolder(GuiType gui, ActionType action, TargetKind targetKind, String targetValue) {
            this(gui, action, targetKind, targetValue, 0);
        }

        @Override public @NonNull Inventory getInventory() {
            // Bukkit не использует это для кастомных холдеров, но контракт InventoryHolder требует non-null
            return Bukkit.createInventory(this, 9);
        }
    }

    private enum GuiType {
        MAIN,
        WD_TARGET,      // withdraw/deposit: выбор игрок/страна
        AMOUNT,         // выбор суммы (пресеты/кастом)
        TRANSFER_MODE,  // голова/баннер
        PICK_PLAYER,    // выбор игрока по головам
        PICK_COUNTRY_LIST
    }

    private enum TargetKind {
        PLAYER,
        COUNTRY
    }

    // ===== Chat input =====

    /** Вернёт true, если мы перехватили чат и его надо cancel. */
    public boolean onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        PendingInput pi = pending.get(p.getUniqueId());
        if (pi == null) return false;

        String msg = e.getMessage();

        String raw = msg.trim();
        if (raw.isEmpty()) return true;

        String low = raw.toLowerCase(Locale.ROOT);
        if (low.equals("cancel") || low.equals("отмена") || low.equals("stop")) {
            pending.remove(p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () ->
                    p.sendMessage(ChatColor.GRAY + "Операция ATM отменена.")
            );
            return true;
        }

        // Любой ввод обрабатываем на main thread (Bukkit API), а тяжёлое — async внутри.
        Bukkit.getScheduler().runTask(plugin, () -> handleChatStep(p, pi, raw));
        return true;
    }

    private void beginAmountChatOnly(Player p, Location atmLoc, SignVariables sv, ActionType type, TargetKind kind, String targetValue) {
        pending.put(p.getUniqueId(), new PendingInput(type, atmLoc, sv, kind, targetValue));

        p.sendMessage(ChatColor.YELLOW + "[ATM] Введи сумму числом.");
        p.sendMessage(ChatColor.DARK_GRAY + "Пример: 250");
        p.sendMessage(ChatColor.DARK_GRAY + "Отмена: cancel / отмена");
    }

    private void beginCountryAndAmountChat(Player p, Location atmLoc, SignVariables sv) {
        pending.put(p.getUniqueId(), new PendingInput(ActionType.TRANSFER_COUNTRY, atmLoc, sv, TargetKind.COUNTRY, null));

        p.sendMessage(ChatColor.YELLOW + "[ATM] Перевод стране.");
        p.sendMessage(ChatColor.GRAY + "Напиши: страна и сумма");
        p.sendMessage(ChatColor.DARK_GRAY + "Пример: FarLands 250");
        p.sendMessage(ChatColor.DARK_GRAY + "Отмена: cancel / отмена");
    }

    private void handleChatStep(Player p, PendingInput pi, String raw) {
        if (!pending.containsKey(p.getUniqueId())) return;

        Location atmLoc = pi.atmLoc;

        // 1) Если targetKind уже известен — ждём только сумму
        if (pi.targetKind != null) {
            double amount = parseAmount(raw, p);
            if (amount <= 0) return;

            boolean done = runActionWithResolvedTarget(p, atmLoc, pi.type, pi.targetKind, pi.targetValue, amount);
            if (done) pending.remove(p.getUniqueId());
            return;
        }

        // 2) Fallback: старый формат "две части"
        String[] parts = raw.split("\\s+");
        if (parts.length < 2) {
            p.sendMessage(ChatColor.RED + "Неверный формат. Нужно 2 значения.");
            return;
        }

        boolean done;
        switch (pi.type) {
            case WITHDRAW -> done = handleWithdraw(p, atmLoc, parts);
            case DEPOSIT -> done = handleDeposit(p, atmLoc, parts);
            case TRANSFER_PLAYER -> done = handleTransferPlayer(p, atmLoc, parts);
            case TRANSFER_COUNTRY -> done = handleTransferCountry(p, atmLoc, parts);
            default -> done = true;
        }

        if (done) pending.remove(p.getUniqueId());
    }

    private boolean runActionWithResolvedTarget(Player p, Location atmLoc, ActionType action, TargetKind kind, String targetValue, double amount) {
        if (p == null || atmLoc == null) return true;
        if (amount <= 0) return false;

        switch (action) {
            case WITHDRAW -> {
                if (kind == TargetKind.PLAYER) {
                    double net = netAfterAtmFee(p, atmLoc, amount);
                    p.sendMessage(ChatColor.GRAY + "Обрабатываем снятие...");
                    plugin.moneyManager.withdrawToCashBurnFee(p, amount, net);
                    return true;
                }
                if (kind == TargetKind.COUNTRY) {
                    if (!UnityLauncher.getInstance().countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
                        p.sendMessage(ChatColor.RED + "Снимать из казны может только лидер страны.");
                        return false;
                    }
                    String myCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                    if (myCountry == null || myCountry.isBlank()) {
                        p.sendMessage(ChatColor.RED + "Ты не состоишь в стране.");
                        return false;
                    }
                    p.sendMessage(ChatColor.GRAY + "Проверяем казну страны...");

                    new BukkitRunnable() {
                        @Override public void run() {
                            double countryMoney = UnityLauncher.getInstance().countryRegistryJdbc.getCountryMoney(myCountry);
                            if (countryMoney < amount) {
                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.RED + "В казне недостаточно средств. Доступно: "
                                            + ChatColor.YELLOW + round2(countryMoney) + ChatColor.RED + " Ⓕ.");
                                }}.runTask(UnityLauncher.getInstance());
                                return;
                            }

                            UnityLauncher.getInstance().countryRegistryJdbc.addCountryMoney(myCountry, -amount);

                            new BukkitRunnable(){ @Override public void run(){
                                double net = netAfterAtmFee(p, atmLoc, amount);
                                plugin.moneyManager.giveCash(p, net);
                                p.sendMessage(ChatColor.GREEN + "Снято из казны: " + ChatColor.YELLOW + round2(net)
                                        + ChatColor.GREEN + " Ⓕ (" + ChatColor.AQUA + myCountry + ChatColor.GREEN + ")");
                            }}.runTask(UnityLauncher.getInstance());
                        }
                    }.runTaskAsynchronously(UnityLauncher.getInstance());

                    return true;
                }
            }

            case DEPOSIT -> {
                AtmFeesUpgrade atmFees = atmFeesUpgradeOrNull();
                double net = amount;
                if (atmFees != null) {
                    double rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);
                    net = amount - (amount * rate);
                    if (net < 0) net = 0;
                }

                if (kind == TargetKind.PLAYER) {
                    p.sendMessage(ChatColor.GRAY + "Вносим наличку на счёт...");
                    return plugin.moneyManager.depositCashToPlayerBurnFee(p, amount, net);
                }
                if (kind == TargetKind.COUNTRY) {
                    p.sendMessage(ChatColor.GRAY + "Вносим наличку в казну...");
                    return plugin.moneyManager.depositCashToCountryBurnFee(p, amount, net);
                }
                return true;
            }

            case TRANSFER_PLAYER -> {
                if (targetValue == null || targetValue.isBlank()) {
                    p.sendMessage(ChatColor.RED + "Не выбран игрок.");
                    return false;
                }
                return handleTransferPlayerResolved(p, atmLoc, targetValue, amount);
            }

            case TRANSFER_COUNTRY -> {
                if (targetValue == null || targetValue.isBlank()) {
                    p.sendMessage(ChatColor.RED + "Не выбрана страна.");
                    return false;
                }
                return handleTransferCountryResolved(p, atmLoc, targetValue, amount);
            }
        }

        return true;
    }

    // ===== Operations (порт из Legacy) =====

    private boolean handleWithdraw(Player p, Location atmLoc, String[] parts) {
        String src = parts[0].trim().toLowerCase(Locale.ROOT);
        double amount = parseAmount(parts[1], p);
        if (amount <= 0) return false;

        if (isPlayerWord(src)) {
            double net = netAfterAtmFee(p, atmLoc, amount);
            p.sendMessage(ChatColor.GRAY + "Обрабатываем снятие...");
            plugin.moneyManager.withdrawToCashBurnFee(p, amount, net);
            return true;
        }

        if (isCountryWord(src)) {
            if (!UnityLauncher.getInstance().countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
                p.sendMessage(ChatColor.RED + "Снимать из казны может только лидер страны.");
                return false;
            }

            String myCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (myCountry == null || myCountry.isBlank()) {
                p.sendMessage(ChatColor.RED + "Ты не состоишь в стране.");
                return false;
            }

            p.sendMessage(ChatColor.GRAY + "Проверяем казну страны...");

            new BukkitRunnable() {
                @Override public void run() {
                    double countryMoney = UnityLauncher.getInstance().countryRegistryJdbc.getCountryMoney(myCountry);
                    if (countryMoney < amount) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "В казне недостаточно средств. Доступно: "
                                    + ChatColor.YELLOW + round2(countryMoney) + ChatColor.RED + " Ⓕ.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }

                    // sender pays full
                    UnityLauncher.getInstance().countryRegistryJdbc.addCountryMoney(myCountry, -amount);

                    new BukkitRunnable(){ @Override public void run(){
                        double net = netAfterAtmFee(p, atmLoc, amount);
                        plugin.moneyManager.giveCash(p, net);
                        p.sendMessage(ChatColor.GREEN + "Снято из казны: " + ChatColor.YELLOW + round2(net)
                                + ChatColor.GREEN + " Ⓕ (" + ChatColor.AQUA + myCountry + ChatColor.GREEN + ")");
                    }}.runTask(UnityLauncher.getInstance());
                }
            }.runTaskAsynchronously(UnityLauncher.getInstance());
            return true;
        }

        p.sendMessage(ChatColor.RED + "Источник: 'игрок' или 'страна'.");
        return false;
    }

    private boolean handleDeposit(Player p, Location atmLoc, String[] parts) {
        String dst = parts[0].trim().toLowerCase(Locale.ROOT);
        double amount = parseAmount(parts[1], p);
        if (amount <= 0) return false;

        AtmFeesUpgrade atmFees = atmFeesUpgradeOrNull();

        if (isCountryWord(dst)) {
            p.sendMessage(ChatColor.GRAY + "Вносим наличку в казну...");

            double net = amount;
            if (atmFees != null) {
                double rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);
                net = amount - (amount * rate);
                if (net < 0) net = 0;
            }

            // gross=amount снимаем наличкой, net в казну, fee сгорает
            return plugin.moneyManager.depositCashToCountryBurnFee(p, amount, net);
        }

        if (isPlayerWord(dst)) {
            p.sendMessage(ChatColor.GRAY + "Вносим наличку на счёт...");

            double net = amount;
            if (atmFees != null) {
                double rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);
                net = amount - (amount * rate);
                if (net < 0) net = 0;
            }

            // gross=amount снимаем наличкой, net на счёт, fee сгорает
            return plugin.moneyManager.depositCashToPlayerBurnFee(p, amount, net);
        }

        p.sendMessage(ChatColor.RED + "Получатель: 'игрок' или 'страна'.");
        return true;
    }

    private boolean handleTransferPlayer(Player p, Location atmLoc, String[] parts) {
        String targetName = parts[0].trim();
        double amount = parseAmount(parts[1], p);
        if (amount <= 0) return false;

        if (targetName.isEmpty()) { p.sendMessage(ChatColor.RED + "Укажи ник."); return false; }
        if (targetName.equalsIgnoreCase(p.getName())) { p.sendMessage(ChatColor.RED + "Нельзя перевести самому себе."); return false; }

        AtmFeesUpgrade atmFees = atmFeesUpgradeOrNull();

        p.sendMessage(ChatColor.GRAY + "Проверяем данные и выполняем перевод...");

        new BukkitRunnable() {
            @Override public void run() {
                List<String> keys = List.of("money");
                Map<String, Object> senderMap = UnityCommands.getInstance()
                        .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), keys);
                Double senderMoney = senderMap.get("money") instanceof Number ? ((Number) senderMap.get("money")).doubleValue() : null;

                UnityCommands.getInstance().getPlayerInfo(targetName, targetData -> {
                    if (senderMoney == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Не удалось получить твой баланс.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }
                    if (targetData == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Игрок '" + targetName + "' не найден.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }
                    if (senderMoney < amount) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Недостаточно средств. Доступно: " + ChatColor.YELLOW + senderMoney + ChatColor.RED + " Ⓕ.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }

                    new BukkitRunnable(){ @Override public void run(){
                        UnityCommands uc = UnityCommands.getInstance();

                        double rate = 0.0;
                        if (atmFees != null) rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);

                        double fee = amount * rate;
                        double net = Math.max(0.0, amount - fee);

                        // sender pays full
                        Map<String, Object> updSender = new HashMap<>();
                        updSender.put("money", round2(senderMoney - amount));
                        uc.mergeAndUpdatePlayerData(p.getName(), "GeneralData", updSender);

                        // receiver gets less
                        Map<String, Object> updTarget = new HashMap<>();
                        updTarget.put("money", round2(targetData.money + net));
                        uc.mergeAndUpdatePlayerData(targetName, "GeneralData", updTarget);

                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.GREEN + "Перевод выполнен: " + ChatColor.YELLOW + round2(amount) + " Ⓕ"
                                    + ChatColor.GREEN + " → " + ChatColor.RESET + targetName
                                    + (fee > 0 ? (ChatColor.GRAY + " (получатель получил " + ChatColor.YELLOW + round2(net) + " Ⓕ"
                                    + ChatColor.GRAY + ", комиссия " + ChatColor.RED + round2(fee) + " Ⓕ" + ChatColor.GRAY + ")") : ""));
                        }}.runTask(UnityLauncher.getInstance());

                    }}.runTaskAsynchronously(UnityLauncher.getInstance());
                });
            }
        }.runTaskAsynchronously(UnityLauncher.getInstance());
        return true;
    }

    private boolean handleTransferCountry(Player p, Location atmLoc, String[] parts) {
        String targetCountry = parts[0].trim();
        double amount = parseAmount(parts[1], p);
        if (amount <= 0) return false;

        if (targetCountry.isEmpty()) { p.sendMessage(ChatColor.RED + "Укажи страну."); return false; }
        if (!UnityLauncher.getInstance().getCountryRegistryJdbc().countryExistsCached(targetCountry)) {
            p.sendMessage(ChatColor.RED + "Страна '" + targetCountry + "' не найдена.");
            return false;
        }

        AtmFeesUpgrade atmFees = atmFeesUpgradeOrNull();

        p.sendMessage(ChatColor.GRAY + "Выполняем перевод стране...");

        double rate = 0.0;
        if (atmFees != null) rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);

        final double fee = amount * rate;
        final double net = Math.max(0.0, amount - fee);

        // sender pays full
        plugin.moneyManager.tryWithdraw(p.getName(), amount, ok -> {
            if (!ok) {
                new BukkitRunnable() { @Override public void run() {
                    p.sendMessage(ChatColor.RED + "Недостаточно средств на счёте для перевода.");
                }}.runTask(UnityLauncher.getInstance());
                return;
            }

            new BukkitRunnable() {
                @Override public void run() {
                    UnityLauncher.getInstance().countryRegistryJdbc.addCountryMoney(targetCountry, net);

                    new BukkitRunnable() { @Override public void run() {
                        p.sendMessage(
                                ChatColor.GREEN + "Перевод выполнен: " +
                                        ChatColor.YELLOW + round2(amount) + " Ⓕ" +
                                        ChatColor.GREEN + " → " + ChatColor.AQUA + targetCountry +
                                        (fee > 0
                                                ? ChatColor.GRAY + " (зачислено " + ChatColor.YELLOW + round2(net) +
                                                ChatColor.GRAY + " Ⓕ, комиссия " + ChatColor.RED + round2(fee) + " Ⓕ)"
                                                : "")
                        );
                    }}.runTask(UnityLauncher.getInstance());
                }
            }.runTaskAsynchronously(UnityLauncher.getInstance());
        });
        return true;
    }

    private boolean handleTransferPlayerResolved(Player p, Location atmLoc, String targetName, double amount) {
        if (targetName.equalsIgnoreCase(p.getName())) { p.sendMessage(ChatColor.RED + "Нельзя перевести самому себе."); return false; }

        AtmFeesUpgrade atmFees = atmFeesUpgradeOrNull();
        p.sendMessage(ChatColor.GRAY + "Проверяем данные и выполняем перевод...");

        new BukkitRunnable() {
            @Override public void run() {
                List<String> keys = List.of("money");
                Map<String, Object> senderMap = UnityCommands.getInstance()
                        .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), keys);
                Double senderMoney = senderMap.get("money") instanceof Number ? ((Number) senderMap.get("money")).doubleValue() : null;

                UnityCommands.getInstance().getPlayerInfo(targetName, targetData -> {
                    if (senderMoney == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Не удалось получить твой баланс.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }
                    if (targetData == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Игрок '" + targetName + "' не найден.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }
                    if (senderMoney < amount) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Недостаточно средств. Доступно: " + ChatColor.YELLOW + senderMoney + ChatColor.RED + " Ⓕ.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }

                    new BukkitRunnable(){ @Override public void run(){
                        UnityCommands uc = UnityCommands.getInstance();

                        double rate = 0.0;
                        if (atmFees != null) rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);

                        double fee = amount * rate;
                        double net = Math.max(0.0, amount - fee);

                        Map<String, Object> updSender = new HashMap<>();
                        updSender.put("money", round2(senderMoney - amount));
                        uc.mergeAndUpdatePlayerData(p.getName(), "GeneralData", updSender);

                        Map<String, Object> updTarget = new HashMap<>();
                        updTarget.put("money", round2(targetData.money + net));
                        uc.mergeAndUpdatePlayerData(targetName, "GeneralData", updTarget);

                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.GREEN + "Перевод выполнен: " + ChatColor.YELLOW + round2(amount) + " Ⓕ"
                                    + ChatColor.GREEN + " → " + ChatColor.RESET + targetName
                                    + (fee > 0 ? (ChatColor.GRAY + " (получатель получил " + ChatColor.YELLOW + round2(net) + " Ⓕ"
                                    + ChatColor.GRAY + ", комиссия " + ChatColor.RED + round2(fee) + " Ⓕ" + ChatColor.GRAY + ")") : ""));
                        }}.runTask(UnityLauncher.getInstance());

                    }}.runTaskAsynchronously(UnityLauncher.getInstance());
                });
            }
        }.runTaskAsynchronously(UnityLauncher.getInstance());

        return true;
    }

    private boolean handleTransferCountryResolved(Player p, Location atmLoc, String targetCountry, double amount) {
        if (!UnityLauncher.getInstance().getCountryRegistryJdbc().countryExistsCached(targetCountry)) {
            p.sendMessage(ChatColor.RED + "Страна '" + targetCountry + "' не найдена.");
            return false;
        }

        AtmFeesUpgrade atmFees = atmFeesUpgradeOrNull();
        p.sendMessage(ChatColor.GRAY + "Выполняем перевод стране...");

        double rate = 0.0;
        if (atmFees != null) rate = atmFees.calculateAtmFee(p.getName(), atmLoc, amount);

        final double fee = amount * rate;
        final double net = Math.max(0.0, amount - fee);

        plugin.moneyManager.tryWithdraw(p.getName(), amount, ok -> {
            if (!ok) {
                new BukkitRunnable() { @Override public void run() {
                    p.sendMessage(ChatColor.RED + "Недостаточно средств на счёте для перевода.");
                }}.runTask(UnityLauncher.getInstance());
                return;
            }

            new BukkitRunnable() {
                @Override public void run() {
                    UnityLauncher.getInstance().countryRegistryJdbc.addCountryMoney(targetCountry, net);

                    new BukkitRunnable() { @Override public void run() {
                        p.sendMessage(
                                ChatColor.GREEN + "Перевод выполнен: " +
                                        ChatColor.YELLOW + round2(amount) + " Ⓕ" +
                                        ChatColor.GREEN + " → " + ChatColor.AQUA + targetCountry +
                                        (fee > 0
                                                ? ChatColor.GRAY + " (зачислено " + ChatColor.YELLOW + round2(net) +
                                                ChatColor.GRAY + " Ⓕ, комиссия " + ChatColor.RED + round2(fee) + " Ⓕ)"
                                                : "")
                        );
                    }}.runTask(UnityLauncher.getInstance());
                }
            }.runTaskAsynchronously(UnityLauncher.getInstance());
        });

        return true;
    }

    private void showInfo(Player p, Location atmLoc, SignVariables sv) {
        double rate = 0.0;
        AtmFeesUpgrade u = atmFeesUpgradeOrNull();
        if (u != null) {
            try { rate = u.calculateAtmFee(p.getName(), atmLoc, 100.0); } catch (Throwable ignored) {}
        }

        String owner = (sv != null && sv.getOwnerName() != null) ? sv.getOwnerName() : "?";
        String country = (sv != null && sv.getOwnerCountry() != null) ? sv.getOwnerCountry() : "?";

        p.sendMessage(ChatColor.YELLOW + "=======[ ATM ]=======\n" +
                ChatColor.GREEN + "Принадлежит: " + ChatColor.RESET + country + "\n" +
                ChatColor.GREEN + "Установлен: " + ChatColor.RESET + owner + "\n" +
                ChatColor.GREEN + "Комиссия ATM сейчас: " + ChatColor.YELLOW + String.format(Locale.ROOT, "%.1f", rate * 100.0) + "%" + "\n" +
                ChatColor.DARK_GRAY + "(модель: получатель получает меньше)"
        );
    }

    // ===== utils =====

    private double parseAmount(String s, Player p) {
        try {
            double v = Double.parseDouble(s.replace(',', '.'));
            if (v <= 0) {
                p.sendMessage(ChatColor.RED + "Сумма должна быть > 0.");
                return -1;
            }
            return v;
        } catch (NumberFormatException ex) {
            p.sendMessage(ChatColor.RED + "Введите корректную сумму.");
            return -1;
        }
    }

    private boolean isPlayerWord(String s) {
        return s.equals("игрок") || s.equals("я") || s.equals("мой") || s.equals("мойсчёт")
                || s.equals("me") || s.equals("player") || s.equals("игр") || s.equals("иг");
    }

    private boolean isCountryWord(String s) {
        return s.equals("страна") || s.equals("государство") || s.equals("country")
                || s.equals("стр") || s.equals("ст") || s.equals("с");
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private AtmFeesUpgrade atmFeesUpgradeOrNull() {
        try {
            var um = plugin.getUpgradesManager();
            if (um == null) return null;
            return um.getEnabled(AtmFeesUpgrade.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Receiver gets less: возвращает "чистую" сумму (amount - fee). */
    private double netAfterAtmFee(Player actor, Location atmLoc, double amount) {
        if (actor == null || atmLoc == null || amount <= 0.0) return amount;
        AtmFeesUpgrade u = atmFeesUpgradeOrNull();
        if (u == null) return amount;
        return u.applyAtmFee(actor, atmLoc, amount);
    }

    private enum ActionType {
        WITHDRAW,
        DEPOSIT,
        TRANSFER_PLAYER,
        TRANSFER_COUNTRY
    }

    private record PendingInput(ActionType type, Location atmLoc, SignVariables sv, TargetKind targetKind, String targetValue) {
    }
}