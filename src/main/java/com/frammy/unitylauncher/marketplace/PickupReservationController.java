package com.frammy.unitylauncher.marketplace;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.auth.FarLandsApiClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * infra/pickup-reservation-design.md — бронь купленного с самовывозом товара
 * авто-лота прямо в сундуке продавца. Нужное количество помечается
 * PDC-тегом (id брони + ник покупателя), при необходимости стак
 * расщепляется. Метку защищают три слоя: клики/драг (здесь), воронки
 * (здесь + SignManager.onShopContainerAutoMove) и табличная торговля
 * (ShopListUpdater.takeVariant/итоги, AutoDebitService — через isReserved).
 *
 * Забор: клик покупателя по меченому слоту отменяется, перенос делаем сами
 * (так клик не доходит ни до ваниллы, ни до AutoDebitService — иначе
 * покупатель заплатил бы второй раз). Когда меток брони в сундуке не
 * осталось — POST collected.
 *
 * Всё состояние — на главном потоке; HTTP — на асинхронном.
 */
public final class PickupReservationController implements Listener {

    public static final NamespacedKey KEY_ID = NamespacedKey.fromString("unitylauncher:pickup.reservation");
    public static final NamespacedKey KEY_BUYER = NamespacedKey.fromString("unitylauncher:pickup.buyer");

    /** Стак помечен бронью самовывоза — его нельзя продавать/списывать/тянуть воронкой. */
    public static boolean isReserved(ItemStack it) {
        return reservationOf(it) != null;
    }

    private static String reservationOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY_ID, PersistentDataType.STRING);
    }

    private final UnityLauncher plugin;
    private final FarLandsApiClient api;
    private final AtomicBoolean busy = new AtomicBoolean();

    // Последний УСПЕШНЫЙ список (без просроченных), id -> бронь. null — ещё
    // ни одного успешного запроса: до этого ничего не снимаем.
    private Map<String, FarLandsApiClient.PickupReservation> pending;
    // Брони, метки которых стоят (или стояли) в сундуке: id -> сундук.
    // Только в памяти; после рестарта бронь с уже стоящими метками
    // считается применённой при первом же тике (см. apply).
    private final Map<String, Location> applied = new HashMap<>();
    private final Set<String> reported = new HashSet<>();
    private final Set<String> warned = new HashSet<>();

    public PickupReservationController(UnityLauncher plugin, FarLandsApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** Call once from onEnable, после регистрации Listener'а. No-op, если API-мост выключен. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, 0L, periodTicks);
    }

    private void pollOnce() {
        if (!busy.compareAndSet(false, true)) return;
        List<FarLandsApiClient.PickupReservation> list = api.fetchPendingPickupReservations();
        // Ошибка запроса — не "броней нет", метки не трогаем.
        if (list == null) { busy.set(false); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { tick(list); } finally { busy.set(false); }
        });
    }

    private void tick(List<FarLandsApiClient.PickupReservation> list) {
        long now = System.currentTimeMillis();
        Map<String, FarLandsApiClient.PickupReservation> live = new LinkedHashMap<>();
        for (var r : list) if (r.expiresAtMs() > now) live.put(r.id(), r);
        pending = live;

        World world = resolveWorld();
        if (world == null) return;

        // Снять: бронь собрана/просрочена/отменена. Сундук в незагруженном
        // чанке — ждём, пока загрузится (или снимет onOpen).
        for (Iterator<Map.Entry<String, Location>> it = applied.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (live.containsKey(e.getKey())) continue;
            Location l = e.getValue();
            if (!world.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            Inventory inv = inventoryAt(l);
            if (inv != null) stripAll(inv, e.getKey()::equals);
            it.remove();
        }

        // Применить — по порядку сайта (createdAt), каждая берёт только СВОБОДНЫЙ остаток.
        for (var r : live.values()) {
            if (applied.containsKey(r.id()) || r.quantity() <= 0) continue;
            if (!world.isChunkLoaded(r.chestX() >> 4, r.chestZ() >> 4)) continue;
            Location l = new Location(world, r.chestX(), r.chestY(), r.chestZ());
            Inventory inv = inventoryAt(l);
            if (inv == null) { warnOnce(r.id(), "по координатам " + fmt(r) + " нет сундука"); continue; }
            // Метки уже стоят (рестарт плагина) — не доливаем, иначе после
            // частичного забора бронь выросла бы обратно.
            if (countMarked(inv, r.id()) > 0) { applied.put(r.id(), l); continue; }
            Material mat = resolveMaterial(r);
            if (mat == null) { warnOnce(r.id(), "неизвестный материал '" + r.material() + "'"); continue; }
            if (!mark(inv, mat, r)) {
                warnOnce(r.id(), "не хватает свободного " + mat + " x" + r.quantity() + " (или слота под расщепление) в " + fmt(r) + ", повторяю");
                continue;
            }
            applied.put(r.id(), l);
            plugin.getLogger().info("[PickupReservation] бронь " + r.id() + ": помечено " + r.quantity() + "x " + mat + " в " + fmt(r) + " для " + r.buyerUsername());
        }
    }

    /** Всё-или-ничего: сначала план по слотам, потом изменения. */
    private boolean mark(Inventory inv, Material mat, FarLandsApiClient.PickupReservation r) {
        List<Integer> slots = new ArrayList<>();
        int have = 0;
        for (int i = 0; i < inv.getSize() && have < r.quantity(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat || isReserved(it)) continue;
            slots.add(i);
            have += it.getAmount();
        }
        if (have < r.quantity()) return false;

        int over = have - r.quantity(); // столько вернуть свободным из последнего слота
        int free = over > 0 ? inv.firstEmpty() : -1;
        if (over > 0 && free < 0) return false;

        for (int slot : slots) {
            ItemStack it = inv.getItem(slot);
            if (over > 0 && slot == slots.get(slots.size() - 1)) {
                ItemStack rest = it.clone();
                rest.setAmount(over);
                inv.setItem(free, rest);
                it.setAmount(it.getAmount() - over);
            }
            inv.setItem(slot, tagged(it, r.id(), r.buyerUsername()));
        }
        return true;
    }

    // ===== события =====

    /**
     * LOW + отмена: клик по меченому слоту не доходит ни до ваниллы, ни до
     * AutoDebitService (тот ignoreCancelled). Любой тип клика — обычный,
     * шифт, цифра, F, Q — ловится одним правилом "текущий предмет меченый".
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();

        // Двойной клик собирает похожие стаки со всего окна — на всякий
        // случай не даём, если в сундуке есть меченое того же материала.
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR && e.getCursor() != null) {
            Material m = e.getCursor().getType();
            for (ItemStack it : top.getContents()) {
                if (it != null && it.getType() == m && isReserved(it)) { e.setCancelled(true); return; }
            }
            return;
        }

        ItemStack cur = e.getCurrentItem();
        String id = reservationOf(cur);
        if (id == null || e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;
        e.setCancelled(true);

        var r = pending == null ? null : pending.get(id);
        if (r == null || r.expiresAtMs() <= System.currentTimeMillis()) {
            if (pending == null) {
                p.sendMessage(ChatColor.YELLOW + "Бронь ещё проверяется, попробуй через пару секунд.");
            } else {
                // Брони больше нет (или просрочена) — снимаем метку на месте.
                int slot = e.getSlot();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (id.equals(reservationOf(top.getItem(slot)))) top.setItem(slot, stripped(top.getItem(slot)));
                    p.updateInventory();
                });
                p.sendMessage(ChatColor.YELLOW + "Бронь на этот товар уже не действует.");
            }
            return;
        }
        if (!p.getName().equalsIgnoreCase(r.buyerUsername())) {
            p.sendMessage(ChatColor.RED + "Этот товар забронирован покупателем с сайта.");
            return;
        }
        int slot = e.getSlot();
        // Как в AutoDebitService (GH#14): правим инвентарь тиком позже, после
        // собственной синхронизации Paper по отменённому клику.
        Bukkit.getScheduler().runTask(plugin, () -> giveToBuyer(p, top, slot, id));
    }

    private void giveToBuyer(Player p, Inventory inv, int slot, String id) {
        ItemStack it = inv.getItem(slot);
        if (!p.isOnline() || !id.equals(reservationOf(it))) return;

        inv.setItem(slot, null);
        int left = p.getInventory().addItem(stripped(it)).values().stream().mapToInt(ItemStack::getAmount).sum();
        if (left > 0) {
            ItemStack back = it.clone(); // с меткой
            back.setAmount(left);
            inv.setItem(slot, back);
            p.sendMessage(ChatColor.YELLOW + "Инвентарь полон — остаток брони ждёт в сундуке.");
        }
        p.updateInventory();

        if (countMarked(inv, id) == 0 && reported.add(id)) {
            api.reportPickupCollected(id);
            p.sendMessage(ChatColor.GREEN + "Заказ получен.");
            plugin.getLogger().info("[PickupReservation] бронь " + id + " забрана " + p.getName());
        }
    }

    /** Меченое на курсор попасть не может, но если вдруг — не даём растащить. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (isReserved(e.getOldCursor())) e.setCancelled(true);
    }

    /** Воронки/выбрасыватели не трогают меченое никогда. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (isReserved(e.getItem())) e.setCancelled(true);
    }

    /** Ленивое снятие "бесхозных" меток (рестарт, сундук в незагруженном чанке во время снятия). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (pending == null) return;
        stripAll(e.getView().getTopInventory(), id -> !pending.containsKey(id));
    }

    // ===== помощники =====

    private static int countMarked(Inventory inv, String id) {
        int n = 0;
        for (ItemStack it : inv.getContents()) if (id.equals(reservationOf(it))) n += it.getAmount();
        return n;
    }

    private static void stripAll(Inventory inv, java.util.function.Predicate<String> which) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            String id = reservationOf(it);
            if (id != null && which.test(id)) inv.setItem(i, stripped(it));
        }
    }

    private static ItemStack tagged(ItemStack it, String id, String buyer) {
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_ID, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(KEY_BUYER, PersistentDataType.STRING, buyer);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack stripped(ItemStack it) {
        ItemStack out = it.clone();
        ItemMeta meta = out.getItemMeta();
        meta.getPersistentDataContainer().remove(KEY_ID);
        meta.getPersistentDataContainer().remove(KEY_BUYER);
        out.setItemMeta(meta);
        return out;
    }

    private static Inventory inventoryAt(Location l) {
        return l.getBlock().getState(false) instanceof Container c ? c.getInventory() : null;
    }

    private Material resolveMaterial(FarLandsApiClient.PickupReservation r) {
        // Как PhantomDeliveryController.resolveMaterial: materialKey, если есть, иначе имя каталога.
        String key = r.materialKey() != null ? r.materialKey() : r.material().trim().replace(' ', '_');
        Material m = Material.matchMaterial(key);
        return m != null && m.isItem() ? m : null;
    }

    private World resolveWorld() {
        World w = Bukkit.getWorld("world");
        if (w != null) return w;
        List<World> all = Bukkit.getWorlds();
        return all.isEmpty() ? null : all.get(0);
    }

    private void warnOnce(String id, String msg) {
        if (warned.add(id + msg)) plugin.getLogger().warning("[PickupReservation] бронь " + id + ": " + msg);
    }

    private static String fmt(FarLandsApiClient.PickupReservation r) {
        return "(" + r.chestX() + ", " + r.chestY() + ", " + r.chestZ() + ")";
    }
}
