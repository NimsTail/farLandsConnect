package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class LoaderUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.loader");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    /* ===================== COPPER CHECK ===================== */

    private static final Set<Material> COPPER_BLOCKS = EnumSet.of(
            Material.COPPER_BLOCK,
            Material.EXPOSED_COPPER,
            Material.WEATHERED_COPPER,
            Material.OXIDIZED_COPPER,
            Material.WAXED_COPPER_BLOCK,
            Material.WAXED_EXPOSED_COPPER,
            Material.WAXED_WEATHERED_COPPER,
            Material.WAXED_OXIDIZED_COPPER,

            Material.CUT_COPPER,
            Material.EXPOSED_CUT_COPPER,
            Material.WEATHERED_CUT_COPPER,
            Material.OXIDIZED_CUT_COPPER,
            Material.WAXED_CUT_COPPER,
            Material.WAXED_EXPOSED_CUT_COPPER,
            Material.WAXED_WEATHERED_CUT_COPPER,
            Material.WAXED_OXIDIZED_CUT_COPPER
    );

    private static final long COPPER_CACHE_MS = 1500L;
    private static final Map<Long, CopperCache> COPPER_CACHE = new ConcurrentHashMap<>();
    private record CopperCache(long untilMs, boolean hasCopper) {}

    /* ===================== TURBO QUEUE ===================== */

    // candidates keyed by holder pair identity; short-lived
    private final Map<String, Candidate> candidates = new ConcurrentHashMap<>();

    private record Candidate(
            UUID worldId,
            Inventory src,
            Inventory dst,

            double tokens,       // accumulated "moves"
            long lastSeenMs,     // last event time
            long lastUpdateMs    // last token update time
    ) {}

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        var cfg = ctx.config().industrial().loader();

        if (!cfg.enabled()) return false;
        if (cfg.radius() < 0) return false;

        int speed = cfg.speed();
        int ips100 = cfg.itemsPerSecondAt100();
        return speed > 0 && ips100 > 0;
    }

    @Override
    protected void onEnable() {
        tasks.add(Bukkit.getScheduler().runTaskTimer(
                plugin(),
                this::runTurboTick,
                1L,
                1L
        ));

        if (C().core().debug()) {
            var cfg = C().industrial().loader();
            plugin().getLogger().info("[Upgrades/Loader] started"
                    + " speed=" + cfg.speed()
                    + " ips100=" + cfg.itemsPerSecondAt100()
                    + " maxBurst=" + cfg.maxBurst()
                    + " maxMovesPerTick=" + cfg.maxMovesPerTick()
                    + " ttlMs=" + cfg.candidateTtlMs()
                    + " radius=" + cfg.radius()
                    + " permBase=" + cfg.permBase());
        }
    }

    @Override
    protected void onDisable() {
        candidates.clear();
        COPPER_CACHE.clear();
    }

    /* ===================== EVENT (MARK CANDIDATE) ===================== */

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMinecartInventoryMove(InventoryMoveItemEvent e) {
        InventoryHolder srcHolder = e.getSource().getHolder();
        InventoryHolder dstHolder = e.getDestination().getHolder();

        boolean srcMinecart = isMinecartInvHolder(srcHolder);
        boolean dstMinecart = isMinecartInvHolder(dstHolder);
        if (!srcMinecart && !dstMinecart) return;

        Location loc = minecartLocation(srcMinecart ? srcHolder : dstHolder);
        if (loc == null || loc.getWorld() == null) return;

        var cfg = C().industrial().loader();

        // speed enabled?
        int speed = clamp(cfg.speed(), 1, 100);
        int ips100 = Math.max(0, cfg.itemsPerSecondAt100());
        if (ips100 <= 0) return;

        // country owner (deep) + canonicalize
        String country = UpgradeCondition.normalizeCountry(UpgradeCondition.locationCountryOwnerDeep(loc));
        if (country == null || country.isBlank()) return;

        // permission level
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        // copper near minecart
        int radius = Math.max(0, cfg.radius());
        if (!hasCopperBlockNearbyChebyshevCached(loc, radius)) return;

        // candidate key
        UUID wuid = loc.getWorld().getUID();
        String key = candidateKey(srcHolder, dstHolder, wuid);

        Inventory srcInv = e.getSource();
        Inventory dstInv = e.getDestination();

        long now = System.currentTimeMillis();
        candidates.compute(key, (k, old) -> {
            if (old == null) {
                return new Candidate(wuid, srcInv, dstInv, 0.0, now, now);
            }
            // keep tokens, refresh inventories, update lastSeen
            return new Candidate(old.worldId(), srcInv, dstInv, old.tokens(), now, old.lastUpdateMs());
        });
    }

    /* ===================== TURBO TICK (TOKEN BUCKET) ===================== */

    private void runTurboTick() {
        var cfg = C().industrial().loader();

        int speed = clamp(cfg.speed(), 1, 100);
        int ips100 = Math.max(0, cfg.itemsPerSecondAt100());
        if (ips100 <= 0) return;

        double rate = ips100 * (speed / 100.0); // items per second
        if (rate <= 0.0001) return;

        int maxBurst = Math.max(0, cfg.maxBurst());
        int maxMovesPerTick = Math.max(0, cfg.maxMovesPerTick());
        long ttlMs = Math.max(0L, cfg.candidateTtlMs());

        if (maxMovesPerTick <= 0 || maxBurst <= 0) return;

        long now = System.currentTimeMillis();

        int processed = 0;
        // simple hard cap to avoid iterating massive map forever
        int hardBudget = 250;

        Iterator<Map.Entry<String, Candidate>> it = candidates.entrySet().iterator();
        while (it.hasNext() && processed < hardBudget) {
            Map.Entry<String, Candidate> en = it.next();
            String key = en.getKey();
            Candidate c = en.getValue();

            if (c == null) {
                candidates.remove(key);
                continue;
            }

            if (now - c.lastSeenMs() > ttlMs) {
                candidates.remove(key);
                continue;
            }

            Inventory src = c.src();
            Inventory dst = c.dst();
            if (src == null || dst == null) {
                candidates.remove(key);
                continue;
            }

            long dtMs = Math.max(0L, now - c.lastUpdateMs());
            double add = rate * (dtMs / 1000.0);

            double tokens = c.tokens() + add;
            if (tokens > maxBurst) tokens = maxBurst;

            int moved = 0;
            while (tokens >= 1.0 && moved < maxMovesPerTick) {
                if (!moveOneAny(src, dst)) break;
                tokens -= 1.0;
                moved++;
            }

            candidates.put(key, new Candidate(
                    c.worldId(),
                    src,
                    dst,
                    tokens,
                    c.lastSeenMs(),
                    now
            ));

            if (moved == 0 && tokens < 1.0) {
                candidates.remove(key);
            }

            processed++;
        }
    }

    /* ===================== HELPERS ===================== */

    private static boolean isMinecartInvHolder(InventoryHolder h) {
        return h instanceof StorageMinecart || h instanceof HopperMinecart;
    }

    private static Location minecartLocation(InventoryHolder h) {
        try {
            if (h instanceof Entity e) return e.getLocation();
        } catch (Throwable ignored) {}
        return null;
    }

    private static String candidateKey(InventoryHolder srcHolder, InventoryHolder dstHolder, UUID world) {
        return world + ":" + System.identityHashCode(srcHolder) + "->" + System.identityHashCode(dstHolder);
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        return Math.min(v, hi);
    }

    private static boolean moveOneAny(Inventory srcInv, Inventory dstInv) {
        if (srcInv == null || dstInv == null) return false;

        int srcSlot = -1;
        ItemStack srcStack = null;

        for (int i = 0; i < srcInv.getSize(); i++) {
            ItemStack it = srcInv.getItem(i);
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            srcSlot = i;
            srcStack = it;
            break;
        }
        if (srcSlot < 0) return false;

        ItemStack one = srcStack.clone();
        one.setAmount(1);

        var leftover = dstInv.addItem(one);
        if (leftover != null && !leftover.isEmpty()) return false;

        int newAmt = srcStack.getAmount() - 1;
        if (newAmt <= 0) srcInv.setItem(srcSlot, null);
        else {
            ItemStack newStack = srcStack.clone();
            newStack.setAmount(newAmt);
            srcInv.setItem(srcSlot, newStack);
        }
        return true;
    }

    private static boolean hasCopperBlockNearbyChebyshevCached(Location center, int radius) {
        World w = center.getWorld();
        if (w == null) return false;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        long now = System.currentTimeMillis();
        long key = copperCacheKey(w.getUID(), cx, cy, cz, radius);

        CopperCache cached = COPPER_CACHE.get(key);
        if (cached != null && cached.untilMs() >= now) {
            return cached.hasCopper();
        }

        boolean res = hasCopperBlockNearbyChebyshev(w, cx, cy, cz, radius);
        COPPER_CACHE.put(key, new CopperCache(now + COPPER_CACHE_MS, res));
        return res;
    }

    private static boolean hasCopperBlockNearbyChebyshev(World w, int cx, int cy, int cz, int radius) {
        if (radius <= 0) {
            return COPPER_BLOCKS.contains(w.getBlockAt(cx, cy, cz).getType());
        }

        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dy = -d; dy <= d; dy++) {
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != d) continue;
                        Material m = w.getBlockAt(cx + dx, cy + dy, cz + dz).getType();
                        if (COPPER_BLOCKS.contains(m)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static long copperCacheKey(UUID world, int x, int y, int z, int r) {
        long h = world.getMostSignificantBits() ^ world.getLeastSignificantBits();
        h ^= (long) x * 73428767L;
        h ^= (long) y * 912367L;
        h ^= (long) z * 19349663L;
        h ^= (long) r * 83492791L;
        return h;
    }

    /* ===================== CLEANUP ===================== */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        UUID w = e.getWorld().getUID();
        candidates.entrySet().removeIf(en -> {
            Candidate c = en.getValue();
            return c != null && w.equals(c.worldId());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent e) {
        Entity ent = e.getEntity();
        if (!(ent instanceof StorageMinecart) && !(ent instanceof HopperMinecart)) return;
        String needle = String.valueOf(System.identityHashCode(ent));
        candidates.keySet().removeIf(k -> k.contains(":" + needle + "->") || k.contains("->" + needle));
    }
}
