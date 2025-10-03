package com.frammy.unitylauncher.upgrades.redstone;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class RedstoneProtectionListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE && p.hasPermission("unity.bypass.redstone")) return;

        Block b = e.getBlockPlaced();
        Material m = b.getType();
        RedstoneTier tier = RedstoneTier.of(m);
        if (tier == null) return;

        switch (tier) {
            case BASIC -> {
                if (!UpgradeCondition.hasGlobalUpgrade(p, "unity.redstone.basic")) {
                    e.setCancelled(true);
                    p.sendMessage("§cНет доступа к базовому редстоуну!");
                }
            }
            case ADVANCED -> {
                if (!UpgradeCondition.hasGlobalUpgrade(p, "unity.redstone.advanced")) {
                    e.setCancelled(true);
                    p.sendMessage("§cНет доступа к продвинутому редстоуну!");
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != null && e.getHand() == EquipmentSlot.OFF_HAND) return;
        Player p = e.getPlayer();
        if (p.isOp()) return;

        if (e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.PHYSICAL) {
            if (e.getClickedBlock() != null) {
                RedstoneTier tier = RedstoneTier.of(e.getClickedBlock().getType());

                if (tier == RedstoneTier.BASIC) {
                    if (!UpgradeCondition.hasGlobalUpgrade(p, "unity.redstone.basic")) {
                        e.setCancelled(true);
                        p.sendMessage("§cБазовый редстоун доступен только при наличии апгрейда!");
                    }
                }

                if (tier == RedstoneTier.ADVANCED) {
                    if (!UpgradeCondition.hasGlobalUpgrade(p, "unity.redstone.advanced")) {
                        e.setCancelled(true);
                        p.sendMessage("§cПродвинутый редстоун доступен только при наличии апгрейда!");
                    }
                }
            }
        }
    }
}
