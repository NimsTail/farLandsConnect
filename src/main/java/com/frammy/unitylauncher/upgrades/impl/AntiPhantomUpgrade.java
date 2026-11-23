package com.frammy.unitylauncher.upgrades.impl;

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class AntiPhantomUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.anti_phantom");
    private final Map<UUID, Integer> insomniaFrozen = new ConcurrentHashMap<>();

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return ctx.config().country().antiPhantom().enabled();
    }

    @Override
    protected void onEnable() {
        tasks.add(org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin(), this::tick, 40L, 40L));
    }

    @Override
    protected void onDisable() {
        insomniaFrozen.clear();
    }

    private boolean eligible(Player p) {
        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return false;

        String lc = UpgradeCondition.locationCountryOwner(p.getLocation());
        if (lc == null || !pc.equals(lc)) return false;

        String permBase = C().country().antiPhantom().permBase();
        return countryMaxLevel(pc, permBase, 1) >= 1;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPhantomPreSpawn(PhantomPreSpawnEvent e) {
        Player nearest = nearestPlayer(e);
        if (nearest != null && eligible(nearest)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.PHANTOM) return;

        var loc = e.getLocation();
        Player nearest = null;
        double best = 64 * 64;

        for (Player p : loc.getWorld().getPlayers()) {
            double dx = p.getLocation().getX() - loc.getX();
            double dz = p.getLocation().getZ() - loc.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 < best) { best = d2; nearest = p; }
        }

        if (nearest != null && eligible(nearest)) e.setCancelled(true);
    }

    private void tick() {
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean protect = eligible(p);
            int cur = p.getStatistic(Statistic.TIME_SINCE_REST);

            if (protect) {
                insomniaFrozen.compute(id, (k, base) -> (base == null || cur < base) ? cur : base);
                int base = insomniaFrozen.get(id);
                if (cur != base) p.setStatistic(Statistic.TIME_SINCE_REST, base);
            } else {
                insomniaFrozen.remove(id);
            }
        }
    }

    private static @Nullable Player nearestPlayer(PhantomPreSpawnEvent e) {
        var loc = e.getSpawnLocation();
        Player nearest = null;
        double best = Double.MAX_VALUE;

        for (Player p : loc.getWorld().getPlayers()) {
            double dx = p.getLocation().getX() - loc.getX();
            double dy = p.getLocation().getY() - loc.getY();
            double dz = p.getLocation().getZ() - loc.getZ();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < best) { best = d2; nearest = p; }
        }
        return nearest;
    }
}
