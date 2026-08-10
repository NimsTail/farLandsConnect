package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Diamond_sword64;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Умереть от моба и убить его через 30 сек после респавна" — remembers which
 * specific mob most recently killed a player, then opens a 30s window
 * starting at that player's respawn during which killing that same mob
 * counts.
 */
public class RevengeKillListener implements Listener {

    private static final long WINDOW_MS = 30_000;

    private final Diamond_sword64 diamondSword64;
    private final Map<UUID, UUID> lastKillerMob = new ConcurrentHashMap<>();
    private final Map<UUID, Long> respawnDeadline = new ConcurrentHashMap<>();

    public RevengeKillListener(Diamond_sword64 diamondSword64) {
        this.diamondSword64 = diamondSword64;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (p.getKiller() != null) return; // killed by another player, not a mob

        if (!(p.getLastDamageCause() instanceof EntityDamageByEntityEvent byEntity)) return;
        if (!(byEntity.getDamager() instanceof LivingEntity mob) || mob instanceof Player) return;

        lastKillerMob.put(p.getUniqueId(), mob.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onRespawn(PlayerRespawnEvent e) {
        if (lastKillerMob.containsKey(e.getPlayer().getUniqueId())) {
            respawnDeadline.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + WINDOW_MS);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent e) {
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;

        UUID expectedMob = lastKillerMob.get(killer.getUniqueId());
        if (expectedMob == null || !expectedMob.equals(e.getEntity().getUniqueId())) return;

        Long deadline = respawnDeadline.get(killer.getUniqueId());
        if (deadline == null || System.currentTimeMillis() > deadline) return;

        diamondSword64.grant(killer);
        lastKillerMob.remove(killer.getUniqueId());
        respawnDeadline.remove(killer.getUniqueId());
    }
}
