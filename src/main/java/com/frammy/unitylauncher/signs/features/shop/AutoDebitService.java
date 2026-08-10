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
        Material material = firstMaterialIn(topInv);
        if (pricePerUnit == null || material == null) {
            e.setCancelled(true); // товар/цена не настроены — трогать нечего
            return;
        }

        String priority = priorityCache.getOrDefault(p.getUniqueId(), "CASH");
        boolean clickedChest = e.getClickedInventory() != null && e.getClickedInventory().equals(topInv);
        ItemStack clicked = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        boolean cursorHasOurItem = cursor != null && cursor.getType() == material
                && chestLoc.equals(unpaidChest.get(p.getUniqueId()));

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

        // ---- C) Клик по СВОЕМУ инвентарю с непроплаченным товаром на курсоре: покупка ----
        if (!clickedChest && cursorHasOurItem) {
            int placing = (e.getClick() == ClickType.RIGHT) ? 1 : cursor.getAmount();
            placing = Math.min(placing, cursor.getAmount());
            placing = Math.min(placing, unpaidUnits.getOrDefault(p.getUniqueId(), 0));
            if (placing <= 0) return;

            double cost = round2(placing * pricePerUnit);
            if (!charge(p, cost, priority)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Недостаточно средств.");
                return;
            }
            // сам перенос не трогаем — пусть Bukkit кладёт как обычно
            untrackUnits(p, placing);
            p.sendMessage(ChatColor.GRAY + "Куплено: " + ChatColor.YELLOW + placing + "x" + ChatColor.GRAY + " за " + ChatColor.GREEN + cost + " Ⓕ");
            return;
        }

        // ---- D) Клик обратно в тот же сундук с непроплаченным товаром на курсоре: возврат, без списания ----
        if (clickedChest && cursorHasOurItem) {
            int beforeCursor = cursor.getAmount();
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack cur = p.getItemOnCursor();
                int afterCursor = (cur != null && cur.getType() == material) ? cur.getAmount() : 0;
                int returned = Math.max(0, beforeCursor - afterCursor);
                if (returned > 0) untrackUnits(p, returned);
            });
        }
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

    private static Material firstMaterialIn(Inventory inv) {
        for (ItemStack it : inv.getContents()) {
            if (it != null && !it.getType().isAir()) return it.getType();
        }
        return null;
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

    private Location chestLocationOf(Inventory inv) {
        Location loc = inv.getLocation();
        if (loc != null) return SignStore.keyLoc(loc);
        var holder = inv.getHolder();
        if (holder instanceof org.bukkit.block.Container c) return SignStore.keyLoc(c.getLocation());
        if (holder instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide() instanceof org.bukkit.block.Chest cl) return SignStore.keyLoc(cl.getLocation());
            if (dc.getRightSide() instanceof org.bukkit.block.Chest cr) return SignStore.keyLoc(cr.getLocation());
        }
        return null;
    }
}
