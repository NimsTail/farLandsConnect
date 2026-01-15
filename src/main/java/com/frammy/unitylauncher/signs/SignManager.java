package com.frammy.unitylauncher.signs;

import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.features.atm.AtmController;
import com.frammy.unitylauncher.signs.features.shop.ShopController;
import com.frammy.unitylauncher.signs.features.shop.ShopListUpdater;
import com.frammy.unitylauncher.signs.features.trash.TrashController;
import com.frammy.unitylauncher.signs.markers.MarkerService;
import com.frammy.unitylauncher.signs.persistence.SignYamlRepository;
import com.frammy.unitylauncher.signs.render.SignRenderer;
import com.frammy.unitylauncher.signs.render.SignScrollService;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.*;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class SignManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final BlueMapIntegration blueMapIntegration;
    private final SignStore store;
    private final AtmController atm;
    private final SignScrollService scroll;
    private final SignYamlRepository repo;
    private final TrashController trash;
    private final ShopListUpdater shopLists;
    private final ShopController shop;

    public SignManager(UnityLauncher plugin, File dataFolder, ZoneManager zoneManager, BlueMapIntegration blueMapIntegration) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.blueMapIntegration = blueMapIntegration;
        this.store = new SignStore();
        SignRenderer renderer = new SignRenderer();
        this.scroll = new SignScrollService(plugin, store);
        this.atm = new AtmController(plugin, blueMapIntegration, store, scroll);

        this.repo = new SignYamlRepository(plugin, dataFolder, SignManager::playerCountryCanonical);
        this.trash = new TrashController(plugin);

        MarkerService markers = new MarkerService(plugin, zoneManager);

        this.shopLists = new ShopListUpdater(plugin, zoneManager, store, renderer,
                (loc) -> shopKey(zoneManager, loc)
        );

        this.shop = new com.frammy.unitylauncher.signs.features.shop.ShopController(
                plugin, zoneManager, blueMapIntegration, markers, store, renderer, scroll, shopLists, this::indexShopSign
        );

    }

    private static String shopKey(ZoneManager zm, Location loc) {
        if (zm == null || loc == null) return null;
        var z = zm.getShopZoneAt(loc);
        if (z == null) return null;
        var w = loc.getWorld();
        if (w == null) return null;
        String name = z.getName();
        if (name == null || name.isBlank()) return null;
        return w.getUID() + ":" + name;
    }

    private final Map<String, Set<Location>> shopToListSigns = new HashMap<>();
    private final Map<String, Set<Location>> shopToSourceSigns = new HashMap<>();

    private void indexShopSign(Location signLoc, SignVariables sv) {
        String key = shopKey(zoneManager, signLoc);
        if (key == null || sv == null) return;

        if (sv.getSignCategory() == SignCategory.SHOP_LIST) {
            shopToListSigns.computeIfAbsent(key, k -> new HashSet<>()).add(signLoc);
        } else if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            shopToSourceSigns.computeIfAbsent(key, k -> new HashSet<>()).add(signLoc);
        }
    }

    private void unindexShopSign(Location signLoc, SignVariables sv) {
        String key = shopKey(zoneManager, signLoc);
        if (key == null || sv == null) return;

        if (sv.getSignCategory() == SignCategory.SHOP_LIST) {
            Set<Location> s = shopToListSigns.get(key);
            if (s != null) { s.remove(signLoc); if (s.isEmpty()) shopToListSigns.remove(key); }
        } else if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            Set<Location> s = shopToSourceSigns.get(key);
            if (s != null) { s.remove(signLoc); if (s.isEmpty()) shopToSourceSigns.remove(key); }
        }
    }

    // ======= helpers (оставляем тут, чтобы не плодить util пока) =======

    private static String playerCountryCanonical(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;

        String c = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(playerName);
        if (c == null || c.isBlank()) return null;

        return c.trim().toLowerCase(Locale.ROOT);
    }

    private static String signCountryCanonical(SignVariables sv) {
        if (sv == null) return null;

        String c = sv.getOwnerCountry();
        if (c != null && !c.isBlank()) return c.trim().toLowerCase(Locale.ROOT);

        String owner = sv.getOwnerName();
        return playerCountryCanonical(owner);
    }

    private static final String ATM_PERM_BASE = "unity.atm";
    private static final String TRASH_PERM_BASE = "unity.trash";
    private static final int SPECIAL_SIGNS_MAX_LEVEL_CAP = 200;

    private int allowedSpecialSignsByPerm(String countryCanonical, String permBase) {
        if (countryCanonical == null || countryCanonical.isBlank()) return 0;
        return countryMaxLevel(countryCanonical, permBase, SPECIAL_SIGNS_MAX_LEVEL_CAP);
    }

    private boolean requireCountryLeader(Player p) {
        if (p == null) return false;
        if (UnityLauncher.getInstance().countryRegistryJdbc.isCountryLeaderCached(p.getName())) return true;
        p.sendMessage(ChatColor.RED + "Создавать специальные таблички может только правитель страны.");
        return false;
    }

    private void stopAllScrollingTasks() {
        for (var t : scroll.scrollingTasks().values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        scroll.scrollingTasks().clear();

        for (var t : scroll.resetTasks().values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        scroll.resetTasks().clear();

        for (var m : scroll.activeScrolls().values()) {
            for (var t : m.values()) {
                try { t.cancel(); } catch (Throwable ignored) {}
            }
        }
        scroll.activeScrolls().clear();
    }

    private void restoreRuntimeStateAfterLoad() {
        // 1) восстановить скролл
        for (var e : store.signs().entrySet()) {
            Location loc = e.getKey();
            SignVariables sv = e.getValue();
            if (loc == null || sv == null) continue;
            if (loc.getWorld() == null) continue;

            BlockState st = loc.getBlock().getState();
            List<String> stWorld = null;
            if (st instanceof Sign s) {
                stWorld = Arrays.asList(s.getLines());
            }

            List<String> base = (stWorld != null && !stWorld.isEmpty()) ? stWorld : sv.getSignText();
            List<Integer> sl = sv.getScrollLines();
            if (sl != null && !sl.isEmpty() && base != null && !base.isEmpty()) {
                Map<Integer, String> m = new HashMap<>();
                for (int idx : sl) {
                    if (idx >= 0 && idx < base.size()) m.put(idx, base.get(idx));
                }
                if (!m.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> scroll.makeSignScrollingLines(loc, m, 6, 13));
            }

            String markerId = sv.getMarkerID();
            if (markerId != null && !markerId.isBlank()) {
                if (sv.getSignCategory() == SignCategory.ATM) {
                    blueMapIntegration.addBlueMapMarker(markerId, loc, "services", "Сервисы", "point_atm", null, null);
                } else if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
                    blueMapIntegration.addBlueMapMarker(markerId, loc, "services", "Сервисы", "point_shop", null, null);
                }
            }
        }

        // 3) на всякий случай перестроить листы (ты это уже делаешь)
//        try { shopLists.rebuildAllListsLater(); } catch (Throwable ignored) {}
    }

    /** Массово перестроить все SHOP_LIST таблички (планировщик внутри ShopListUpdater). */
    public void rebuildAllShopListsLater() {
        try {
            shopLists.rebuildAllListsLater();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Signs] rebuildAllShopListsLater failed: " + t.getMessage());
        }
    }

    // ======= persistence API =======

    public void saveSignData() {
        repo.save(store, false);
    }

    private void rebuildShopIndexAndListsWhenZonesReady() {
        final int[] tries = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            tries[0]++;

            boolean zonesReadyForShop = false;

            // Проверяем, что хотя бы одна SHOP табличка уже видит свою зону
            for (var e : store.signs().entrySet()) {
                var sv = e.getValue();
                if (sv == null) continue;
                if (sv.getSignCategory() != SignCategory.SHOP_SOURCE
                        && sv.getSignCategory() != SignCategory.SHOP_LIST) continue;

                Location loc = e.getKey();
                if (loc == null || loc.getWorld() == null) continue;

                if (zoneManager.getShopZoneAt(loc) != null) {
                    zonesReadyForShop = true;
                    break;
                }
            }

            if (zonesReadyForShop) {
                // 1) пересобираем shopTo* индексы НОРМАЛЬНО, когда shopKey уже не null
                shopToListSigns.clear();
                shopToSourceSigns.clear();
                for (var e : store.signs().entrySet()) {
                    indexShopSign(e.getKey(), e.getValue());
                }

                // 2) чинить source state/text (см. фикс 2 ниже)
                repairAllShopSourcesFromWorld();

                // 3) пересобрать листы магазина
                try { shopLists.rebuildAllListsLater(); }
                catch (Throwable t) { plugin.getLogger().warning("[Signs] rebuildAllListsLater failed: " + t.getMessage()); }

                task.cancel();
                return;
            }

            if (tries[0] >= 40) { // 40*10 тиков = 20 секунд
                plugin.getLogger().warning("[Signs] SHOP zones not ready after 20s. Shop lists may stay empty.");
                task.cancel();
            }
        }, 10L, 10L);
    }

    public void loadSignData() {
        repo.load(store, false, null); // <-- ВАЖНО: не rebuildAllListsLater тут

        // runtime бинды контейнер->source восстановим сразу (не зависит от зон)
        store.rebuildShopContainerBinds();

        // индексы shopTo* НЕ строим тут (зоны могут быть не готовы)
        shopToListSigns.clear();
        shopToSourceSigns.clear();
    }

    public SignStore store() { return store; }

    public TrashController getTrashController() {
        return trash;
    }

    public ShopController getShopController() {
        return shop;
    }

    /** Перестроить ownerName у всех табличек на основе текущих зон (мягко, батчами). */
    public void recalcOwnershipLater(int signsPerTick) {
        // делегируем в ZoneManager, потому что геометрия там
        zoneManager.scheduleSignOwnershipRecalc(this, signsPerTick);
    }

    /** Сообщить, что имя SHOP-зоны изменилось — обновить все связанные таблички магазина. */
    public void onShopZoneRenamed() {
        shopLists.rebuildAllListsLater();
    }

    private void rebuildShopListsWhenZonesReady() {
        // ждём максимум ~10 секунд (200 тиков), проверка каждые 10 тиков
        final int[] tries = {0};

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            tries[0]++;

            boolean ready = false;

            // если есть хотя бы одна SHOP табличка и её зона уже определяется — считаем, что зоны готовы
            for (var e : store.signs().entrySet()) {
                var sv = e.getValue();
                if (sv == null) continue;
                if (sv.getSignCategory() != SignCategory.SHOP_SOURCE && sv.getSignCategory() != SignCategory.SHOP_LIST) continue;

                Location loc = e.getKey();
                if (loc == null || loc.getWorld() == null) continue;

                if (zoneManager.getShopZoneAt(loc) != null) {
                    ready = true;
                    break;
                }
            }

            if (ready) {
                try { shopLists.rebuildAllListsLater(); }
                catch (Throwable t) { plugin.getLogger().warning("[Signs] rebuildAllListsLater failed: " + t.getMessage()); }
                task.cancel();
                return;
            }

            if (tries[0] >= 20) { // 20 * 10 тиков = 200 тиков = 10 секунд
                plugin.getLogger().warning("[Signs] SHOP zones not ready after 10s. Rebuild skipped (will stay empty until next update).");
                task.cancel();
            }
        }, 10L, 10L);
    }

    // ======= events =======

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        // 1) защита контейнеров магазина
        BlockState st = b.getState();
        if (st instanceof Container c) {
            Location chestLoc = SignStore.keyLoc(c.getLocation());
            Location src = store.sourceSignByContainer(chestLoc);

            // если это double chest, а ломают “вторую половину” — тоже проверим соседей
            if (src == null && b.getType() == Material.CHEST) {
                // проверим 4 стороны на случай второй половины (дёшево)
                for (BlockFace face : new BlockFace[]{
                        BlockFace.NORTH,
                        BlockFace.SOUTH,
                        BlockFace.WEST,
                        BlockFace.EAST
                }) {
                    Block nb = b.getRelative(face);
                    if (nb.getType() != Material.CHEST) continue;
                    BlockState nst = nb.getState();
                    if (!(nst instanceof Container nc)) continue;
                    Location nLoc = SignStore.keyLoc(nc.getLocation());
                    src = store.sourceSignByContainer(nLoc);
                    if (src != null) break;
                }
            }

            if (src != null && !zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), src)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Этот сундук связан с магазином. Ломать может только владелец SHOP-зоны.");
                return;
            }
        }

        if (!(e.getBlock().getState() instanceof Sign)) return;

        Location loc = SignStore.keyLoc(e.getBlock().getLocation());
        SignVariables sv = store.get(loc);
        if (sv == null) return;

        // остановить скролл
        scroll.stopScrollingTask(loc);
        if (sv.getScrollLines() != null) {
            for (int idx : sv.getScrollLines()) {
                scroll.stopHorizontalScroll(loc, idx);
            }
        }

        // отбиндить ВСЕ контейнеры, если это source (важно для double chest)
        if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            store.unbindAllForSourceSign(loc);
        }

        unindexShopSign(loc, sv);

        // убрать запись
        store.remove(loc);

        // пересобрать списки магазина после удаления источника
//        try { shopLists.rebuildAllListsLater(); } catch (Throwable ignored) {}

        // убрать маркер
        if (sv.getMarkerID() != null && !sv.getMarkerID().isBlank()) {
            try {
                World w = e.getBlock().getWorld();
                blueMapIntegration.removeBlueMapMarker(sv.getMarkerID(), w.getName(), "services");
            } catch (Throwable ignored) {}
        }

    }

    private void repairAllShopSourcesFromWorld() {
        for (var e : store.signs().entrySet()) {
            Location loc = e.getKey();
            SignVariables sv = e.getValue();
            if (loc == null || sv == null) continue;
            if (sv.getSignCategory() != SignCategory.SHOP_SOURCE) continue;
            if (loc.getWorld() == null) continue;

            try {
                var st = loc.getBlock().getState();
                if (!(st instanceof Sign sign)) continue;

                String l2 = sign.getLine(2);
                String l3 = sign.getLine(3);

                Integer amount = parseIntLoose(l2);
                Double price = parseDoubleLoose(l3);

                // Если на табличке реально стоят цифры — считаем, что товар настроен
                if (amount != null && amount > 0 && price != null && price > 0) {
                    // Приводим к единому виду (как ты делаешь при подтверждении)
                    List<String> t = safe4(sv.getSignText());
                    String line2 = "Кол-во: " + ChatColor.YELLOW + amount;
                    String line3 = "Цена: " + ChatColor.GREEN + price;

                    sv.setSignText(Arrays.asList(t.get(0), t.get(1), line2, line3));
                    sv.setSignState(SignState.SHOP_DEFINED);

                    // опционально: поправим табличку в мире, если там “грязный” формат
                    sign.setLine(2, line2);
                    sign.setLine(3, line3);
                    sign.update();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static List<String> safe4(List<String> in) {
        ArrayList<String> out = new ArrayList<>(4);
        if (in != null) out.addAll(in);
        while (out.size() < 4) out.add("");
        if (out.size() > 4) out.subList(4, out.size()).clear();
        return out;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShopContainerAutoMove(InventoryMoveItemEvent e) {
        if (isShopLinkedInventory(e.getSource()) || isShopLinkedInventory(e.getDestination())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSignChange(SignChangeEvent e) {
        if (!(e.getBlock().getState() instanceof Sign sign)) return;

        Player p = e.getPlayer();
        Location loc = SignStore.keyLoc(sign.getLocation());

        // ===== EDIT existing SHOP_SOURCE (через openSign) =====
        SignVariables existing = store.get(loc);
        if (existing != null && existing.getSignCategory() == SignCategory.SHOP_SOURCE) {

            // только владелец SHOP-зоны может редактировать
            if (!zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), loc)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Редактировать эту табличку может только владелец.");
                return;
            }

            // (опционально) синхронизируем ownerName, чтобы оно не устаревало
            if (existing.getOwnerName() == null || !existing.getOwnerName().equalsIgnoreCase(p.getName())) {
                existing.setOwnerName(p.getName());
            }

            // фиксируем 0-1 строки (чтобы не ломали заголовок и координаты)
            List<String> prev = existing.getSignText() != null ? existing.getSignText() : List.of("", "", "", "");
            String l0 = !prev.isEmpty() ? prev.get(0) : "";
            String l1 = prev.size() > 1 ? prev.get(1) : "";

            e.setLine(0, l0);
            e.setLine(1, l1);

            // берём новые 2-3 строки из события (игрок их изменил)
            String l2 = safeLine(e, 2);
            String l3 = safeLine(e, 3);

            existing.setSignText(Arrays.asList(l0, l1, l2, l3));

            // если меняли цену/кол-во — отправляем в режим "нужно подтвердить"
            existing.setSignState(SignState.SHOP_UNDEFINED);

            // подсказка
            Bukkit.getScheduler().runTask(plugin, () ->
                    p.sendMessage(ChatColor.GRAY + "Изменения сохранены. Теперь Shift+ЛКМ по табличке для подтверждения.")
            );

            return; // важно: НЕ идти дальше в логику создания табличек
        }

        // hanging запреты (как в легаси)
        boolean isHanging = e.getBlock().getType().toString().contains("HANGING");

        String line0raw = e.getLine(0);
        if (line0raw == null) line0raw = "";

        String line1raw = e.getLine(1);

        store.originalSignTexts().putIfAbsent(loc, new String[]{
                safeLine(e, 0),
                safeLine(e, 1),
                safeLine(e, 2),
                safeLine(e, 3)
        });

        // ===== SHOP =====
        if (line0raw.equalsIgnoreCase("shop") || line0raw.equalsIgnoreCase("магазин")) {

            // hanging запреты оставляем тут (как у тебя было)
            if (isHanging) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать как магазинные!");
                return;
            }

            shop.onSignCreateShop(e, SignManager::playerCountryCanonical);
            return;
        }

        // ===== ATM =====
        if (line0raw.equalsIgnoreCase("ATM")) {

            if (isHanging) {
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве банковского автомата!");
                e.setCancelled(true);
                return;
            }

            String pc = playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "ATM можно ставить только будучи в составе страны.");
                return;
            }

            if (!requireCountryLeader(p)) {
                e.setCancelled(true);
                return;
            }

            int have = store.countByCountry(SignCategory.ATM, pc, SignManager::signCountryCanonical);
            int allowed = allowedSpecialSignsByPerm(pc, ATM_PERM_BASE);

            atm.onSignCreateATM(e, pc, have, allowed);
            return;
        }

        // ===== TRASH =====
        if (line0raw.equalsIgnoreCase("TRASH")
                || line0raw.equalsIgnoreCase("МУСОР")
                || line0raw.equalsIgnoreCase("MUSOR")) {

            if (isHanging) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве мусорного приёмника!");
                return;
            }

            String pc = playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Таблички приёма мусора можно ставить только будучи в составе страны.");
                return;
            }

            if (!requireCountryLeader(p)) {
                e.setCancelled(true);
                return;
            }

            int have = store.countByCountry(SignCategory.TRASH_SELL, pc, SignManager::signCountryCanonical);
            int need = have + 1;
            int allowed = allowedSpecialSignsByPerm(pc, TRASH_PERM_BASE);

            if (allowed < need) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Нельзя поставить мусорку №" + need + " для страны [" + pc + "]. "
                        + ChatColor.GRAY + "Нужно разрешение! (куплено: " + allowed + ").");
                return;
            }

            String title = "Мусорка [" + pc + "]";
            sign.setLine(0, title);
            sign.setLine(1, "ПКМ с пустой");
            sign.setLine(2, "рукой, чтобы");
            sign.setLine(3, "сдать мусор");
            sign.update();

            Map<Integer, String> scrollLines = new HashMap<>();
            scrollLines.put(0, title);

            Bukkit.getScheduler().runTask(plugin, () ->
                    scroll.makeSignScrollingLines(loc, scrollLines, 6, 13)
            );

            store.put(loc, new SignVariables(
                    p.getName(),
                    pc,
                    List.of(title, sign.getLine(1), sign.getLine(2), sign.getLine(3)),
                    List.of(0),
                    false,
                    false,
                    SignCategory.TRASH_SELL,
                    SignState.SHOP_DEFINED,
                    null
            ));

            p.sendMessage(ChatColor.GREEN + "Табличка приёма мусора установлена. " + ChatColor.GRAY + "(" + need + "/" + allowed + ")");
        }
    }

    private static String safeLine(SignChangeEvent e, int i) {
        String s = e.getLine(i);
        return s == null ? "" : s;
    }

    private static Integer parseIntLoose(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        s = s.replaceAll("[^0-9\\-]", "");
        if (s.isBlank() || s.equals("-")) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return null; }
    }

    private static Double parseDoubleLoose(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        s = s.replace(',', '.');
        s = s.replaceAll("[^0-9.\\-]", "");
        if (s.isBlank() || s.equals("-") || s.equals(".")) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { return null; }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (!(e.getClickedBlock().getState() instanceof Sign sign)) return;

        Location loc = SignStore.keyLoc(e.getClickedBlock().getLocation());
        SignVariables sv = store.get(loc);
        if (sv == null) return;

        // TRASH: оставляем правило "только пустая рука"
        if (sv.getSignCategory() == SignCategory.TRASH_SELL) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                Player p = e.getPlayer();
                if (p.getInventory().getItemInMainHand().getType() != Material.AIR) {
                    p.sendMessage(ChatColor.RED + "Освободи основную руку, чтобы сдать мусор.");
                    e.setCancelled(true);
                    e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                    e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                    return;
                }
                trash.handleTrashSell(p, sign);
                e.setCancelled(true);
                e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            }
            return;
        }

        // ATM: пусть работает и с предметом в руке
        if (sv.getSignCategory() == SignCategory.ATM) {
            atm.onInteract(e, sv);
            return;
        }

        // SHOP
        if (sv.getSignCategory() == SignCategory.SHOP_LIST || sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            shop.onInteract(e);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        // сначала ATM пропускаем
        if (e.getInventory().getHolder() instanceof AtmController.AtmHolder) {
            shop.onInventoryOpen(e);
            return;
        }

        if (e.getPlayer() instanceof Player p) {
            // если это магазинный контейнер — открыть может только владелец SHOP-зоны источника
            if (isShopLinkedInventory(e.getInventory())) {

                // найдём source sign по локации инвентаря (самый прямой способ)
                Location invLoc = e.getInventory().getLocation();
                if (invLoc != null) {
                    invLoc = SignStore.keyLoc(invLoc);
                    Location src = store.sourceSignByContainer(invLoc);

                    // если вдруг это double-chest и invLoc указывает “не на ту половину”
                    if (src == null) {
                        // попробуем через holder (две половины)
                        var holder = e.getInventory().getHolder();
                        if (holder instanceof org.bukkit.block.DoubleChest dc) {
                            if (dc.getLeftSide() instanceof org.bukkit.block.Chest cl) {
                                src = store.sourceSignByContainer(SignStore.keyLoc(cl.getLocation()));
                            }
                            if (src == null && dc.getRightSide() instanceof org.bukkit.block.Chest cr) {
                                src = store.sourceSignByContainer(SignStore.keyLoc(cr.getLocation()));
                            }
                        }
                    }

                    if (src != null && !zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), src)) {
                        e.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "Этот сундук связан с магазином. Открывать может только владелец SHOP-зоны.");
                        return;
                    }
                } else {
                    // инвентарь без location, но мы уже знаем что он shop-linked -> просто режем всем кроме опа
                    // (если хочешь — можешь тут разрешить op)
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Этот контейнер связан с магазином. Открывать может только владелец.");
                    return;
                }
            }
        }

        // дальше обычная логика магазина (привязка/апдейт листов)
        shop.onInventoryOpen(e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        atm.onInventoryClick(e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        try { shop.onInventoryClose(e); } catch (Throwable ignored) {}
        try { atm.onInventoryClose(e); } catch (Throwable ignored) {}
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        atm.onQuit(e.getPlayer());
        shop.onPlayerQuit(e.getPlayer());
    }

    private static final UpgradeKey KEY = UpgradeKey.of("core.signs");

    public void enable() { onEnable(); }
    public void disable() { onDisable(); }

    private void onEnable() {
        loadSignData();
        store.pruneMissingWorlds();

        // скроллы/маркеры можно сразу
        restoreRuntimeStateAfterLoad();

        // А вот shop индексы + листы — только когда зоны готовы
        rebuildShopIndexAndListsWhenZonesReady();
    }

    private void onDisable() {
        // 14) перед сохранением подчистим мусорные записи
        store.pruneMissingWorlds();

        // 1) сохранить
        saveSignData();

        // 2) корректно остановить ВСЁ, что крутится
        stopAllScrollingTasks();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onAtmChat(AsyncPlayerChatEvent e) {
        if (atm.onChat(e)) {
            e.setCancelled(true);
        }
    }

    private boolean isShopLinkedContainerBlock(Block b) {
        if (b == null) return false;

        BlockState st = b.getState();
        if (!(st instanceof org.bukkit.block.Container c)) return false;

        Location loc = SignStore.keyLoc(c.getLocation());
        return store.sourceSignByContainer(loc) != null;
    }

    private boolean isShopLinkedInventory(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return false;

        // Самый надёжный путь (как с сейфами): location у контейнерного инвентаря
        Location loc = inv.getLocation();
        if (loc != null) {
            loc = SignStore.keyLoc(loc);
            if (store.sourceSignByContainer(loc) != null) return true;
        }

        // fallback по holder’у
        var holder = inv.getHolder();
        if (holder instanceof org.bukkit.block.Container c) {
            Location l = SignStore.keyLoc(c.getLocation());
            return store.sourceSignByContainer(l) != null;
        }
        if (holder instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide() instanceof org.bukkit.block.Chest cl) {
                if (store.sourceSignByContainer(SignStore.keyLoc(cl.getLocation())) != null) return true;
            }
            if (dc.getRightSide() instanceof org.bukkit.block.Chest cr) {
                return store.sourceSignByContainer(SignStore.keyLoc(cr.getLocation())) != null;
            }
        }

        return false;
    }

}
