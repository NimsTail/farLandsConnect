package com.frammy.unitylauncher.upgrades.industry;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;

public class FastMinecartIOListener implements Listener {

    @EventHandler
    public void onMinecartTransfer(InventoryMoveItemEvent e) {
        // Проверяем: источник или получатель — вагонетка-сундук
        if (!(e.getInitiator().getHolder() instanceof StorageMinecart cart)) return;

        Block under = cart.getLocation().getBlock().getRelative(0, -1, 0);

        // Должен быть "спец-блок" (например, медь)
        if (under.getType() != Material.COPPER_BLOCK) return;

        // Проверяем зону
        ZoneInfo zone = UnityLauncher.getInstance().getZoneManager().getZoneAt(cart.getLocation());
        if (zone == null) return;

        String country = zone.getOwnerCountry();
        if (country == null) return;

        // Проверка апгрейда: unity.minecart.fast_io
        if (!hasGroupPermission(country)) return;

        // ⚡ Ускорение: заменяем перемещение 1 предмета на весь стак
        ItemStack moving = e.getItem();

        int slot = e.getSource().first(moving);
        if (slot == -1) return;

        ItemStack stack = e.getSource().getItem(slot);
        if (stack == null) return;

        // Заменяем предмет на полный стак
        ItemStack fullStack = stack.clone();
        e.setItem(fullStack);

        // Убираем его из источника
        e.getSource().setItem(slot, null);
    }

    /** Проверяем права страны (как в SmartHopperListener) */
    private boolean hasGroupPermission(String country) {
        String groupNode = "group." + country.toLowerCase();
        for (var p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(groupNode) && p.hasPermission("unity.minecart.fast_io")) {
                return true;
            }
        }
        return false;
    }
}
