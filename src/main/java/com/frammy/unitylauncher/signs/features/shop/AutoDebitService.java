package com.frammy.unitylauncher.signs.features.shop;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.zones.ZoneManager;
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
 * Design (see chat — this was worked out with the user across a few rounds,
 * not guessed blind):
 *  - Picking an item OUT of the chest onto an empty cursor is free/instant,
 *    but the amount taken is clamped up front to what the player could ever
 *    afford (cash + bank combined) — this is what guarantees the later
 *    "finalize whatever's left on the cursor" step can never fail for
 *    insufficient funds.
 *  - Placing cursor units into the PLAYER's own inventory is the actual
 *    purchase moment — charged then, per unit actually placed (so the
 *    classic "hold, then right-click your own slot 3 times" flow pays for
 *    exactly 3, matching a normal chest).
 *  - Placing cursor units BACK into the same chest is a free return — no
 *    charge, no need to "undo" anything since nothing was charged yet.
 *  - Shift-click bypasses the cursor entirely (straight to inventory in one
 *    action) — handled as its own case, clamped and charged synchronously.
 *  - Whatever's still sitting unpaid on the cursor when the window closes,
 *    the player drops it (Q), or disconnects gets charged then — vanilla
 *    Minecraft itself guarantees a cursor item lands in the player's
 *    inventory or on the ground in all of those cases (never just vanishes),
 *    and the up-front clamp means that charge can't fail. See chat for the
 *    reasoning (also covers why we don't try to build a "hold and refund on
 *    disconnect" system — there's nothing left to refund by the time it'd
 *    matter, and fighting that isn't worth it).
 *
 * Not handled in this first version: multi-slot drag (InventoryDragEvent)
 * touching the chest is simply blocked for non-owners with auto-debit on —
 * ask them to use plain clicks. Same reasoning as everywhere else in this
 * class: partial-affordability math for an arbitrary multi-slot drag is a
 * lot of extra surface area for a case players can always route around with
 * ordinary clicks instead.
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

    // Сколько единиц (и какого материала, из какого сундука) сейчас у игрока
    // "на курсоре" из этого магазина, но ещё НЕ оплачено.
    private final Map<UUID, Integer> unpaidUnits = new ConcurrentHashMap<>();
    private final Map<UUID, Material> unpaidMaterial = new ConcurrentHashMap<>();
    private final Map<UUID, Location> unpaidChest = new ConcurrentHashMap<>();
    private final Map<UUID, Double> unpaidPricePerUnit = new ConcurrentHashMap<>();
    // Не даём копиться нескольким reconcile-задачам на одного игрока за один тик.
    private final Map<UUID, Boolean> reconcilePending = new ConcurrentHashMap<>();

    // ===== preferences (см. ATM "Настройки") =====

    public void getPreferencesAsync(String playerName, java.util.function.BiConsumer<Boolean, String> onResult) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Object> map = UnityCommands.getInstance()
                    .getJsonFieldValues("Users", "GeneralData", "Name", playerName, List.of("autoDebitEnabled", "paymentPriority"));
            boolean enabled = Boolean.TRUE.equals(map.get("autoDebitEnabled"));
            String priority = (map.get("paymentPriority") instanceof String s && !s.isBlank()) ? s : "CASH";
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> onResult.accept(enabled, priority));
        });
    }

    public void setAutoDebitEnabled(String playerName, boolean enabled) {
        UnityCommands.getInstance().mergeAndUpdatePlayerData(playerName, "GeneralData", Map.of("autoDebitEnabled", enabled));
    }

    public void setPaymentPriority(String playerName, String priority) {
        UnityCommands.getInstance().mergeAndUpdatePlayerData(playerName, "GeneralData", Map.of("paymentPriority", priority));
    }

    public record Prefs(boolean autoDebitEnabled, String paymentPriority) {}

    /** Блокирующий (см. ensurePrefsCachedBlocking) — используется ATM "Настройки", вызывается редко (раз за вход в меню). */
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

    // ===== events =====

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
        if (!Boolean.TRUE.equals(autoDebitCache.get(p.getUniqueId()))) {
            e.setCancelled(true); // авто-списание выключено — только просмотр
            return;
        }

        Double pricePerUnit = pricePerUnit(sv);
        Material material = soleMaterialIn(topInv);
        if (pricePerUnit == null || material == null) {
            e.setCancelled(true);
            if (pricePerUnit != null && material == null && !isEmpty(topInv)) {
                // Сундук непуст, но содержит больше одного типа предмета —
                // раньше молча брался "первый попавшийся" материал, а второй
                // (и любой другой) тип оставался вообще вне учёта: не в
                // списке SHOP_LIST, без цены — забрать бесплатно. Не гадаем,
                // какой из них "настоящий" — считаем такой сундук
                // неоднозначно настроенным и просто не даём в нём ничего
                // трогать не-владельцу, пока не останется один тип товара.
                p.sendMessage(ChatColor.RED + "В сундуке смешаны разные товары — авто-списание временно недоступно, пусть владелец магазина оставит только один тип товара в сундуке.");
            }
            return;
        }

        String priority = priorityCache.getOrDefault(p.getUniqueId(), "CASH");
        boolean clickedChest = e.getClickedInventory() != null && e.getClickedInventory().equals(topInv);
        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();

        // ---- A) Shift-клик по товару в сундуке: сразу в инвентарь, минуя курсор ----
        if (clickedChest && (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT)
                && clicked != null && clicked.getType() == material) {
            e.setCancelled(true);
            int space = freeSpaceFor(p.getInventory(), material);
            int want = Math.min(clicked.getAmount(), space);
            int affordable = maxAffordable(p, pricePerUnit);
            int take = Math.min(want, affordable);
            if (take <= 0) {
                p.sendMessage(ChatColor.RED + "Недостаточно средств.");
                return;
            }
            double cost = round2(take * pricePerUnit);
            if (!charge(p, cost, priority)) {
                p.sendMessage(ChatColor.RED + "Недостаточно средств.");
                return;
            }
            reduceSlot(topInv, clicked, take);
            giveToPlayer(p, material, take);
            p.sendMessage(ChatColor.GRAY + "Куплено: " + ChatColor.YELLOW + take + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
            return;
        }

        // ---- B) Обычный клик по товару в сундуке, курсор пуст: забрать на курсор (клэмп по деньгам, не оплата) ----
        if (clickedChest && (e.getClick() == ClickType.LEFT || e.getClick() == ClickType.RIGHT)
                && clicked != null && clicked.getType() == material
                && (cursor == null || cursor.getType().isAir())) {
            int wouldTake = e.getClick() == ClickType.LEFT ? clicked.getAmount() : (clicked.getAmount() + 1) / 2;
            int affordable = maxAffordable(p, pricePerUnit);
            int take = Math.min(wouldTake, affordable);
            if (take <= 0) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Недостаточно средств даже на 1 шт.");
                return;
            }
            if (take == wouldTake) {
                // хватает и на дефолтное поведение клика — не мешаем самому переносу,
                // просто помечаем как непроплаченное.
                trackPickup(p, chestLoc, material, take, pricePerUnit);
                return;
            }
            e.setCancelled(true);
            reduceSlot(topInv, clicked, take);
            p.setItemOnCursor(new ItemStack(material, take));
            trackPickup(p, chestLoc, material, take, pricePerUnit);
            return;
        }

        // ---- Всё остальное: не гадаем, что именно сделает Bukkit (двойной
        // клик собирает стак ИЗ ВСЕГО инвентаря включая уже принадлежащие
        // игроку предметы, клик мимо слота роняет предмет, клик по своему
        // инвентарю при открытом сундуке иногда кладёт В сундук, а не из
        // него — слишком много вариантов, чтобы классифицировать заранее).
        // Если у игрока сейчас есть неоплаченные единицы именно этого
        // сундука/товара — просто сверяем через тик, что реально
        // изменилось в самом сундуке и на курсоре, и списываем/возвращаем
        // по факту. См. reconcileNow.
        UUID id = p.getUniqueId();
        if (unpaidUnits.getOrDefault(id, 0) > 0
                && material.equals(unpaidMaterial.get(id))
                && chestLoc.equals(unpaidChest.get(id))) {
            scheduleReconcile(p, chestLoc, material);
        }
    }

    /** Коалесцируется: если проверка уже запланирована на этот тик — второй раз не планируем, дождавшийся тик всё равно увидит суммарный эффект всех кликов. */
    private void scheduleReconcile(Player p, Location chestLoc, Material material) {
        UUID id = p.getUniqueId();
        if (Boolean.TRUE.equals(reconcilePending.get(id))) return;
        reconcilePending.put(id, true);
        int chestCountBefore = countMaterialInChestBlock(chestLoc, material);

        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            reconcilePending.remove(id);
            reconcileNow(p, chestLoc, material, chestCountBefore);
        });
    }

    private void reconcileNow(Player p, Location chestLoc, Material material, int chestCountBefore) {
        UUID id = p.getUniqueId();
        int unpaid = unpaidUnits.getOrDefault(id, 0);
        if (unpaid <= 0) return;
        if (!chestLoc.equals(unpaidChest.get(id)) || material != unpaidMaterial.get(id)) return;

        // Сколько вернулось обратно в сундук с прошлой проверки — без списания.
        int chestCountAfter = countMaterialInChestBlock(chestLoc, material);
        int returned = Math.max(0, chestCountAfter - chestCountBefore);
        if (returned > 0) {
            untrackUnits(p, Math.min(returned, unpaid));
            unpaid = unpaidUnits.getOrDefault(id, 0);
            if (unpaid <= 0) return;
        }

        // Что осталось из неоплаченного и НЕ вернулось в сундук и НЕ висит
        // на курсоре — значит уже физически у игрока (в инвентаре) или
        // было выброшено (это уже могло списаться отдельно через onDrop —
        // тогда unpaidUnits уже уменьшен ДО этой проверки, двойного
        // списания не будет).
        ItemStack cursor = p.getItemOnCursor();
        int stillOnCursor = (cursor != null && cursor.getType() == material) ? cursor.getAmount() : 0;
        int committed = Math.max(0, unpaid - stillOnCursor);
        if (committed <= 0) return;

        Double ppu = unpaidPricePerUnit.get(id);
        if (ppu == null) return;
        double cost = round2(committed * ppu);
        String priority = priorityCache.getOrDefault(id, "CASH");

        if (charge(p, cost, priority)) {
            p.sendMessage(ChatColor.GRAY + "Куплено: " + ChatColor.YELLOW + committed + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
        } else {
            p.sendMessage(ChatColor.RED + "Не удалось списать за товар (" + committed + "x) — недостаточно средств.");
        }
        untrackUnits(p, committed);
    }

    private int countMaterialInChestBlock(Location chestLoc, Material material) {
        if (!(chestLoc.getBlock().getState() instanceof org.bukkit.block.Container c)) return 0;
        int total = 0;
        for (ItemStack it : c.getInventory().getContents()) {
            if (it != null && it.getType() == material) total += it.getAmount();
        }
        return total;
    }

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

        // Затрагивает ли драг хоть один слот сундука (верхний инвентарь)?
        boolean touchesChest = e.getRawSlots().stream().anyMatch(slot -> slot < topInv.getSize());
        if (!touchesChest) return;

        // v1: перетаскивание по нескольким слотам поверх магазинного сундука не
        // поддерживаем — слишком много вариантов частичной оплаты для случая,
        // который игрок всегда может обойти обычными кликами.
        e.setCancelled(true);
        p.sendMessage(ChatColor.YELLOW + "Перетаскивание сюда не поддерживается — бери товар обычным кликом.");
    }

    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Material mat = unpaidMaterial.get(id);
        if (mat == null || e.getItemDrop().getItemStack().getType() != mat) return;

        int dropped = e.getItemDrop().getItemStack().getAmount();
        int unpaid = unpaidUnits.getOrDefault(id, 0);
        int chargeable = Math.min(dropped, unpaid);
        if (chargeable <= 0) return;

        Double ppu = unpaidPricePerUnit.get(id);
        if (ppu == null) return;
        double cost = round2(chargeable * ppu);
        String priority = priorityCache.getOrDefault(id, "CASH");

        if (!charge(p, cost, priority)) {
            // Уже уронил — по итогам разбора в чате: не боремся с логикой игры,
            // предмет уже покинул сундук, оплата "как получится" (best effort).
            p.sendMessage(ChatColor.RED + "Не удалось списать за выброшенный товар (" + chargeable + "x) — недостаточно средств.");
        } else {
            p.sendMessage(ChatColor.GRAY + "Списано за выброшенный товар: " + ChatColor.YELLOW + chargeable + "x"
                    + ChatColor.GRAY + " — " + ChatColor.GREEN + cost + " Ⓕ");
        }
        untrackUnits(p, chargeable);
    }

    /** Вызывать из onInventoryClose/onPlayerQuit — то, что осталось непроплаченным на курсоре, ванилла всё равно доставит игроку. */
    public void finalizeUnpaid(Player p) {
        UUID id = p.getUniqueId();
        autoDebitCache.remove(id);
        priorityCache.remove(id);

        int unpaid = unpaidUnits.getOrDefault(id, 0);
        if (unpaid <= 0) { clearTracking(id); return; }

        Double ppu = unpaidPricePerUnit.get(id);
        if (ppu == null) { clearTracking(id); return; }

        double cost = round2(unpaid * ppu);
        String priority = priorityCache.getOrDefault(id, "CASH");
        boolean ok = charge(p, cost, priority);
        if (p.isOnline()) {
            if (ok) {
                p.sendMessage(ChatColor.GRAY + "Списано за товар при закрытии: " + ChatColor.YELLOW + unpaid + "x"
                        + ChatColor.GRAY + " — " + ChatColor.GREEN + cost + " Ⓕ");
            } else {
                p.sendMessage(ChatColor.RED + "Не удалось списать за товар (" + unpaid + "x) — недостаточно средств.");
            }
        }
        clearTracking(id);
    }

    private void clearTracking(UUID id) {
        unpaidUnits.remove(id);
        unpaidMaterial.remove(id);
        unpaidChest.remove(id);
        unpaidPricePerUnit.remove(id);
        reconcilePending.remove(id);
    }

    private void trackPickup(Player p, Location chestLoc, Material material, int take, double pricePerUnit) {
        UUID id = p.getUniqueId();
        unpaidUnits.merge(id, take, Integer::sum);
        unpaidMaterial.put(id, material);
        unpaidChest.put(id, chestLoc);
        unpaidPricePerUnit.put(id, pricePerUnit);
    }

    private void untrackUnits(Player p, int amount) {
        UUID id = p.getUniqueId();
        int left = unpaidUnits.getOrDefault(id, 0) - amount;
        if (left <= 0) clearTracking(id);
        else unpaidUnits.put(id, left);
    }

    // ===== payment =====

    private int maxAffordable(Player p, double pricePerUnit) {
        if (pricePerUnit <= 0) return Integer.MAX_VALUE;
        double cash = plugin.moneyManager.getInventoryCash(p);
        Double bank = UnityCommands.getInstance().getBalance(p.getName());
        double total = cash + (bank != null ? bank : 0.0);
        return (int) Math.floor(total / pricePerUnit + 1e-9);
    }

    private boolean charge(Player p, double amount, String priority) {
        if (amount <= 0) return true;
        boolean cashFirst = !"BANK".equalsIgnoreCase(priority);
        if (cashFirst) {
            if (plugin.moneyManager.spendCash(p, amount)) return true;
            return chargeBank(p, amount);
        } else {
            if (chargeBank(p, amount)) return true;
            return plugin.moneyManager.spendCash(p, amount);
        }
    }

    private boolean chargeBank(Player p, double amount) {
        Double bal = UnityCommands.getInstance().getBalance(p.getName());
        if (bal == null || bal < amount - 1e-9) return false;
        plugin.moneyManager.withdraw(p.getName(), amount); // async запись — баланс уже проверен синхронно выше
        return true;
    }

    // ===== inventory/material helpers =====

    /** null если пусто ИЛИ содержит больше одного типа предмета — см. использование выше (bug: смешанные сундуки = бесплатный второй товар). */
    private static Material soleMaterialIn(Inventory inv) {
        Material found = null;
        for (ItemStack it : inv.getContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (found == null) found = it.getType();
            else if (found != it.getType()) return null;
        }
        return found;
    }

    private static boolean isEmpty(Inventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir()) return false;
        }
        return true;
    }

    private static int freeSpaceFor(PlayerInventory inv, Material mat) {
        int space = 0;
        int max = mat.getMaxStackSize();
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) space += max;
            else if (it.getType() == mat) space += Math.max(0, max - it.getAmount());
        }
        return space;
    }

    private static void reduceSlot(Inventory inv, ItemStack stack, int amount) {
        int newAmount = stack.getAmount() - amount;
        // ищем по ссылке — inv.first(ItemStack) требует точного совпадения meta, ненадёжно
        int slot = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == stack) { slot = i; break; }
        }
        if (slot < 0) return;
        if (newAmount <= 0) inv.setItem(slot, null);
        else { stack.setAmount(newAmount); inv.setItem(slot, stack); }
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

    private static final Pattern INT_ANY = Pattern.compile("-?\\d+");
    private static final Pattern DOUBLE_ANY = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    /** dealPrice / dealQuantity из строк 3-4 таблички source (см. ShopController/ShopListUpdater — та же логика). */
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
     * НЕ используем Inventory#getLocation() как основной источник — для
     * PlayerInventory (когда игрок открывает СВОЙ инвентарь, не сундук) он
     * возвращает позицию самого ИГРОКА, а не null. Если игрок в этот момент
     * физически стоит на/у блока сундука магазина, координаты совпадали, и
     * вся эта логика ошибочно принимала его личный инвентарь за сундук
     * магазина — отсюда и запрет перетаскивать что-либо в своих собственных
     * слотах просто от стояния рядом с сундуком. Смотрим только на holder.
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
