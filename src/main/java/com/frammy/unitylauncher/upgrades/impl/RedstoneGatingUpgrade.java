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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public final class RedstoneGatingUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("gating.redstone");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private volatile Set<Material> craftBlocked0 = Set.of();
    private volatile Set<Material> useBlocked0 = Set.of();
    private volatile Set<Material> craftBlocked1 = Set.of();
    private volatile Set<Material> useBlocked1 = Set.of();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        CountryCfg.RedstoneGatingCfg cfg = ctx.config().country().redstoneGating();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().country().redstoneGating();
        craftBlocked0 = UpgradeCondition.parseMaterialSet(cfg.blockCraftLvl0());
        useBlocked0 = UpgradeCondition.parseMaterialSet(cfg.blockUseLvl0());
        craftBlocked1 = UpgradeCondition.parseMaterialSet(cfg.blockCraftLvl1());
        useBlocked1 = UpgradeCondition.parseMaterialSet(cfg.blockUseLvl1());
    }

    private boolean allow(Player p, Material m, GatingAction action) {
        var cfg = C().country().redstoneGating();

        if (p == null || m == null) return true;

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        int lvl = UpgradeCondition.countryMaxLevel(pc, cfg.permBase(), 2);

        // lvl2: всё разрешено (по этому апгрейду)
        if (lvl >= 2) return true;

        // lvl0/lvl1: используем соответствующие блок-листы
        Set<Material> bc = (lvl <= 0) ? craftBlocked0 : craftBlocked1;
        Set<Material> bu = (lvl <= 0) ? useBlocked0 : useBlocked1;

        return UpgradeCondition.gatingAllowedByCountry(
                p, m, action,
                bc, bu,
                cfg.permBase(),
                1
        );
    }

    private void deny(Player p, String msg) {
        if (p == null) return;
        if (msg != null && !msg.isBlank()) p.sendMessage(msg);
        try { p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f); } catch (Throwable ignored) {}
    }

    private String denyMsgFor(Player p) {
        var cfg = C().country().redstoneGating();
        if (p == null) return cfg.errmsgLvl0();

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        int lvl = UpgradeCondition.countryMaxLevel(pc, cfg.permBase(), 2);

        return (lvl <= 0) ? cfg.errmsgLvl0() : cfg.errmsgLvl1();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack res = e.getCurrentItem();
        if (res == null) return;

        Material m = res.getType();
        if (!allow(p, m, GatingAction.CRAFT)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(p));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlockPlaced().getType();
        if (!allow(p, m, GatingAction.USE)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(p));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (it == null) return;

        Player p = e.getPlayer();
        Material m = it.getType();
        if (!allow(p, m, GatingAction.USE)) {
            e.setCancelled(true);
            deny(p, denyMsgFor(p));
        }
    }
}
