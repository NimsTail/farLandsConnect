package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_1;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;

/**
 * "Выложить ковёр из 150 блоков розовых лепестков, поставленных игроком" —
 * counts distinct locations the player has placed PINK_PETALS at (a
 * high-water mark; doesn't un-count blocks later broken/griefed).
 */
public class PinkPetalCarpetListener implements Listener {

    private static final int REQUIRED = 150;

    private final Ach1_1 ach1_1;
    private final NamespacedKey locsKey;
    private final NamespacedKey doneKey;

    public PinkPetalCarpetListener(UnityLauncher plugin, Ach1_1 ach1_1) {
        this.ach1_1 = ach1_1;
        this.locsKey = new NamespacedKey(plugin, "ach1_1_petal_locs");
        this.doneKey = new NamespacedKey(plugin, "ach1_1_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.PINK_PETALS) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        Location loc = e.getBlockPlaced().getLocation();
        String locKey = loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();

        Set<String> placed = new HashSet<>();
        String stored = pdc.get(locsKey, PersistentDataType.STRING);
        if (stored != null && !stored.isBlank()) placed.addAll(Set.of(stored.split(",")));
        if (!placed.add(locKey)) return;

        pdc.set(locsKey, PersistentDataType.STRING, String.join(",", placed));

        if (placed.size() >= REQUIRED) {
            ach1_1.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
            pdc.remove(locsKey);
        }
    }
}
