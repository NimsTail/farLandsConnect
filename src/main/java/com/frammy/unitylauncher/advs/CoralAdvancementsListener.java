package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_2_1;
import com.frammy.unitylauncher.advs.achievements.Ach1_2_2;
import com.frammy.unitylauncher.advs.achievements.Ach1_2_3;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.loot.LootTables;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashSet;
import java.util.Set;

/**
 * Ach1_2_1/2/3 conditions were redefined per infra/frames-catalog.md §6 —
 * this replaces the old "kill on land" / "obtain" / "dolphin drop" checks.
 */
public class CoralAdvancementsListener implements Listener {

    private final Ach1_2_1 achBlueHold;
    private final Ach1_2_2 achRedChest;
    private final Ach1_2_3 achPurpleShipwrecks;

    private final NamespacedKey blueDoneKey;
    private final NamespacedKey redDoneKey;
    private final NamespacedKey shipwreckLocsKey;
    private final NamespacedKey purpleDoneKey;

    // Ach1_2_1: держать одновременно (блок + коралл + веер) — только "живые".
    private static final Set<Material> BLUE_REQUIRED = Set.of(
            Material.TUBE_CORAL_BLOCK,
            Material.TUBE_CORAL,
            Material.TUBE_CORAL_FAN
    );

    // Ach1_2_2: держать одновременно в одном сундуке (форма может быть живой
    // или мёртвой — считаем как один и тот же "вариант").
    private static final Set<Material> RED_SHAPE_BLOCK = Set.of(Material.FIRE_CORAL_BLOCK, Material.DEAD_FIRE_CORAL_BLOCK);
    private static final Set<Material> RED_SHAPE_CORAL = Set.of(Material.FIRE_CORAL, Material.DEAD_FIRE_CORAL);
    private static final Set<Material> RED_SHAPE_FAN = Set.of(Material.FIRE_CORAL_FAN, Material.DEAD_FIRE_CORAL_FAN);

    // Ach1_2_3: 25 разных кораблекрушений/руин океана.
    private static final Set<NamespacedKey> SHIPWRECK_LOOT_TABLES = Set.of(
            LootTables.SHIPWRECK_SUPPLY.getKey(),
            LootTables.SHIPWRECK_MAP.getKey(),
            LootTables.SHIPWRECK_TREASURE.getKey(),
            LootTables.UNDERWATER_RUIN_SMALL.getKey(),
            LootTables.UNDERWATER_RUIN_BIG.getKey()
    );

    public CoralAdvancementsListener(UnityLauncher plugin,
                                      Ach1_2_1 achBlueHold,
                                      Ach1_2_2 achRedChest,
                                      Ach1_2_3 achPurpleShipwrecks) {
        this.achBlueHold = achBlueHold;
        this.achRedChest = achRedChest;
        this.achPurpleShipwrecks = achPurpleShipwrecks;

        this.blueDoneKey = new NamespacedKey(plugin, "ach1_2_1_done");
        this.redDoneKey = new NamespacedKey(plugin, "ach1_2_2_done");
        this.shipwreckLocsKey = new NamespacedKey(plugin, "ach1_2_3_locs");
        this.purpleDoneKey = new NamespacedKey(plugin, "ach1_2_3_done");
    }

    // --------------- Ach1_2_1: все варианты синего коралла в инвентаре разом ---------------
    @EventHandler(ignoreCancelled = true)
    public void onPickupBlue(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        checkBlueCoral(p);
    }

    @EventHandler
    public void onJoinCheckBlue(PlayerJoinEvent e) {
        checkBlueCoral(e.getPlayer());
    }

    private void checkBlueCoral(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(blueDoneKey, PersistentDataType.BYTE)) return;

        PlayerInventory inv = p.getInventory();
        boolean hasAll = BLUE_REQUIRED.stream().allMatch(inv::contains);
        if (hasAll) {
            achBlueHold.grant(p);
            pdc.set(blueDoneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    // --------------- Ach1_2_2: все варианты красного коралла в одном сундуке ---------------
    @EventHandler(ignoreCancelled = true)
    public void onContainerClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(redDoneKey, PersistentDataType.BYTE)) return;

        Inventory inv = e.getInventory();
        InventoryHolder holder = inv.getHolder();
        // Любой контейнер-хранилище (сундук/бочка/сдвоенный сундук и т.п.),
        // не мерчант/крафт/прочий служебный инвентарь.
        if (!(holder instanceof org.bukkit.block.Chest || holder instanceof org.bukkit.block.DoubleChest
                || holder instanceof org.bukkit.block.Barrel)) return;

        boolean hasBlock = containsAny(inv, RED_SHAPE_BLOCK);
        boolean hasCoral = containsAny(inv, RED_SHAPE_CORAL);
        boolean hasFan = containsAny(inv, RED_SHAPE_FAN);

        if (hasBlock && hasCoral && hasFan) {
            achRedChest.grant(p);
            pdc.set(redDoneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    private boolean containsAny(Inventory inv, Set<Material> options) {
        for (ItemStack stack : inv.getContents()) {
            if (stack != null && options.contains(stack.getType())) return true;
        }
        return false;
    }

    // --------------- Ach1_2_3: 25 разных кораблекрушений/руин океана ---------------
    @EventHandler(ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!SHIPWRECK_LOOT_TABLES.contains(e.getLootTable().getKey())) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(purpleDoneKey, PersistentDataType.BYTE)) return;

        org.bukkit.Location loc = e.getInventoryHolder() != null
                ? e.getInventoryHolder().getInventory().getLocation()
                : null;
        if (loc == null || loc.getWorld() == null) return;

        String locKey = loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();

        String stored = pdc.get(shipwreckLocsKey, PersistentDataType.STRING);
        Set<String> visited = new HashSet<>();
        if (stored != null && !stored.isBlank()) {
            visited.addAll(Set.of(stored.split(",")));
        }
        if (!visited.add(locKey)) return; // уже считали этот сундук

        pdc.set(shipwreckLocsKey, PersistentDataType.STRING, String.join(",", visited));

        if (visited.size() >= 25) {
            achPurpleShipwrecks.grant(p);
            pdc.set(purpleDoneKey, PersistentDataType.BYTE, (byte) 1);
            pdc.remove(shipwreckLocsKey);
        }
    }
}
