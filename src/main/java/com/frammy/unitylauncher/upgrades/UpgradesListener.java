package com.frammy.unitylauncher.upgrades;

import com.destroystokyo.paper.event.entity.PhantomPreSpawnEvent;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
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
import org.jetbrains.annotations.Nullable;

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

    private static BukkitTask cropsLL_Task;
    private final Map<UUID, Long> lastApplied = new ConcurrentHashMap<>();

    private static BukkitTask antiPhantomTask;
    private static final Map<UUID, Integer> INSOMNIA_FROZEN = new ConcurrentHashMap<>();

    private static final Map<Block, Double> BREW_ACCEL = new ConcurrentHashMap<>();
    private static BukkitTask brewTask;

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

        if (cropsLL_Task != null) cropsLL_Task.cancel();
        cropsLL_Task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                UpgradesListener::runCropsLowLightTick,
                C.cropsLL_ScanPeriodTicks,
                C.cropsLL_ScanPeriodTicks
        );
        d("cropsLowLight task started: period=" + C.cropsLL_ScanPeriodTicks + " ticks");

        if (antiPhantomTask != null) antiPhantomTask.cancel();
        antiPhantomTask = Bukkit.getScheduler().runTaskTimer(
                plugin, UpgradesListener::runAntiPhantomTick,
                40, 40
        );

        if (brewTask != null) brewTask.cancel();
        brewTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                UpgradesListener::runBrewTick,
                1L, 1L // тикаем раз в тик — точно и дёшево (на активных стойках)
        );
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
        if (cropsLL_Task != null) { cropsLL_Task.cancel(); cropsLL_Task = null; }
        if (antiPhantomTask != null) { antiPhantomTask.cancel(); antiPhantomTask = null; }
        INSOMNIA_FROZEN.clear();
        if (brewTask != null) { brewTask.cancel(); brewTask = null; }
        BREW_ACCEL.clear();
        HandlerList.unregisterAll(new UpgradesListener());
    }

    // =====================================================================
    //  РЕДСТОУН — блокировки по апгрейдам (ноды из конфига)
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Material m = e.getBlockPlaced().getType();

        boolean rsLvl1 = p.isOp() || (C.rsL1.perm() != null && p.hasPermission(C.rsL1.perm()));
        boolean rsLvl2 = p.isOp() || (C.rsL2.perm() != null && p.hasPermission(C.rsL2.perm()));

        if (C.rsL1.allowed().contains(m) && !rsLvl1) {
            e.setCancelled(true);
            if (!C.rsL1.errmsg().isEmpty()) p.sendMessage(C.rsL1.errmsg());
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
            return;
        }
        if (C.rsL2.allowed().contains(m) && !rsLvl2) {
            e.setCancelled(true);
            if (!C.rsL2.errmsg().isEmpty()) p.sendMessage(C.rsL2.errmsg());
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
        if (!C.premiumFoods.contains(e.getItem().getType())) return;
        Player p = e.getPlayer();
        if (hasGoldenFoodUnlock(p)) return;
        e.setCancelled(true);
        if (!C.goldenFoodMsgConsume.isEmpty()) p.sendMessage(C.goldenFoodMsgConsume);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        ItemStack result = e.getCurrentItem();
        if (result == null || !C.premiumFoods.contains(result.getType())) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (hasGoldenFoodUnlock(p)) return;
        e.setCancelled(true);
        if (!C.goldenFoodMsgCraft.isEmpty()) p.sendMessage(C.goldenFoodMsgCraft);
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

        // === Lvl 0 (нет страны ИЛИ нет апгрейда): каждый второй тик пропускаем ===
        // работает и ВНЕ страны (country == null/blank), и в стране с lvl<=0
        boolean slowEveryOther = C.hopperSlowModeLvl0 && (country == null || country.isBlank() || hopperLvl <= 0);
        if (slowEveryOther) {
            String key = hopperKeyFromHolder(initiator);
            if (key == null) {
                // Больше НИКОГДА не гасим событие из-за ключа — делаем эфемерный ключ
                key = "ephemeral:" + System.identityHashCode(initiator);
            }
            boolean prev = HOPPER_TOGGLE.getOrDefault(key, false);
            boolean allowThisTick = !prev;
            HOPPER_TOGGLE.put(key, allowThisTick);
            if (!allowThisTick) e.setCancelled(true);
            return;
        }

        // === Lvl 1: ванильная скорость ===
        if (hopperLvl < 2) return;

        // === Lvl 2: турбо только если инициатор — Hopper и внутри INDUSTRIAL ===
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
            // Любой блочный инвентарь (сундук, печь, воронка и т.д.)
            if (holder instanceof BlockState bs) {
                Location l = bs.getLocation();
                return l.getWorld().getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
            }
            // Двойной сундук имеет свою локацию
            if (holder instanceof org.bukkit.inventory.DoubleChestInventory dc) {
                Location l = dc.getLocation();
                if (l != null && l.getWorld() != null) {
                    return l.getWorld().getUID() + ":" + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
                }
            }
            // Вагонетки-хранилища (в т.ч. HopperMinecart, StorageMinecart)
            if (holder instanceof org.bukkit.entity.minecart.HopperMinecart hm) {
                var w = hm.getWorld();
                return w.getUID() + ":" + (int)Math.floor(hm.getLocation().getX())
                        + "," + (int)Math.floor(hm.getLocation().getY())
                        + "," + (int)Math.floor(hm.getLocation().getZ())
                        + ":" + hm.getUniqueId();
            }
            if (holder instanceof org.bukkit.entity.minecart.StorageMinecart sm) {
                var w = sm.getWorld();
                return w.getUID() + ":" + (int)Math.floor(sm.getLocation().getX())
                        + "," + (int)Math.floor(sm.getLocation().getY())
                        + "," + (int)Math.floor(sm.getLocation().getZ())
                        + ":" + sm.getUniqueId();
            }
            // Любая другая сущность с инвентарём
            if (holder instanceof org.bukkit.entity.Entity ent) {
                var w = ent.getWorld();
                return w.getUID() + ":" + (int)Math.floor(ent.getLocation().getX())
                        + "," + (int)Math.floor(ent.getLocation().getY())
                        + "," + (int)Math.floor(ent.getLocation().getZ())
                        + ":" + ent.getUniqueId();
            }
        } catch (Throwable ignored) {}
        // Вернём null, но вызывающий код больше не будет из-за этого гасить событие
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
        BREW_ACCEL.keySet().removeIf(b -> b.getChunk().equals(ch));
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
        if (e.getBlock().getType() == Material.BREWING_STAND) {
            BREW_ACCEL.remove(e.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent e) {
        var wuid = e.getWorld().getUID();
        TURBO_HOPPERS.keySet().removeIf(b -> b.getWorld().getUID().equals(wuid));
        TURBO_ELIGIBILITY.keySet().removeIf(b -> b.getWorld().getUID().equals(wuid));
        HOPPER_TOGGLE.entrySet().removeIf(en -> en.getKey().startsWith(wuid + ":"));
        BREW_ACCEL.keySet().removeIf(b -> b.getWorld().getUID().equals(wuid));
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
        trackChurchProgress(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> checkAndApply(e.getPlayer()), 20L);
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> trackChurchProgress(e.getPlayer()), 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> checkAndApply(e.getPlayer()), 20L);

        // уже есть отложенный checkAndApply — не трогаем
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> {
            // «психподдержка»: Luck I для любого игрока
            int dur = C.psychSupportLuckTicks;
            if (dur > 0) {
                e.getPlayer().addPotionEffect(
                        new PotionEffect(PotionEffectType.LUCK, dur, 0, true, false, true)
                );
                if (DEBUG) d("Psych support: Luck I for " + e.getPlayer().getName() + " for " + dur + " ticks");
            }
        }, 20L); // 1 секунда после респавна — чтобы всё стабильно применилось
        Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> trackChurchProgress(e.getPlayer()), 20L);
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

    // =====================================================================
    //  ПЕЧИ — ускорение от лавы/магмы: 0% @ radius .. max% вплотную
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFurnaceStart(FurnaceStartSmeltEvent e) {

        Block block = e.getBlock();
        Location loc = block.getLocation();

        // Должны быть в стране/колонии
        if (!UpgradeCondition.isInsideCountryOrColony(loc)) return;

        // Проверка ноды (отдельная или общая с C.furnacePerm)
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        String permBase = (C.furnaceHeatPerm != null && !C.furnaceHeatPerm.isBlank()) ? C.furnaceHeatPerm : C.furnacePerm;
        if (UpgradeCondition.countryMaxLevel(country, permBase, 1) < 1) return;

        // Находим ближайший источник тепла в кубе радиуса R
        final int R = Math.max(1, C.furnaceHeatRadius);
        int d = nearestHeatDistance(block, R);
        double pct = lerpHeatBoostPercent(d, R, C.furnaceHeatMaxPct);
        if (pct <= 0.0) return;

        int base = e.getTotalCookTime();
        if (base <= 1) return;

        int reduced = Math.max(1, (int)Math.round(base * (1.0 - pct / 100.0)));
        if (reduced >= base) return; // на всякий

        e.setTotalCookTime(reduced);

        if (C.furnaceSfx) {
            Location fx = loc.clone().add(0.5, 1.0, 0.5);
            block.getWorld().spawnParticle(Particle.SMALL_FLAME, fx, 3, 0.08, 0.08, 0.08, 0.0);
            block.getWorld().playSound(fx, Sound.BLOCK_LAVA_POP, 0.15f, 1.6f);
        }

        if (DEBUG) d("Furnace heat-boost " + String.format(java.util.Locale.ROOT, "%.1f", pct)
                + "%% (d=" + d + ", R=" + R + ") at " + loc + " country=" + country
                + " base=" + base + " -> " + reduced);
    }

    /** Проверяем минимальную целочисленную дистанцию до лавы/магмы в кубе радиуса r.
     *  Возвращает:
     *    1  — если источник тепла впритык (по любой из 6 сторон или сверху/снизу),
     *    2..r — ближайшая дистанция,
     *    Integer.MAX_VALUE — если не найдено.
     *
     *  Оптимизация: выходим сразу при нахождении distance==1.
     */
    private static int nearestHeatDistance(Block origin, int r) {
        final int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        final var w = origin.getWorld();
        int best = Integer.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    // ранний отсев по кубу Чебышёва/Манхэттен? оставим простой манхэттен
                    int md = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (md >= best) continue;

                    Material t = w.getBlockAt(ox + dx, oy + dy, oz + dz).getType();
                    if (t == Material.LAVA || t == Material.MAGMA_BLOCK) {
                        best = md;
                        if (best <= 1) return 1; // лучше уже не будет
                    }
                }
            }
        }
        return best;
    }

    /** Линейная интерполяция процента:
     *  d ∈ [1..radius] -> boost ∈ [maxPct..0], d>=radius => 0, d<=1 => maxPct
     */
    private static double lerpHeatBoostPercent(int d, int radius, double maxPct) {
        if (d >= radius) return 0.0;
        if (d <= 1) return maxPct;
        // t = (radius - d) / (radius - 1)
        double t = (double)(radius - d) / (double)(Math.max(1, radius - 1));
        if (t < 0) t = 0; else if (t > 1) t = 1;
        return t * maxPct;
    }

    // =====================================================================
    //  Теплицы — рост растений даже без света
    // =====================================================================

    private static final Set<Material> GLASS_FULL = EnumSet.of(
            Material.GLASS, Material.TINTED_GLASS,
            Material.WHITE_STAINED_GLASS, Material.LIGHT_GRAY_STAINED_GLASS, Material.GRAY_STAINED_GLASS,
            Material.BLACK_STAINED_GLASS, Material.BROWN_STAINED_GLASS, Material.RED_STAINED_GLASS,
            Material.ORANGE_STAINED_GLASS, Material.YELLOW_STAINED_GLASS, Material.LIME_STAINED_GLASS,
            Material.GREEN_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.LIGHT_BLUE_STAINED_GLASS,
            Material.BLUE_STAINED_GLASS, Material.PURPLE_STAINED_GLASS, Material.MAGENTA_STAINED_GLASS,
            Material.PINK_STAINED_GLASS
    );
    private static final Set<Material> GLASS_PANES = EnumSet.of(
            Material.GLASS_PANE, Material.WHITE_STAINED_GLASS_PANE, Material.LIGHT_GRAY_STAINED_GLASS_PANE,
            Material.GRAY_STAINED_GLASS_PANE, Material.BLACK_STAINED_GLASS_PANE, Material.BROWN_STAINED_GLASS_PANE,
            Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.GREEN_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE, Material.PINK_STAINED_GLASS_PANE
    );

    private static boolean isAgeableCropOnFarmland(Block b) {
        if (!(b.getBlockData() instanceof Ageable)) return false;
        return b.getRelative(BlockFace.DOWN).getType() == Material.FARMLAND;
    }

    /** Первая НЕ-воздух «крыша» в пределах 6 блоков — стекло/панель? */
    private static boolean hasGlassRoof(Block base) {
        final int MAX_H = 6;
        var w = base.getWorld();
        int x = base.getX(), y = base.getY(), z = base.getZ();
        for (int dy = 1; dy <= MAX_H; dy++) {
            Material t = w.getBlockAt(x, y + dy, z).getType();
            if (t.isAir()) continue;
            return GLASS_FULL.contains(t) || GLASS_PANES.contains(t);
        }
        return false;
    }

    /** Шанс от темноты: light < 9 → (0...max]; при light<=4 → max. */
    private static double chanceFromLight(int light, double maxPct) {
        final int THRESH = 9, MIN = 4;
        if (light >= THRESH) return 0.0;
        if (light <= MIN)    return maxPct;
        double t = (double)(THRESH - light) / (double)(THRESH - MIN);
        return Math.max(0.0, Math.min(1.0, t)) * maxPct;
    }

    private static void runCropsLowLightTick() {
        var zm = UnityLauncher.getInstance().getZoneManager();
        if (zm == null) return;

        var zones = zm.getAllZonesSnapshot();
        if (zones == null || zones.isEmpty()) return;

        var rnd = ThreadLocalRandom.current();

        for (ZoneInfo z : zones) {
            ZoneType t = z.getType();
            // теперь проверяем только теплицы
            if (t != ZoneType.GREENHOUSE) continue;

            // у зоны должна быть страна и у неё — апгрейд теплиц
            String country = UpgradeCondition.zoneCountryCanonical(z);
            if (country == null || country.isBlank()) continue;
            if (UpgradeCondition.countryMaxLevel(country, "unity.crops.lowlight", 1) < 1) continue;

            // бюджет попыток на зону
            int budget = C.cropsLL_PerZoneBudget;

            for (int i = 0; i < budget; i++) {
                // случайная точка в bbox + строгая проверка вхождения
                Location p = z.randomPointInBox(rnd);
                if (p == null) continue; // зона пустая/битая — пропустим

                Block base = p.getBlock();
                for (int dy = -2; dy <= 2; dy++) {
                    Block b = base.getRelative(0, dy, 0);
                    if (!isAgeableCropOnFarmland(b)) continue;

                    // локация должна оставаться внутри теплицы
                    if (!UpgradeCondition.isInsideZoneTypeRaw(b.getLocation(), ZoneType.GREENHOUSE)) break;

                    if (!hasGlassRoof(b)) break;

                    int light = b.getLightLevel();
                    double chance = chanceFromLight(light, C.cropsLL_MaxPercent);
                    if (chance <= 0.0) break;

                    Ageable age = (Ageable) b.getBlockData();
                    if (age.getAge() >= age.getMaximumAge()) break;

                    if (rnd.nextDouble(100.0) < chance) {
                        age.setAge(Math.min(age.getMaximumAge(), age.getAge() + 1));
                        b.setBlockData(age, false);

                        if (C.furnaceSfx) { // общий флаг визуалки
                            var fx = b.getLocation().clone().add(0.5, 0.7, 0.5);
                            b.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, fx, 2, 0.15, 0.1, 0.15, 0.0);
                        }
                        if (DEBUG) d("Greenhouse grow +1 at " + b.getLocation() +
                                " light=" + light + " zone=" + z.getType() +
                                " country=" + country);
                    }
                    break;
                }
            }
        }
    }

    // =====================================================================
    //  Отключение фантомов — в зоне фантомы для игрока не спавнятся
    // =====================================================================

    private static boolean antiPhantomEligible(Player p) {
        // должен стоять в СВОЕЙ стране/колонии
        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null) return false;
        ZoneType t = z.getType();
        if (t != ZoneType.COUNTRY && t != ZoneType.COLONY) return false;

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        String zc = UpgradeCondition.zoneCountryCanonical(z);
        if (pc == null || !pc.equals(zc)) return false;

        return UpgradeCondition.countryMaxLevel(pc, C.antiPhantomPermBase, 1) >= 1;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPhantomPreSpawn(PhantomPreSpawnEvent e) {
        // У этого ивента нет прямого Player-виновника — найдём ближайшего
        var phantom = e.getSpawningEntity(); // ещё не заспавнился полностью, но позиция есть
        Player nearest = getNearest(e);

        if (nearest != null && antiPhantomEligible(nearest)) {
            e.setCancelled(true);
            if (DEBUG) d("AntiPhantom: cancelled pre-spawn near " + nearest.getName());
        }
    }

    private static @Nullable Player getNearest(PhantomPreSpawnEvent e) {
        var loc = e.getSpawnLocation();
        Player nearest = null;
        double best = Double.MAX_VALUE;

        for (Player p : loc.getWorld().getPlayers()) {
            if (!p.getWorld().equals(loc.getWorld())) continue;
            double dx = p.getLocation().getX() - loc.getX();
            double dy = p.getLocation().getY() - loc.getY();
            double dz = p.getLocation().getZ() - loc.getZ();
            double d2 = dx*dx + dy*dy + dz*dz;
            if (d2 < best) { best = d2; nearest = p; }
        }
        return nearest;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCreatureSpawn(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.PHANTOM) return;

        var loc = e.getLocation();
        Player nearest = null;
        double best = 64*64;

        for (Player p : loc.getWorld().getPlayers()) {
            double dx = p.getLocation().getX() - loc.getX();
            double dz = p.getLocation().getZ() - loc.getZ();
            double d2 = dx*dx + dz*dz;
            if (d2 < best) { best = d2; nearest = p; }
        }

        if (nearest != null && antiPhantomEligible(nearest)) {
            e.setCancelled(true);
            if (DEBUG) d("AntiPhantom(fallback): cancelled spawn near " + nearest.getName());
        }
    }

    private static void runAntiPhantomTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            boolean protect = antiPhantomEligible(p);
            int cur = p.getStatistic(Statistic.TIME_SINCE_REST);

            if (protect) {
                INSOMNIA_FROZEN.compute(id, (k, base) -> (base == null || cur < base) ? cur : base);
                int base = INSOMNIA_FROZEN.get(id);
                if (cur != base) {
                    p.setStatistic(Statistic.TIME_SINCE_REST, base);
                    if (DEBUG) d("AntiPhantom: freeze TIME_SINCE_REST@" + p.getName() + " = " + base);
                }
            } else {
                INSOMNIA_FROZEN.remove(id);
            }
        }
    }

    // =====================================================================
    //  Быстрая варка зелий
    // =====================================================================

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (e.getInventory().getType() != InventoryType.BREWING) return;
        InventoryHolder h = e.getInventory().getHolder();
        if (!(h instanceof BrewingStand bs)) return;
        Block b = bs.getBlock();
        // отметим — далее таск сам проверит пригодность/зону/апгрейд
        BREW_ACCEL.putIfAbsent(b, 0.0);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        e.getInventory().getType();
        // не удаляем из карты — стойка может продолжать варить без GUI
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBrew(BrewEvent e) {
        // цикл варки завершился — стойка часто сразу начинает новый, держим в карте
        BREW_ACCEL.putIfAbsent(e.getBlock(), 0.0);
    }

    private static void runBrewTick() {
        if (C.brewSpeedPercent <= 0.0) return;

        // extra = сколько ДОПОЛНИТЕЛЬНЫХ «тик-срезов» в среднем на 1 игровой тик
        // Формула: хотим скорость 1/(1-s). Дополнение = (1/(1-s) - 1) = s/(1-s).
        final double s = C.brewSpeedPercent / 100.0;
        final double extraPerTick = (s >= 0.999) ? 1000.0 : (s / Math.max(1e-6, (1.0 - s)));

        Iterator<Map.Entry<Block, Double>> it = BREW_ACCEL.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Block, Double> en = it.next();
            Block b = en.getKey();

            if (!b.getChunk().isLoaded()) continue;
            BlockState st = b.getState();
            if (!(st instanceof BrewingStand bs)) { it.remove(); continue; }

            // проверка «это своя страна/колония» + апгрейд страны
            Location loc = b.getLocation();
            if (!UpgradeCondition.isInsideCountryOrColony(loc)) { it.remove(); continue; }

            String country = UpgradeCondition.locationCountryOwner(loc);
            if (country == null || country.isBlank()) { it.remove(); continue; }
            if (UpgradeCondition.countryMaxLevel(country, C.brewPerm, 1) < 1) { it.remove(); continue; }

            int time = bs.getBrewingTime(); // тики до готовности (обычно 400..1 во время процесса)
            if (time <= 1) continue;        // не варит сейчас

            double acc = en.getValue() + extraPerTick;
            int extra = (int) Math.floor(acc);
            if (extra > 0) {
                acc -= extra;
                int newTime = Math.max(1, time - extra);
                bs.setBrewingTime(newTime);
                bs.update(false, false);
                en.setValue(acc);
                if (DEBUG) d("Brew turbo @" + loc + " time " + time + " -> " + newTime + " (acc=" + String.format(java.util.Locale.ROOT, "%.2f", acc) + ")");
            } else {
                en.setValue(acc);
            }
        }
    }

    // =====================================================================
    //  ЦЕРКОВЬ: прогресс нахождения и кулдауны
    // =====================================================================

    private static final java.util.Set<PotionEffectType> NEGATIVE_EFFECTS = java.util.Set.of(
            PotionEffectType.BAD_OMEN,
            PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.NAUSEA,
            PotionEffectType.POISON,
            PotionEffectType.SLOWNESS,
            PotionEffectType.UNLUCK,
            PotionEffectType.WEAKNESS,
            PotionEffectType.WITHER
    );

    private static final Map<UUID, ChurchProgress> CHURCH_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<String, Long> CHURCH_BELL_COOLDOWN_UNTIL = new ConcurrentHashMap<>();      // per-church (zoneId) cooldown
    private static final Map<String, Map<UUID, Long>> CHURCH_PILGRIM_COOLDOWN_UNTIL = new ConcurrentHashMap<>(); // per-church per-player

    private record ChurchProgress(String zoneId, long enteredAtMs) {}

    private void trackChurchProgress(Player p) {
        if (p == null || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null || z.getType() != ZoneType.CHURCH) {
            // вышли из церкви -> сброс
            CHURCH_PROGRESS.remove(p.getUniqueId());
            return;
        }

        // «Гражданин этой церкви»: страна игрока должна совпадать со страной зоны
        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        String zc = UpgradeCondition.zoneCountryCanonical(z);
        if (pc == null || !pc.equals(zc)) {
            CHURCH_PROGRESS.remove(p.getUniqueId());
            return;
        }

        // апгрейды страны (через perms из конфига)
        boolean bellEnabled = UpgradeCondition.countryMaxLevel(pc, C.churchBellPerm, 1) >= 1;
        boolean pilgEnabled = UpgradeCondition.countryMaxLevel(pc, C.churchPilgrimagePerm, 1) >= 1;

        // Если ни один апгрейд не включён — сбрасываем трекер
        if (!bellEnabled && !pilgEnabled) {
            CHURCH_PROGRESS.remove(p.getUniqueId());
            return;
        }

        String zoneId = z.getID(); // у тебя он уже используется в BlueMapIntegration; если имя другое — подставь эквивалент
        long now = System.currentTimeMillis();
        ChurchProgress cur = CHURCH_PROGRESS.get(p.getUniqueId());
        if (cur == null || !Objects.equals(cur.zoneId, zoneId)) {
            CHURCH_PROGRESS.put(p.getUniqueId(), new ChurchProgress(zoneId, now));
            return;
        }

        long stayedMs = now - cur.enteredAtMs;

        // ===== Мирный колокол =====
        if (bellEnabled) {
            long needMs = C.churchBellStayMinutes * 60_000L;
            if (stayedMs >= needMs) {
                long until = CHURCH_BELL_COOLDOWN_UNTIL.getOrDefault(zoneId, 0L);
                if (now >= until) {
                    // снимаем негативные эффекты с ИГРОКА (локально)
                    clearNegativeEffects(p);

                    // визуалка/звук
                    if (C.churchBellSfx) {
                        var pos = p.getLocation().clone().add(0, 1.5, 0);
                        p.getWorld().playSound(pos, Sound.BLOCK_BELL_RESONATE, 1.0f, 1.0f);
                        p.getWorld().spawnParticle(Particle.NOTE, pos, 8, 0.6, 0.6, 0.6, 0.0);
                    }

                    // ставим кулдаун на ХРАМ (пер-церковь)
                    long cdMs = Math.max(0, C.churchBellCooldownMinutes) * 60_000L;
                    CHURCH_BELL_COOLDOWN_UNTIL.put(zoneId, now + cdMs);

                    // перезапускаем прогресс, чтобы «сидеть заново»
                    CHURCH_PROGRESS.put(p.getUniqueId(), new ChurchProgress(zoneId, now));
                }
            }
        }

        // ===== Паломничество =====
        if (pilgEnabled) {
            long needMs = C.churchPilgrimageStayMinutes * 60_000L;
            if (stayedMs >= needMs) {
                // индивидуальный кулдаун (если включён)
                long cdMs = Math.max(0, C.churchPilgrimageCooldownMinutes) * 60_000L;
                long nowMs = System.currentTimeMillis();
                Map<UUID, Long> perPlayer = CHURCH_PILGRIM_COOLDOWN_UNTIL.computeIfAbsent(zoneId, k -> new ConcurrentHashMap<>());
                long until = perPlayer.getOrDefault(p.getUniqueId(), 0L);

                if (cdMs == 0 || nowMs >= until) {
                    applyPilgrimageBuff(p);
                    if (cdMs > 0) perPlayer.put(p.getUniqueId(), nowMs + cdMs);

                    if (C.churchPilgrimageSfx) {
                        var pos = p.getLocation().clone().add(0, 1.0, 0);
                        p.getWorld().playSound(pos, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
                        p.getWorld().spawnParticle(Particle.SPLASH, pos, 20, 0.5, 0.6, 0.5, 0.0);
                    }

                    // перезапуск прогресса, чтобы не триггерить постоянно
                    CHURCH_PROGRESS.put(p.getUniqueId(), new ChurchProgress(zoneId, nowMs));
                }
            }
        }
    }

    private static void clearNegativeEffects(Player p) {
        try {
            for (PotionEffectType t : NEGATIVE_EFFECTS) {
                if (t == null) continue;
                p.removePotionEffect(t);
            }
        } catch (Throwable ignored) {}
        if (DEBUG) d("Peace Bell: cleared negatives for " + p.getName());
    }

    private static void applyPilgrimageBuff(Player p) {
        if (C.churchPilgrimageEffects == null || C.churchPilgrimageEffects.isEmpty()) return;

        // случайный эффект из списка в конфиге
        java.util.List<String> list = new java.util.ArrayList<>(C.churchPilgrimageEffects);
        String pick = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        PotionEffectType type = PotionEffectType.getByName(pick);
        if (type == null) return;

        int amp = Math.max(0, C.churchPilgrimageAmplifier);
        int durTicks = Math.max(20, C.churchPilgrimageBuffMinutes * 60 * 20);

        p.addPotionEffect(new PotionEffect(type, durTicks, amp, true, true, true));

        if (DEBUG) d("Pilgrimage: " + p.getName() + " got " + type.getName() + " for " + C.churchPilgrimageBuffMinutes + "m @amp=" + amp);
    }

}
