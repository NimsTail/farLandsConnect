package com.frammy.unitylauncher.upgrades.industry;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Умная воронка:
 * - Уровень определяется глобальными апгрейдами СТРАНЫ (через LP Group API), по зоне размещения воронки.
 * - 0 уровень: базовый интервал 24 тика, перенос по 1 предмету (как vanilla).
 * - 1 уровень: ускоренный интервал 8 тиков, перенос по 1 предмету.
 * - 2 уровень: ускоренный интервал 8 тиков, перенос целого стака.
 */
public class SmartHopperListener implements Listener {

    /** Последний тик срабатывания, ключ — позиция блока (toBlockKey) */
    private final Map<Long, Long> lastMoveTick = new HashMap<>();

    @EventHandler
    public void onMove(InventoryMoveItemEvent e) {
        if (e.getInitiator().getType() != org.bukkit.event.inventory.InventoryType.HOPPER) return;

        Inventory hopperInv = e.getInitiator();
        if (!(hopperInv.getHolder() instanceof Hopper hopperState)) return;
        Block hopperBlock = hopperState.getBlock();

        // Уровень апгрейда страны по ЛОКАЦИИ воронки, через UpgradeCondition API
        int level = UpgradeCondition.getTieredGlobalUpgradeAt(hopperBlock.getLocation(), "unity.hopper.smart", 2); // 0..2
        int interval = (level == 0 ? 24 : 8);

        long now = Bukkit.getCurrentTick();
        long posKey = hopperBlock.getLocation().toBlockKey();
        long last = lastMoveTick.getOrDefault(posKey, 0L);

        // Троттлинг
        if (now - last < interval) {
            e.setCancelled(true);
            return;
        }

        // Находим самый полный слот
        int bestSlot = findMaxStackSlot(hopperInv);
        if (bestSlot == -1) {
            e.setCancelled(true);
            return;
        }

        ItemStack stack = hopperInv.getItem(bestSlot);
        if (stack == null || stack.getAmount() <= 0) {
            e.setCancelled(true);
            return;
        }

        // Двигаем предметы в зависимости от уровня
        if (level == 0 || level == 1) {
            // переносим 1 предмет
            ItemStack moveOne = stack.clone();
            moveOne.setAmount(1);
            stack.setAmount(stack.getAmount() - 1);
            e.setItem(moveOne);
        } else {
            // уровень 2 — переносим весь стак
            ItemStack moveStack = stack.clone();
            hopperInv.clear(bestSlot); // корректнее, чем setAmount(0)
            e.setItem(moveStack);
        }

        lastMoveTick.put(posKey, now);
    }

    /** Находим слот с максимальным количеством предметов */
    private int findMaxStackSlot(Inventory inv) {
        int bestSlot = -1;
        int maxAmount = 0;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null) continue;
            int amt = it.getAmount();
            if (amt > maxAmount) {
                maxAmount = amt;
                bestSlot = i;
            }
        }
        return bestSlot;
    }
}
