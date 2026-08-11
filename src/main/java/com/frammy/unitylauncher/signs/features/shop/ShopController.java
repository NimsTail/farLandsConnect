package com.frammy.unitylauncher.signs.features.shop;

import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.MoneyManager;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignState;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.markers.MarkerService;
import com.frammy.unitylauncher.signs.render.SignRenderer;
import com.frammy.unitylauncher.signs.render.SignScrollService;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShopController {

    private static final String ERR_NOT_OWNER = ChatColor.RED + "Вы не владелец этой таблички.";
    private static final String ERR_INVALID_FORMAT = ChatColor.RED + "Неверный формат. Проверьте количество/цену.";

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final BlueMapIntegration blueMap;
    private final MarkerService markers;
    private final SignStore store;
    private final SignRenderer renderer;
    private final SignScrollService scroll;
    private final ShopListUpdater shopLists;
    private final BiConsumer<Location, SignVariables> onStored;

    // runtime session state
    private final Map<UUID, Location> signSelectionMap = new HashMap<>();
    private final Map<UUID, Integer> playerScrollIndex = new HashMap<>();
    // Какой SHOP_LIST игрок сейчас "открыл" детальным просмотром товара
    // (ЛКМ), в отличие от простого списка/скролла — второй ЛКМ по тому же
    // списку теперь спрашивает про покупку вместо перехода к следующему
    // товару, а ПКМ возвращает к списку. См. onInteract SHOP_LIST-ветку.
    private final Map<UUID, Location> viewingDetailAt = new HashMap<>();

    // pending confirm: игрок -> (табличка, индекс, дедлайн)
    private final Map<UUID, PendingBuy> pendingBuys = new ConcurrentHashMap<>();
    private static final long BUY_CONFIRM_TTL_MS = 15_000;

    private record PendingBuy(
            Location signLoc,
            int selectedIndex,
            long expiresAtMs,

            // snapshot (что именно подтверждали)
            String ownerName,
            Location chestLoc,
            String materialKey,
            int qty,
            double price
    ) {
        boolean expired(long now) { return now > expiresAtMs; }
    }

    // anti-race: один сундук — одна покупка за раз + авто-очистка
    private static final long CHEST_LOCK_IDLE_TTL_MS = 30 * 60 * 1000L; // 30 минут
    private static final long CHEST_LOCK_CLEAN_PERIOD_TICKS = 20L * 60L * 5L; // каждые 5 минут

    private static final class ChestLockEntry {
        final ReentrantLock lock = new ReentrantLock();
        volatile long lastUsedMs = System.currentTimeMillis();
    }

    private final ConcurrentHashMap<String, ChestLockEntry> chestLocks = new ConcurrentHashMap<>();

    private ChestLockEntry lockForChest(Location chestLoc) {
        chestLoc = SignStore.keyLoc(chestLoc);
        String k = SignStore.locKey(chestLoc);

        ChestLockEntry e = chestLocks.computeIfAbsent(k, __ -> new ChestLockEntry());
        e.lastUsedMs = System.currentTimeMillis();
        return e;
    }

    public ShopController(UnityLauncher plugin,
                          ZoneManager zoneManager,
                          BlueMapIntegration blueMap,
                          MarkerService markers,
                          SignStore store,
                          SignRenderer renderer,
                          SignScrollService scroll,
                          ShopListUpdater shopLists,
                          BiConsumer<Location, SignVariables> onStored) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.blueMap = blueMap;
        this.markers = markers;
        this.store = store;
        this.renderer = renderer;
        this.scroll = scroll;
        this.shopLists = shopLists;
        this.onStored = onStored;
        // периодическая очистка старых lock'ов (не трогаем, если сейчас залочено)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            long now = System.currentTimeMillis();
            chestLocks.entrySet().removeIf(en -> {
                ChestLockEntry v = en.getValue();
                if (v == null) return true;
                if (v.lock.isLocked()) return false; // критично: не удаляем активные
                return (now - v.lastUsedMs) > CHEST_LOCK_IDLE_TTL_MS;
            });
        }, CHEST_LOCK_CLEAN_PERIOD_TICKS, CHEST_LOCK_CLEAN_PERIOD_TICKS);

        startOverviewCycler();
    }

    // ===== SHOP_LIST overview auto-cycle =====

    // offset для SignRenderer.updateSignView по каждой табличке-списку —
    // раньше он всегда звался с offset=0 (и при первой сборке, и при
    // возврате из детального просмотра), из-за чего список НИКОГДА не
    // листался сам: с более чем 3 товарами дальше третьего было не
    // добраться никак. Крутим сами, как обычную бегущую строку.
    private final Map<Location, Integer> overviewOffset = new ConcurrentHashMap<>();
    private static final long OVERVIEW_CYCLE_TICKS = 30L; // ~1.5с
    // Радиус, в котором должен стоять хоть один игрок, чтобы табличку вообще
    // имело смысл крутить — иначе это чистый расход на getBlock().getState()
    // + пакет обновления таблички на КАЖДОЙ SHOP_LIST на карте каждые 1.5с,
    // 24/7, даже когда рядом с магазином никого нет неделями.
    private static final double OVERVIEW_CYCLE_RADIUS_SQ = 24.0 * 24.0;

    private void startOverviewCycler() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickOverviewCycle, OVERVIEW_CYCLE_TICKS, OVERVIEW_CYCLE_TICKS);
    }

    private void tickOverviewCycle() {
        for (var entry : store.signs().entrySet()) {
            Location loc = entry.getKey();
            SignVariables sv = entry.getValue();
            if (sv == null || sv.getSignCategory() != SignCategory.SHOP_LIST) continue;
            if (viewingDetailAt.containsValue(loc)) continue; // кто-то сейчас смотрит товар детально — не мешаем
            if (isWheelBrowsed(loc)) continue; // кто-то сейчас сам крутит колесом — не перебиваем автопрокруткой

            List<String> items = store.signPages().get(loc);
            if (items == null || items.size() <= 3) continue; // и так целиком влезает — листать нечего

            if (!anyPlayerNear(loc, OVERVIEW_CYCLE_RADIUS_SQ)) continue; // никого рядом — крутить незачем

            if (!(loc.getBlock().getState() instanceof Sign sign)) continue;

            int offset = overviewOffset.merge(loc, 1, Integer::sum);
            renderer.updateSignView(sign, items, offset);
        }
    }

    private static boolean anyPlayerNear(Location loc, double radiusSq) {
        if (loc == null || loc.getWorld() == null) return false;
        for (Player pl : loc.getWorld().getPlayers()) {
            if (pl.getLocation().distanceSquared(loc) <= radiusSq) return true;
        }
        return false;
    }

    /** Индекс товара, который СЕЙЧАС реально подсвечен зелёным на строке 2 таблички-списка — см. SignRenderer.updateSignView. */
    private int currentlyHighlightedIndex(Location loc) {
        List<String> items = store.signPages().get(loc);
        int n = (items == null) ? 0 : items.size();
        if (n <= 1) return 0;
        int offset = overviewOffset.getOrDefault(loc, 0);
        return Math.floorMod(offset + 1, n);
    }

    // ===== ручная прокрутка SHOP_LIST колесом мыши (аналог AtmController.onItemHeld) =====
    // Раньше единственным способом полистать список товаров было Shift+ЛКМ
    // (на один шаг) уже находясь в детальном просмотре — колесо мыши никак
    // не перехватывалось, и единственное, что двигало обзорный список,
    // была автопрокрутка (tickOverviewCycle), которую нельзя было ни
    // остановить, ни направить в нужную сторону. Табличка физическая
    // (реальный текст блока, не виртуальный per-player пакет как у ATM),
    // так что сессия тут одна на локацию, а не на игрока — что смотрит один,
    // видят все, ровно как уже было с автопрокруткой.

    private static final int SHOP_LIST_LOOK_RANGE = 6;
    private static final double SHOP_LIST_WHEEL_MAX_DISTANCE_SQ = 8.0 * 8.0;

    private record WheelBrowse(Location loc, int anchorSlot) {}
    private final Map<UUID, WheelBrowse> wheelBrowse = new ConcurrentHashMap<>();

    private boolean isWheelBrowsed(Location loc) {
        for (WheelBrowse wb : wheelBrowse.values()) if (wb.loc().equals(loc)) return true;
        return false;
    }

    /** true если событие "съедено" (перехвачен скролл активной SHOP_LIST-сессии). */
    public boolean onItemHeld(Player p, int previousSlot, int newSlot) {
        UUID id = p.getUniqueId();
        WheelBrowse wb = wheelBrowse.get(id);

        if (wb != null && !stillLookingAtShopListOverview(p, wb.loc())) {
            wheelBrowse.remove(id);
            wb = null;
        }

        if (wb == null) {
            Location target = shopListOverviewUnderCursor(p);
            if (target == null) return false;
            wb = new WheelBrowse(target, p.getInventory().getHeldItemSlot());
            wheelBrowse.put(id, wb);
        }

        List<String> items = store.signPages().get(wb.loc());
        if (items == null || items.size() <= 1) {
            wheelBrowse.remove(id);
            return false;
        }

        if (!(wb.loc().getBlock().getState() instanceof Sign sign)) {
            wheelBrowse.remove(id);
            return false;
        }

        int dir = scrollDirection(wb.anchorSlot(), newSlot);
        int offset = overviewOffset.merge(wb.loc(), dir, Integer::sum);
        renderer.updateSignView(sign, items, offset);

        if (p.getInventory().getHeldItemSlot() != wb.anchorSlot()) {
            p.getInventory().setHeldItemSlot(wb.anchorSlot());
        }
        return true;
    }

    /** Строго то же, что даёт возможность взять сессию: смотрит на SHOP_LIST в обзорном (не детальном) режиме. */
    private Location shopListOverviewUnderCursor(Player p) {
        try {
            var target = p.getTargetBlockExact(SHOP_LIST_LOOK_RANGE, FluidCollisionMode.NEVER);
            if (target == null || !(target.getState() instanceof Sign)) return null;
            Location loc = SignStore.keyLoc(target.getLocation());
            SignVariables sv = store.get(loc);
            if (sv == null || sv.getSignCategory() != SignCategory.SHOP_LIST) return null;
            if (loc.equals(viewingDetailAt.get(p.getUniqueId()))) return null; // в детальном просмотре — не мешаем
            List<String> items = store.signPages().get(loc);
            if (items == null || items.size() <= 1) return null;
            return loc;
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean stillLookingAtShopListOverview(Player p, Location loc) {
        try {
            if (!p.getWorld().equals(loc.getWorld())) return false;
            if (p.getLocation().distanceSquared(loc) > SHOP_LIST_WHEEL_MAX_DISTANCE_SQ) return false;
            if (loc.equals(viewingDetailAt.get(p.getUniqueId()))) return false; // перешёл в детальный просмотр
            var target = p.getTargetBlockExact(SHOP_LIST_LOOK_RANGE, FluidCollisionMode.NEVER);
            return target != null && SignStore.keyLoc(target.getLocation()).equals(loc);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Тот же кольцевой алгоритм, что в AtmController.scrollDirection — см.
     * подробный комментарий там. Наивный sign(next - prev) ломается на
     * переходе через шов хотбара (0/8); нормализация к кратчайшей дуге
     * (диапазон (-4, 4]) чинит это для любого реального прыжка.
     */
    private static int scrollDirection(int prev, int next) {
        int diff = next - prev;
        if (diff > 4) diff -= 9;
        if (diff < -4) diff += 9;
        return Integer.signum(diff);
    }

    private static final Pattern INT_ANY = Pattern.compile("-?\\d+");
    private static final Pattern DOUBLE_ANY = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    private static Integer parsePositiveInt(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        Matcher m = INT_ANY.matcher(s);
        if (!m.find()) return null;
        try {
            int v = Integer.parseInt(m.group());
            return Math.abs(v); // <- ключ: никогда не будет -32
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double parsePositiveDouble(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        Matcher m = DOUBLE_ANY.matcher(s);
        if (!m.find()) return null;

        String raw = m.group().replace(',', '.');
        try {
            double v = Double.parseDouble(raw);
            return Math.abs(v); // <- никогда не будет отрицательной цены
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ======= SIGN CREATE (SignChangeEvent) =======

    public void onSignCreateShop(org.bukkit.event.block.SignChangeEvent e, java.util.function.Function<String, String> playerCountryCanonical) {
        Player p = e.getPlayer();
        Location signLoc = SignStore.keyLoc(e.getBlock().getLocation());

        if (zoneManager.getShopZoneAt(signLoc) == null) {
            e.setCancelled(true);
            // Различаем "зоны в принципе ещё не подгрузились после старта
            // сервера" (транзиентно, реально бывает только в первые секунды)
            // от "зон тут просто нет" (табличка стоит вне SHOP-зоны — самый
            // частый случай) — раньше оба давали одно и то же сбивающее с
            // толку "зоны не загружены, попробуй через 5-10с".
            if (!zoneManager.zonesReady()) {
                p.sendMessage(ChatColor.RED + "SHOP-зоны ещё не загружены. Попробуй через 5–10 секунд.");
            } else {
                p.sendMessage(ChatColor.RED + "Эту табличку можно ставить только внутри SHOP-зоны.");
            }
            return;
        }

        // только в своей SHOP-зоне
        if (!zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), signLoc)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Магазинные таблички можно ставить только в вашей SHOP-зоне.");
            return;
        }

        ExtrudeMarker marker = markers.findExtrudeMarker(signLoc, "zones_shop", false);
        String label = (marker != null && marker.getLabel() != null && !marker.getLabel().isBlank())
                ? marker.getLabel()
                : "SHOP";

        String line0 = "Торговая точка [ " + label + " ]";
        e.setLine(0, line0);

        String mode = safeLower(e.getLine(1));

        if (mode == null) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Отсутствуют параметры на 2-ой строке таблички.");
            return;
        }

        switch (mode) {
            case "source", "источник" -> {
                Block nearestStorage = findNearestContainer(signLoc, p);
                if (nearestStorage == null) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Поблизости не найдено ни одного хранилища!");
                    return;
                }

                Location containerLoc = SignStore.keyLoc(nearestStorage.getLocation());
                String line1 = containerLoc.getBlockX() + " " + containerLoc.getBlockY() + " " + containerLoc.getBlockZ();

                e.setLine(1, line1);
                e.setLine(2, "<Количество>");
                e.setLine(3, "<Цена>");

                String pcShop = playerCountryCanonical.apply(p.getName());

                store.put(signLoc, new SignVariables(
                        p.getName(),
                        pcShop,
                        Arrays.asList(line0, line1, "<Количество>", "<Цена>"),
                        List.of(0),
                        true,
                        false,
                        SignCategory.SHOP_SOURCE,
                        SignState.SHOP_UNDEFINED,
                        null
                ));

                if (onStored != null) onStored.accept(signLoc, store.get(signLoc));

                // если этот контейнер уже был привязан к старой табличке — отцепим (после рестарта мог остаться хвост)
                Location oldSrc = store.sourceSignByContainer(containerLoc);
                if (oldSrc != null && !oldSrc.equals(signLoc)) {
                    // отцепляем контейнер от старого source
                    store.unbindContainer(containerLoc); // если такого метода нет — см. ниже
                }

                store.bindContainer(containerLoc, signLoc);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try { shopLists.updateAllRelatedShopListSigns(containerLoc); } catch (Throwable ignored) {}
                });

                // скролл первой строки
                Map<Integer, String> toScroll = new HashMap<>();
                toScroll.put(0, line0);
                Bukkit.getScheduler().runTask(plugin, () -> scroll.makeSignScrollingLines(signLoc, toScroll, 6, 13));

                p.sendMessage(ChatColor.GRAY + "Координаты источника установлены.\n" +
                        "Чтобы выбрать другое хранилище — кликните Shift+ПКМ по табличке, затем откройте нужное хранилище.");

                return;
            }
            case "list", "список" -> {
                String pcShop = playerCountryCanonical.apply(p.getName());

                store.put(signLoc, new SignVariables(
                        p.getName(),
                        pcShop,
                        Arrays.asList(line0, "...", "Загрузка", "..."),
                        List.of(0),
                        false,
                        false,
                        SignCategory.SHOP_LIST,
                        SignState.SHOP_DEFINED,
                        null
                ));

                if (onStored != null) onStored.accept(signLoc, store.get(signLoc));

                Map<Integer, String> toScroll = new HashMap<>();
                toScroll.put(0, line0);
                Bukkit.getScheduler().runTask(plugin, () -> scroll.makeSignScrollingLines(signLoc, toScroll, 6, 13));

                // FIX: стартовая позиция выбора товара
                final int next = 0;
                final Location loc = signLoc;

                playerScrollIndex.put(p.getUniqueId(), next);

                // обновить табличку, чтобы выделение реально поменялось
                Bukkit.getScheduler().runTask(plugin, () -> updateShopListSignSelection(loc, next));

                Bukkit.getScheduler().runTask(plugin, () -> shopLists.updateAllRelatedShopListSigns(signLoc));
                p.sendMessage(ChatColor.GREEN + "Список товаров обновлён.");
                return;
            }

            case "seller", "продавец", "info", "инфо", "информация", "help", "помощь" -> {
                // пока заглушки, как у тебя было
                p.sendMessage(ChatColor.YELLOW + "Этот тип таблички пока не вынесен. Сейчас работают source и list.");
                return;
            }
        }

        e.setCancelled(true);
        p.sendMessage(ChatColor.RED + "Неизвестный режим на 2-ой строке: " + mode);
    }

    private void updateShopListSignSelection(Location loc, int selectedIndex) {
        if (loc == null || loc.getWorld() == null) return;

        List<String> items = store.signPages().get(loc);
        List<ItemData> dataList = store.signItemData().get(loc);
        if (items == null || items.isEmpty() || dataList == null || dataList.isEmpty()) return;

        int n = Math.min(items.size(), dataList.size());
        int idx = Math.floorMod(selectedIndex, n);

        Block b = loc.getBlock();
        if (!(b.getState() instanceof Sign sign)) return;

        ItemData it = dataList.get(idx);

        String title = sign.getLine(0); // у тебя скролл заголовка
        String name = ChatColor.stripColor(items.get(idx));

        // Только числа (кол-во/цена) окрашены — остальной текст в дефолтном
        // цвете таблички, без искусственного WHITE поверх него.
        String line1 = ChatColor.GOLD + "▶ " + ChatColor.RESET + name;
        String line2 = "Кол-во: " + ChatColor.YELLOW + it.dealQuantity()
                + ChatColor.RESET + " (всего " + it.totalQuantity() + ")";
        String line3 = "Цена: " + ChatColor.GREEN + it.dealPrice() + ChatColor.RESET + " Ⓕ";

        sign.setLine(0, title);
        sign.setLine(1, line1);
        sign.setLine(2, line2);
        sign.setLine(3, line3);
        sign.update(true, false);

        // ВАЖНО: обновляем store, иначе renderer/scroll может перезатереть строки обратно
        SignVariables sv = store.get(loc);
        if (sv != null && sv.getSignCategory() == SignCategory.SHOP_LIST) {
            sv.setSignText(Arrays.asList(title, line1, line2, line3));
            store.put(loc, sv);
            if (onStored != null) onStored.accept(loc, sv);
        }
    }

    // ======= INTERACT (PlayerInteractEvent) =======

    private static BlockState getLiveState(Block block) {
        try {
            // Paper имеет Block#getState(boolean useSnapshot)
            Method m = block.getClass().getMethod("getState", boolean.class);
            return (BlockState) m.invoke(block, false); // false = live tile entity (не snapshot)
        } catch (Throwable ignored) {
            // fallback: обычный snapshot (хуже, но пусть будет)
            return block.getState();
        }
    }

    public void onInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (!(e.getClickedBlock().getState() instanceof Sign sign)) return;

        Location loc = SignStore.keyLoc(e.getClickedBlock().getLocation());
        SignVariables sv = store.get(loc);
        if (sv == null) return;

        if (sv.getSignCategory() != SignCategory.SHOP_SOURCE && sv.getSignCategory() != SignCategory.SHOP_LIST) return;

        Player p = e.getPlayer();
        Action action = e.getAction();
        boolean isOwner = zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), loc);

        // SHIFT+ПКМ по SHOP_SOURCE => режим выбора контейнера
        if (action == Action.RIGHT_CLICK_BLOCK && p.isSneaking()) {
            if (!isOwner) { p.sendMessage(ERR_NOT_OWNER); e.setCancelled(true); return; }
            if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
                signSelectionMap.put(p.getUniqueId(), loc);
                p.sendMessage(ChatColor.YELLOW + "Открой нужное хранилище для привязки.");
                e.setCancelled(true);
                return;
            }
        }

        // ПКМ без шифта:
        // - SHOP_SOURCE: только владелец (пауза скролла для редактирования/настройки)
        // - SHOP_LIST: доступно всем (это "публичная" табличка магазина)
        if (action == Action.RIGHT_CLICK_BLOCK && !p.isSneaking()) {

            if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
                e.setCancelled(true);

                if (isOwner) {
                    // владелец: как было — пауза скролла для настройки
                    scroll.pauseScrolling(loc);
                    return;
                }

                // НЕ владелец: покупка прямо с source
                handleShopSourceBuyClick(p, loc);
                return;
            }

            if (sv.getSignCategory() == SignCategory.SHOP_LIST) {
                // Покупка теперь спрашивается вторым ЛКМ (см. ниже) — ПКМ
                // просто возвращает от детального просмотра товара обратно
                // к обзорному списку.
                e.setCancelled(true);
                viewingDetailAt.remove(p.getUniqueId());
                restoreShopListOverview(loc);
                return;
            }

        }

        // ЛКМ без шифта по SHOP_SOURCE владельцем => открыть Sign Editor (обходит моды "клик сквозь таблички")
        if (action == Action.LEFT_CLICK_BLOCK
                && sv.getSignCategory() == SignCategory.SHOP_SOURCE
                && !p.isSneaking()) {

            if (!isOwner) { p.sendMessage(ERR_NOT_OWNER); e.setCancelled(true); return; }

            // пауза скролла, чтобы он не мешал редактировать
            scroll.pauseScrolling(loc);

            // важно: отменяем, чтобы не ударять/не триггерить лишнее
            e.setCancelled(true);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

            // открываем редактор на следующем тике (надёжнее)
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    p.openSign(sign); // Paper/Spigot 1.20+ (у тебя 1.21.x должно быть ок)
                    p.sendMessage(ChatColor.GRAY + "Отредактируй строки 3-4 и затем Shift+ЛКМ для подтверждения.");
                } catch (Throwable t) {
                    p.sendMessage(ChatColor.RED + "Не удалось открыть редактор таблички (openSign недоступен).");
                }
            });
            return;
        }

        // ЛКМ по SHOP_SOURCE: подтверждение (как в легаси) — переносим базовую логику
        if (action == Action.LEFT_CLICK_BLOCK && sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            if (!isOwner) return;
            if (p.getInventory().getItemInMainHand().getType() != org.bukkit.Material.AIR) return;

            if (sv.getSignState() == SignState.SHOP_UNDEFINED && p.isSneaking()) {
                String l2 = sign.getLine(2);
                String l3 = sign.getLine(3);
                if (l2.isEmpty() || l3.isEmpty()) return;

                Integer amountObj = parsePositiveInt(l2);
                Double priceObj = parsePositiveDouble(l3);
                if (amountObj == null || priceObj == null) {
                    p.sendMessage(ERR_INVALID_FORMAT);
                    sign.setLine(2, "<Количество>");
                    sign.setLine(3, "<Цена>");
                    sign.update();
                    return;
                }

                int amount = amountObj;
                double price = priceObj;

                List<String> t = safe4(sv.getSignText());
                String line2 = "Кол-во: " + ChatColor.YELLOW + amount;
                String line3 = "Цена: " + ChatColor.GREEN + price;

                sv.setSignText(Arrays.asList(t.get(0), t.get(1), line2, line3));
                sign.setLine(2, line2);
                sign.setLine(3, line3);
                sign.update();

                sv.setSignState(SignState.SHOP_DEFINED);

                store.put(loc, sv);                 // <<< ВАЖНО
                if (onStored != null) onStored.accept(loc, sv);

                scroll.resumeScrolling(loc);

                if (sv.getMarkerID() == null) {
                    String markerId = "marker_" + UUID.randomUUID();
                    sv.setMarkerID(markerId);
                    store.put(loc, sv);             // <<< ещё раз после markerId (или один раз в конце)
                    if (onStored != null) onStored.accept(loc, sv);

                    blueMap.addBlueMapMarker(markerId, loc, "services", "Сервисы", "point_shop", null, p);
                }

                p.sendMessage(ChatColor.GREEN + "Табличка товара подтверждена.");

                try {
                    Location chest = resolveChestLocationFromSource(loc, sv);
                    if (chest != null) shopLists.updateAllRelatedShopListSigns(chest);
                } catch (Throwable ignored) {}

                return;

            }

            // SHIFT+ЛКМ по определённой табличке — вернуть в режим редактирования
            if (sv.getSignState() == SignState.SHOP_DEFINED && p.isSneaking()) {
                List<String> t = safe4(sv.getSignText());
                String line2 = ChatColor.stripColor(t.get(2)).replaceFirst("^Кол-во:\\s*", "");
                String line3 = ChatColor.stripColor(t.get(3)).replaceFirst("^Цена:\\s*", "");

                sv.setSignText(Arrays.asList(t.get(0), t.get(1), line2, line3));
                sign.setLine(2, line2);
                sign.setLine(3, line3);
                sign.update();

                sv.setSignState(SignState.SHOP_UNDEFINED);

                store.put(loc, sv);
                if (onStored != null) onStored.accept(loc, sv);

                p.sendMessage(ChatColor.GRAY + "Табличка переключена в режим редактирования.");
            }
        }

        // ЛКМ по SHOP_LIST: список -> детально -> "купить?" (ПКМ выше — назад к списку)
        if (action == Action.LEFT_CLICK_BLOCK && sv.getSignCategory() == SignCategory.SHOP_LIST) {
            e.setCancelled(true);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

            if (p.isSneaking()) {
                // Shift+ЛКМ — как и раньше, предыдущий товар; тоже считается "уже смотрим детально".
                viewingDetailAt.put(p.getUniqueId(), loc);
                handleShopListInfoClick(p, loc, true);
                return;
            }

            boolean alreadyViewingThis = loc.equals(viewingDetailAt.get(p.getUniqueId()));
            if (!alreadyViewingThis) {
                // Первый ЛКМ по этому списку — открыть текущий товар детально,
                // БЕЗ смещения индекса (иначе первый же клик перескакивал бы товар 0).
                // ВАЖНО: раньше тут читался playerScrollIndex, который для
                // игрока, ещё ни разу не листавшего список Shift+ЛКМ, всегда
                // == 0 — то есть клик открывал товар №0, даже если на самой
                // табличке (за счёт автопрокрутки обзора, см.
                // tickOverviewCycle/overviewOffset) сейчас подсвечен
                // (зелёным, строка 2) СОВСЕМ другой товар. Берём индекс из
                // того же overviewOffset, которым реально нарисован текущий
                // вид таблички — то, что игрок видит зелёным, то и открывается.
                int idx = currentlyHighlightedIndex(loc);
                playerScrollIndex.put(p.getUniqueId(), idx);
                viewingDetailAt.put(p.getUniqueId(), loc);
                updateShopListSignSelection(loc, idx);
                return;
            }

            // Уже смотрели этот же список детально — второй ЛКМ подряд спрашивает про покупку.
            promptShopListBuyConfirm(p, loc);
        }

    }

    /** Возвращает табличку SHOP_LIST от детального просмотра товара обратно к обзорному списку (ПКМ). */
    private void restoreShopListOverview(Location loc) {
        if (!(loc.getBlock().getState() instanceof Sign sign)) return;
        List<String> itemLines = store.signPages().get(loc);
        renderer.updateSignView(sign, itemLines, 0);
    }

    private void promptShopListBuyConfirm(Player p, Location loc) {
        if (p == null || loc == null) return;

        SignVariables sv = store.get(loc);
        if (sv == null || sv.getSignCategory() != SignCategory.SHOP_LIST) return;

        List<String> items = store.signPages().get(loc);
        List<ItemData> dataList = store.signItemData().get(loc);
        if (items == null || items.isEmpty() || dataList == null || dataList.isEmpty()) {
            p.sendMessage(ChatColor.RED + "В этом магазине пока нет товаров.");
            return;
        }

        int n = Math.min(items.size(), dataList.size());
        int selected = Math.floorMod(playerScrollIndex.getOrDefault(p.getUniqueId(), 0), n);
        ItemData item = dataList.get(selected);

        if (item == null || !item.available() || item.dealPrice() <= 0 || item.dealQuantity() <= 0) {
            p.sendMessage(ChatColor.RED + "Этот товар сейчас недоступен.");
            return;
        }

        String ownerName = resolveShopOwnerName(loc, sv);
        if (ownerName == null || ownerName.isBlank()) {
            p.sendMessage(ChatColor.RED + "У магазина не задан владелец (ownerName).");
            return;
        }

        Location chestLoc = SignStore.keyLoc(item.chestLocation());
        String itemName = ChatColor.stripColor(items.get(selected));

        final int qty = item.dealQuantity();
        final double price = item.dealPrice();
        final boolean payWithCash = isCashInHand(p);

        pendingBuys.put(p.getUniqueId(), new PendingBuy(
                loc, selected, System.currentTimeMillis() + BUY_CONFIRM_TTL_MS,
                ownerName, chestLoc, item.materialKey(), qty, price
        ));

        p.sendMessage(ChatColor.DARK_AQUA + "=== Подтверждение покупки ===");
        p.sendMessage(ChatColor.GRAY + "Товар: " + ChatColor.WHITE + qty + "x " + itemName);
        p.sendMessage(ChatColor.GRAY + "Цена: " + ChatColor.GREEN + price + " Ⓕ");
        p.sendMessage(ChatColor.GRAY + "Оплата: " + (payWithCash ? (ChatColor.GOLD + "Наличными") : (ChatColor.AQUA + "Со счёта")));
        p.sendMessage(ChatColor.GRAY + "Сундук: " + ChatColor.WHITE + formatLocation(chestLoc));
        p.sendMessage(ChatColor.DARK_GRAY + "Действительно 15 секунд.");

        TextComponent buyBtn = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "[КУПИТЬ]");
        buyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul shop buy"));
        buyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Нажми, чтобы купить").create()));

        TextComponent cancelBtn = new TextComponent(ChatColor.RED + "" + ChatColor.BOLD + " [ОТМЕНА]");
        cancelBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul shop cancel"));
        cancelBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("Отменить покупку").create()));

        TextComponent line = new TextComponent("");
        line.addExtra(buyBtn);
        line.addExtra(cancelBtn);
        p.spigot().sendMessage(line);
    }

    public void cancelPendingBuy(Player p) {
        if (p == null) return;
        pendingBuys.remove(p.getUniqueId());
        p.sendMessage(ChatColor.GRAY + "Покупка отменена.");
    }

    public void confirmPendingBuy(Player p) {
        if (p == null) return;

        PendingBuy pb = pendingBuys.remove(p.getUniqueId());
        if (pb == null) {
            p.sendMessage(ChatColor.RED + "Нет покупки для подтверждения.");
            return;
        }

        long now = System.currentTimeMillis();
        if (pb.expired(now)) {
            p.sendMessage(ChatColor.RED + "Время подтверждения истекло. Нажмите ПКМ по табличке ещё раз.");
            return;
        }

        // 1) проверяем, что табличка и товар не изменились
        SignVariables sv = store.get(pb.signLoc());
        if (sv == null || sv.getSignCategory() != SignCategory.SHOP_LIST) {
            p.sendMessage(ChatColor.RED + "Магазин недоступен.");
            return;
        }

        List<ItemData> dataList = store.signItemData().get(pb.signLoc());
        List<String> items = store.signPages().get(pb.signLoc());
        if (dataList == null || items == null || dataList.isEmpty() || items.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Список товаров обновился. Открой подтверждение заново (ПКМ).");
            return;
        }

        int n = Math.min(items.size(), dataList.size());
        int idx = Math.floorMod(pb.selectedIndex(), n);
        ItemData cur = dataList.get(idx);

        // snapshot checks
        if (cur == null
                || cur.dealQuantity() != pb.qty()
                || Math.abs(cur.dealPrice() - pb.price()) > 1e-9
                || !Objects.equals(cur.materialKey(), pb.materialKey())
                || !SignStore.keyLoc(cur.chestLocation()).equals(pb.chestLoc())) {
            p.sendMessage(ChatColor.RED + "Товар/цена/количество изменились. Нажми ПКМ ещё раз для нового подтверждения.");
            return;
        }

        // ownerCountry тоже проверим (паранойя полезна)
        String ownerName = resolveShopOwnerName(pb.signLoc(), sv);
        if (ownerName == null || ownerName.isBlank() || !ownerName.equalsIgnoreCase(pb.ownerName())) {
            p.sendMessage(ChatColor.RED + "Владелец магазина изменился. Нажми ПКМ ещё раз.");
            return;
        }

        // 2) всё совпало — покупаем именно этот индекс
        handleShopListBuyClickForcedIndex(p, pb.signLoc(), idx);
    }

    private void handleShopListInfoClick(Player p, Location loc, boolean backward) {
        if (p == null || loc == null) return;

        List<String> items = store.signPages().get(loc);
        var dataList = store.signItemData().get(loc);
        if (items == null || items.isEmpty() || dataList == null || dataList.isEmpty()) return;

        int n = Math.min(items.size(), dataList.size());
        int cur = playerScrollIndex.getOrDefault(p.getUniqueId(), 0);

        int next = !backward ? (cur + 1) % n : (cur - 1 + n) % n;
        playerScrollIndex.put(p.getUniqueId(), next);

        updateShopListSignSelection(loc, next);
    }

    private boolean isCashInHand(Player p) {
        if (p == null) return false;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return false;

        MoneyManager mm = UnityLauncher.getInstance().getMoneyManager(); // или .moneyManager
        return mm != null && mm.isMoneyItem(hand);
    }

    private void handleShopListBuyClickForcedIndex(Player p, Location loc, int selectedIndex) {
        if (p == null || loc == null) return;

        SignVariables sv = store.get(loc);
        if (sv == null || sv.getSignCategory() != SignCategory.SHOP_LIST) return;

        List<String> items = store.signPages().get(loc);
        List<ItemData> dataList = store.signItemData().get(loc);

        if (items == null || items.isEmpty() || dataList == null || dataList.isEmpty()) {
            p.sendMessage(ChatColor.RED + "В этом магазине пока нет товаров.");
            return;
        }

        int n = Math.min(items.size(), dataList.size());
        int selected = Math.floorMod(selectedIndex, n);

        ItemData item = dataList.get(selected);
        if (item == null) return;

        if (!item.available()) {
            p.sendMessage(ChatColor.RED + "Товар недоступен (нет количества или цена = 0).");
            return;
        }

        if (item.dealPrice() <= 0) {
            p.sendMessage(ChatColor.RED + "Некорректная цена.");
            return;
        }

        Material mat = Material.matchMaterial(item.materialKey());
        if (mat == null) {
            p.sendMessage(ChatColor.RED + "Неизвестный предмет: " + item.materialKey());
            return;
        }

        // страна-владелец магазина (казна получит деньги)
        String ownerName = resolveShopOwnerName(loc, sv);
        if (ownerName == null || ownerName.isBlank()) {
            p.sendMessage(ChatColor.RED + "У магазина не задан владелец (ownerName).");
            return;
        }

        final double price = item.dealPrice();
        final int qty = item.dealQuantity();
        final Location chestLoc = SignStore.keyLoc(item.chestLocation());

        final boolean payWithCash = isCashInHand(p);

        if (payWithCash) {
            MoneyManager mm = UnityLauncher.getInstance().getMoneyManager();
            if (mm == null) {
                p.sendMessage(ChatColor.RED + "MoneyManager не доступен.");
                return;
            }

            // Списываем наличку сразу (main thread) — spendCash сам даст сдачу.
            boolean okCash = mm.spendCash(p, price);
            if (!okCash) {
                double haveCash = mm.getInventoryCash(p); // <-- ТА САМАЯ СТРОЧКА
                p.sendMessage(ChatColor.RED + "Недостаточно наличных. Нужно: "
                        + ChatColor.YELLOW + price + " Ⓕ"
                        + ChatColor.RED + " (у тебя: " + ChatColor.YELLOW + haveCash + " Ⓕ" + ChatColor.RED + ")");
                return;
            }

            // Зачисляем в казну async, а выдачу товара делаем main thread
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean deposited = true;
                try {
                    boolean credited = UnityCommands.getInstance().applyMoneyDelta(ownerName, price, shopSaleNote(loc, qty, mat, p.getName()));
                    if (!credited) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            mm.giveCash(p, price);
                            p.sendMessage(ChatColor.RED + "Оплата не прошла (владелец недоступен). Деньги возвращены наличными.");
                        });
                        return;
                    }

                } catch (Exception ex) {
                    deposited = false;
                    plugin.getLogger().warning("[Shop] addCountryMoney failed for " + ownerName + ": " + ex);
                }

                if (!deposited) {
                    // вернуть наличку (best-effort)
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        mm.giveCash(p, price);
                        p.sendMessage(ChatColor.RED + "Оплата не прошла (казна). Деньги возвращены наличными.");
                    });
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean ok = dispenseFromChestAtomic(chestLoc, mat, qty, p);
                    if (!ok) {
                        // товара нет → вернуть наличку + откатить казну best-effort
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            try { UnityCommands.getInstance().applyMoneyDelta(ownerName, -price, "Возврат: покупка отменена (товара не хватило)"); }
                            catch (Throwable ignored) {}
                        });
                        mm.giveCash(p, price);

                        p.sendMessage(ChatColor.RED + "Покупка не удалась: товара в сундуке не хватает. Деньги возвращены наличными.");
                        try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                        return;
                    }

                    p.sendMessage(ChatColor.GREEN + "Куплено (наличными): " + ChatColor.YELLOW + qty + "x " + ChatColor.RESET
                            + pretty(mat) + ChatColor.GREEN + " за " + ChatColor.YELLOW + price + " Ⓕ");

                    try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                });
            });

            return; // важно: не идти в банковскую ветку
        }

        // 1) сначала проверим баланс игрока и спишем деньги (async)
        UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
            if (data == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(ChatColor.RED + "Не удалось получить баланс игрока."));
                return;
            }

            if (data.money + 1e-9 < price) {

                // FIX: кэш мог быть протухшим — инвалидируем и прогреваем заново
                UnityCommands.getInstance().refreshPlayerCacheAsync(p.getName());

                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(ChatColor.RED + "Недостаточно денег на счёте. Нужно: " + ChatColor.YELLOW + price + " Ⓕ"));
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean withdrew = UnityCommands.getInstance().applyMoneyDelta(p.getName(), -price, "Покупка в магазине: " + qty + "x " + mat.name());
                if (!withdrew) {

                    // На случай гонки/минуса/протухшего кэша — обновим
                    UnityCommands.getInstance().refreshPlayerCacheAsync(p.getName());

                    Bukkit.getScheduler().runTask(plugin, () ->
                            p.sendMessage(ChatColor.RED + "Не удалось списать деньги (БД)."));
                    return;
                }

                // 2) зачисляем в казну страны (async). если не удалось — вернём деньги и выйдем
                boolean deposited = true;
                try {
                    boolean credited = UnityCommands.getInstance().applyMoneyDelta(ownerName, price, shopSaleNote(loc, qty, mat, p.getName()));
                    if (!credited) {
                        UnityCommands.getInstance().applyMoneyDelta(p.getName(), price, "Возврат: продавец недоступен"); // вернуть покупателю
                        Bukkit.getScheduler().runTask(plugin, () ->
                                p.sendMessage(ChatColor.RED + "Оплата не прошла (владелец недоступен). Деньги возвращены."));
                        return;
                    }
                } catch (Exception ex) {
                    deposited = false;
                    plugin.getLogger().warning("[Shop] addCountryMoney failed for " + ownerName + ": " + ex);
                }

                if (!deposited) {
                    UnityCommands.getInstance().applyMoneyDelta(p.getName(), price, "Возврат: ошибка казны"); // best-effort refund
                    Bukkit.getScheduler().runTask(plugin, () ->
                            p.sendMessage(ChatColor.RED + "Оплата не прошла (казна). Деньги возвращены."));
                    return;
                }

                // 3) выдача товара — ТОЛЬКО main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean ok = dispenseFromChestAtomic(chestLoc, mat, qty, p);
                    if (!ok) {
                        // товара нет → возврат денег (async) + сообщение (main)
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            UnityCommands.getInstance().applyMoneyDelta(p.getName(), price, "Возврат: товара не хватило");
                            try {
                                UnityCommands.getInstance().applyMoneyDelta(ownerName, -price, "Возврат: покупка отменена (товара не хватило)");
                            } catch (Exception ignored) {}
                        });

                        p.sendMessage(ChatColor.RED + "Покупка не удалась: товара в сундуке не хватает. Деньги возвращены.");
                        // пересоберём листы, чтобы обновилось “доступное количество”
                        try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                        return;
                    }

                    p.sendMessage(ChatColor.GREEN + "Куплено: " + ChatColor.YELLOW + qty + "x " + ChatColor.RESET
                            + pretty(mat) + ChatColor.GREEN + " за " + ChatColor.YELLOW + price + " Ⓕ");

                    // после покупки обновим листы
                    try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                });
            });
        });
    }

    private void handleShopSourceBuyClick(Player p, Location signLoc) {
        if (p == null || signLoc == null) return;

        SignVariables sv = store.get(signLoc);
        if (sv == null || sv.getSignCategory() != SignCategory.SHOP_SOURCE) return;

        String ownerName = resolveShopOwnerName(signLoc, sv);
        if (ownerName == null || ownerName.isBlank()) {
            p.sendMessage(ChatColor.RED + "У магазина не задан владелец (ownerName).");
            return;
        }

        // qty/price берём из signText (или из мира позже)
        List<String> t = safe4(sv.getSignText());
        String l2 = t.get(2);
        String l3 = t.get(3);

        Integer qtyObj = parsePositiveInt(l2);
        Double priceObj = parsePositiveDouble(l3);


        if (qtyObj == null || qtyObj <= 0 || priceObj == null || priceObj <= 0) {
            p.sendMessage(ChatColor.RED + "Товар не настроен. Попроси владельца выставить количество и цену.");
            return;
        }

        final int qty = qtyObj;
        final double price = priceObj;

        // chestLoc — из строки с координатами (line1)
        Location chestLoc = resolveChestLocationFromSource(signLoc, sv);
        if (chestLoc == null) {
            p.sendMessage(ChatColor.RED + "Источник (сундук) не найден. Попроси владельца перепривязать хранилище.");
            return;
        }

        // Материал: берём первый НЕ воздух из сундука
        Material mat = peekFirstMaterialInChest(chestLoc);
        if (mat == null) {
            p.sendMessage(ChatColor.RED + "В сундуке нет товара.");
            return;
        }

        // наличка?
        final boolean payWithCash = isCashInHand(p);

        if (payWithCash) {
            MoneyManager mm = UnityLauncher.getInstance().getMoneyManager();
            if (mm == null) {
                p.sendMessage(ChatColor.RED + "MoneyManager не доступен.");
                return;
            }

            boolean okCash = mm.spendCash(p, price);
            if (!okCash) {
                double haveCash = mm.getInventoryCash(p);
                p.sendMessage(ChatColor.RED + "Недостаточно наличных. Нужно: "
                        + ChatColor.YELLOW + price + " Ⓕ"
                        + ChatColor.RED + " (у тебя: " + ChatColor.YELLOW + haveCash + " Ⓕ" + ChatColor.RED + ")");
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean deposited = true;
                try {
                    boolean credited = UnityCommands.getInstance().applyMoneyDelta(ownerName, price, shopSaleNote(signLoc, qty, mat, p.getName()));
                    if (!credited) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            mm.giveCash(p, price);
                            p.sendMessage(ChatColor.RED + "Оплата не прошла (владелец недоступен). Деньги возвращены наличными.");
                        });
                        return;
                    }
                } catch (Exception ex) {
                    deposited = false;
                    plugin.getLogger().warning("[Shop] addCountryMoney failed for " + ownerName + ": " + ex);
                }

                if (!deposited) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        mm.giveCash(p, price);
                        p.sendMessage(ChatColor.RED + "Оплата не прошла (казна). Деньги возвращены наличными.");
                    });
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean ok = dispenseFromChestAtomic(chestLoc, mat, qty, p);
                    if (!ok) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            try { UnityCommands.getInstance().applyMoneyDelta(ownerName, -price, "Возврат: покупка отменена (товара не хватило)"); }
                            catch (Throwable ignored) {}
                        });
                        mm.giveCash(p, price);
                        p.sendMessage(ChatColor.RED + "Товара не хватает. Деньги возвращены наличными.");
                        try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                        return;
                    }

                    p.sendMessage(ChatColor.GREEN + "Куплено (наличными): " + ChatColor.YELLOW + qty + "x "
                            + ChatColor.RESET + pretty(mat) + ChatColor.GREEN + " за " + ChatColor.YELLOW + price + " Ⓕ");

                    try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                });
            });

            return;
        }

        // без налички: банковская ветка (как в list)
        UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
            if (data == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(ChatColor.RED + "Не удалось получить баланс игрока."));
                return;
            }

            if (data.money + 1e-9 < price) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(ChatColor.RED + "Недостаточно денег на счёте. Нужно: " + ChatColor.YELLOW + price + " Ⓕ"));
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean withdrew = UnityCommands.getInstance().applyMoneyDelta(p.getName(), -price, "Покупка в магазине: " + qty + "x " + mat.name());
                if (!withdrew) {
                    Bukkit.getScheduler().runTask(plugin, () ->
                            p.sendMessage(ChatColor.RED + "Не удалось списать деньги (БД)."));
                    return;
                }

                boolean deposited = true;
                try {
                    boolean credited = UnityCommands.getInstance().applyMoneyDelta(ownerName, price, shopSaleNote(signLoc, qty, mat, p.getName()));
                    if (!credited) {
                        // вернуть на счёт игрока, потому что платили со счёта
                        UnityCommands.getInstance().applyMoneyDelta(p.getName(), price, "Возврат: продавец недоступен");
                        Bukkit.getScheduler().runTask(plugin, () ->
                                p.sendMessage(ChatColor.RED + "Оплата не прошла (владелец недоступен). Деньги возвращены на счёт."));
                        return;
                    }
                } catch (Exception ex) {
                    deposited = false;
                    plugin.getLogger().warning("[Shop] addCountryMoney failed for " + ownerName + ": " + ex);
                }

                if (!deposited) {
                    UnityCommands.getInstance().applyMoneyDelta(p.getName(), price, "Возврат: ошибка казны");
                    Bukkit.getScheduler().runTask(plugin, () ->
                            p.sendMessage(ChatColor.RED + "Оплата не прошла (казна). Деньги возвращены на счёт."));
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean ok = dispenseFromChestAtomic(chestLoc, mat, qty, p);
                    if (!ok) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            UnityCommands.getInstance().applyMoneyDelta(p.getName(), price, "Возврат: товара не хватило");
                            try { UnityCommands.getInstance().applyMoneyDelta(ownerName, -price, "Возврат: покупка отменена (товара не хватило)"); }
                            catch (Throwable ignored) {}
                        });

                        p.sendMessage(ChatColor.RED + "Товара не хватает. Деньги возвращены.");
                        try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                        return;
                    }

                    p.sendMessage(ChatColor.GREEN + "Куплено: " + ChatColor.YELLOW + qty + "x "
                            + ChatColor.RESET + pretty(mat) + ChatColor.GREEN + " за " + ChatColor.YELLOW + price + " Ⓕ");

                    try { shopLists.updateAllRelatedShopListSigns(chestLoc); } catch (Throwable ignored) {}
                });
            });
        });
    }

    private Location resolveChestLocationFromSource(Location signLoc, SignVariables sv) {
        // берём строку 1: "x y z"
        List<String> t = safe4(sv.getSignText());
        String line1 = t.get(1);
        if (line1 == null) return null;

        String[] parts = ChatColor.stripColor(line1).trim().split("\\s+");
        if (parts.length < 3) return null;

        Integer x = parseIntLoose(parts[0]);
        Integer y = parseIntLoose(parts[1]);
        Integer z = parseIntLoose(parts[2]);
        if (x == null || y == null || z == null) return null;

        World w = signLoc.getWorld();
        if (w == null) return null;

        return SignStore.keyLoc(new Location(w, x, y, z));
    }

    private Material peekFirstMaterialInChest(Location chestLoc) {
        if (chestLoc == null || chestLoc.getWorld() == null) return null;

        var st = chestLoc.getBlock().getState();
        if (!(st instanceof Container c)) return null;

        for (ItemStack it : c.getInventory().getContents()) {
            if (it == null) continue;
            if (it.getType().isAir()) continue;
            return it.getType();
        }
        return null;
    }

    private boolean dispenseFromChestAtomic(Location chestLoc, Material ignoredMat, int qty, Player buyer) {
        if (chestLoc == null || buyer == null || chestLoc.getWorld() == null) return false;

        if (!Bukkit.isPrimaryThread()) {
            plugin.getLogger().warning("[Shop] dispenseFromChestAtomic called off-main-thread! chestLoc=" + chestLoc);
            return false;
        }

        ChestLockEntry entry = lockForChest(chestLoc);
        ReentrantLock lock = entry.lock;

        lock.lock();
        try {
            Block block = chestLoc.getBlock();
            BlockState live = getLiveState(block);

            if (!(live instanceof Container container)) {
                plugin.getLogger().warning("[Shop] Not a container at " + chestLoc + " type=" + block.getType());
                return false;
            }

            Inventory inv = container.getInventory();

            ItemStack template = findFirstNonAir(inv);
            if (template == null) return false;

            int before = countSimilar(inv, template);
            if (before < qty) return false;

            ItemStack[] snapshot = Arrays.stream(inv.getContents())
                    .map(it -> it == null ? null : it.clone())
                    .toArray(ItemStack[]::new);

            ItemStack request = template.clone();
            request.setAmount(qty);

            Map<Integer, ItemStack> notRemoved = inv.removeItem(request);

            if (!notRemoved.isEmpty()) {
                inv.setContents(snapshot);
                safeUpdate(container);
                return false;
            }

            safeUpdate(container);

            int after = countSimilar(inv, template);
            if (after != before - qty) {
                plugin.getLogger().warning("[Shop] Container did NOT decrease properly! before=" + before
                        + " after=" + after + " qty=" + qty + " chestLoc=" + chestLoc
                        + " type=" + block.getType() + " template=" + template.getType());

                inv.setContents(snapshot);
                safeUpdate(container);
                return false;
            }

            ItemStack out = template.clone();
            out.setAmount(qty);

            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(out);
            if (!leftovers.isEmpty()) {
                leftovers.values().forEach(it -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), it));
            }

            return true;
        } finally {
            entry.lastUsedMs = System.currentTimeMillis(); // отметим использование в конце
            lock.unlock();
        }
    }

    private static void safeUpdate(Container c) {
        try { c.update(true, false); } catch (Throwable ignored) {}
    }

    private static int countSimilar(Inventory inv, ItemStack template) {
        int n = 0;
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (!it.isSimilar(template)) continue;
            n += it.getAmount();
        }
        return n;
    }

    /** Первая не-air вещь (с meta), которая будет "товаром" этого сундука. */
    private static ItemStack findFirstNonAir(Inventory inv) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            return it;
        }
        return null;
    }

    private String pretty(Material mat) {
        String s = mat.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (s.isEmpty()) return mat.name();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ======= INVENTORY OPEN (привязка сундука) =======

    public void onInventoryOpen(InventoryOpenEvent e) {
        var holder = e.getInventory().getHolder();
        // ATM больше не открывает инвентарь (см. AtmController — целиком
        // табличный интерфейс), так что отдельной проверки holder'а тут
        // больше не нужно: resolveContainerLocations и так вернёт пусто
        // для чего угодно, что не является реальным контейнером.

        // определяем локации контейнера (1 или 2 — для double chest)
        List<Location> containerLocs = resolveContainerLocations(holder);
        if (containerLocs.isEmpty()) return;

        // для логики будем использовать "главную" локацию (первая)
        Location containerLoc = SignStore.keyLoc(containerLocs.getFirst());

        HumanEntity he = e.getPlayer();
        Player p = (he instanceof Player pp) ? pp : null;

        // режим привязки (Shift+ПКМ по source)
        if (p != null && signSelectionMap.containsKey(p.getUniqueId())) {
            Location signLoc = SignStore.keyLoc(signSelectionMap.get(p.getUniqueId()));

            Block signBlock = signLoc.getBlock();
            if (!(signBlock.getState() instanceof Sign sign)) {
                signSelectionMap.remove(p.getUniqueId());
                return;
            }

            if (zoneManager.getShopZoneAt(signLoc) == null || zoneManager.getShopZoneAt(containerLoc) == null) {
                p.sendMessage(ChatColor.RED + "SHOP-зоны ещё не загружены. Попробуй через 5–10 секунд.");
                signSelectionMap.remove(p.getUniqueId());
                e.setCancelled(true);
                return;
            }

            if (!sameShopKey(signLoc, containerLoc)) {
                p.sendMessage(ChatColor.RED + "Хранилище должно быть в той же SHOP-зоне, что и табличка.");
                signSelectionMap.remove(p.getUniqueId());
                e.setCancelled(true);
                return;
            }

            if (!zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), signLoc)) {
                p.sendMessage(ChatColor.RED + "Привязка доступна только владельцу этой SHOP-зоны.");
                signSelectionMap.remove(p.getUniqueId());
                e.setCancelled(true);
                return;
            }

            SignVariables vars = store.get(signLoc);
            if (vars != null && vars.getSignCategory() == SignCategory.SHOP_SOURCE) {
                List<String> text = safe4(vars.getSignText());
                String coords = containerLoc.getBlockX() + " " + containerLoc.getBlockY() + " " + containerLoc.getBlockZ();
                text.set(1, coords);
                vars.setSignText(text);

                store.put(signLoc, vars);
                if (onStored != null) onStored.accept(signLoc, vars);

                sign.setLine(1, coords);
                sign.update();

                // перед rebinding — убираем старые привязки этого source
                store.unbindAllForSourceSign(signLoc);

                // ВАЖНО: привязываем все половины (double chest)
                for (Location l : containerLocs) {
                    store.bindContainer(SignStore.keyLoc(l), signLoc);
                }

                p.sendMessage(ChatColor.GREEN + "Привязано хранилище: " + formatLocation(containerLoc));

                try { shopLists.updateAllRelatedShopListSigns(containerLoc); } catch (Throwable ignored) {}

                signSelectionMap.remove(p.getUniqueId());
                scroll.resumeScrolling(signLoc);
            } else {
                signSelectionMap.remove(p.getUniqueId());
            }
        }

        // Больше НЕ блокируем открытие привязанного контейнера не-владельцам —
        // просмотр разрешён всем, трогать вещи можно только с включённым
        // авто-списанием (см. AutoDebitService.onClick/onDrag, отдельные
        // обработчики в SignManager). Раньше тут был точно такой же запрет,
        // как в SignManager.onInventoryOpen — тот убрали, а этот, второй,
        // пропустили, из-за чего ошибка "открывать может только владелец"
        // продолжала появляться несмотря на удаление первого запрета.

        // кто угодно открыл => обновим списки (актуализирует остатки на табличках)
        try { shopLists.updateAllRelatedShopListSigns(containerLoc); } catch (Throwable ignored) {}
    }

    public void onInventoryClose(InventoryCloseEvent e) {
        var holder = e.getInventory().getHolder();
        List<Location> locs = resolveContainerLocations(holder);
        for (Location l : locs) {
            try { shopLists.updateAllRelatedShopListSigns(SignStore.keyLoc(l)); } catch (Throwable ignored) {}
        }
    }

    private static List<Location> resolveContainerLocations(Object holder) {
        if (holder instanceof Container c) {
            return List.of(c.getLocation());
        }
        if (holder instanceof org.bukkit.block.DoubleChest dc) {
            List<Location> out = new ArrayList<>(2);
            if (dc.getLeftSide() instanceof org.bukkit.block.Chest cl) out.add(cl.getLocation());
            if (dc.getRightSide() instanceof org.bukkit.block.Chest cr) out.add(cr.getLocation());
            return out;
        }
        return List.of();
    }

    public void onPlayerQuit(Player p) {
        if (p == null) return;
        signSelectionMap.remove(p.getUniqueId());
        playerScrollIndex.remove(p.getUniqueId());
        viewingDetailAt.remove(p.getUniqueId());
        wheelBrowse.remove(p.getUniqueId());
    }

    // ======= utils =======

    private static String safeLower(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s).trim();
        if (s.isEmpty()) return null;
        return s.toLowerCase(Locale.ROOT);
    }

    private static Integer parseIntLoose(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        s = s.replaceAll("[^0-9\\-]", "");
        if (s.isBlank() || s.equals("-")) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return null; }
    }

    private static List<String> safe4(List<String> in) {
        ArrayList<String> out = new ArrayList<>(4);
        if (in != null) out.addAll(in);
        while (out.size() < 4) out.add("");
        if (out.size() > 4) out.subList(4, out.size()).clear();
        return out;
    }

    private String formatLocation(Location loc) {
        if (loc == null) return "X:? Y:? Z:?";
        return String.format("X: %d Y: %d Z: %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private Block findNearestContainer(Location origin, Player p) {
        World world = origin.getWorld();
        if (world == null) return null;

        // Если зона на месте таблички не определяется (например, зоны ещё не прогрузились),
        // лучше честно сказать игроку, чем "сундуков нет".
        if (zoneManager.getShopZoneAt(origin) == null) {
            p.sendMessage(ChatColor.RED + "SHOP-зоны ещё не загружены. Попробуй через 5–10 секунд.");
            return null;
        }

        Block nearest = null;
        double minDist2 = Double.MAX_VALUE;

        boolean foundContainersButRejected = false;

        for (int x = -5; x <= 5; x++)
            for (int y = -5; y <= 5; y++)
                for (int z = -5; z <= 5; z++) {

                    Block b = world.getBlockAt(origin.getBlockX() + x, origin.getBlockY() + y, origin.getBlockZ() + z);
                    if (!(b.getState() instanceof Container)) continue;

                    // Главное правило: контейнер должен быть в ТОМ ЖЕ магазине, что и табличка.
                    if (!sameShopKey(origin, b.getLocation())) {
                        foundContainersButRejected = true;
                        continue;
                    }

                    double d2 = origin.distanceSquared(b.getLocation());
                    if (d2 < minDist2) {
                        minDist2 = d2;
                        nearest = b;
                    }
                }

        if (nearest == null && foundContainersButRejected) {
            p.sendMessage(ChatColor.RED + "Хранилище должно находиться в той же SHOP-зоне, что и табличка.");
        }

        return nearest;
    }

    /**
     * farlandsconnect GH #11 follow-up: the site's transaction note never
     * carried the shop's zone name, so the bank/orders history couldn't
     * group or even show which shop a sale came from. Reuses the same
     * SHOP zone lookup resolveShopOwnerName already falls back to.
     */
    private String resolveShopZoneName(Location loc) {
        try {
            ZoneInfo zone = zoneManager.getShopZoneAt(loc);
            return zone != null ? zone.getName() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * "Продажа в магазине «{zone}»: {qty}x {mat} игроку {buyer}" — the «zone»
     * segment is omitted entirely when the zone name can't be resolved,
     * keeping the note backward-compatible with economy.ts's SHOP_SALE_RE
     * (the zone-name capture group is optional there).
     */
    private String shopSaleNote(Location loc, int qty, Material mat, String buyerName) {
        String zoneName = resolveShopZoneName(loc);
        String zonePart = (zoneName != null && !zoneName.isBlank()) ? " «" + zoneName + "»" : "";
        return "Продажа в магазине" + zonePart + ": " + qty + "x " + mat.name() + (buyerName != null ? " игроку " + buyerName : "");
    }

    private String resolveShopOwnerName(Location loc, SignVariables sv) {
        // 1) самый надёжный источник в твоей текущей архитектуре — ownerName в SignVariables
        if (sv != null) {
            String o = sv.getOwnerName();
            if (o != null && !o.isBlank()) return o;
        }

        // 2) fallback: попробуем достать владельца из SHOP-зоны через reflection (если у зоны есть owner/ownerName)
        try {
            Object zone = zoneManager.getShopZoneAt(loc);
            if (zone != null) {
                for (String mName : new String[]{"getOwnerName", "getOwner", "ownerName", "owner"}) {
                    try {
                        var m = zone.getClass().getMethod(mName);
                        Object v = m.invoke(zone);
                        if (v instanceof String s && !s.isBlank()) return s;
                    } catch (NoSuchMethodException ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private boolean sameShopKey(Location a, Location b) {
        if (a == null || b == null) return false;
        World wa = a.getWorld();
        World wb = b.getWorld();
        if (wa == null || wb == null) return false;
        if (!wa.getUID().equals(wb.getUID())) return false;

        var za = zoneManager.getShopZoneAt(a);
        var zb = zoneManager.getShopZoneAt(b);
        if (za == null || zb == null) return false;

        String na = za.getName();
        String nb = zb.getName();
        if (na == null || nb == null) return false;

        return na.equalsIgnoreCase(nb);
    }

}
