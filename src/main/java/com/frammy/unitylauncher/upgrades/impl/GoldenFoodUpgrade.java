package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class GoldenFoodUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.golden_food");

    private volatile Set<org.bukkit.Material> cachedPremiumFoods = EnumSet.noneOf(org.bukkit.Material.class);

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().goldenFood().enabled();
    }

    @Override
    protected void onEnable() {
        rebuildFoodsCache();
    }

    @Override
    protected void onDisable() {
        cachedPremiumFoods = EnumSet.noneOf(org.bukkit.Material.class);
    }

    private void rebuildFoodsCache() {
        var cfg = C().country().goldenFood();
        EnumSet<org.bukkit.Material> set = EnumSet.noneOf(org.bukkit.Material.class);

        for (String s : cfg.premiumFoods()) {
            if (s == null || s.isBlank()) continue;
            try {
                set.add(org.bukkit.Material.valueOf(s.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                if (C().core().debug()) plugin().getLogger().warning("[Upgrades/GoldenFood] unknown material in premiumFoods: " + s);
            }
        }
        cachedPremiumFoods = set;
    }

    private boolean hasUnlock(Player p) {
        var cfg = C().country().goldenFood();
        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return false;
        return countryMaxLevel(pc, cfg.permBase(), 1) >= 1;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!cachedPremiumFoods.contains(e.getItem().getType())) return;

        Player p = e.getPlayer();
        if (hasUnlock(p)) return;

        var cfg = C().country().goldenFood();
        e.setCancelled(true);

        org.bukkit.Bukkit.getScheduler().runTask(plugin(), p::updateInventory);

        String msg = cfg.msgConsume();
        if (msg != null && !msg.isBlank()) p.sendMessage(msg);

        if (cfg.sfx()) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        ItemStack result = e.getCurrentItem();
        if (result == null) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (!cachedPremiumFoods.contains(result.getType())) return;
        if (hasUnlock(p)) return;

        var cfg = C().country().goldenFood();
        e.setCancelled(true);

        org.bukkit.Bukkit.getScheduler().runTask(plugin(), p::updateInventory);

        String msg = cfg.msgCraft();
        if (msg != null && !msg.isBlank()) p.sendMessage(msg);

        if (cfg.sfx()) {
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.8f);
        }
    }
}
