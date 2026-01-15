package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_2_1;
import com.frammy.unitylauncher.advs.achievements.Ach1_2_2;
import com.frammy.unitylauncher.advs.achievements.Ach1_2_3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

public class CoralAdvancementsListener implements Listener {

    private final UnityLauncher plugin;

    private final Ach1_2_1 achBlueKill;   // убить на суше синий (tube)
    private final Ach1_2_2 achRedObtain;  // добыть красный (fire)
    private final Ach1_2_3 achPurpleDolphin; // поднять фиолетовый коралл, выброшенный дельфином

    private final NamespacedKey blueMaskKey, blueDoneKey;
    private final NamespacedKey redMaskKey, redDoneKey;
    private final NamespacedKey purpleDoneKey;

    private static final int ALL4 = 0b1111;

    // ---- BLUE (TUBE) kill-on-land ----
    private static final Map<Material, Integer> BLUE_LIVE_BITS = new EnumMap<>(Material.class);
    private static final Map<Material, Material> BLUE_LIVE_TO_DEAD = new EnumMap<>(Material.class);

    // ---- RED (FIRE) obtain ----
    private static final Map<Material, Integer> RED_BITS = new EnumMap<>(Material.class);

    static {
        // BLUE live -> bit
        BLUE_LIVE_BITS.put(Material.TUBE_CORAL_BLOCK, 1 << 0);
        BLUE_LIVE_BITS.put(Material.TUBE_CORAL, 1 << 1);
        BLUE_LIVE_BITS.put(Material.TUBE_CORAL_FAN, 1 << 2);
        BLUE_LIVE_BITS.put(Material.TUBE_CORAL_WALL_FAN, 1 << 3);

        // BLUE live -> dead
        BLUE_LIVE_TO_DEAD.put(Material.TUBE_CORAL_BLOCK, Material.DEAD_TUBE_CORAL_BLOCK);
        BLUE_LIVE_TO_DEAD.put(Material.TUBE_CORAL, Material.DEAD_TUBE_CORAL);
        BLUE_LIVE_TO_DEAD.put(Material.TUBE_CORAL_FAN, Material.DEAD_TUBE_CORAL_FAN);
        BLUE_LIVE_TO_DEAD.put(Material.TUBE_CORAL_WALL_FAN, Material.DEAD_TUBE_CORAL_WALL_FAN);

        // RED obtain (по форме 4) — живой + мёртвый
        RED_BITS.put(Material.FIRE_CORAL_BLOCK, 1 << 0);
        RED_BITS.put(Material.DEAD_FIRE_CORAL_BLOCK, 1 << 0);

        RED_BITS.put(Material.FIRE_CORAL, 1 << 1);
        RED_BITS.put(Material.DEAD_FIRE_CORAL, 1 << 1);

        RED_BITS.put(Material.FIRE_CORAL_FAN, 1 << 2);
        RED_BITS.put(Material.DEAD_FIRE_CORAL_FAN, 1 << 2);

        RED_BITS.put(Material.FIRE_CORAL_WALL_FAN, 1 << 3);
        RED_BITS.put(Material.DEAD_FIRE_CORAL_WALL_FAN, 1 << 3);
    }

    // ---- PURPLE коралл (ты выбрал Bubble как “фиолетовый”) ----
    private static final Set<Material> PURPLE_CORAL_ITEMS = Set.of(
            Material.BUBBLE_CORAL,
            Material.BUBBLE_CORAL_FAN,
            Material.BUBBLE_CORAL_BLOCK,
            Material.DEAD_BUBBLE_CORAL,
            Material.DEAD_BUBBLE_CORAL_FAN,
            Material.DEAD_BUBBLE_CORAL_BLOCK
    );

    // метка на Item entity (чтобы засчитывалось только с дельфина)
    private static final String DOLPHIN_TAG = "fl_dolphin_purple_coral";

    public CoralAdvancementsListener(UnityLauncher plugin,
                                     Ach1_2_1 achBlueKill,
                                     Ach1_2_2 achRedObtain,
                                     Ach1_2_3 achPurpleDolphin) {
        this.plugin = plugin;
        this.achBlueKill = achBlueKill;
        this.achRedObtain = achRedObtain;
        this.achPurpleDolphin = achPurpleDolphin;

        this.blueMaskKey = new NamespacedKey(plugin, "ach1_2_1_blue_mask");
        this.blueDoneKey = new NamespacedKey(plugin, "ach1_2_1_done");

        this.redMaskKey = new NamespacedKey(plugin, "ach1_2_2_red_mask");
        this.redDoneKey = new NamespacedKey(plugin, "ach1_2_2_done");

        this.purpleDoneKey = new NamespacedKey(plugin, "ach1_2_3_done");
    }

    // --------------- Ach1_2_1: "убить на суше" (BlockPlace + delayed check) ---------------
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (p.getPersistentDataContainer().has(blueDoneKey, PersistentDataType.BYTE)) return;

        Block placed = e.getBlockPlaced();
        Material live = placed.getType();

        Integer bit = BLUE_LIVE_BITS.get(live);
        if (bit == null) return;

        Material expectedDead = BLUE_LIVE_TO_DEAD.get(live);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;

            // блок мог пропасть/сломаться
            if (placed.getType() != expectedDead) return;

            PersistentDataContainer pdc = p.getPersistentDataContainer();
            if (pdc.has(blueDoneKey, PersistentDataType.BYTE)) return;

            int mask = pdc.getOrDefault(blueMaskKey, PersistentDataType.INTEGER, 0);
            int newMask = mask | bit;

            if (newMask != mask) pdc.set(blueMaskKey, PersistentDataType.INTEGER, newMask);

            if (newMask == ALL4) {
                achBlueKill.grant(p);
                pdc.set(blueDoneKey, PersistentDataType.BYTE, (byte) 1);
            }
        }, 60L);
    }

    // --------------- Ach1_2_2 + Ach1_2_3: pickup ---------------
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        org.bukkit.entity.Item itemEnt = e.getItem();
        Material mat = itemEnt.getItemStack().getType();

        // ---- Ach1_2_2: "добыть" красный коралл (по форме 4) ----
        if (!pdc.has(redDoneKey, PersistentDataType.BYTE)) {
            Integer bit = RED_BITS.get(mat);
            if (bit != null) {
                int mask = pdc.getOrDefault(redMaskKey, PersistentDataType.INTEGER, 0);
                int newMask = mask | bit;

                if (newMask != mask) pdc.set(redMaskKey, PersistentDataType.INTEGER, newMask);

                if (newMask == ALL4) {
                    achRedObtain.grant(p);
                    pdc.set(redDoneKey, PersistentDataType.BYTE, (byte) 1);
                }
            }
        }

        // ---- Ach1_2_3: поднять фиолетовый коралл, выброшенный дельфином ----
        if (!pdc.has(purpleDoneKey, PersistentDataType.BYTE)) {
            if (PURPLE_CORAL_ITEMS.contains(mat) && itemEnt.getScoreboardTags().contains(DOLPHIN_TAG)) {
                achPurpleDolphin.grant(p);
                pdc.set(purpleDoneKey, PersistentDataType.BYTE, (byte) 1);

                // на всякий случай уберём метку
                itemEnt.removeScoreboardTag(DOLPHIN_TAG);
            }
        }
    }

    // --------------- Помечаем дропы дельфина ---------------
    @EventHandler(ignoreCancelled = true)
    public void onDolphinDeath(EntityDeathEvent e) {
        if (e.getEntityType() != EntityType.DOLPHIN) return;

        // если в дропах нет нужного коралла — выходим
        boolean hasPurple = e.getDrops().stream().anyMatch(it -> PURPLE_CORAL_ITEMS.contains(it.getType()));
        if (!hasPurple) return;

        // Через 1 тик найдём Item-entity рядом и пометим их
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Location loc = e.getEntity().getLocation();
            for (Entity ent : loc.getWorld().getNearbyEntities(loc, 2.0, 2.0, 2.0)) { // чуть меньше радиус
                if (!(ent instanceof org.bukkit.entity.Item itemEnt)) continue;

                Material t = itemEnt.getItemStack().getType();
                if (!PURPLE_CORAL_ITEMS.contains(t)) continue;

                itemEnt.addScoreboardTag(DOLPHIN_TAG);

                // снять метку через минуту
                plugin.getServer().getScheduler().runTaskLater(plugin,
                        () -> itemEnt.removeScoreboardTag(DOLPHIN_TAG),
                        20L * 60
                );
            }
        }, 1L);
    }
}
