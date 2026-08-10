package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Oak_sapling72;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Set;

/**
 * "Иметь полный инвентарь из полностью разных предметов" — polled rather
 * than event-driven since no single Bukkit event reliably fires for every
 * way an inventory can change.
 */
public class UniqueInventoryChecker extends BukkitRunnable {

    private static final long CHECK_INTERVAL_TICKS = 100L; // 5 сек

    private final Oak_sapling72 oakSapling72;
    private final NamespacedKey doneKey;

    public UniqueInventoryChecker(Plugin plugin, Oak_sapling72 oakSapling72) {
        this.oakSapling72 = oakSapling72;
        this.doneKey = new NamespacedKey(plugin, "oaksapling72_done");
    }

    public void start(Plugin plugin) {
        runTaskTimer(plugin, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    @Override
    public void run() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            var pdc = p.getPersistentDataContainer();
            if (pdc.has(doneKey, PersistentDataType.BYTE)) continue;

            ItemStack[] main = p.getInventory().getStorageContents();
            Set<Material> seen = new HashSet<>();
            boolean full = true;
            for (ItemStack item : main) {
                if (item == null || item.getType() == Material.AIR) {
                    full = false;
                    break;
                }
                if (!seen.add(item.getType())) {
                    full = false;
                    break;
                }
            }

            if (full) {
                oakSapling72.grant(p);
                pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
            }
        }
    }
}
