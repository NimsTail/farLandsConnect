package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UpgradesListener implements Listener {

    public static boolean DEBUG = false;
    private static void d(String msg) {
        if (DEBUG) {
            Bukkit.getLogger().info("[UL/UpgradesListener] " + msg);
        }
    }

    // NEW: храним кандидатов на турбо-тик.
    // Ключ = координаты блока хоппера. Значение = последний момент, когда он был активен.
    // ConcurrentHashMap чтоб не орать в логах про модификацию из эвента и тика.
    private static final Map<Block, Long> TURBO_HOPPERS = new ConcurrentHashMap<>();

    // NEW: задача, которая крутится раз в тик и реально двигает предметы.
    private static BukkitTask turboTask;

    public static void registerAll(JavaPlugin plugin) {
        d("registerAll() called, plugin=" + (plugin != null ? plugin.getName() : "null"));
        if (plugin == null) return;

        Bukkit.getPluginManager().registerEvents(new UpgradesListener(), plugin);
        d("registerAll() listener registered");

        // NEW: запускаем тикер турбо-хопперов.
        // Выполняется СИНХРОННО в основном треде сервера.
        if (turboTask != null) {
            turboTask.cancel();
        }
        turboTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();

            int processed = 0;
            int BUDGET_PER_RUN = 50; // например не больше 50 турбо-хопперов за запуск задачи

            Iterator<Map.Entry<Block, Long>> it = TURBO_HOPPERS.entrySet().iterator();
            while (it.hasNext() && processed < BUDGET_PER_RUN) {
                Map.Entry<Block, Long> en = it.next();
                Block b = en.getKey();

                if (!(b.getState() instanceof org.bukkit.block.Hopper hopperState)) {
                    it.remove();
                    continue;
                }

                if (!b.getChunk().isLoaded()) {
                    continue;
                }

                Location loc = b.getLocation();
                if (!canTurboFastCached(loc)) {
                    it.remove();
                    continue;
                }

                fastTickHopper(hopperState);
                processed++;

                if (now - en.getValue() > 5000L) {
                    it.remove();
                }
            }
        }, 2L, 2L);

        d("registerAll() turboTask started");
    }

    /* ==========================
       1) РЕДСТОУН — по группе
       ========================== */

    private static final EnumSet<Material> REDSTONE_BASIC = EnumSet.of(
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

    private static final EnumSet<Material> REDSTONE_ADVANCED = EnumSet.of(
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
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlockPlaced().getType();

        boolean rsLvl1 = p.isOp() || p.hasPermission("unity.upgrade.redstone.1");
        boolean rsLvl2 = p.isOp() || p.hasPermission("unity.upgrade.redstone.2");

        if (REDSTONE_BASIC.contains(m) && !rsLvl1) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "⚡ Базовые редстоун-компоненты доступны с апгрейдом 'Редстоун I'.");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
            return;
        }

        if (REDSTONE_ADVANCED.contains(m) && !rsLvl2) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "⚙ Продвинутые редстоун-механизмы требуют апгрейда 'Редстоун II'.");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
        }
    }

    /* ==================================================
       2) ПЕЧКИ — работают только в стране/колонии
       ================================================== */

    private static final Set<Material> ORE_SMELT_OUTPUTS = EnumSet.of(
            Material.IRON_INGOT,
            Material.GOLD_INGOT,
            Material.COPPER_INGOT
    );

    // шанс, что результат будет +1
    private static final double ORE_BOOST_CHANCE = 0.15;

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFurnaceSmelt(org.bukkit.event.inventory.FurnaceSmeltEvent e) {
        Block block = e.getBlock();
        Location loc = block.getLocation();
        if (!UpgradeCondition.isInsideCountryOrColony(loc)) return;
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;
        if (UpgradeCondition.countryMaxLevel(country, "unity.furnace.ore_boost", 1) < 1) return;
        ItemStack result = e.getResult();
        if (result.getType() == Material.AIR) return;
        if (!ORE_SMELT_OUTPUTS.contains(result.getType())) return;
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() >= ORE_BOOST_CHANCE) return;

        ItemStack bonus = result.clone();
        int maxStack = Math.min(bonus.getMaxStackSize(), 64);
        int newAmount = Math.min(bonus.getAmount() + 1, maxStack);
        bonus.setAmount(newAmount);

        e.setResult(bonus);

        World w = block.getWorld();
        Location fxLoc = block.getLocation().add(0.5, 1.0, 0.5);
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, fxLoc, 2);
        w.playSound(fxLoc, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.3f, 1.2f);

        if (DEBUG) {
            d("onFurnaceSmelt BONUS +1 " + result.getType() +
                    " at " + loc + " country=" + country);
        }
    }

    /* ==============================================================
       3) ВОРОНКИ — L1 замедление/норма; L2 турбо в INDUSTRIAL
       ============================================================== */

    // каждая воронка без апгрейда работает через тик (toggle)
    private static final Map<String, Boolean> HOPPER_TOGGLE = new HashMap<>();

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHopperMove(InventoryMoveItemEvent e) {
        InventoryHolder initiator = e.getInitiator().getHolder();
        Location loc = holderLocation(initiator);
        if (loc == null) {
            d("onHopperMove: holder location null");
            return;
        }

        String country = UpgradeCondition.locationCountryOwner(loc); // canonical страны
        int hopperLvl = UpgradeCondition.countryMaxLevel(country, "unity.hopper.smart", 2);

        d("onHopperMove at " + loc + " country=" + country + " hopperLvl=" + hopperLvl);

        // --- Lvl 0: замедление - каждый второй тик блокируем
        if (hopperLvl <= 0) {
            String key = hopperKeyFromHolder(initiator);
            if (key == null) {
                d("onHopperMove lvl0 slow-mode DEFAULT CANCEL (no key)");
                e.setCancelled(true);
                return;
            }

            boolean prev = HOPPER_TOGGLE.getOrDefault(key, false);
            boolean allowThisTick = !prev;
            HOPPER_TOGGLE.put(key, allowThisTick);

            d("onHopperMove lvl0 slow-mode key=" + key + " allow=" + allowThisTick);

            if (!allowThisTick) {
                e.setCancelled(true);
            }
            return;
        }

        // Lvl1+: не трогаем, ванильный перенос разрешён.
        // Если нет турбо — стоп на этом.
        if (hopperLvl < 2) {
            d("onHopperMove: hopperLvl < 2 -> normal speed, no turbo");
            return;
        }

        // Lvl2+: возможен турбо, НО только если инициатор именно Hopper и он в INDUSTRIAL.
        if (!(initiator instanceof org.bukkit.block.Hopper hopperState)) {
            d("onHopperMove: initiator is not Hopper -> skip turbo mark");
            return;
        }

        boolean insideIndustrial = UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL);
        d("onHopperMove turbo check: insideIndustrial=" + insideIndustrial);

        if (!insideIndustrial) {
            return;
        }

        // CHANGED: раньше мы делали tryFastChainPush(hopperState) прямо тут.
        // Теперь мы просто помечаем этот хоппер для фоновой обработки.
        Block hopperBlock = hopperState.getBlock();
        TURBO_HOPPERS.put(hopperBlock, System.currentTimeMillis());
        d("onHopperMove TURBO MARKED at " + loc);
    }

    // кэш права на турбо, чтоб не дёргать LuckPerms и зоны каждый тик
    private static final Map<Block, Eligibility> TURBO_ELIGIBILITY = new ConcurrentHashMap<>();

    private record Eligibility(boolean canTurbo, long checkedAt) {
    }

    // сколько мс кэшируем право на турбо для одного хоппера
    private static final long TURBO_ELIGIBILITY_CACHE_MS = 1000L;

    private static boolean canTurboFastCached(Location loc) {
        Block b = loc.getBlock();
        Eligibility e = TURBO_ELIGIBILITY.get(b);

        long now = System.currentTimeMillis();
        if (e != null && (now - e.checkedAt) < TURBO_ELIGIBILITY_CACHE_MS) {
            return e.canTurbo;
        }

        // Пересчитать "честно"
        boolean fresh = computeTurboEligibility(loc);

        TURBO_ELIGIBILITY.put(b, new Eligibility(fresh, now));
        return fresh;
    }

    // это твоя оригинальная логика "а точно ли этот хоппер турбо-допущен"
    private static boolean computeTurboEligibility(Location loc) {
        String country = UpgradeCondition.locationCountryOwner(loc);
        int hopperLvl = UpgradeCondition.countryMaxLevel(country, "unity.hopper.smart", 2);
        if (hopperLvl < 2) return false;

        return UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL);
    }

    // =======================
    // TURBO-логика для воронок (теперь вызывается ТОЛЬКО таймером)
    // =======================

    // NEW: отдельный метод, который тикает уже помеченный турбо-хоппер.
    private static void fastTickHopper(org.bukkit.block.Hopper hopperState) {
        if (hopperState == null) return;

        org.bukkit.inventory.Inventory hopperInv = hopperState.getInventory();

        boolean pulled = pullOneMoreFromAbove(hopperState, hopperInv);
        boolean pushed = pushOneMoreForward(hopperState, hopperInv);
    }

    /**
     * Попытаться стянуть +1 предмет из инвентаря НАД воронкой в саму воронку.
     * Возвращает true если реально переместили предмет.
     */
    private static boolean pullOneMoreFromAbove(org.bukkit.block.Hopper hopperState,
                                                org.bukkit.inventory.Inventory hopperInv) {
        Location myLoc = hopperState.getLocation();
        Location aboveLoc = myLoc.clone().add(0, 1, 0);

        org.bukkit.block.BlockState aboveState = aboveLoc.getBlock().getState();
        if (!(aboveState instanceof InventoryHolder srcHolder)) return false;

        org.bukkit.inventory.Inventory srcInv = srcHolder.getInventory();

        int srcSlot = -1;
        ItemStack srcStack = null;
        ItemStack[] contents = srcInv.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            srcSlot = i;
            srcStack = it;
            break;
        }
        if (srcSlot < 0) return false;

        ItemStack oneItem = srcStack.clone();
        oneItem.setAmount(1);

        HashMap<Integer, ItemStack> leftover = hopperInv.addItem(oneItem);
        boolean success = leftover.isEmpty();
        if (!success) {
            return false;
        }

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

    /**
     * Попытаться протолкнуть +1 предмет из воронки вперёд по направлению клюва.
     * Возвращает true если реально переместили 1 предмет.
     */
    private static boolean pushOneMoreForward(org.bukkit.block.Hopper hopperState,
                                              org.bukkit.inventory.Inventory hopperInv) {
        Location myLoc = hopperState.getLocation();

        org.bukkit.block.data.type.Hopper data =
                (org.bukkit.block.data.type.Hopper) hopperState.getBlock().getBlockData();
        org.bukkit.block.BlockFace face = data.getFacing(); // NORTH, SOUTH, EAST, WEST, DOWN

        Location outLoc = myLoc.clone().add(face.getModX(), face.getModY(), face.getModZ());
        org.bukkit.block.BlockState outState = outLoc.getBlock().getState();
        if (!(outState instanceof InventoryHolder dstHolder)) {
            d("pushOneMoreForward: no InventoryHolder at " + outLoc + " from hopper " + myLoc);
            return false;
        }

        org.bukkit.inventory.Inventory dstInv = dstHolder.getInventory();

        int hopperSlot = -1;
        ItemStack hopperStack = null;
        ItemStack[] hopperContents = hopperInv.getContents();
        for (int i = 0; i < hopperContents.length; i++) {
            ItemStack it = hopperContents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            hopperSlot = i;
            hopperStack = it;
            break;
        }
        if (hopperSlot < 0) return false;

        ItemStack oneItem = hopperStack.clone();
        oneItem.setAmount(1);

        HashMap<Integer, ItemStack> leftover = dstInv.addItem(oneItem);
        boolean success = leftover.isEmpty();
        if (!success) {
            return false;
        }

        int newAmount = hopperStack.getAmount() - 1;
        if (newAmount <= 0) {
            hopperInv.setItem(hopperSlot, null);
        } else {
            ItemStack newStack = hopperStack.clone();
            newStack.setAmount(newAmount);
            hopperInv.setItem(hopperSlot, newStack);
        }

        d("pushOneMoreForward: pushed +1 " + oneItem.getType()
                + " from hopper " + myLoc + " toward " + face);
        return true;
    }

    private static String hopperKeyFromHolder(InventoryHolder holder) {
        try {
            if (holder instanceof org.bukkit.block.Hopper hopperState) {
                org.bukkit.Location l = hopperState.getLocation();
                return l.getWorld().getUID() + ":" +
                        l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
            if (holder instanceof org.bukkit.block.BlockState bs) {
                org.bukkit.Location l = bs.getLocation();
                return l.getWorld().getUID() + ":" +
                        l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Location holderLocation(InventoryHolder h) {
        try {
            if (h instanceof org.bukkit.block.BlockState bs) {
                return bs.getLocation();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /* ============================================
       4) TNT-«QUARRY» — в стране/колониях
       ============================================ */

    private static final Map<Material, Double> TNT_DUP_WHITELIST = new HashMap<>();
    static {
        TNT_DUP_WHITELIST.put(Material.DIAMOND_ORE, 0.10);
        TNT_DUP_WHITELIST.put(Material.DEEPSLATE_DIAMOND_ORE, 0.10);

        TNT_DUP_WHITELIST.put(Material.IRON_ORE, 0.12);
        TNT_DUP_WHITELIST.put(Material.DEEPSLATE_IRON_ORE, 0.12);

        TNT_DUP_WHITELIST.put(Material.GOLD_ORE, 0.12);
        TNT_DUP_WHITELIST.put(Material.DEEPSLATE_GOLD_ORE, 0.12);

        TNT_DUP_WHITELIST.put(Material.COPPER_ORE, 0.12);
        TNT_DUP_WHITELIST.put(Material.DEEPSLATE_COPPER_ORE, 0.12);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) {
        Location loc = e.getLocation();
        String country = UpgradeCondition.locationCountryOwner(loc);
        d("onExplode at " + loc + " country=" + country + " blocks=" + e.blockList().size());
        if (country == null) return;

        boolean enabled = UpgradeCondition.countryMaxLevel(country, "unity.tnt.quarry", 1) >= 1;
        d("onExplode Enabled=" + enabled);
        if (!enabled) return;

        Random rnd = new Random();
        for (Block b : e.blockList()) {
            Double prob = TNT_DUP_WHITELIST.get(b.getType());
            if (prob == null) continue;

            if (rnd.nextDouble() <= prob) {
                Collection<ItemStack> drops = b.getDrops();
                d("drop dup for " + b.getType() + " items=" + drops.size());
                for (ItemStack dIt : drops) {
                    if (dIt == null || dIt.getType().isAir() || dIt.getAmount() <= 0) continue;
                    loc.getWorld().dropItemNaturally(b.getLocation(), dIt.clone());
                }
            }
        }
    }

    /* =======================================================
       5) ЭФФЕКТЫ (Haste и др.) — в своей стране/колонии
       ======================================================= */

    private static final int EFFECT_TICKS = 20 * 12;
    private static final long REAPPLY_COOLDOWN_MS = 4000;
    private final Map<UUID, Long> lastApplied = new ConcurrentHashMap<>();

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (sameBlock(e)) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastApplied.get(p.getUniqueId());
        if (last != null && (now - last) < REAPPLY_COOLDOWN_MS) return;
        checkAndApply(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        d("onJoin " + e.getPlayer().getName());
        Bukkit.getScheduler().runTaskLater(
                UnityLauncher.getInstance(),
                () -> checkAndApply(e.getPlayer()),
                20L
        );
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        d("onRespawn " + e.getPlayer().getName());
        Bukkit.getScheduler().runTaskLater(
                UnityLauncher.getInstance(),
                () -> checkAndApply(e.getPlayer()),
                20L
        );
    }

    private boolean sameBlock(PlayerMoveEvent e) {
        return e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ();
    }

    private void checkAndApply(Player p) {
        if (p == null || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;

        long now = System.currentTimeMillis();
        Long last = lastApplied.get(p.getUniqueId());
        if (last != null && (now - last) < REAPPLY_COOLDOWN_MS) {
            return;
        }

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null) {
            d("checkAndApply " + p.getName() + ": no zone -> clear");
            lastApplied.put(p.getUniqueId(), now);
            return;
        }

        ZoneType zt = z.getType();
        if (zt != ZoneType.COUNTRY && zt != ZoneType.COLONY) {
            d("checkAndApply " + p.getName() + ": zone is " + zt + " -> clear");
            lastApplied.put(p.getUniqueId(), now);
            return;
        }

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        String zc = UpgradeCondition.zoneCountryCanonical(z);

        d("checkAndApply " + p.getName() + ": pc=" + pc + " zc=" + zc);

        if (pc == null || !pc.equals(zc)) {
            d("checkAndApply " + p.getName() + ": not same country -> clear");
            lastApplied.put(p.getUniqueId(), now);
            return;
        }

        int hasteLvl = UpgradeCondition.countryMaxLevel(pc, "unity.zone.haste", 2);
        int speedLvl = UpgradeCondition.countryMaxLevel(pc, "unity.zone.speed", 2);
        int resLvl   = UpgradeCondition.countryMaxLevel(pc, "unity.zone.resistance", 2);

        int hasteAmp = (hasteLvl > 0 ? hasteLvl - 1 : -1);
        int speedAmp = (speedLvl > 0 ? speedLvl - 1 : -1);
        int resAmp   = (resLvl   > 0 ? resLvl   - 1 : -1);

        d("checkAndApply " + p.getName() + ": amps haste=" + hasteAmp + " speed=" + speedAmp + " res=" + resAmp);

        applyOrRemove(p, PotionEffectType.HASTE, hasteAmp);
        applyOrRemove(p, PotionEffectType.SPEED, speedAmp);
        applyOrRemove(p, PotionEffectType.RESISTANCE, resAmp);

        lastApplied.put(p.getUniqueId(), now);
    }

    private void applyOrRemove(Player p, PotionEffectType type, int amplifier) {
        if (amplifier >= 0)
            p.addPotionEffect(new PotionEffect(type, EFFECT_TICKS, amplifier, true, false, true));
    }
}
