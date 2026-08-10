package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Dragon_head85;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

public class VoidElytraDeathListener implements Listener {

    private final Dragon_head85 dragonHead85;

    public VoidElytraDeathListener(Dragon_head85 dragonHead85) {
        this.dragonHead85 = dragonHead85;
    }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        EntityDamageEvent cause = p.getLastDamageCause();
        if (cause == null || cause.getCause() != EntityDamageEvent.DamageCause.VOID) return;

        var equipment = p.getEquipment();
        if (equipment == null || equipment.getChestplate() == null) return;
        if (equipment.getChestplate().getType() != Material.ELYTRA) return;

        dragonHead85.grant(p);
    }
}
