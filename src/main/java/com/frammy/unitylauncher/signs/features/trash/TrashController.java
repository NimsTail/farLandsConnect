package com.frammy.unitylauncher.signs.features.trash;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TrashController {

    private final UnityLauncher plugin;

    private static final long CONFIRM_TTL_MS = 30_000L; // 30 секунд на подтверждение
    private final Map<java.util.UUID, PendingSell> pending = new ConcurrentHashMap<>();

    public TrashController(UnityLauncher plugin) {
        this.plugin = plugin;
    }

    private record TrashLine(Material type, int amount, double price, double subtotal) {}

    private record TrashSnap(
            double totalReward,
            int totalItems,
            Map<Integer, ItemStack> slots,
            Map<Material, TrashLine> lines
    ) {}

    private record PendingSell(long createdAtMs, TrashSnap snap) {}

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public void handleTrashSell(Player p) {
        TrashSellConfig.Data ts = TrashSellConfig.get();
        if (!ts.enabled()) {
            p.sendMessage(ChatColor.RED + "Продажа мусора временно отключена на сервере.");
            return;
        }
        // чистим протухшие pending
        cleanupExpired(p.getUniqueId());

        // 1) СКАН ИНВЕНТАРЯ — только main thread
        TrashSnap snap = scanTrash(p, ts);
        if (snap.totalReward <= 0.0 || snap.totalItems <= 0) {
            p.sendMessage(ChatColor.YELLOW + "В инвентаре нет предметов, которые принимаются как мусор.");
            return;
        }

        // кладём в pending и показываем предпросмотр
        pending.put(p.getUniqueId(), new PendingSell(System.currentTimeMillis(), snap));
        sendPreview(p, snap);
    }

    public void handleTrashConfirm(Player p) {
        TrashSellConfig.Data ts = TrashSellConfig.get();
        if (!ts.enabled()) {
            p.sendMessage(ChatColor.RED + "Продажа мусора временно отключена на сервере.");
            return;
        }

        java.util.UUID id = p.getUniqueId();
        PendingSell pend = pending.get(id);
        if (pend == null) {
            p.sendMessage(ChatColor.YELLOW + "Нечего подтверждать. Сначала нажми табличку приёма мусора.");
            return;
        }
        if (System.currentTimeMillis() - pend.createdAtMs() > CONFIRM_TTL_MS) {
            pending.remove(id);
            p.sendMessage(ChatColor.RED + "Подтверждение просрочено. Нажми табличку ещё раз.");
            return;
        }

        // ВАЖНО: пересканим заново, чтобы не продавать “не то”, если игрок успел поменять инвентарь
        TrashSnap now = scanTrash(p, ts);
        if (now.totalItems <= 0 || now.totalReward <= 0) {
            pending.remove(id);
            p.sendMessage(ChatColor.YELLOW + "Похоже, подходящего мусора уже нет. Отмена.");
            return;
        }

        // Если сумма/состав изменились — показываем обновлённый preview и просим подтвердить ещё раз
        if (!sameSnapshot(pend.snap(), now)) {
            pending.put(id, new PendingSell(System.currentTimeMillis(), now));
            p.sendMessage(ChatColor.YELLOW + "Состав мусора изменился. Проверь обновлённый список и подтверди ещё раз:");
            sendPreview(p, now);
            return;
        }

        // Всё совпало — сначала забираем предметы (main thread, тот же тик, что и
        // пересканирование), и только потом начисляем деньги атомарной дельтой.
        // Раньше деньги писались первыми абсолютным значением из кэша, а удаление
        // пропускало изменённые слоты/офлайн — можно было получить деньги и
        // оставить предметы себе.
        pending.remove(id);
        Map<Integer, ItemStack> taken = applyTrashRemoval(p, now.slots());
        if (taken == null) {
            p.sendMessage(ChatColor.YELLOW + "Инвентарь изменился во время сдачи. Отмена, нажми табличку ещё раз.");
            return;
        }

        p.sendMessage(ChatColor.GRAY + "Сдаём мусор...");
        String name = p.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean ok = UnityCommands.getInstance().applyMoneyDelta(name, now.totalReward(), "Сдача мусора");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (ok) {
                    if (p.isOnline()) {
                        p.sendMessage(ChatColor.GREEN + "Сдано мусора: " + ChatColor.YELLOW + now.totalItems() + ChatColor.GREEN + " шт.");
                        p.sendMessage(ChatColor.GREEN + "Зачислено: " + ChatColor.YELLOW + now.totalReward() + ChatColor.GREEN + " Ⓕ.");
                    }
                    return;
                }
                // начисление не прошло — возвращаем предметы
                if (!p.isOnline()) {
                    plugin.getLogger().severe("[Trash] начисление " + now.totalReward() + " игроку " + name
                            + " не прошло, игрок вышел — предметы не возвращены: " + taken.values());
                    return;
                }
                for (ItemStack it : taken.values()) {
                    p.getInventory().addItem(it).values()
                            .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                }
                p.sendMessage(ChatColor.RED + "Не удалось начислить деньги (БД). Предметы возвращены.");
            });
        });
    }

    public void handleTrashCancel(Player p) {
        pending.remove(p.getUniqueId());
        p.sendMessage(ChatColor.YELLOW + "Сдача мусора отменена.");
    }

    private void cleanupExpired(java.util.UUID id) {
        PendingSell ps = pending.get(id);
        if (ps == null) return;
        if (System.currentTimeMillis() - ps.createdAtMs() > CONFIRM_TTL_MS) {
            pending.remove(id);
        }
    }

    private boolean sameSnapshot(TrashSnap a, TrashSnap b) {
        if (a.totalItems() != b.totalItems()) return false;
        if (Double.compare(a.totalReward(), b.totalReward()) != 0) return false;

        return sameSlotStacks(a.slots(), b.slots());
    }

    private boolean sameSlotStacks(Map<Integer, ItemStack> a, Map<Integer, ItemStack> b) {
        if (a.size() != b.size()) return false;

        for (var e : a.entrySet()) {
            int slot = e.getKey();
            ItemStack as = e.getValue();
            ItemStack bs = b.get(slot);
            if (!sameStack(as, bs)) return false;
        }
        return true;
    }

    private boolean sameStack(ItemStack a, ItemStack b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        if (a.getAmount() != b.getAmount()) return false;
        return a.isSimilar(b); // тип+meta
    }

    private void sendPreview(Player p, TrashSnap snap) {
        p.sendMessage(ChatColor.GOLD + "Сдача мусора (предпросмотр):");
        p.sendMessage(ChatColor.GRAY + "Будет удалено и оплачено:");

        // топ-12 строк, чтобы чат не засирать
        int shown = 0;
        for (TrashLine line : snap.lines().values()) {
            if (shown++ >= 12) break;
            p.sendMessage(ChatColor.DARK_GRAY + "• " + ChatColor.YELLOW + line.type().name()
                    + ChatColor.GRAY + " x" + ChatColor.WHITE + line.amount()
                    + ChatColor.GRAY + " @ " + ChatColor.WHITE + round2(line.price())
                    + ChatColor.GRAY + " = " + ChatColor.GOLD + round2(line.subtotal()) + " Ⓕ");
        }

        if (snap.lines().size() > 12) {
            p.sendMessage(ChatColor.DARK_GRAY + "... и ещё " + (snap.lines().size() - 12) + " типов");
        }

        p.sendMessage(ChatColor.GREEN + "Итого предметов: " + ChatColor.YELLOW + snap.totalItems()
                + ChatColor.GREEN + ", сумма: " + ChatColor.YELLOW + snap.totalReward() + ChatColor.GREEN + " Ⓕ");
        p.sendMessage(ChatColor.GRAY + "Подтверждение действует " + (CONFIRM_TTL_MS / 1000) + " сек.");

        TextComponent confirm = new TextComponent(ChatColor.GREEN + "[Подтвердить]");
        confirm.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul trash confirm"));
        confirm.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(ChatColor.GRAY + "Продать ровно этот набор предметов").create()));

        TextComponent cancel = new TextComponent(ChatColor.RED + "[Отмена]");
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul trash cancel"));

        p.spigot().sendMessage(new TextComponent(" "), confirm, new TextComponent(" "), cancel);
    }

    private TrashSnap scanTrash(Player p, TrashSellConfig.Data ts) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        double totalReward = 0.0;
        int totalItems = 0;
        Map<Integer, ItemStack> slots = new HashMap<>();
        Map<Material, TrashLine> lines = new HashMap<>();

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;

            Material type = stack.getType();
            if (ts.blacklist().contains(type)) continue;

            Double price = ts.prices().get(type);
            if (price == null || price <= 0.0) continue;

            int amount = stack.getAmount();
            if (amount < ts.minStackSize()) continue;

            double subtotal = price * amount;

            totalReward += subtotal;
            totalItems += amount;

            ItemStack snapStack = stack.clone();     // фиксируем тип+meta+amount
            snapStack.setAmount(amount);            // на всякий (clone и так хранит amount)
            slots.put(i, snapStack);

            TrashLine prev = lines.get(type);
            if (prev == null) {
                lines.put(type, new TrashLine(type, amount, price, subtotal));
            } else {
                int newAmt = prev.amount() + amount;
                double newSub = prev.subtotal() + subtotal;
                lines.put(type, new TrashLine(type, newAmt, price, newSub));
            }
        }

        return new TrashSnap(round2(totalReward), totalItems, slots, lines);
    }

    /** Удаляет ровно снятые слоты; если хоть один изменился — ничего не трогает и возвращает null. */
    private Map<Integer, ItemStack> applyTrashRemoval(Player p, Map<Integer, ItemStack> slots) {
        if (!p.isOnline()) return null;
        Inventory inv = p.getInventory();
        for (var e : slots.entrySet()) {
            if (!sameStack(e.getValue(), inv.getItem(e.getKey()))) return null;
        }
        Map<Integer, ItemStack> taken = new HashMap<>();
        for (var e : slots.entrySet()) {
            taken.put(e.getKey(), e.getValue().clone());
            inv.setItem(e.getKey(), null);
        }
        return taken;
    }

}
