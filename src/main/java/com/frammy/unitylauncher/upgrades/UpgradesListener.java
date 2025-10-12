package com.frammy.unitylauncher.upgrades;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Единый слушатель апгрейдов.
 * Поддерживает:
 * - Redstone (basic/advanced)
 * - Item Branding при крафте
 * - Furnace Ore Boost
 * - Smart Hopper (уровни 0/1/2)
 */
public class UpgradesListener implements Listener {

    public UpgradesListener() {
    }
    public static void registerAll(JavaPlugin plugin) {
        if (plugin == null) return;
        Bukkit.getPluginManager().registerEvents(new UpgradesListener(), plugin);
    }

    /* ==============================
       1) Redstone
       ============================== */

    private static final EnumSet<Material> BASIC_REDS = EnumSet.of(
            Material.REDSTONE,
            Material.REDSTONE_WIRE,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_BLOCK,
            Material.LEVER,
            Material.NOTE_BLOCK,
            Material.PISTON,
            Material.STICKY_PISTON,
            Material.DISPENSER,
            Material.DROPPER
    );

    private static final EnumSet<Material> ADVANCED_REDS = EnumSet.of(
            Material.REPEATER,
            Material.COMPARATOR,
            Material.OBSERVER,
            Material.DAYLIGHT_DETECTOR,
            Material.SCULK_SENSOR,
            Material.CALIBRATED_SCULK_SENSOR,
            Material.HOPPER,
            Material.POWERED_RAIL,
            Material.DETECTOR_RAIL,
            Material.ACTIVATOR_RAIL,
            Material.TRIPWIRE_HOOK,
            Material.TRIPWIRE
    );

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onRedstonePlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        boolean hasBasic = UpgradeCondition.hasGlobalUpgrade(p, "unity.redstone.basic");
        boolean hasAdvanced = UpgradeCondition.hasGlobalUpgrade(p, "unity.redstone.advanced");
        Material type = e.getBlockPlaced().getType();

        if (!hasBasic && !hasAdvanced) {
            if (BASIC_REDS.contains(type) || ADVANCED_REDS.contains(type)) {
                denyRedstonePlaceActionBar(e, p, "Требуется апгрейд: Базовый редстоун!");
            }
            return;
        }
        if (hasBasic && !hasAdvanced && ADVANCED_REDS.contains(type)) {
            denyRedstonePlaceActionBar(e, p, "Требуется апгрейд: Продвинутый редстоун!");
        }
    }

    private void denyRedstonePlaceActionBar(BlockPlaceEvent e, Player p, String msg) {
        e.setCancelled(true);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "⚠ " + msg));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
    }

    /* ==============================
       3) Furnace Ore Boost (+15% к выходу)
       ============================== */

    private static final Set<Material> ORE_SMELT_OUTPUTS = EnumSet.of(
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT
    );
    private static final double ORE_BOOST_CHANCE = 0.15;

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFurnaceSmelt(FurnaceSmeltEvent e) {
        Block b = e.getBlock();
        ItemStack result = e.getResult();
        if (result.getType() == Material.AIR) return;
        if (!UpgradeCondition.hasGlobalUpgradeAt(b.getLocation(), "unity.furnace.ore_boost")) return;

        if (ORE_SMELT_OUTPUTS.contains(result.getType())) {
            if (ThreadLocalRandom.current().nextDouble() < ORE_BOOST_CHANCE) {
                ItemStack bonus = result.clone();
                int max = Math.min(bonus.getMaxStackSize(), 64);
                int newAmount = Math.min(bonus.getAmount() + 1, max);
                bonus.setAmount(newAmount);
                e.setResult(bonus);

                b.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, b.getLocation().add(0.5, 1.0, 0.5), 2);
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.3f, 1.2f);
            }
        }
    }

    /* ==============================
       4) Smart Hoppers
       ============================== */

    public static final class SmartHopperListener implements Listener {

        private final Plugin plugin;
        private final Map<String, Boolean> flipMap = new ConcurrentHashMap<>();

        public SmartHopperListener(Plugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onInventoryMove(InventoryMoveItemEvent e) {
            HopperRef hopperSrc = asHopper(e.getSource());
            HopperRef hopperDst = asHopper(e.getDestination());
            if (hopperSrc == null && hopperDst == null) return;

            Location anchor = hopperSrc != null ? hopperSrc.location() : hopperDst.location();
            int level = getUpgradeLevel(anchor); // 0 — без апгрейда; 1 — ваниль; 2 — перенос целого слота

            if (level == 0) {
                String key = (hopperSrc != null ? hopperSrc.key() : hopperDst.key());
                boolean flip = flipMap.getOrDefault(key, false);
                if (flip) e.setCancelled(true);
                flipMap.put(key, !flip);
                return;
            }

            if (level == 1) return;

            if (hopperSrc != null) {
                final Inventory hopperInv = e.getSource();
                final Inventory dst = e.getDestination();
                new BukkitRunnable() {
                    @Override public void run() {
                        moveFullestSlotFromHopper(hopperInv, dst);
                    }
                }.runTaskLater(plugin, 1L);
            }
        }

        private int getUpgradeLevel(Location loc) {
            try {
                if (UpgradeCondition.hasGlobalOrIndustrialUpgradeAt(loc, "unity.hopper.smart.2")) return 2;
                if (UpgradeCondition.hasGlobalOrIndustrialUpgradeAt(loc, "unity.hopper.smart.1")) return 1;
            } catch (Throwable ignored) {}
            return 0;
        }

        /** Переносит целиком самый наполненный стек из хоппера в приёмник, без дюпа. */
        private void moveFullestSlotFromHopper(Inventory hopperInv, Inventory dst) {
            if (hopperInv == null || dst == null) return;

            int bestSlot = -1;
            ItemStack best = null;
            ItemStack[] contents = hopperInv.getContents();

            for (int i = 0; i < contents.length; i++) {
                ItemStack it = contents[i];
                if (it == null || it.getType() == Material.AIR) continue;
                if (best == null || it.getAmount() > best.getAmount()) {
                    best = it; bestSlot = i;
                }
            }
            if (bestSlot < 0) return;

            ItemStack toMove = best.clone();
            toMove.getAmount();

            hopperInv.setItem(bestSlot, null);

            Map<Integer, ItemStack> leftover = dst.addItem(toMove);

            if (!leftover.isEmpty()) {
                ItemStack rest = leftover.values().iterator().next();
                int restAmt = (rest != null ? rest.getAmount() : 0);
                if (restAmt > 0) {
                    ItemStack slotNow = hopperInv.getItem(bestSlot);
                    if (slotNow == null || slotNow.getType() == Material.AIR) {
                        hopperInv.setItem(bestSlot, rest);
                    } else {
                        HashMap<Integer, ItemStack> backLeft = hopperInv.addItem(rest);
                        if (!backLeft.isEmpty()) {
                            Location drop = inventoryAnchor(hopperInv);
                            if (drop != null) drop.getWorld().dropItemNaturally(drop, backLeft.values().iterator().next());
                        }
                    }
                }
            }
        }

        private Location inventoryAnchor(Inventory inv) {
            try {
                InventoryHolder h = inv.getHolder();
                if (h instanceof BlockState bs) return bs.getLocation();
                if (h instanceof HopperMinecart mh) return mh.getLocation();
            } catch (Throwable ignored) {}
            return null;
        }

        private HopperRef asHopper(Inventory inv) {
            try {
                if (inv == null) return null;
                InventoryHolder holder = inv.getHolder();
                if (holder instanceof org.bukkit.block.Hopper bs) {
                    return HopperRef.forBlock(bs.getLocation());
                }
                if (holder instanceof HopperMinecart cart) {
                    return HopperRef.forMinecart(cart.getUniqueId(), cart.getLocation());
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private record HopperRef(String key, Location loc) {
            static HopperRef forBlock(Location loc) {
                String k = "B:" + loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
                return new HopperRef(k, loc);
            }
            static HopperRef forMinecart(UUID uuid, Location loc) {
                return new HopperRef("M:" + uuid, loc);
            }
            Location location() { return loc; }
        }
    }











    /* ==============================
       3) Fast Minecart IO (ускорение)
       ============================== */

//    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
//    public void onMinecartIO(InventoryMoveItemEvent e, @SuppressWarnings("unused") PlayerInteractEvent dummy) {
//        // тот же евент, но проверим конкретно хоппер-вагонетку с локацией её инвентаря
//        Inventory src = e.getSource();
//        Inventory dst = e.getDestination();
//
//        Location anchor = inventoryAnchor(src);
//        if (anchor == null) anchor = inventoryAnchor(dst);
//        if (anchor == null) return;
//
//        if (!UpgradeCondition.hasGlobalUpgradeAt(anchor, "unity.minecart.fastio")) return;
//
//        // добавочный перенос ещё одного стека с задержкой (не ломаем ванильный)
//        ItemStack moving = e.getItem();
//        if (moving.getType() == Material.AIR) return;
//
//        final ItemStack extra = moving.clone();
//        new BukkitRunnable() {
//            @Override
//            public void run() {
//                try {
//                    e.getDestination().addItem(extra);
//                } catch (Throwable ignored) {
//                }
//            }
//        }.runTaskLater(plugin, 2L);
//    }

//
//
//    /* ==============================
//       6) TNT Quarry — «правильный» дроп и дубли
//       ============================== */
//
//    // Белый список предметов для возможного дублирования и шанс
//    private static final Map<Material, Double> DUP_WHITELIST = new HashMap<>();
//
//    static {
//        DUP_WHITELIST.put(Material.DIAMOND, 0.10); // 10% шанс удвоить
//        DUP_WHITELIST.put(Material.RAW_IRON, 0.12);
//        DUP_WHITELIST.put(Material.RAW_GOLD, 0.12);
//        DUP_WHITELIST.put(Material.RAW_COPPER, 0.12);
//        DUP_WHITELIST.put(Material.ANCIENT_DEBRIS, 0.05);
//    }
//
//    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
//    public void onTntExplode(EntityExplodeEvent e) {
//        Entity ent = e.getEntity();
//        Location loc = ent.getLocation();
//
//        // включаем механику только если у страны в этой локации есть апгрейд
//        if (!UpgradeCondition.hasGlobalUpgradeAt(loc, "unity.tnt.quarry")) return;
//
//        List<Block> blocks = new ArrayList<>(e.blockList());
//        if (blocks.isEmpty()) return;
//
//        World w = loc.getWorld();
//        if (w == null) return;
//
//        // 1) Собираем весь дроп со взрываемых блоков
//        List<ItemStack> toDrop = new ArrayList<>();
//        for (Block b : blocks) {
//            if (b.getType() == Material.AIR) continue;
//            try {
//                // используем ванильный getDrops (без инструмента)
//                Collection<ItemStack> drops = b.getDrops();
//                toDrop.addAll(drops);
//            } catch (Throwable ignored) {
//            }
//        }
//
//        // 2) Применяем логику дублирования по whitelist
//        List<ItemStack> extra = new ArrayList<>();
//        ThreadLocalRandom rnd = ThreadLocalRandom.current();
//        for (ItemStack is : toDrop) {
//            Double chance = DUP_WHITELIST.get(is.getType());
//            if (chance != null && rnd.nextDouble() < chance) {
//                ItemStack dup = is.clone();
//                extra.add(dup);
//            }
//        }
//        toDrop.addAll(extra);
//
//        // 3) Отменяем слом блоков от ванили и сами «выкидываем» дроп
//        e.setYield(0f); // ванильный дроп от взрыва — отключаем
//        for (Block b : blocks) {
//            try {
//                b.setType(Material.AIR, false);
//            } catch (Throwable ignored) {
//            }
//        }
//        final Location dropCenter = averageLocation(blocks, w, loc);
//
//        // 4) Спаун предметов
//        for (ItemStack stack : toDrop) {
//            if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0) continue;
//            Item item = w.dropItemNaturally(dropCenter, stack);
//            item.setPickupDelay(10);
//        }
//
//        // эффект
//        w.playSound(dropCenter, Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
//    }
//
//    private Location averageLocation(List<Block> blocks, World w, Location fallback) {
//        if (blocks == null || blocks.isEmpty()) return fallback;
//        double x = 0, y = 0, z = 0;
//        int n = 0;
//        for (Block b : blocks) {
//            if (b == null) continue;
//            x += b.getX() + 0.5;
//            y += b.getY() + 0.5;
//            z += b.getZ() + 0.5;
//            n++;
//        }
//        if (n == 0) return fallback;
//        return new Location(w, x / n, y / n, z / n);
//    }
//
//    /* ==============================
//       Дополнительно: эффект в индустриальной зоне
//       ============================== */
//
//    // Пример: в INDUSTRIAL зоне — лёгкий HASTE, если есть апгрейд unity.zone.haste.[basic|advanced]
//    // (переместил сюда, чтобы эффекты тоже жили в одном месте)
//    @EventHandler
//    public void onPlayerInteractApplyZoneEffects(PlayerInteractEvent e) {
//        Player p = e.getPlayer();
//        if (!p.isOnline()) return;
//
//        // Используем существующую модель: апгрейды — через permission у игрока (или группы страны)
//        boolean adv = UpgradeCondition.hasGlobalUpgrade(p, "unity.zone.haste.advanced");
//        boolean basic = adv || UpgradeCondition.hasGlobalUpgrade(p, "unity.zone.haste.basic");
//
//        boolean inIndustrial = isInZoneType(p, ZoneType.INDUSTRIAL);
//
//        if (inIndustrial && (adv || basic)) {
//            int amp = adv ? 1 : 0;
//            p.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 20 * 12, amp, true, false));
//        }
//    }
//
//    private static ZoneManager zoneManager() {
//        try { return UnityLauncher.getInstance().getZoneManager(); }
//        catch (Throwable ignored) {
//            try { return UnityLauncher.getInstance().getZoneManager(); }
//            catch (Throwable ignored2) { return null; }
//        }
//    }
//
//    private static ZoneInfo safeGetZoneAt(Location loc) {
//        ZoneManager zm = zoneManager();
//        return (zm != null && loc != null) ? zm.getZoneAt(loc) : null;
//    }
//
//// ====== Новые методы ======
//
//    /** Проверка: находится ли ЛОКАЦИЯ в зоне указанного типа. */
//    public static boolean isInZoneType(Location loc, ZoneType type) {
//        if (loc == null || type == null) return false;
//        try {
//            ZoneInfo zi = safeGetZoneAt(loc);
//            boolean result = zi != null && zi.getType() == type;
//            // отладка в стиле твоих логов
//            String w = (loc.getWorld() != null) ? loc.getWorld().getName() : "null";
//            dbg("inZone|" + w + ":" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
//                    + " -> " + (zi != null ? zi.getType() : "none") + " [need " + type + "] = " + result);
//            return result;
//        } catch (Throwable t) {
//            Bukkit.getLogger().warning("[UpgradeCondition] isInZoneType failed at " + loc + " : " + t.getMessage());
//            return false;
//        }
//    }

//    /** Проверка по игроку: где стоит игрок — та локация в зоне указанного типа? */
//    public static boolean isInZoneType(Player player, ZoneType type) {
//        if (player == null || !player.isOnline()) return false;
//        return isInZoneType(player.getLocation(), type);
//    }
//
//    /** Удобный синоним именно для индустриальных зон по локации. */
//    public static boolean isInIndustrialZoneAt(Location loc) {
//        return isInZoneType(loc, ZoneType.INDUSTRIAL);
//    }

}
