package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition.GatingAction;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.CountryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.inventory.SmithItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class NetheriteAndBeaconUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("gating.netherite_beacon");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private volatile java.util.Set<Material> craftBlocked = java.util.Set.of();
    private volatile java.util.Set<Material> useBlocked = java.util.Set.of();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        CountryCfg.NetheriteBeaconGatingCfg cfg = ctx.config().country().netheriteBeaconGating();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().netheriteBeaconGating();
        craftBlocked = UpgradeCondition.parseMaterialSet(cfg.blockCraft());
        useBlocked = UpgradeCondition.parseMaterialSet(cfg.blockUse());
    }

    private String permBaseFor(Material m) {
        var cfg = C().country().netheriteBeaconGating();
        // если это BEACON — проверяем beaconPermBase, иначе считаем что это нетерит-гейт
        return (m == Material.BEACON) ? cfg.beaconPermBase() : cfg.netheritePermBase();
    }

    private boolean allow(Player p, Material m, GatingAction action) {
        if (p == null || m == null) return true;

        var cfg = C().country().netheriteBeaconGating();

        // быстрый отсев: если материал вообще не в списках — разрешаем
        if (action == GatingAction.CRAFT) {
            if (!craftBlocked.contains(m)) return true;
        } else {
            if (!useBlocked.contains(m)) return true;
        }

        String base = permBaseFor(m);

        return UpgradeCondition.gatingAllowedByCountry(
                p, m, action,
                craftBlocked, useBlocked,
                base,
                1
        );
    }

    private String denyMsgFor(Material m) {
        var cfg = C().country().netheriteBeaconGating();
        return (m == Material.BEACON) ? cfg.errmsgBeacon() : cfg.errmsgNetherite();
    }

    private void deny(Player p, String msg) {
        if (p == null) return;
        if (msg != null && !msg.isBlank()) p.sendMessage(msg);
        try { p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f); } catch (Throwable ignored) {}
    }

    private static boolean isArmor(Material m) {
        return m == Material.NETHERITE_HELMET
                || m == Material.NETHERITE_CHESTPLATE
                || m == Material.NETHERITE_LEGGINGS
                || m == Material.NETHERITE_BOOTS;
    }

    private static boolean isNetheriteArmor(Material m) {
        return m == Material.NETHERITE_HELMET
                || m == Material.NETHERITE_CHESTPLATE
                || m == Material.NETHERITE_LEGGINGS
                || m == Material.NETHERITE_BOOTS;
    }

    private static ItemStack lockedResultItem(String msg) {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§cЗапрещено");
            if (msg != null && !msg.isBlank()) {
                meta.setLore(List.of(msg, "§7Открой апгрейд страны."));
            } else {
                meta.setLore(List.of("§7Открой апгрейд страны."));
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * Принудительная проверка гейта (даже если материал не в списках).
     * Используем для нетерит-брони по ПКМ и для урона.
     */
    private boolean allowForced(Player p, Material m, GatingAction action) {
        if (p == null || m == null) return true;

        String base = permBaseFor(m);
        return UpgradeCondition.gatingAllowedByCountry(
                p, m, action,
                java.util.Set.of(m), java.util.Set.of(m),
                base,
                1
        );
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPrepareSmithing(PrepareSmithingEvent e) {
        ItemStack res = e.getResult();
        if (res == null) return;

        Material m = res.getType();

        // Мы хотим именно "крафт" запретить, поэтому проверяем craftBlocked
        if (!craftBlocked.contains(m)) return;

        // Нельзя: показываем барьер с подсказкой
        e.setResult(lockedResultItem(denyMsgFor(m)));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSmith(SmithItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack res = e.getCurrentItem();
        if (res == null) return;

        // Если там BARRIER — это наш "запрещено"
        if (res.getType() == Material.BARRIER) {
            e.setCancelled(true);
            deny(p, "§cЭтот предмет запрещён. Открой апгрейд страны.");
            return;
        }

        Material m = res.getType();
        if (!allow(p, m, GatingAction.CRAFT)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(m));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryEquip(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // 1) Перетаскивание курсором в слот брони
        if (e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.ARMOR) {
            ItemStack cursor = e.getCursor();
            if (cursor != null) {
                Material m = cursor.getType();
                if (!allow(p, m, GatingAction.USE)) {
                    e.setCancelled(true);
                    deny(p, denyMsgFor(m));
                }
            }
            return;
        }

        // 2) Shift-клик по предмету (сервер сам пытается надеть в armor slot)
        if (e.isShiftClick()) {
            ItemStack cur = e.getCurrentItem();
            if (cur == null) return;

            Material m = cur.getType();
            if (!isArmor(m)) return; // нас интересует именно авто-экип
            if (!allow(p, m, GatingAction.USE)) {
                e.setCancelled(true);
                deny(p, denyMsgFor(m));
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player p = null;

        if (e.getDamager() instanceof Player pp) {
            p = pp;
        } else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pp) {
            // на будущее (если вдруг захочешь гейтить арбалеты/луки и т.п.)
            p = pp;
        }

        if (p == null) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return;

        Material m = hand.getType();
        if (!allow(p, m, GatingAction.USE)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(m));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack res = e.getCurrentItem();
        if (res == null) return;

        Material m = res.getType();
        if (!allow(p, m, GatingAction.CRAFT)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(m));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlockPlaced().getType();

        if (!allow(p, m, GatingAction.USE)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(m));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;

        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack it = e.getItem();
        if (it == null) return;

        Player p = e.getPlayer();
        Material m = it.getType();

        // ВАЖНО: броню по ПКМ проверяем принудительно (даже если забыли добавить в useBlocked)
        if (isNetheriteArmor(m) && !allowForced(p, m, GatingAction.USE)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(m));
            return;
        }

        if (!allow(p, m, GatingAction.USE)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(m));
        }
    }

}
