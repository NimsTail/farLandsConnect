package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class FarmlandProtectionUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.farmland_protection");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().farmland().enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFarmlandTrample_EntityChange(EntityChangeBlockEvent e) {
        var cfg = C().country().farmland();

        Block b = e.getBlock();
        if (b.getType() != Material.FARMLAND) return;

        String country = UpgradeCondition.locationCountryOwner(b.getLocation());
        if (country == null || country.isBlank()) return;

        int lvl = countryMaxLevel(country, cfg.permBase(), 2);
        if (lvl <= 0) return;

        if (lvl >= 2) {
            e.setCancelled(true);
            if (C().core().debug()) plugin().getLogger().info("[Upgrades/Farmland] protect L2 (EntityChange)");
            return;
        }

        if (e.getEntity() instanceof Player p) {
            if (p.getFallDistance() < cfg.bigFallThreshold()) {
                e.setCancelled(true);
                if (C().core().debug()) plugin().getLogger().info("[Upgrades/Farmland] protect L1 (EntityChange) fall=" + p.getFallDistance());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFarmlandTrample_Physical(PlayerInteractEvent e) {
        var cfg = C().country().farmland();

        if (e.getAction() != Action.PHYSICAL) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.FARMLAND) return;

        String country = UpgradeCondition.locationCountryOwner(block.getLocation());
        if (country == null || country.isBlank()) return;

        int lvl = countryMaxLevel(country, cfg.permBase(), 2);
        if (lvl <= 0) return;

        if (lvl >= 2) {
            e.setCancelled(true);
            if (C().core().debug()) plugin().getLogger().info("[Upgrades/Farmland] protect L2 (PHYSICAL)");
            return;
        }

        Player p = e.getPlayer();
        if (p.getFallDistance() < cfg.bigFallThreshold()) {
            e.setCancelled(true);
            if (C().core().debug()) plugin().getLogger().info("[Upgrades/Farmland] protect L1 (PHYSICAL) fall=" + p.getFallDistance());
        }
    }
}
