package com.frammy.unitylauncher.signs.features.shop;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Lets a non-owner open a SHOP_SOURCE's linked chest and, if they've opted
 * in via ATM preferences ("авто-списание"), take items straight out of it —
 * paying for exactly what actually reaches their possession, not what
 * merely passes through their cursor.
 *
 * Pricing model: the "Кол-во:/Цена:" pair on the SHOP_SOURCE sign is a flat
 * PER-UNIT RATE for the whole chest — it does NOT pin the shop to one
 * specific material. A chest can hold several different item types at once,
 * every one of them sold at the same rate; this is what makes mixing item
 * types in the chest a completely normal, supported thing instead of
 * something that needs detecting/blocking.
 *
 * Design (see chat — worked out with the user over a few rounds, not
 * guessed blind; revised again after the first version's click-type
 * predictions kept getting bugs filed against them):
 *  - Picking an item OUT of the chest onto an empty cursor is free/instant,
 *    but the amount taken is clamped up front to what the player could ever
 *    afford (cash + bank combined) — guarantees the later "finalize
 *    whatever's left on the cursor" step can never fail for funds.
 *  - Placing cursor units into the PLAYER's own inventory is the actual
 *    purchase — charged per unit actually placed.
 *  - Placing units BACK into the same chest is a free return, but ONLY for
 *    materials currently tracked as this player's own unpaid pickup from
 *    THIS chest — anything else attempting to go INTO the chest from a
 *    non-owner (shift-clicking a random inventory item at it, dragging junk
 *    onto it) is blocked outright. Customers buy, they don't stock shelves.
 *  - Shift-click and double-click are handled as their own explicit,
 *    deterministic cases (see handleShiftFromChest/handleDoubleClick) —
 *    double-click in particular pulls from the ENTIRE inventory view
 *    (chest AND the player's own pre-owned stack), so it's computed
 *    manually: whatever comes from the player's own inventory is free,
 *    whatever comes from the chest is priced and clamped.
 *  - Multi-slot drag (InventoryDragEvent) is resolved by comparing, per
 *    dragged-into slot, whether it's on the chest side (return — gated the
 *    same as any other deposit) or the player side (purchase — charged for
 *    the total landing there).
 *  - Everything else (clicks that don't fit a case above — clicking outside
 *    the window, odd click types) is settled by comparing actual chest
 *    contents + cursor contents a tick later against a snapshot from just
 *    before, rather than trying to predict what happened.
 *  - Whatever's still unpaid when the window closes, the player drops it
 *    (Q), or disconnects gets charged then — vanilla guarantees a cursor
 *    item lands in the inventory or on the ground in all of those cases,
 *    and the up-front clamp means that charge can't fail.
 */
public final class AutoDebitService {

    private final UnityLauncher plugin;
    private final SignStore store;
    private final ZoneManager zoneManager;

    public AutoDebitService(UnityLauncher plugin, SignStore store, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.store = store;
        this.zoneManager = zoneManager;
    }

    // ===== per-open-session state =====

    private final Map<UUID, Boolean> autoDebitCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> priorityCache = new ConcurrentHashMap<>();

    // Один активный "неоплаченный" сундук на игрока — цена в нём одна на все
    // материалы (см. класс-javadoc), поэтому chestLoc/pricePerUnit не нужно
    // дублировать по материалам, а вот количество по каждому материалу — да
    // (игрок вполне может держать в процессе покупки сразу два разных товара).
    private final Map<UUID, Location> unpaidChest = new ConcurrentHashMap<>();
    private final Map<UUID, Double> unpaidPricePerUnit = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Material, Integer>> unpaidByMaterial = new ConcurrentHashMap<>();
    // Не даём копиться нескольким reconcile-задачам на одного игрока за один тик.
    private final Map<UUID, Boolean> reconcilePending = new ConcurrentHashMap<>();

    // ===== preferences (см. ATM "Настройки") =====

    public void setAutoDebitEnabled(String playerName, boolean enabled) {
        UnityCommands.getInstance().mergeAndUpdatePlayerData(playerName, "GeneralData", Map.of("autoDebitEnabled", enabled));
    }

    public void setPaymentPriority(String playerName, String priority) {
        UnityCommands.getInstance().mergeAndUpdatePlayerData(playerName, "GeneralData", Map.of("paymentPriority", priority));
    }

    public record Prefs(boolean autoDebitEnabled, String paymentPriority) {}

    /** Блокирующий — используется ATM "Настройки", вызывается редко (раз за вход в меню). */
    public Prefs getPreferencesBlocking(String playerName) {
        Map<String, Object> map = UnityCommands.getInstance()
                .getJsonFieldValues("Users", "GeneralData", "Name", playerName, List.of("autoDebitEnabled", "paymentPriority"));
        boolean enabled = Boolean.TRUE.equals(map.get("autoDebitEnabled"));
        String priority = (map.get("paymentPriority") instanceof String s && !s.isBlank()) ? s : "CASH";
        return new Prefs(enabled, priority);
    }

    /** Блокирующий (единожды за открытие сессии, см. кэш) — тот же паттерн, что и остальной синхронный DB-доступ в этом проекте. */
    private void ensurePrefsCachedBlocking(Player p) {
        UUID id = p.getUniqueId();
        if (autoDebitCache.containsKey(id)) return;
        Map<String, Object> map = UnityCommands.getInstance()
                .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), List.of("autoDebitEnabled", "paymentPriority"));
        autoDebitCache.put(id, Boolean.TRUE.equals(map.get("autoDebitEnabled")));
        priorityCache.put(id, (map.get("paymentPriority") instanceof String s && !s.isBlank()) ? s : "CASH");
    }

    // ===== click handling =====

    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory topInv = e.getView().getTopInventory();
        Location chestLoc = chestLocationOf(topInv);
        if (chestLoc == null) return;

        Location sourceLoc = store.sourceSignByContainer(chestLoc);
        if (sourceLoc == null) return;
        SignVariables sv = store.get(sourceLoc);
        if (sv == null || sv.getSignCategory() != SignCategory.SHOP_SOURCE) return;
        if (zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), sourceLoc)) return; // владельцу — без ограничений

        ensurePrefsCachedBlocking(p);
        UUID id = p.getUniqueId();
        if (!Boolean.TRUE.equals(autoDebitCache.get(id))) {
            e.setCancelled(true); // авто-списание выключено — только просмотр
            return;
        }

        Double pricePerUnit = pricePerUnit(sv);
        if (pricePerUnit == null) {
            e.setCancelled(true); // цена не настроена вообще
            return;
        }

        boolean clickedChest = e.getClickedInventory() != null && e.getClickedInventory().equals(topInv);
        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        ClickType click = e.getClick();

        // ---- Двойной клик: тянет из ВСЕГО инвентаря сразу (сундук + уже
        // принадлежащее игроку) — считаем сами, своё бесплатно, из сундука платно ----
        if (click == ClickType.DOUBLE_CLICK) {
            handleDoubleClick(e, p, chestLoc, topInv, pricePerUnit);
            return;
        }

        // ---- Шифт-клик ПО ТОВАРУ В СУНДУКЕ: сразу в инвентарь, платно ----
        if (clickedChest && (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT)
                && clicked != null && !clicked.getType().isAir()) {
            handleShiftFromChest(e, p, chestLoc, topInv, clicked.getType(), pricePerUnit);
            return;
        }

        // ---- Шифт-клик по СВОЕМУ инвентарю (кладёт В сундук) — только возврат уже взятого, и не больше, чем реально должен ----
        if (!clickedChest && (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT)
                && clicked != null && !clicked.getType().isAir()) {
            if (!canReturn(id, chestLoc, clicked.getType(), clicked.getAmount())) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "В этот сундук можно вернуть только то, что реально должен: "
                        + trackedAmount(id, clicked.getType()) + "x.");
                return;
            }
            scheduleReconcile(p, chestLoc);
            return;
        }

        // ---- Обычный клик по товару в сундуке, курсор пуст: забрать (клэмп по деньгам) ----
        if (clickedChest && (click == ClickType.LEFT || click == ClickType.RIGHT)
                && clicked != null && !clicked.getType().isAir()
                && (cursor == null || cursor.getType().isAir())) {
            handlePickup(e, p, chestLoc, topInv, clicked.getType(), pricePerUnit);
            return;
        }

        // ---- Q / Ctrl+Q по товару В СУНДУКЕ: выбрасывает его на землю, минуя
        // курсор — покупка+бросок одним действием, платно и с клэмпом ----
        if (clickedChest && (click == ClickType.DROP || click == ClickType.CONTROL_DROP)
                && clicked != null && !clicked.getType().isAir()) {
            handleDropFromChest(e, p, chestLoc, topInv, clicked.getType(), pricePerUnit);
            return;
        }

        // ---- Клик, кладущий курсор В сундук (любой слот сундука, курсор непуст) — только возврат, и не больше, чем реально должен ----
        if (clickedChest && cursor != null && !cursor.getType().isAir()) {
            // ЛКМ кладёт весь курсор, ПКМ — обычно 1 шт (или доливает существующий
            // слот) — берём точную границу запроса, а не весь курсор с запасом,
            // чтобы не блокировать легитимный частичный возврат по ПКМ.
            int requesting = (click == ClickType.RIGHT) ? 1 : cursor.getAmount();
            if (!canReturn(id, chestLoc, cursor.getType(), requesting)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "В этот сундук можно вернуть только то, что реально должен: "
                        + trackedAmount(id, cursor.getType()) + "x.");
                return;
            }
            scheduleReconcile(p, chestLoc);
            return;
        }

        // ---- Всё остальное (клик по своему инвентарю с курсором — покупка,
        // клик мимо слота — выброс, и т.п.) — не гадаем, сверяем по факту через тик.
        if (totalUnpaid(id) > 0 && chestLoc.equals(unpaidChest.get(id))) {
            scheduleReconcile(p, chestLoc);
        }
    }

    /**
     * Q/Ctrl+Q на слот в сундуке уходит через InventoryClickEvent (ClickType
     * DROP/CONTROL_DROP), а не через PlayerDropItemEvent — тот фиксирует
     * дропы только когда никакой инвентарь не открыт. Без этого случая товар
     * уходил бы на землю прямо из сундука бесплатно, минуя весь учёт.
     */
    private void handleDropFromChest(InventoryClickEvent e, Player p, Location chestLoc, Inventory topInv, Material material, double pricePerUnit) {
        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        int wouldTake = (e.getClick() == ClickType.CONTROL_DROP) ? clicked.getAmount() : 1;
        int affordable = maxAffordable(p, pricePerUnit);
        int take = Math.min(wouldTake, affordable);
        if (take <= 0) {
            p.sendMessage(ChatColor.RED + "Недостаточно средств.");
            return;
        }
        double cost = round2(take * pricePerUnit);
        if (!charge(p, cost, priorityCache.getOrDefault(p.getUniqueId(), "CASH"), chestLoc)) {
            p.sendMessage(ChatColor.RED + "Недостаточно средств.");
            return;
        }
        removeMaterialFromChest(topInv, material, take);
        p.getWorld().dropItemNaturally(p.getLocation(), new ItemStack(material, take));
        p.sendMessage(ChatColor.GRAY + "Куплено и выброшено: " + ChatColor.YELLOW + take + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
    }

    private void handleShiftFromChest(InventoryClickEvent e, Player p, Location chestLoc, Inventory topInv, Material material, double pricePerUnit) {
        e.setCancelled(true);
        ItemStack clicked = e.getCurrentItem();
        int space = freeSpaceFor(p.getInventory(), material);
        int want = Math.min(clicked.getAmount(), space);
        int affordable = maxAffordable(p, pricePerUnit);
        int take = Math.min(want, affordable);
        if (take <= 0) {
            p.sendMessage(ChatColor.RED + "Недостаточно средств.");
            return;
        }
        double cost = round2(take * pricePerUnit);
        if (!charge(p, cost, priorityCache.getOrDefault(p.getUniqueId(), "CASH"), chestLoc)) {
            p.sendMessage(ChatColor.RED + "Недостаточно средств.");
            return;
        }
        removeMaterialFromChest(topInv, material, take);
        giveToPlayer(p, material, take);
        p.sendMessage(ChatColor.GRAY + "Куплено: " + ChatColor.YELLOW + take + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
    }

    private void handlePickup(InventoryClickEvent e, Player p, Location chestLoc, Inventory topInv, Material material, double pricePerUnit) {
        ItemStack clicked = e.getCurrentItem();
        int wouldTake = e.getClick() == ClickType.LEFT ? clicked.getAmount() : (clicked.getAmount() + 1) / 2;
        int affordable = maxAffordable(p, pricePerUnit);
        int take = Math.min(wouldTake, affordable);
        if (take <= 0) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Недостаточно средств даже на 1 шт.");
            return;
        }
        if (take == wouldTake) {
            // хватает и на дефолтное поведение клика — не мешаем самому переносу.
            trackPickup(p, chestLoc, material, take, pricePerUnit);
            return;
        }
        e.setCancelled(true);
        removeMaterialFromChest(topInv, material, take);
        p.setItemOnCursor(new ItemStack(material, take));
        trackPickup(p, chestLoc, material, take, pricePerUnit);
    }

    /**
     * Двойной клик в вании собирает совпадающий материал ИЗ ВСЕГО обзора
     * инвентаря (сундук + инвентарь игрока) на курсор, до размера стака.
     * Если позволить дефолтному поведению — на курсоре окажется
     * неразличимая смесь "уже своего" и "из сундука", и посчитать, сколько
     * из этого реально нужно оплатить, станет невозможно (это и было
     * причиной бага). Считаем и переносим сами: сначала бесплатно из
     * своего инвентаря, остаток — из сундука, с клэмпом по деньгам.
     */
    private void handleDoubleClick(InventoryClickEvent e, Player p, Location chestLoc, Inventory topInv, double pricePerUnit) {
        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        Material mat = (clicked != null && !clicked.getType().isAir()) ? clicked.getType()
                : (cursor != null && !cursor.getType().isAir() ? cursor.getType() : null);
        if (mat == null) return;

        e.setCancelled(true);

        int cursorNow = (cursor != null && cursor.getType() == mat) ? cursor.getAmount() : 0;
        int need = mat.getMaxStackSize() - cursorNow;
        if (need <= 0) return;

        int fromOwnInv = takeFromPlayerInventory(p.getInventory(), mat, need);
        int stillNeed = need - fromOwnInv;

        int inChest = countMaterialInChestBlock(chestLoc, mat);
        int wantFromChest = Math.min(stillNeed, inChest);
        int affordable = maxAffordable(p, pricePerUnit);
        int takeFromChest = Math.min(wantFromChest, affordable);

        int totalCursor = cursorNow + fromOwnInv + takeFromChest;
        if (totalCursor > 0) {
            if (takeFromChest > 0) removeMaterialFromChest(topInv, mat, takeFromChest);
            p.setItemOnCursor(new ItemStack(mat, totalCursor));
        }
        if (takeFromChest > 0) {
            trackPickup(p, chestLoc, mat, takeFromChest, pricePerUnit);
        }
        if (wantFromChest > takeFromChest) {
            p.sendMessage(ChatColor.YELLOW + "Не хватило средств добрать до полного стака — взято "
                    + ChatColor.WHITE + totalCursor + "x" + ChatColor.YELLOW + " вместо " + (cursorNow + need) + "x.");
        }
    }

    // ===== drag handling =====

    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory topInv = e.getView().getTopInventory();
        Location chestLoc = chestLocationOf(topInv);
        if (chestLoc == null) return;

        Location sourceLoc = store.sourceSignByContainer(chestLoc);
        if (sourceLoc == null) return;
        SignVariables sv = store.get(sourceLoc);
        if (sv == null || sv.getSignCategory() != SignCategory.SHOP_SOURCE) return;
        if (zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), sourceLoc)) return;

        int chestSize = topInv.getSize();
        boolean touchesChest = e.getRawSlots().stream().anyMatch(slot -> slot < chestSize);
        if (!touchesChest) return;

        ensurePrefsCachedBlocking(p);
        UUID id = p.getUniqueId();
        if (!Boolean.TRUE.equals(autoDebitCache.get(id))) {
            e.setCancelled(true);
            return;
        }

        Double pricePerUnit = pricePerUnit(sv);
        if (pricePerUnit == null) {
            e.setCancelled(true);
            return;
        }

        Material dragMat = e.getOldCursor() != null ? e.getOldCursor().getType() : null;
        if (dragMat == null || dragMat.isAir()) return;

        Inventory bottomInv = e.getView().getBottomInventory();
        int intoChest = 0, intoInv = 0;

        for (var entry : e.getNewItems().entrySet()) {
            int rawSlot = entry.getKey();
            ItemStack newStack = entry.getValue();
            int newAmount = (newStack != null) ? newStack.getAmount() : 0;

            boolean isChestSlot = rawSlot < chestSize;
            ItemStack before = isChestSlot ? topInv.getItem(rawSlot) : bottomInv.getItem(rawSlot - chestSize);
            int beforeAmount = (before != null && before.getType() == dragMat) ? before.getAmount() : 0;
            int delta = newAmount - beforeAmount;
            if (delta <= 0) continue;

            if (isChestSlot) intoChest += delta; else intoInv += delta;
        }

        if (intoChest == 0 && intoInv == 0) return;

        if (intoChest > 0 && !canReturn(id, chestLoc, dragMat, intoChest)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "В этот сундук можно вернуть только то, что реально должен: "
                    + trackedAmount(id, dragMat) + "x.");
            return;
        }

        if (intoInv > 0) {
            int affordable = maxAffordable(p, pricePerUnit);
            if (intoInv > affordable) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Недостаточно средств на такое количество.");
                return;
            }
            double cost = round2(intoInv * pricePerUnit);
            String priority = priorityCache.getOrDefault(id, "CASH");
            if (!charge(p, cost, priority, chestLoc)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Недостаточно средств.");
                return;
            }
            p.sendMessage(ChatColor.GRAY + "Куплено: " + ChatColor.YELLOW + intoInv + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
            untrackUnits(p, dragMat, intoInv); // если часть уже числилась неоплаченной (была на курсоре из pickup) — не даём reconcile посчитать её ещё раз
        }

        if (intoChest > 0) {
            untrackUnits(p, dragMat, intoChest);
        }
    }

    // ===== drop handling =====

    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Material mat = e.getItemDrop().getItemStack().getType();

        Map<Material, Integer> tracked = unpaidByMaterial.get(id);
        if (tracked == null) return;
        int unpaid = tracked.getOrDefault(mat, 0);
        if (unpaid <= 0) return;

        int dropped = e.getItemDrop().getItemStack().getAmount();
        int chargeable = Math.min(dropped, unpaid);
        if (chargeable <= 0) return;

        Double ppu = unpaidPricePerUnit.get(id);
        Location chestLoc = unpaidChest.get(id);
        if (ppu == null || chestLoc == null) return;
        double cost = round2(chargeable * ppu);
        String priority = priorityCache.getOrDefault(id, "CASH");

        if (!charge(p, cost, priority, chestLoc)) {
            p.sendMessage(ChatColor.RED + "Не удалось списать за выброшенный товар (" + chargeable + "x) — недостаточно средств.");
        } else {
            p.sendMessage(ChatColor.GRAY + "Списано за выброшенный товар: " + ChatColor.YELLOW + chargeable + "x"
                    + ChatColor.GRAY + " — " + ChatColor.GREEN + cost + " Ⓕ");
        }
        untrackUnits(p, mat, chargeable);
    }

    // ===== reconcile (для всего, что не подошло под явные случаи выше) =====

    private void scheduleReconcile(Player p, Location chestLoc) {
        UUID id = p.getUniqueId();
        if (Boolean.TRUE.equals(reconcilePending.get(id))) return;
        reconcilePending.put(id, true);

        Map<Material, Integer> tracked = unpaidByMaterial.getOrDefault(id, Map.of());
        Map<Material, Integer> before = new HashMap<>();
        for (Material m : tracked.keySet()) before.put(m, countMaterialInChestBlock(chestLoc, m));

        Bukkit.getScheduler().runTask(plugin, () -> {
            reconcilePending.remove(id);
            reconcileNow(p, chestLoc, before);
        });
    }

    private void reconcileNow(Player p, Location chestLoc, Map<Material, Integer> chestCountsBefore) {
        UUID id = p.getUniqueId();
        if (!chestLoc.equals(unpaidChest.get(id))) return;
        Double ppu = unpaidPricePerUnit.get(id);
        if (ppu == null) return;

        Map<Material, Integer> tracked = unpaidByMaterial.get(id);
        if (tracked == null || tracked.isEmpty()) return;

        for (Material material : List.copyOf(tracked.keySet())) {
            int unpaid = tracked.getOrDefault(material, 0);
            if (unpaid <= 0) continue;

            int before = chestCountsBefore.getOrDefault(material, countMaterialInChestBlock(chestLoc, material));
            int after = countMaterialInChestBlock(chestLoc, material);
            int returned = Math.max(0, after - before);
            if (returned > 0) {
                untrackUnits(p, material, Math.min(returned, unpaid));
                unpaid = unpaidByMaterial.getOrDefault(id, Map.of()).getOrDefault(material, 0);
                if (unpaid <= 0) continue;
            }

            // Что осталось из неоплаченного, не вернулось в сундук и не висит
            // на курсоре — значит уже физически у игрока (или было выброшено —
            // тогда onDrop уже списал и уменьшил unpaid ДО этой проверки).
            ItemStack cursor = p.getItemOnCursor();
            int stillOnCursor = (cursor != null && cursor.getType() == material) ? cursor.getAmount() : 0;
            int committed = Math.max(0, unpaid - stillOnCursor);
            if (committed <= 0) continue;

            double cost = round2(committed * ppu);
            String priority = priorityCache.getOrDefault(id, "CASH");
            if (charge(p, cost, priority, chestLoc)) {
                p.sendMessage(ChatColor.GRAY + "Куплено: " + ChatColor.YELLOW + committed + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
            } else {
                p.sendMessage(ChatColor.RED + "Не удалось списать за товар (" + committed + "x) — недостаточно средств.");
            }
            untrackUnits(p, material, committed);
        }
    }

    // ===== finalize (close / quit) =====

    /** Вызывать из onInventoryClose/onPlayerQuit — то, что осталось непроплаченным на курсоре, ванилла всё равно доставит игроку. */
    public void finalizeUnpaid(Player p) {
        UUID id = p.getUniqueId();
        autoDebitCache.remove(id);
        priorityCache.remove(id);

        Map<Material, Integer> tracked = unpaidByMaterial.get(id);
        Double ppu = unpaidPricePerUnit.get(id);
        Location chestLoc = unpaidChest.get(id);
        if (tracked == null || tracked.isEmpty() || ppu == null || chestLoc == null) { clearTracking(id); return; }

        String priority = priorityCache.getOrDefault(id, "CASH");
        for (var entry : Map.copyOf(tracked).entrySet()) {
            int unpaid = entry.getValue();
            if (unpaid <= 0) continue;
            double cost = round2(unpaid * ppu);
            boolean ok = charge(p, cost, priority, chestLoc);
            if (p.isOnline()) {
                if (ok) {
                    p.sendMessage(ChatColor.GRAY + "Списано за товар при закрытии: " + ChatColor.YELLOW + unpaid + "x"
                            + ChatColor.GRAY + " (" + entry.getKey().name() + ") — " + ChatColor.GREEN + cost + " Ⓕ");
                } else {
                    p.sendMessage(ChatColor.RED + "Не удалось списать за товар (" + unpaid + "x " + entry.getKey().name() + ") — недостаточно средств.");
                }
            }
        }
        clearTracking(id);
    }

    private void clearTracking(UUID id) {
        unpaidByMaterial.remove(id);
        unpaidChest.remove(id);
        unpaidPricePerUnit.remove(id);
        reconcilePending.remove(id);
    }

    private void trackPickup(Player p, Location chestLoc, Material material, int take, double pricePerUnit) {
        UUID id = p.getUniqueId();
        unpaidChest.put(id, chestLoc);
        unpaidPricePerUnit.put(id, pricePerUnit);
        unpaidByMaterial.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).merge(material, take, Integer::sum);
    }

    private void untrackUnits(Player p, Material material, int amount) {
        if (amount <= 0) return;
        UUID id = p.getUniqueId();
        Map<Material, Integer> tracked = unpaidByMaterial.get(id);
        if (tracked == null) return;
        int left = tracked.getOrDefault(material, 0) - amount;
        if (left <= 0) tracked.remove(material); else tracked.put(material, left);
        if (tracked.isEmpty()) {
            unpaidByMaterial.remove(id);
            unpaidChest.remove(id);
            unpaidPricePerUnit.remove(id);
        }
    }

    private int totalUnpaid(UUID id) {
        Map<Material, Integer> tracked = unpaidByMaterial.get(id);
        if (tracked == null) return 0;
        int sum = 0;
        for (int v : tracked.values()) sum += v;
        return sum;
    }

    /** true если этот материал у игрока сейчас числится неоплаченным взятым из этого же сундука — только такое разрешаем класть обратно. */
    /**
     * true только если amount (не просто "хоть сколько-то") этого материала
     * реально числится неоплаченным взятым игроком из этого же сундука.
     * Без учёта точного количества игрок мог собрать двойным кликом стак из
     * "своего + из сундука" и одним действием сдать ВЕСЬ стак обратно —
     * зачлось бы как возврат, и его собственные (уже принадлежавшие ему)
     * единицы молча оседали бы в чужом сундуке без всякой компенсации.
     */
    private boolean canReturn(UUID id, Location chestLoc, Material material, int amount) {
        if (amount <= 0) return true;
        if (!chestLoc.equals(unpaidChest.get(id))) return false;
        return trackedAmount(id, material) >= amount;
    }

    private int trackedAmount(UUID id, Material material) {
        Map<Material, Integer> tracked = unpaidByMaterial.get(id);
        return tracked != null ? tracked.getOrDefault(material, 0) : 0;
    }

    // ===== payment =====

    private int maxAffordable(Player p, double pricePerUnit) {
        if (pricePerUnit <= 0) return Integer.MAX_VALUE;
        double cash = plugin.moneyManager.getInventoryCash(p);
        Double bank = UnityCommands.getInstance().getBalance(p.getName());
        double total = cash + (bank != null ? bank : 0.0);
        return (int) Math.floor(total / pricePerUnit + 1e-9);
    }

    /**
     * Списывает у покупателя И зачисляет продавцу — раньше зачисления не
     * было вовсе (в отличие от покупки через табличку, где applyMoneyDelta
     * вызывается на обе стороны сделки): деньги покупателя просто исчезали,
     * владелец магазина ничего не получал.
     */
    private boolean charge(Player p, double amount, String priority, Location chestLoc) {
        if (amount <= 0) return true;
        boolean cashFirst = !"BANK".equalsIgnoreCase(priority);
        boolean ok = cashFirst
                ? (plugin.moneyManager.spendCash(p, amount) || chargeBank(p, amount))
                : (chargeBank(p, amount) || plugin.moneyManager.spendCash(p, amount));
        if (ok) creditSeller(chestLoc, amount);
        return ok;
    }

    private boolean chargeBank(Player p, double amount) {
        Double bal = UnityCommands.getInstance().getBalance(p.getName());
        if (bal == null || bal < amount - 1e-9) return false;
        plugin.moneyManager.withdraw(p.getName(), amount); // async запись — баланс уже проверен синхронно выше
        return true;
    }

    /** Зеркалит handleShopSourceBuyClick: зачисление владельцу магазина + запись транзакции (applyMoneyDelta сам её шлёт на сайт). */
    private void creditSeller(Location chestLoc, double amount) {
        if (amount <= 0 || chestLoc == null) return;
        String ownerName = resolveShopOwnerName(chestLoc);
        if (ownerName == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { UnityCommands.getInstance().applyMoneyDelta(ownerName, amount); }
            catch (Throwable ignored) {}
        });
    }

    private String resolveShopOwnerName(Location chestLoc) {
        Location sourceLoc = store.sourceSignByContainer(chestLoc);
        if (sourceLoc == null) return null;
        SignVariables sv = store.get(sourceLoc);
        if (sv == null) return null;

        String owner = sv.getOwnerName();
        if (owner != null && !owner.isBlank()) return owner;

        try {
            Object zone = zoneManager.getShopZoneAt(chestLoc);
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

    // ===== inventory/material helpers =====

    private static int freeSpaceFor(PlayerInventory inv, Material mat) {
        int space = 0;
        int max = mat.getMaxStackSize();
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) space += max;
            else if (it.getType() == mat) space += Math.max(0, max - it.getAmount());
        }
        return space;
    }

    /** Забирает до need единиц mat из инвентаря игрока (реально уменьшает слоты), возвращает сколько реально забрал. */
    private static int takeFromPlayerInventory(PlayerInventory inv, Material mat, int need) {
        if (need <= 0) return 0;
        int taken = 0;
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && taken < need; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(it.getAmount(), need - taken);
            int newAmt = it.getAmount() - take;
            contents[i] = (newAmt <= 0) ? null : withAmount(it, newAmt);
            taken += take;
        }
        inv.setStorageContents(contents);
        return taken;
    }

    /** Убирает до amount единиц mat из инвентаря (сундука), возможно распределённых по нескольким слотам. Возвращает сколько реально убрал. */
    private static int removeMaterialFromChest(Inventory inv, Material mat, int amount) {
        if (amount <= 0) return 0;
        int taken = 0;
        for (int i = 0; i < inv.getSize() && taken < amount; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(it.getAmount(), amount - taken);
            int newAmt = it.getAmount() - take;
            inv.setItem(i, (newAmt <= 0) ? null : withAmount(it, newAmt));
            taken += take;
        }
        return taken;
    }

    private static ItemStack withAmount(ItemStack it, int amount) {
        it.setAmount(amount);
        return it;
    }

    private static void giveToPlayer(Player p, Material mat, int amount) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack(mat, amount));
        for (ItemStack extra : leftover.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), extra);
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private int countMaterialInChestBlock(Location chestLoc, Material material) {
        if (!(chestLoc.getBlock().getState() instanceof org.bukkit.block.Container c)) return 0;
        int total = 0;
        for (ItemStack it : c.getInventory().getContents()) {
            if (it != null && it.getType() == material) total += it.getAmount();
        }
        return total;
    }

    private static final Pattern INT_ANY = Pattern.compile("-?\\d+");
    private static final Pattern DOUBLE_ANY = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    /** dealPrice / dealQuantity из строк 3-4 таблички source — единая ставка за единицу для ВСЕГО сундука (см. class javadoc). */
    private static Double pricePerUnit(SignVariables sv) {
        List<String> t = sv.getSignText();
        if (t == null || t.size() < 4) return null;
        Integer qty = parsePositiveInt(t.get(2));
        Double price = parsePositiveDouble(t.get(3));
        if (qty == null || qty <= 0 || price == null || price <= 0) return null;
        return price / qty;
    }

    private static Integer parsePositiveInt(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        var m = INT_ANY.matcher(s);
        if (!m.find()) return null;
        try { return Math.abs(Integer.parseInt(m.group())); } catch (NumberFormatException e) { return null; }
    }

    private static Double parsePositiveDouble(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        var m = DOUBLE_ANY.matcher(s);
        if (!m.find()) return null;
        try { return Math.abs(Double.parseDouble(m.group().replace(',', '.'))); } catch (NumberFormatException e) { return null; }
    }

    /**
     * НЕ используем Inventory#getLocation() как источник — для
     * PlayerInventory (когда игрок открывает СВОЙ инвентарь, не сундук) он
     * возвращает позицию самого игрока, а не null; если игрок физически
     * стоял рядом с сундуком магазина, координаты совпадали и его личный
     * инвентарь ошибочно принимался за сундук. Смотрим только на holder.
     */
    private Location chestLocationOf(Inventory inv) {
        var holder = inv.getHolder();
        if (holder instanceof org.bukkit.block.Container c) return SignStore.keyLoc(c.getLocation());
        if (holder instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide() instanceof org.bukkit.block.Chest cl) return SignStore.keyLoc(cl.getLocation());
            if (dc.getRightSide() instanceof org.bukkit.block.Chest cr) return SignStore.keyLoc(cr.getLocation());
        }
        return null;
    }
}
