package com.frammy.unitylauncher.upgrades.industry;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

public class ItemBrandingListener implements Listener {

    private static final boolean DEBUG = false;
    private final Logger log = UnityLauncher.getInstance().getLogger();
    private void debug(String msg) { if (DEBUG) log.info("[ItemBranding] " + msg); }

    private static final String PERM = "unity.item.branding";
    private static final String BRAND_PREFIX = "§7Made by ";

    /** Брендирование результата крафта */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (!p.hasPermission(PERM)) {
            debug("onCraft: у игрока " + p.getName() + " нет разрешения " + PERM);
            return;
        }

        ItemStack result = e.getCurrentItem();
        if (result == null) return;

        boolean added = addBranding(result, p.getName());
        debug("onCraft: " + (added ? "бренд добавлен" : "бренд уже был"));
    }

    /** Добавляем метку в lore */
    private boolean addBranding(ItemStack item, String playerName) {
        if (item == null) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        String branding = BRAND_PREFIX + playerName;

        if (lore.contains(branding)) return false;

        lore.add(branding);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return true;
    }
}
