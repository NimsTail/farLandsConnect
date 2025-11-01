package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * UpgradesListener — оптимизированный под конфиг.
 * Все тексты/параметры/пермишены читаются из UpgradesConfig (C).
 */
public class UpgradesListener implements Listener {

    // ===== ССЫЛКА НА КОНФИГ (единый источник правды) =====
    private static UpgradesConfig C;

    // ===== ОТЛАДКА =====
    public static boolean DEBUG = false;
    private static void d(String msg) { if (DEBUG) Bukkit.getLogger().info("[UL/UpgradesListener] " + msg); }

    // ====== ТУРБО-ВОРОНКИ (runtime-состояние) ======
    private static final Map<Block, Long> TURBO_HOPPERS = new ConcurrentHashMap<>();
    private static final Map<Block, Eligibility> TURBO_ELIGIBILITY = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> HOPPER_TOGGLE = new HashMap<>();
    private static BukkitTask turboTask;

    private record Eligibility(boolean canTurbo, long checkedAt) {}

    // ====== КЭШ эффектов для игроков ======
    private final Map<UUID, Long> lastApplied = new ConcurrentHashMap<>();

    // ===== Регистрация/перезапуск =====

    public static void registerAll(JavaPlugin plugin) {
        // грузим конфиг
        C = UpgradesConfig.load(plugin);
        DEBUG = C.DEBUG;
        d("registerAll() using config, DEBUG=" + DEBUG);

        // регистрируем листенер
        Bukkit.getPluginManager().registerEvents(new UpgradesListener(), plugin);

        // запускаем задачу турбо-воронок
        if (turboTask != null) turboTask.cancel();
        turboTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                UpgradesListener::runTurboTick,
                C.hopperTurboTaskPeriodTicks,
                C.hopperTurboTaskPeriodTicks
        );
        d("registerAll() turboTask started period=" + C.hopperTurboTaskPeriodTicks + " ticks");
    }

    public static void reload(JavaPlugin plugin) {
        // 1) перечитать конфиг плагина (вызовем из команды в UnityLauncher, но дубль не мешает)
        try {
            plugin.reloadConfig();
        } catch (Throwable ignored) {}

        // 2) полностью пересобрать слушатели/таски апгрейдов
        unregisterAll(plugin);
        registerAll(plugin);
    }

    public static void unregisterAll(JavaPlugin plugin) {
        d("unregisterAll() called");
        if (turboTask != null) {
            turboTask.cancel();
            turboTask = null;
        }
        TURBO_HOPPERS.clear();
        TURBO_ELIGIBILITY.clear();
        HOPPER_TOGGLE.clear();
        HandlerList.unregisterAll(new UpgradesListener());
    }

    // =====================================================================
    //  РЕДСТОУН — блокировки по апгрейдам (ноды из конфига)
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlockPlaced().getType();

        boolean rsLvl1 = p.isOp() || (C.redstonePerm1 != null && p.hasPermission(C.redstonePerm1));
        boolean rsLvl2 = p.isOp() || (C.redstonePerm2 != null && p.hasPermission(C.redstonePerm2));

        if (C.redstoneBasic.contains(m) && !rsLvl1) {
            e.setCancelled(true);
            p.sendMessage(C.redstoneMsg1);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
            return;
        }
        if (C.redstoneAdvanced.contains(m) && !rsLvl2) {
            e.setCancelled(true);
            p.sendMessage(C.redstoneMsg2);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
        }
    }

    // =====================================================================
    //  ЦЕННАЯ ЕДА — апгрейд unity.food.golden.1 из конфига
    // =====================================================================

    private static boolean hasGoldenFoodUnlock(Player p) {
        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        return UpgradeCondition.countryMaxLevel(pc, C.goldenFoodPerm, 1) >= 1;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        Material m = it.getType();
        if (!C.premiumFoods.contains(m)) return;

        Player p = e.getPlayer();
        if (hasGoldenFoodUnlock(p)) return;

        e.setCancelled(true);
        p.sendMessage(C.goldenFoodMsgConsume);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        if (result == null || !C.premiumFoods.contains(result.getType())) return;

        if (!(e.getView().getPlayer() instanceof Player p)) return;
        if (hasGoldenFoodUnlock(p)) return;

        e.getInventory().setResult(new ItemStack(Material.AIR));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        ItemStack result = e.getCurrentItem();
        if (result == null || !C.premiumFoods.contains(result.getType())) return;

        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (hasGoldenFoodUnlock(p)) return;

        e.setCancelled(true);
        p.sendMessage(C.goldenFoodMsgCraft);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 0.8f);
    }

    // =====================================================================
    //  ПЕЧКИ — бонус +1 к слиткам при апгрейде (в стране/колонии)

    // =====================================================================
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFurnaceSmelt(FurnaceSmeltEvent e) {
        Block block = e.getBlock();
        Location loc = block.getLocation();
        if (!UpgradeCondition.isInsideCountryOrColony(loc)) return;

        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;
        if (UpgradeCondition.countryMaxLevel(country, C.furnacePerm, 1) < 1) return;

        ItemStack result = e.getResult();
        if (result.getType().isAir()) return;
        if (!C.furnaceOutputs.contains(result.getType())) return;

        if (ThreadLocalRandom.current().nextDouble() >= C.furnaceChance) return;

        ItemStack bonus = result.clone();
        bonus.setAmount(Math.min(result.getAmount() + 1, bonus.getMaxStackSize()));
        e.setResult(bonus);

        if (C.furnaceSfx) {
            World w = block.getWorld();
            Location fxLoc = loc.clone().add(0.5, 1.0, 0.5);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, fxLoc, 2);
            w.playSound(fxLoc, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.3f, 1.2f);
        }

        if (DEBUG) d("onFurnaceSmelt BONUS +1 " + result.getType() + " at " + loc + " country=" + country);
    }

    // =====================================================================
    //  ВОРОНКИ — L0 замедление; L2 турбо внутри INDUSTRIAL
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHopperMove(InventoryMoveItemEvent e) {
        InventoryHolder initiator = e.getInitiator().getHolder();
        if (initiator == null) return;

        Location loc = holderLocation(initiator);
        if (loc == null) return;

        String country = UpgradeCondition.locationCountryOwner(loc);
        int hopperLvl = UpgradeCondition.countryMaxLevel(country, C.hopperSmartPermBase, 2);

        d("onHopperMove at " + loc + " country=" + country + " hopperLvl=" + hopperLvl);

        // Lvl 0: при включённом флаге в конфиге — каждый второй тик блокируем
        if (hopperLvl <= 0 && C.hopperSlowModeLvl0) {
            String key = hopperKeyFromHolder(initiator);
            if (key == null) {
                e.setCancelled(true);
                return;
            }
            boolean prev = HOPPER_TOGGLE.getOrDefault(key, false);
            boolean allowThisTick = !prev;
            HOPPER_TOGGLE.put(key, allowThisTick);
            if (!allowThisTick) e.setCancelled(true);
            return;
        }

        // Lvl1: ванильная скорость без турбо
        if (hopperLvl < 2) return;

        // Lvl2: помечаем для турбо, только если инициатор — Hopper и зона INDUSTRIAL
        if (!(initiator instanceof Hopper hopperState)) return;
        boolean insideIndustrial = UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL);
        if (!insideIndustrial) return;

        TURBO_HOPPERS.put(hopperState.getBlock(), System.currentTimeMillis());
        d("onHopperMove TURBO MARKED at " + loc);
    }

    /** Тикер турбо-воронок: ограничиваем бюджетом из конфига. */
    private static void runTurboTick() {
        long now = System.currentTimeMillis();
        int processed = 0;

        Iterator<Map.Entry<Block, Long>> it = TURBO_HOPPERS.entrySet().iterator();
        while (it.hasNext() && processed < C.hopperTurboBudgetPerRun) {
            Map.Entry<Block, Long> en = it.next();
            Block b = en.getKey();

            if (!(b.getState() instanceof Hopper hopperState)) { it.remove(); continue; }
            if (!b.getChunk().isLoaded()) { continue; }

            Location loc = b.getLocation();
            if (!canTurboFastCached(loc)) { it.remove(); continue; }

            fastTickHopper(hopperState);
            processed++;

            if (now - en.getValue() > 5000L) it.remove();
        }
    }

    private static boolean canTurboFastCached(Location loc) {
        Block b = loc.getBlock();
        Eligibility e = TURBO_ELIGIBILITY.get(b);

        long now = System.currentTimeMillis();
        if (e != null && (now - e.checkedAt) < C.hopperTurboEligibilityCacheMs) {
            return e.canTurbo;
        }

        boolean fresh = computeTurboEligibility(loc);
        TURBO_ELIGIBILITY.put(b, new Eligibility(fresh, now));
        return fresh;
    }

    private static boolean computeTurboEligibility(Location loc) {
        String country = UpgradeCondition.locationCountryOwner(loc);
        int hopperLvl = UpgradeCondition.countryMaxLevel(country, C.hopperSmartPermBase, 2);
        if (hopperLvl < 2) return false;
        return UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL);
    }

    /** Попытаться стянуть +1 предмет сверху во внутренний инвентарь хоппера. */
    private static boolean pullOneMoreFromAbove(Hopper hopperState,
                                                Inventory hopperInv) {
        Location myLoc = hopperState.getLocation();
        Location aboveLoc = myLoc.clone().add(0, 1, 0);

        BlockState aboveState = aboveLoc.getBlock().getState();
        if (!(aboveState instanceof InventoryHolder srcHolder)) return false;

        Inventory srcInv = srcHolder.getInventory();
        ItemStack[] contents = srcInv.getContents();

        int srcSlot = -1;
        ItemStack srcStack = null;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            srcSlot = i; srcStack = it; break;
        }
        if (srcSlot < 0) return false;

        ItemStack oneItem = srcStack.clone(); oneItem.setAmount(1);
        HashMap<Integer, ItemStack> leftover = hopperInv.addItem(oneItem);
        boolean success = leftover.isEmpty();
        if (!success) return false;

        int newAmount = srcStack.getAmount() - 1;
        if (newAmount <= 0) srcInv.setItem(srcSlot, null);
        else {
            ItemStack newStack = srcStack.clone();
            newStack.setAmount(newAmount);
            srcInv.setItem(srcSlot, newStack);
        }
        return true;
    }

    /** Попытаться протолкнуть +1 предмет по направлению клюва хоппера. */
    private static boolean pushOneMoreForward(Hopper hopperState,
                                              Inventory hopperInv) {
        Location myLoc = hopperState.getLocation();

        org.bukkit.block.data.type.Hopper data = (org.bukkit.block.data.type.Hopper) hopperState.getBlock().getBlockData();
        BlockFace face = data.getFacing(); // NORTH/SOUTH/EAST/WEST/DOWN

        Location outLoc = myLoc.clone().add(face.getModX(), face.getModY(), face.getModZ());
        BlockState outState = outLoc.getBlock().getState();
        if (!(outState instanceof InventoryHolder dstHolder)) return false;

        Inventory dstInv = dstHolder.getInventory();

        int hopperSlot = -1;
        ItemStack hopperStack = null;
        ItemStack[] hopperContents = hopperInv.getContents();
        for (int i = 0; i < hopperContents.length; i++) {
            ItemStack it = hopperContents[i];
            if (it == null || it.getType().isAir() || it.getAmount() <= 0) continue;
            hopperSlot = i; hopperStack = it; break;
        }
        if (hopperSlot < 0) return false;

        ItemStack oneItem = hopperStack.clone(); oneItem.setAmount(1);
        HashMap<Integer, ItemStack> leftover = dstInv.addItem(oneItem);
        boolean success = leftover.isEmpty();
        if (!success) return false;

        int newAmount = hopperStack.getAmount() - 1;
        if (newAmount <= 0) hopperInv.setItem(hopperSlot, null);
        else {
            ItemStack newStack = hopperStack.clone();
            newStack.setAmount(newAmount);
            hopperInv.setItem(hopperSlot, newStack);
        }

        d("pushOneMoreForward: pushed +1 " + oneItem.getType() + " from " + myLoc + " toward " + face);
        return true;
    }

    /** Выполнить «турбо-тик» для указанного хоппера (pull+push по 1 ед.). */
    private static void fastTickHopper(Hopper hopperState) {
        if (hopperState == null) return;
        Inventory hopperInv = hopperState.getInventory();
        boolean pulled = pullOneMoreFromAbove(hopperState, hopperInv);
        boolean pushed = pushOneMoreForward(hopperState, hopperInv);
        if (DEBUG) d("fastTickHopper: pulled=" + pulled + " pushed=" + pushed);
    }

    private static String hopperKeyFromHolder(InventoryHolder holder) {
        try {
            if (holder instanceof Hopper hopperState) {
                Location l = hopperState.getLocation();
                return l.getWorld().getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
            if (holder instanceof BlockState bs) {
                Location l = bs.getLocation();
                return l.getWorld().getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Location holderLocation(InventoryHolder h) {
        try {
            if (h instanceof BlockState bs) return bs.getLocation();
        } catch (Throwable ignored) {}
        return null;
    }

    // Чистим состояния при системных событиях
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        var ch = e.getChunk();
        TURBO_HOPPERS.keySet().removeIf(b -> b.getChunk().equals(ch));
        TURBO_ELIGIBILITY.keySet().removeIf(b -> b.getChunk().equals(ch));
        cleanupToggleForChunk(ch);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() == Material.HOPPER) {
            TURBO_HOPPERS.remove(b);
            TURBO_ELIGIBILITY.remove(b);
            removeToggleForBlock(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        var wuid = e.getWorld().getUID();
        TURBO_HOPPERS.keySet().removeIf(b -> b.getWorld().getUID().equals(wuid));
        TURBO_ELIGIBILITY.keySet().removeIf(b -> b.getWorld().getUID().equals(wuid));
        HOPPER_TOGGLE.entrySet().removeIf(en -> en.getKey().startsWith(wuid + ":"));
    }

    private void cleanupToggleForChunk(Chunk ch) {
        String prefix = ch.getWorld().getUID() + ":";
        int bx = ch.getX() << 4;
        int bz = ch.getZ() << 4;
        int maxX = bx + 15;
        int maxZ = bz + 15;
        HOPPER_TOGGLE.entrySet().removeIf(en -> {
            String s = en.getKey();
            if (!s.startsWith(prefix)) return false;
            int idx = s.indexOf(':'); if (idx < 0) return false;
            String[] xyz = s.substring(idx + 1).split(",");
            if (xyz.length != 3) return false;
            try {
                int x = Integer.parseInt(xyz[0]);
                int z = Integer.parseInt(xyz[2]);
                return x >= bx && x <= maxX && z >= bz && z <= maxZ;
            } catch (Exception ignore) { return false; }
        });
    }

    private void removeToggleForBlock(Block b) {
        String key = b.getWorld().getUID() + ":" + b.getX() + "," + b.getY() + "," + b.getZ();
        HOPPER_TOGGLE.remove(key);
    }

    // =====================================================================
    //  TNT-«QUARRY» — дублирование дропа для whitelisted руд
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) {
        Location loc = e.getLocation();
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null) return;
        if (UpgradeCondition.countryMaxLevel(country, C.tntPerm, 1) < 1) return;

        var rnd = ThreadLocalRandom.current();
        for (Block b : e.blockList()) {
            Double prob = C.tntDupWhitelist.get(b.getType());
            if (prob == null) continue;
            if (rnd.nextDouble() > prob) continue;

            Collection<ItemStack> drops = b.getDrops();
            for (ItemStack dIt : drops) {
                if (dIt == null || dIt.getType().isAir() || dIt.getAmount() <= 0) continue;
                loc.getWorld().dropItemNaturally(b.getLocation(), dIt.clone());
            }
        }
    }

    // =====================================================================
    //  ЭФФЕКТЫ — в своей стране/колонии, уровни из нод в конфиге
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (sameBlock(e)) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastApplied.get(p.getUniqueId());
        if (last != null && (now - last) < C.reapplyCooldownMs) return;
        checkAndApply(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> checkAndApply(e.getPlayer()), 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> checkAndApply(e.getPlayer()), 20L);
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
        if (last != null && (now - last) < C.reapplyCooldownMs) return;

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null) { lastApplied.put(p.getUniqueId(), now); return; }

        ZoneType zt = z.getType();
        if (zt != ZoneType.COUNTRY && zt != ZoneType.COLONY) { lastApplied.put(p.getUniqueId(), now); return; }

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        String zc = UpgradeCondition.zoneCountryCanonical(z);
        if (pc == null || !pc.equals(zc)) { lastApplied.put(p.getUniqueId(), now); return; }

        int hasteLvl = UpgradeCondition.countryMaxLevel(pc, C.permHaste, C.effectsMaxLevel);
        int speedLvl = UpgradeCondition.countryMaxLevel(pc, C.permSpeed, C.effectsMaxLevel);
        int resLvl   = UpgradeCondition.countryMaxLevel(pc, C.permResist, C.effectsMaxLevel);

        applyOrRemove(p, PotionEffectType.HASTE,      hasteLvl > 0 ? hasteLvl - 1 : -1);
        applyOrRemove(p, PotionEffectType.SPEED,      speedLvl > 0 ? speedLvl - 1 : -1);
        applyOrRemove(p, PotionEffectType.RESISTANCE, resLvl   > 0 ? resLvl   - 1 : -1);

        lastApplied.put(p.getUniqueId(), now);
    }

    private void applyOrRemove(Player p, PotionEffectType type, int amplifier) {
        if (amplifier >= 0) {
            p.addPotionEffect(new PotionEffect(type, C.effectTicks, amplifier, true, false, true));
        }
    }

    // =====================================================================
    //  Защита грядок — country perm base unity.zone.farmland (Lv1/Lv2)
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFarmlandTrample_EntityChange(EntityChangeBlockEvent e) {
        var b = e.getBlock();
        if (b.getType() != Material.FARMLAND) return;

        String country = UpgradeCondition.locationCountryOwner(b.getLocation());
        if (country == null || country.isBlank()) return;

        int lvl = UpgradeCondition.countryMaxLevel(country, C.farmlandPermBase, 2);
        if (lvl <= 0) return;

        // Lv2: полная защита
        if (lvl >= 2) { e.setCancelled(true); if (DEBUG) d("Farmland protect L2 (EntityChange)"); return; }

        // Lv1: защищаем от прыжков/малого падения игрока
        if (e.getEntity() instanceof Player p) {
            if (p.getFallDistance() < C.farmlandBigFallThreshold) {
                e.setCancelled(true);
                if (DEBUG) d("Farmland protect L1 (EntityChange) fall=" + p.getFallDistance());
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFarmlandTrample_Physical(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        var block = e.getClickedBlock();
        if (block == null || block.getType() != Material.FARMLAND) return;

        String country = UpgradeCondition.locationCountryOwner(block.getLocation());
        if (country == null || country.isBlank()) return;

        int lvl = UpgradeCondition.countryMaxLevel(country, C.farmlandPermBase, 2);
        if (lvl <= 0) return;

        if (lvl >= 2) { e.setCancelled(true); if (DEBUG) d("Farmland protect L2 (PHYSICAL)"); return; }

        Player p = e.getPlayer();
        if (p.getFallDistance() < C.farmlandBigFallThreshold) {
            e.setCancelled(true);
            if (DEBUG) d("Farmland protect L1 (PHYSICAL) fall=" + p.getFallDistance());
        }
    }
}
