package com.frammy.unitylauncher.signs.features.trash;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.chunkactivity.ZonesEconomyConfig;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Sign;
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
            Map<Integer, Integer> slots,
            Map<Material, TrashLine> lines
    ) {}

    private record PendingSell(long createdAtMs, TrashSnap snap) {}

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public void handleTrashSell(Player p, Sign sign) {
        ZonesEconomyConfig.TrashSell ts = ZonesEconomyConfig.get().trashSell;
        if (!ts.enabled) {
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
        ZonesEconomyConfig.TrashSell ts = ZonesEconomyConfig.get().trashSell;
        if (!ts.enabled) {
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

        // Всё совпало — идём продавать (далее твоя старая логика, но строго по now.snap)
        p.sendMessage(ChatColor.GRAY + "Сдаём мусор...");

        UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
            if (data == null) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        p.sendMessage(ChatColor.RED + "Не удалось получить твои данные. Сообщи администрации.")
                );
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                double newMoney = round2(data.money + now.totalReward);
                Map<String, Object> updates = new HashMap<>();
                updates.put("money", newMoney);
                UnityCommands.getInstance().mergeAndUpdatePlayerData(p.getName(), "GeneralData", updates);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) return;

                    // удаляем строго то, что пересканили при confirm
                    applyTrashRemoval(p, now.slots());

                    pending.remove(id);

                    p.sendMessage(ChatColor.GREEN + "Сдано мусора: " + ChatColor.YELLOW + now.totalItems + ChatColor.GREEN + " шт.");
                    p.sendMessage(ChatColor.GREEN + "Зачислено: " + ChatColor.YELLOW + now.totalReward + ChatColor.GREEN + " Ⓕ.");
                });
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
        // достаточно сравнить “что будет удалено”: слоты+кол-во
        // (если игрок переставил предметы — слоты изменятся → попросим подтвердить ещё раз)
        return a.slots().equals(b.slots()) && a.totalItems() == b.totalItems() && a.totalReward() == b.totalReward();
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

    private TrashSnap scanTrash(Player p, ZonesEconomyConfig.TrashSell ts) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        double totalReward = 0.0;
        int totalItems = 0;
        Map<Integer, Integer> slots = new HashMap<>();
        Map<Material, TrashLine> lines = new HashMap<>();

        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType().isAir()) continue;

            Material type = stack.getType();
            if (ts.blacklist.contains(type)) continue;

            Double price = ts.prices.get(type);
            if (price == null || price <= 0.0) continue;

            int amount = stack.getAmount();
            if (amount < ts.minStackSize) continue;

            double subtotal = price * amount;

            totalReward += subtotal;
            totalItems += amount;
            slots.put(i, amount);

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

    private void applyTrashRemoval(Player p, Map<Integer, Integer> slots) {
        Inventory inv = p.getInventory();
        for (var e : slots.entrySet()) {
            int slot = e.getKey();
            int expectedAmount = e.getValue();

            ItemStack cur = inv.getItem(slot);
            if (cur == null || cur.getType().isAir()) continue;
            if (cur.getAmount() != expectedAmount) continue; // предмет поменяли — не трогаем
            inv.setItem(slot, null);
        }
    }

}
