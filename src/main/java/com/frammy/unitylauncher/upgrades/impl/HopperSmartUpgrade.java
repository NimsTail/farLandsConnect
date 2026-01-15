package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.*;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class HopperSmartUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.hopper_smart");

    // turbo candidates (block hopper only)
    private final Map<Block, Long> turboHoppers = new ConcurrentHashMap<>();

    // eligibility cache for turbo (key: hopper block)
    private final Map<Block, Eligibility> turboEligibility = new ConcurrentHashMap<>();

    // slow-mode toggle by holder key (supports minecarts, doublechest, etc.)
    private final Map<String, Boolean> hopperToggle = new HashMap<>();

    private record Eligibility(boolean canTurbo, long checkedAtMs) {}

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().industrial().hopperSmart();
        return cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().industrial().hopperSmart();
        long period = Math.max(1L, cfg.turboTaskPeriodTicks());

        tasks.add(org.bukkit.Bukkit.getScheduler().runTaskTimer(
                plugin(),
                this::runTurboTick,
                period,
                period
        ));

        if (C().core().debug()) {
            plugin().getLogger().info("[Upgrades/HopperSmart] started period=" + period
                    + " budget=" + cfg.turboBudgetPerRun()
                    + " cacheMs=" + cfg.turboEligibilityCacheMs()
                    + " slowLvl0=" + cfg.slowModeLvl0()
                    + " requireIndustrial=" + cfg.requireIndustrialZoneForTurbo());
        }
    }

    @Override
    protected void onDisable() {
        turboHoppers.clear();
        turboEligibility.clear();
        hopperToggle.clear();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHopperMove(InventoryMoveItemEvent e) {
        InventoryHolder initiator = e.getInitiator().getHolder();
        if (initiator == null) return;

        Location loc = holderLocation(initiator);
        if (loc == null || loc.getWorld() == null) return;

        var cfg = C().industrial().hopperSmart();

        String country = resolveCountry(loc);
        int hopperLvl = countryMaxLevel(country, cfg.permBase(), 2);

        boolean slowEveryOther = cfg.slowModeLvl0() && (country == null || country.isBlank() || hopperLvl <= 0);
        if (slowEveryOther) {
            String key = hopperKeyFromHolder(initiator);
            if (key == null) key = "ephemeral:" + System.identityHashCode(initiator);

            boolean prev = hopperToggle.getOrDefault(key, false);
            boolean allowThisTick = !prev;
            hopperToggle.put(key, allowThisTick);

            if (!allowThisTick) e.setCancelled(true);
            return;
        }

        // L1: vanilla
        if (hopperLvl < 2) return;

        // L2 turbo: only if initiator is block Hopper + (optional) inside INDUSTRIAL
        if (!(initiator instanceof Hopper hopperState)) return;
        if (cfg.requireIndustrialZoneForTurbo() && !UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL)) return;

        turboHoppers.put(hopperState.getBlock(), System.currentTimeMillis());
    }

    private void runTurboTick() {
        var cfg = C().industrial().hopperSmart();

        long now = System.currentTimeMillis();
        int budget = Math.max(1, cfg.turboBudgetPerRun());
        int processed = 0;

        Iterator<Map.Entry<Block, Long>> it = turboHoppers.entrySet().iterator();
        while (it.hasNext() && processed < budget) {
            Map.Entry<Block, Long> en = it.next();
            Block b = en.getKey();

            if (b == null) {
                turboHoppers.remove(null);
                continue;
            }

            // critical fix: do not keep unloaded chunks forever
            if (!b.getChunk().isLoaded()) {
                turboHoppers.remove(b);
                continue;
            }

            BlockState st = b.getState();
            if (!(st instanceof Hopper hopperState)) {
                turboHoppers.remove(b);
                continue;
            }

            if (!canTurboFastCached(b, now, cfg.turboEligibilityCacheMs(), cfg.permBase(), cfg.requireIndustrialZoneForTurbo())) {
                turboHoppers.remove(b);
                continue;
            }

            int moved = fastTickHopper(hopperState); // 0..2
            processed++;

            Long lastSeen = en.getValue();
            if (lastSeen == null || (now - lastSeen) > 5000L) {
                turboHoppers.remove(b);
            }
        }

    }

    private boolean canTurboFastCached(Block hopperBlock,
                                       long now,
                                       long cacheMs,
                                       String permBase,
                                       boolean requireIndustrial) {

        long ttl = Math.max(0L, cacheMs);

        Eligibility e = turboEligibility.get(hopperBlock);
        if (e != null && (now - e.checkedAtMs) < ttl) return e.canTurbo;

        boolean fresh = computeTurboEligibility(hopperBlock.getLocation(), permBase, requireIndustrial);
        turboEligibility.put(hopperBlock, new Eligibility(fresh, now));
        return fresh;
    }

    private boolean computeTurboEligibility(Location loc, String permBase, boolean requireIndustrial) {
        String country = resolveCountry(loc);
        int hopperLvl = countryMaxLevel(country, permBase, 2);
        if (hopperLvl < 2) return false;

        if (requireIndustrial) {
            return UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL);
        }
        return true;
    }

    private int fastTickHopper(Hopper hopperState) {
        Inventory hopperInv = hopperState.getInventory();
        if (hopperInv == null) return 0;

        int moved = 0;
        if (pullOneMoreFromAbove(hopperState, hopperInv)) moved |= 1;
        if (pushOneMoreForward(hopperState, hopperInv)) moved |= 2;
        return moved;
    }

    private boolean pullOneMoreFromAbove(Hopper hopperState, Inventory hopperInv) {
        Location myLoc = hopperState.getLocation();
        Location aboveLoc = myLoc.clone().add(0, 1, 0);

        BlockState aboveState = aboveLoc.getBlock().getState();
        if (!(aboveState instanceof InventoryHolder srcHolder)) return false;

        Inventory srcInv = srcHolder.getInventory();
        if (srcInv == null) return false;

        ItemStack[] contents = srcInv.getContents();

        int srcSlot = -1;
        ItemStack srcStack = null;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            srcSlot = i; srcStack = it; break;
        }
        if (srcSlot < 0) return false;

        ItemStack oneItem = srcStack.clone();
        oneItem.setAmount(1);

        var leftover = hopperInv.addItem(oneItem);
        if (leftover != null && !leftover.isEmpty()) return false;

        int newAmount = srcStack.getAmount() - 1;
        if (newAmount <= 0) {
            srcInv.setItem(srcSlot, null);
        } else {
            ItemStack newStack = srcStack.clone();
            newStack.setAmount(newAmount);
            srcInv.setItem(srcSlot, newStack);
        }
        return true;
    }

    private static String resolveCountry(Location loc) {
        if (loc == null) return null;
        return UpgradeCondition.locationCountryOwner(loc);
    }

    private boolean pushOneMoreForward(Hopper hopperState, Inventory hopperInv) {
        // safe cast: hopper block data implements org.bukkit.block.data.type.Hopper on Paper/Spigot
        org.bukkit.block.data.BlockData bd = hopperState.getBlock().getBlockData();
        if (!(bd instanceof org.bukkit.block.data.type.Hopper data)) return false;

        var face = data.getFacing();
        Location outLoc = hopperState.getLocation().clone()
                .add(face.getModX(), face.getModY(), face.getModZ());

        BlockState outState = outLoc.getBlock().getState();
        if (!(outState instanceof InventoryHolder dstHolder)) return false;

        Inventory dstInv = dstHolder.getInventory();
        if (dstInv == null) return false;

        int hopperSlot = -1;
        ItemStack hopperStack = null;

        ItemStack[] hopperContents = hopperInv.getContents();
        for (int i = 0; i < hopperContents.length; i++) {
            ItemStack it = hopperContents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            hopperSlot = i; hopperStack = it; break;
        }
        if (hopperSlot < 0) return false;

        ItemStack oneItem = hopperStack.clone();
        oneItem.setAmount(1);

        var leftover = dstInv.addItem(oneItem);
        if (leftover != null && !leftover.isEmpty()) return false;

        int newAmount = hopperStack.getAmount() - 1;
        if (newAmount <= 0) {
            hopperInv.setItem(hopperSlot, null);
        } else {
            ItemStack newStack = hopperStack.clone();
            newStack.setAmount(newAmount);
            hopperInv.setItem(hopperSlot, newStack);
        }
        return true;
    }

    private static Location holderLocation(InventoryHolder h) {
        try {
            if (h instanceof BlockState bs) return bs.getLocation();
            if (h instanceof DoubleChestInventory dc) {
                InventoryHolder ih = dc.getHolder();
                if (ih instanceof org.bukkit.block.DoubleChest chest) return chest.getLocation();
            }

            if (h instanceof org.bukkit.entity.Entity ent) return ent.getLocation();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String hopperKeyFromHolder(InventoryHolder holder) {
        try {
            if (holder instanceof Hopper hopperState) {
                Location l = hopperState.getLocation();
                World w = l.getWorld();
                if (w != null) return w.getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
            if (holder instanceof BlockState bs) {
                Location l = bs.getLocation();
                World w = l.getWorld();
                if (w != null) return w.getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
            if (holder instanceof DoubleChestInventory dc) {
                Location l = dc.getLocation();
                if (l != null && l.getWorld() != null) {
                    return l.getWorld().getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
                }
            }
            if (holder instanceof org.bukkit.entity.minecart.HopperMinecart hm) {
                var w = hm.getWorld();
                return w.getUID() + ":" + (int) Math.floor(hm.getLocation().getX())
                        + "," + (int) Math.floor(hm.getLocation().getY())
                        + "," + (int) Math.floor(hm.getLocation().getZ())
                        + ":" + hm.getUniqueId();
            }
            if (holder instanceof org.bukkit.entity.minecart.StorageMinecart sm) {
                var w = sm.getWorld();
                return w.getUID() + ":" + (int) Math.floor(sm.getLocation().getX())
                        + "," + (int) Math.floor(sm.getLocation().getY())
                        + "," + (int) Math.floor(sm.getLocation().getZ())
                        + ":" + sm.getUniqueId();
            }
            if (holder instanceof org.bukkit.entity.Entity ent) {
                var w = ent.getWorld();
                return w.getUID() + ":" + (int) Math.floor(ent.getLocation().getX())
                        + "," + (int) Math.floor(ent.getLocation().getY())
                        + "," + (int) Math.floor(ent.getLocation().getZ())
                        + ":" + ent.getUniqueId();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk ch = e.getChunk();
        turboHoppers.keySet().removeIf(b -> b != null && b.getChunk().equals(ch));
        turboEligibility.keySet().removeIf(b -> b != null && b.getChunk().equals(ch));
        cleanupToggleForChunk(ch);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == org.bukkit.Material.HOPPER) {
            turboHoppers.remove(b);
            turboEligibility.remove(b);
            removeToggleForBlock(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        var wuid = e.getWorld().getUID();
        turboHoppers.keySet().removeIf(b -> b != null && b.getWorld().getUID().equals(wuid));
        turboEligibility.keySet().removeIf(b -> b != null && b.getWorld().getUID().equals(wuid));
        hopperToggle.entrySet().removeIf(en -> en.getKey().startsWith(wuid + ":"));
    }

    private void cleanupToggleForChunk(Chunk ch) {
        String prefix = ch.getWorld().getUID() + ":";
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;
        int maxX = bx + 15;
        int maxZ = bz + 15;

        hopperToggle.entrySet().removeIf(en -> {
            String s = en.getKey();
            if (!s.startsWith(prefix)) return false;

            int idx = s.indexOf(':');
            if (idx < 0) return false;

            String[] xyz = s.substring(idx + 1).split(",");
            if (xyz.length != 3) return false;

            try {
                int x = Integer.parseInt(xyz[0]);
                String zRaw = xyz[2];
                int colon = zRaw.indexOf(':');
                if (colon >= 0) zRaw = zRaw.substring(0, colon);
                int z = Integer.parseInt(zRaw);
                return x >= bx && x <= maxX && z >= bz && z <= maxZ;
            } catch (Exception ignore) {
                return false;
            }
        });
    }

    private void removeToggleForBlock(Block b) {
        String key = b.getWorld().getUID() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
        hopperToggle.remove(key);
    }
}
