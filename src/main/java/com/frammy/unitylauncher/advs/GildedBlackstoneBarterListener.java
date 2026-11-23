package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Gilded_blackstone57;
import org.bukkit.Material;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class GildedBlackstoneBarterListener implements Listener {

    private final Gilded_blackstone57 gildedBlackstone57;

    public GildedBlackstoneBarterListener(Gilded_blackstone57 gildedBlackstone57) {
        this.gildedBlackstone57 = gildedBlackstone57;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Piglin)) return;

        Player p = e.getPlayer();
        Material mainHand = p.getInventory().getItemInMainHand().getType();
        Material offHand = p.getInventory().getItemInOffHand().getType();
        if (mainHand != Material.GILDED_BLACKSTONE && offHand != Material.GILDED_BLACKSTONE) return;

        gildedBlackstone57.grant(p);
    }
}
