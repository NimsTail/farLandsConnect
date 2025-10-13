package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

public class UpgradesListener implements Listener {

    public UpgradesListener() { }

    public static void registerAll(JavaPlugin plugin) {
        if (plugin == null) return;
        Bukkit.getPluginManager().registerEvents(new UpgradesListener(), plugin);
    }

    /* ==============================
       Redstone
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
       Furnace Ore Boost (+15% к выходу)
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

        // было: UpgradeCondition.hasGlobalUpgradeAt(...)
        if (!hasCountryUpgradeAt(b.getLocation(), "unity.furnace.ore_boost")) return;

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
       Smart Hoppers
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
                // было: hasGlobalOrIndustrialUpgradeAt(...)
                if (hasCountryUpgradeAt(loc, "unity.hopper.smart.2")) return 2;
                if (hasCountryUpgradeAt(loc, "unity.hopper.smart.1")) return 1;
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
       TNT dupe
       ============================== */

    private static final Map<Material, Double> DUP_WHITELIST = Map.of(
            Material.DIAMOND, 0.10,
            Material.RAW_IRON, 0.12,
            Material.RAW_GOLD, 0.12,
            Material.RAW_COPPER, 0.12,
            Material.ANCIENT_DEBRIS, 0.05
    );

    private static final ItemStack FORTUNE2_PICK;
    static {
        ItemStack p = new ItemStack(Material.DIAMOND_PICKAXE);
        p.addUnsafeEnchantment(Enchantment.FORTUNE, 2);
        FORTUNE2_PICK = p;
    }

    private static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );

    private static boolean isOre(Material m) { return ORES.contains(m); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTntQuarryExplode(EntityExplodeEvent e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof TNTPrimed)) return;

        Location loc = ent.getLocation();
        World w = loc.getWorld();

        // было: UpgradeCondition.hasGlobalUpgradeAt(loc, "unity.tnt.quarry")
        if (w == null || !hasCountryUpgradeAt(loc, "unity.tnt.quarry")) return;

        List<Block> blocks = e.blockList();
        if (blocks.isEmpty()) return;

        List<ItemStack> toDrop = new ArrayList<>(blocks.size() * 2);
        for (Block b : blocks) {
            Material t = b.getType();
            if (t == Material.AIR) continue;
            try {
                Collection<ItemStack> drops = isOre(t) ? b.getDrops(FORTUNE2_PICK) : b.getDrops();
                if (!drops.isEmpty()) toDrop.addAll(drops);
            } catch (Throwable ignored) {}
        }

        if (!toDrop.isEmpty()) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            List<ItemStack> extra = new ArrayList<>();
            for (ItemStack is : toDrop) {
                Double ch = DUP_WHITELIST.get(is.getType());
                if (ch != null && rnd.nextDouble() < ch) extra.add(is.clone());
            }
            if (!extra.isEmpty()) toDrop.addAll(extra);
        }

        e.setYield(0f);
        for (Block b : blocks) {
            try { if (b.getType() != Material.AIR) b.setType(Material.AIR, false); } catch (Throwable ignored) {}
        }

        Location dropCenter = averageLocation(blocks, w, loc);
        for (ItemStack s : toDrop) {
            if (s == null || s.getType() == Material.AIR || s.getAmount() <= 0) continue;
            Item item = w.dropItemNaturally(dropCenter, s);
            item.setPickupDelay(10);
        }
        if (!toDrop.isEmpty()) w.playSound(dropCenter, Sound.ENTITY_ITEM_PICKUP, 0.5f, 0.9f);
    }

    private static Location averageLocation(List<Block> blocks, World w, Location fallback) {
        if (blocks == null || blocks.isEmpty()) return fallback;
        double x = 0, y = 0, z = 0; int n = 0;
        for (Block b : blocks) { x += b.getX() + .5; y += b.getY() + .5; z += b.getZ() + .5; n++; }
        return n == 0 ? fallback : new Location(w, x / n, y / n, z / n);
    }

    /* ==============================
       Haste
       ============================== */

    private final ZoneManager zones = UnityLauncher.getInstance().getZoneManager();
    private final Map<UUID, Long> lastApplied = new HashMap<>();

    private static final int HASTE_TICKS = 20 * 12;
    private static final long REAPPLY_COOLDOWN_MS = 4000;

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) return;
        checkAndApply(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(),
                () -> checkAndApply(e.getPlayer()), 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(),
                () -> checkAndApply(e.getPlayer()), 20L);
    }

    private void checkAndApply(Player p) {
        if (!p.isOnline()) return;

        ZoneInfo zi = zones != null ? zones.getZoneAt(p.getLocation()) : null;
        boolean inIndustrial = zi != null && zi.getType() == ZoneType.INDUSTRIAL;

        // было: groupHas(zi, node)
        boolean hasAdv = inIndustrial && UpgradeCondition.hasUpgradeInZone(p, zi, "unity.zone.haste.advanced");
        boolean hasBasicOnly = inIndustrial && !hasAdv && UpgradeCondition.hasUpgradeInZone(p, zi, "unity.zone.haste.basic");

        boolean shouldHave = inIndustrial && (hasAdv || hasBasicOnly);
        int amp = hasAdv ? 1 : 0; // basic -> 0 (Haste I), advanced -> 1 (Haste II)

        PotionEffect current = p.getPotionEffect(PotionEffectType.HASTE);
        if (shouldHave) {
            long now = System.currentTimeMillis();
            Long last = lastApplied.get(p.getUniqueId());

            boolean needReapply =
                    current == null
                            || current.getAmplifier() != amp
                            || current.getDuration() < 20 * 4
                            || last == null
                            || (now - last) > REAPPLY_COOLDOWN_MS;

            if (needReapply) {
                p.addPotionEffect(new PotionEffect(
                        PotionEffectType.HASTE,
                        HASTE_TICKS,
                        amp,
                        true,   // ambient
                        false,  // без частиц
                        true    // с иконкой
                ));
                lastApplied.put(p.getUniqueId(), now);
            }
        } else {
            if (current != null && current.getAmplifier() <= 1 && current.isAmbient() && !current.hasParticles()) {
                p.removePotionEffect(PotionEffectType.HASTE);
            }
        }
    }

    /* ==============================
       Общий хелпер для апгрейдов по локации
       ============================== */

    /**
     * Проверка ноды пермишена у страны, «владеющей» зоной по данной локации.
     * Логика соответствуют новой UpgradeCondition: если зона — COUNTRY,
     * страна берётся из самой зоны, иначе — страна берётся из кэша по создателю зоны.
     */
    private static boolean hasCountryUpgradeAt(Location loc, String permissionKey) {
        if (loc == null) return false;

        ZoneManager zm = UnityLauncher.getInstance().getZoneManager();
        ZoneInfo zi = (zm != null) ? zm.getZoneAt(loc) : null;
        if (zi == null) return false;

        String country;
        if (zi.getType() == ZoneType.COUNTRY && zi.hasCountry()) {
            country = zi.getCountryName();
        } else {
            // страна создателя зоны — из кэша (без SQL)
            country = UnityLauncher.getInstance().countryRegistryJdbc.getCountryCached(zi.getOwner());
        }
        if (country == null || country.isBlank()) return false;

        return UpgradeCondition.countryHasPermission(country, permissionKey);
    }
}
