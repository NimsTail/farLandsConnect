package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_1;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.Map;

public class CraftAdvancementsListener implements Listener {

    private final UnityLauncher plugin;
    private final Ach1_1 ach1_1;

    private final NamespacedKey cherryMaskKey;
    private final NamespacedKey cherryDoneKey;

    private static final Map<Material, Integer> CHERRY_BITS = new EnumMap<>(Material.class);
    static {
        CHERRY_BITS.put(Material.CHERRY_SLAB, 1 << 0);
        CHERRY_BITS.put(Material.CHERRY_STAIRS, 1 << 1);
        CHERRY_BITS.put(Material.CHERRY_TRAPDOOR, 1 << 2);
        CHERRY_BITS.put(Material.CHERRY_DOOR, 1 << 3);
        CHERRY_BITS.put(Material.CHERRY_FENCE, 1 << 4);
        CHERRY_BITS.put(Material.CHERRY_FENCE_GATE, 1 << 5);
        CHERRY_BITS.put(Material.CHERRY_BUTTON, 1 << 6);
        CHERRY_BITS.put(Material.CHERRY_PRESSURE_PLATE, 1 << 7);
    }

    private static final int ALL = (1 << 8) - 1; // 0xFF

    public CraftAdvancementsListener(UnityLauncher plugin, Ach1_1 ach1_1) {
        this.plugin = plugin;
        this.ach1_1 = ach1_1;
        this.cherryMaskKey = new NamespacedKey(plugin, "ach1_1_cherry_mask");
        this.cherryDoneKey = new NamespacedKey(plugin, "ach1_1_cherry_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        ItemStack result = e.getRecipe().getResult();
        Material mat = result.getType();

        Integer bit = CHERRY_BITS.get(mat);
        if (bit == null) return; // не вишня/не из списка

        PersistentDataContainer pdc = p.getPersistentDataContainer();

        // уже выполнено — не трогаем
        if (pdc.has(cherryDoneKey, PersistentDataType.BYTE)) return;

        int mask = pdc.getOrDefault(cherryMaskKey, PersistentDataType.INTEGER, 0);
        int newMask = mask | bit;

        if (newMask != mask) {
            pdc.set(cherryMaskKey, PersistentDataType.INTEGER, newMask);
        }

        if (newMask == ALL) {
            ach1_1.grant(p); // ✅ выдаём достижение (и giveReward сработает сам)
            pdc.set(cherryDoneKey, PersistentDataType.BYTE, (byte) 1); // чтобы больше не считать
        }
    }
}

