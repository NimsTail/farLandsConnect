package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Obsidian60;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;

public class BiomeSleepListener implements Listener {

    private static final int REQUIRED_BIOMES = 53;

    private final Obsidian60 obsidian60;
    private final NamespacedKey biomesKey;
    private final NamespacedKey doneKey;

    public BiomeSleepListener(UnityLauncher plugin, Obsidian60 obsidian60) {
        this.obsidian60 = obsidian60;
        this.biomesKey = new NamespacedKey(plugin, "obsidian60_biomes");
        this.doneKey = new NamespacedKey(plugin, "obsidian60_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        if (e.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        if (!e.getPlayer().getWorld().getEnvironment().equals(org.bukkit.World.Environment.NORMAL)) return;

        Player p = e.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        String biomeKey = e.getBed().getBiome().getKey().toString();

        Set<String> visited = new HashSet<>();
        String stored = pdc.get(biomesKey, PersistentDataType.STRING);
        if (stored != null && !stored.isBlank()) {
            visited.addAll(Set.of(stored.split(",")));
        }
        if (!visited.add(biomeKey)) return;

        pdc.set(biomesKey, PersistentDataType.STRING, String.join(",", visited));

        if (visited.size() >= REQUIRED_BIOMES) {
            obsidian60.grant(p);
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
            pdc.remove(biomesKey);
        }
    }
}
