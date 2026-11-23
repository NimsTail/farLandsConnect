package com.frammy.unitylauncher.marketplace;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.auth.FarLandsApiClient;
import com.frammy.unitylauncher.auth.FarLandsApiClient.CourierJob;
import com.frammy.unitylauncher.auth.FarLandsApiClient.CourierLine;
import com.frammy.unitylauncher.auth.FarLandsApiClient.CourierSeller;
import com.frammy.unitylauncher.signs.features.shop.ShopListUpdater;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фантом-курьер v2 (сайт: infra/phantom-courier-design.md, routes/plugin/marketplace.ts
 * phantom-courier/*). У продавца fleetSize курьеров с capacitySlots слотами;
 * курьер берёт заказы из очереди продавца (FIFO по createdAt), объезжает
 * сундуки-источники (ближайший сосед), затем адреса доставки, и сразу
 * начинает следующий заход с того места, где закончил.
 *
 * Упрощения относительно дизайн-документа (см. UNTESTED.md):
 *  - строка заказа (Trade) никогда не делится между заходами: заход набирается
 *    целыми строками, пока хватает физических слотов (ceil(qty/maxStack));
 *    первая строка в очереди берётся всегда, даже если больше ёмкости;
 *  - позиция курьера — функция времени по ногам маршрута (скорость сайта),
 *    реальный торговец существует только рядом с игроками (как v1), без лодок.
 *
 * Состояние груза по Trade (CARRYING/DROPPED/DELIVERED) — в phantom-courier.yml,
 * как у v1: после рестарта сундук продавца повторно не списывается, доставленное
 * не выдаётся второй раз. Выпавший при смерти курьера груз несёт те же теги
 * phantom.tradeId/phantom.buyer, что v1 — исход решают обработчики подбора и
 * деспавна в PhantomDeliveryController.
 */
public final class PhantomCourierController implements Listener {

    // Как PHANTOM_SPEED_BLOCKS_PER_SEC на сайте (lib/phantomDelivery.ts).
    private static final double SPEED_BLOCKS_PER_SEC = 4.317 * 0.8;
    private static final double VISIBILITY_RADIUS_BLOCKS = 48.0;
    private static final double LOOKAHEAD_BLOCKS = 12.0;
    private static final double WALK_SPEED = 0.5;
    // Реальная сущность отстала от расчётной точки дальше этого — "задерживается".
    private static final double DELAY_GAP_BLOCKS = 32.0;

    private enum CargoState { CARRYING, DROPPED, DELIVERED }

    private record Planned(CourierJob job, CourierLine line, Material material) {}

    /** Нога маршрута: к точке `to`; на источнике — забрать pickups, у получателя — выгрузить drops заказа jobs. */
    private record Leg(Location to, List<Planned> pickups, List<Planned> drops) {}

    private static final class Trip {
        final List<Leg> legs;
        final Set<String> jobIds = new HashSet<>();
        int legIdx;
        long legStartMs;
        Location legFrom;
        boolean delayReported;

        Trip(List<Leg> legs, Location from, long now) {
            this.legs = legs;
            this.legFrom = from;
            this.legStartMs = now;
        }

        Leg leg() { return legs.get(legIdx); }
    }

    private static final class Courier {
        final String seller;
        final int index;
        Location pos; // где курьер стоит без захода / откуда начнёт следующий
        Trip trip;
        UUID entity;

        Courier(String seller, int index) {
            this.seller = seller;
            this.index = index;
        }

        String id() { return seller + "#" + index; }
    }

    private final UnityLauncher plugin;
    private final FarLandsApiClient api;
    private final NamespacedKey keyTradeId;
    private final NamespacedKey keyBuyer;
    private final NamespacedKey keyCourier;
    private final File storeFile;
    private final YamlConfiguration store;
    private final Map<String, List<Courier>> couriers = new HashMap<>();
    private final Set<String> reported = new HashSet<>();
    private final AtomicBoolean ticking = new AtomicBoolean();

    public PhantomCourierController(UnityLauncher plugin, FarLandsApiClient api) {
        this.plugin = plugin;
        this.api = api;
        // Те же ключи, что у v1 — обработчики подбора/деспавна и запрет торговли общие.
        this.keyTradeId = new NamespacedKey(plugin, "phantom.tradeId");
        this.keyBuyer = new NamespacedKey(plugin, "phantom.buyer");
        this.keyCourier = new NamespacedKey(plugin, "phantom.courier");
        this.storeFile = new File(plugin.getDataFolder(), "phantom-courier.yml");
        this.store = YamlConfiguration.loadConfiguration(storeFile);
    }

    /** periodTicks: 20 = 1с. No-op, если API-мост выключен. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tickAsync, periodTicks, periodTicks);
    }

    private void tickAsync() {
        if (!ticking.compareAndSet(false, true)) return;
        List<CourierSeller> sellers = api.fetchCourierJobs();
        if (sellers == null) { ticking.set(false); return; } // ошибка запроса — пропускаем тик
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { tickSync(sellers); } finally { ticking.set(false); }
        });
    }

    // ===== планировщик (главный поток) =====

    private void tickSync(List<CourierSeller> sellers) {
        World world = resolveWorld();
        if (world == null) return;
        long now = System.currentTimeMillis();

        Set<String> activeTrades = new HashSet<>();
        for (CourierSeller s : sellers) {
            for (CourierJob j : s.jobs()) for (CourierLine l : j.lines()) activeTrades.add(l.tradeId());
        }

        // Доставлено, но сайт ещё не убрал строку — повторяем отчёт раз за процесс.
        for (String id : activeTrades) {
            if (state(id) == CargoState.DELIVERED && reported.add(id)) {
                api.reportPhantomDelivered(id, store.getBoolean(id + ".droppedNearby"));
            }
        }

        for (CourierSeller s : sellers) {
            int fleet = s.fleetSize();
            if (fleet < 1) {
                // Апгрейд отключён после оплаты — заказы всё равно надо довезти.
                plugin.getLogger().warning("[PhantomCourier] у продавца " + s.sellerUsername() + " fleetSize=" + fleet + ", работает 1 курьер");
                fleet = 1;
            }
            List<Courier> list = couriers.computeIfAbsent(s.sellerUsername(), k -> new ArrayList<>());
            while (list.size() < fleet) list.add(new Courier(s.sellerUsername(), list.size()));
        }

        Map<String, CourierSeller> bySeller = new HashMap<>();
        for (CourierSeller s : sellers) bySeller.put(s.sellerUsername(), s);

        for (var it = couriers.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            CourierSeller seller = bySeller.get(e.getKey());
            for (Courier c : e.getValue()) {
                if (c.trip == null && seller != null) c.trip = plan(world, c, seller, now);
                if (c.trip != null) advance(world, c, now);
                updateEntity(world, c, now);
            }
            if (seller == null && e.getValue().stream().allMatch(c -> c.trip == null)) {
                e.getValue().forEach(this::removeEntity);
                it.remove();
            }
        }

        // Состояние груза строк, которых больше нет в очереди сайта.
        boolean changed = false;
        for (String id : store.getKeys(false)) {
            if (!activeTrades.contains(id) && !carriedByAnyTrip(id)) { store.set(id, null); changed = true; }
        }
        if (changed) save();
        reported.retainAll(activeTrades);
    }

    /** Набор захода: FIFO по заказам продавца, целыми строками, пока хватает слотов. */
    private Trip plan(World world, Courier c, CourierSeller seller, long now) {
        Set<String> busy = new HashSet<>();
        for (Courier other : couriers.getOrDefault(seller.sellerUsername(), List.of())) {
            if (other.trip == null) continue;
            for (Leg leg : other.trip.legs) for (Planned p : leg.pickups()) busy.add(p.line().tradeId());
        }

        List<CourierJob> jobs = new ArrayList<>(seller.jobs());
        jobs.sort((a, b) -> Long.compare(a.createdAtMs(), b.createdAtMs()));
        int capacity = Math.max(1, seller.capacitySlots());
        int used = 0;
        List<Planned> load = new ArrayList<>();
        for (CourierJob job : jobs) {
            for (CourierLine line : job.lines()) {
                if (busy.contains(line.tradeId())) continue;
                CargoState st = state(line.tradeId());
                if (st == CargoState.DROPPED || st == CargoState.DELIVERED) continue;
                Material m = PhantomDeliveryController.resolveMaterial(plugin.getLogger(), line.itemMaterial(), line.materialKey(), line.tradeId());
                if (m == null) {
                    // Выдать нечего — закрываем как ограбление (возврат покупателю), не висим вечно.
                    store.set(line.tradeId() + ".state", CargoState.DROPPED.name());
                    save();
                    api.reportPhantomRobbed(line.tradeId());
                    continue;
                }
                int slots = (int) Math.ceil((double) Math.max(1, line.quantity()) / m.getMaxStackSize());
                if (!load.isEmpty() && used + slots > capacity) continue; // не влезает — ищем дальше по очереди
                load.add(new Planned(job, line, m));
                used += slots;
            }
        }
        if (load.isEmpty()) return null;

        Location start = c.pos != null ? c.pos : source(world, load.get(0).line());
        List<Leg> legs = new ArrayList<>();

        // Источники: группируем по блоку сундука, порядок — ближайший сосед.
        Map<String, List<Planned>> bySource = new LinkedHashMap<>();
        for (Planned p : load) bySource.computeIfAbsent(blockKey(source(world, p.line())), k -> new ArrayList<>()).add(p);
        Location cur = start;
        List<List<Planned>> sourceGroups = new ArrayList<>(bySource.values());
        while (!sourceGroups.isEmpty()) {
            List<Planned> next = nearest(sourceGroups, cur, p -> source(world, p.line()));
            sourceGroups.remove(next);
            cur = source(world, next.get(0).line());
            legs.add(new Leg(cur, next, List.of()));
        }

        // Адреса доставки: по одному разу на уникальную точку.
        Map<String, List<Planned>> byDest = new LinkedHashMap<>();
        for (Planned p : load) byDest.computeIfAbsent(blockKey(dest(world, p.job())), k -> new ArrayList<>()).add(p);
        List<List<Planned>> destGroups = new ArrayList<>(byDest.values());
        while (!destGroups.isEmpty()) {
            List<Planned> next = nearest(destGroups, cur, p -> dest(world, p.job()));
            destGroups.remove(next);
            cur = dest(world, next.get(0).job());
            legs.add(new Leg(cur, List.of(), next));
        }

        Trip trip = new Trip(legs, start, now);
        for (Planned p : load) trip.jobIds.add(p.job().id());
        return trip;
    }

    /** Проходит все ноги, до которых курьер по времени уже дошёл, и выполняет их действия. */
    private void advance(World world, Courier c, long now) {
        Trip t = c.trip;
        while (t.legIdx < t.legs.size()) {
            Leg leg = t.leg();
            long arriveAt = t.legStartMs + legDurationMs(t.legFrom, leg.to());
            if (now < arriveAt) return;
            if (!leg.pickups().isEmpty()) pickUp(leg);
            if (!leg.drops().isEmpty()) unload(world, leg);
            t.legFrom = leg.to();
            t.legStartMs = arriveAt;
            t.legIdx++;
            updateHand(c);
        }
        c.pos = t.legFrom;
        c.trip = null;
    }

    private void pickUp(Leg leg) {
        for (Planned p : leg.pickups()) {
            String id = p.line().tradeId();
            if (state(id) != null) continue; // уже везём (например, после рестарта) — второй раз не списываем
            String sig = ShopListUpdater.variantSig(p.line().enchantSlugs(), p.line().colorHex());
            List<ItemStack> items = PhantomDeliveryController.withdraw(plugin.getLogger(), leg.to(), p.material(), sig,
                    p.line().quantity(), id);
            store.set(id + ".state", CargoState.CARRYING.name());
            store.set(id + ".items", items);
        }
        save();
    }

    private void unload(World world, Leg leg) {
        Location at = leg.to();
        Container container = PhantomDeliveryController.findContainerNear(at, PhantomDeliveryController.DEST_CONTAINER_SEARCH_RADIUS);
        Map<String, Boolean> dropped = new LinkedHashMap<>();
        for (Planned p : leg.drops()) {
            String id = p.line().tradeId();
            if (state(id) != CargoState.CARRYING) continue; // выпал при смерти курьера / уже выдан
            List<ItemStack> items = items(id);
            List<ItemStack> rest = new ArrayList<>();
            ItemStack[] copies = items.stream().map(ItemStack::clone).toArray(ItemStack[]::new);
            if (container != null) rest.addAll(container.getInventory().addItem(copies).values());
            else Collections.addAll(rest, copies);
            for (ItemStack r : rest) world.dropItem(at, r);
            dropped.put(id, !rest.isEmpty());
            store.set(id + ".state", CargoState.DELIVERED.name());
            store.set(id + ".items", null);
            store.set(id + ".droppedNearby", !rest.isEmpty());
        }
        save();

        // trip-completed — до отчётов о строках: после последней "delivered"
        // сайт закрывает job, и trip-completed получил бы 409.
        Set<String> jobs = new HashSet<>();
        for (Planned p : leg.drops()) if (dropped.containsKey(p.line().tradeId())) jobs.add(p.job().id());
        for (String jobId : jobs) api.reportCourierTripCompleted(jobId);
        for (var e : dropped.entrySet()) {
            reported.add(e.getKey());
            api.reportPhantomDelivered(e.getKey(), e.getValue());
        }
    }

    // ===== сущность =====

    private void updateEntity(World world, Courier c, long now) {
        Entity existing = c.entity != null ? Bukkit.getEntity(c.entity) : null;
        if (c.trip == null) {
            removeEntity(c);
            return;
        }
        Trip t = c.trip;
        Location virtual = position(world, t, now);
        Location ref = existing != null && existing.isValid() ? existing.getLocation() : virtual;
        if (!isAnyPlayerNear(world, ref)) {
            removeEntity(c);
            return;
        }
        if (existing == null || !existing.isValid()) {
            spawn(world, c, virtual);
            return;
        }
        if (existing instanceof WanderingTrader mob) {
            Location here = mob.getLocation();
            if (!t.delayReported && flatDistance(here, virtual) > DELAY_GAP_BLOCKS) {
                t.delayReported = true;
                for (String jobId : t.jobIds) api.reportCourierDelayed(jobId);
                notifyDelayed(world, here);
            }
            mob.getPathfinder().moveTo(walkTarget(world, here, t.leg().to()), WALK_SPEED);
        }
    }

    private void spawn(World world, Courier c, Location virtual) {
        Location at = virtual.clone();
        at.setY(world.getHighestBlockYAt(at.getBlockX(), at.getBlockZ()) + 1);
        WanderingTrader mob = (WanderingTrader) world.spawnEntity(at, EntityType.WANDERING_TRADER);
        mob.setPersistent(false);
        mob.setRecipes(Collections.emptyList());
        // keyTradeId — чтобы общий запрет торговли из v1 (onTraderInteract) сработал и здесь.
        mob.getPersistentDataContainer().set(keyTradeId, PersistentDataType.STRING, "courier:" + c.id());
        mob.getPersistentDataContainer().set(keyCourier, PersistentDataType.STRING, c.id());
        c.entity = mob.getUniqueId();
        updateHand(c);
    }

    private void updateHand(Courier c) {
        Entity e = c.entity != null ? Bukkit.getEntity(c.entity) : null;
        if (!(e instanceof WanderingTrader mob) || mob.getEquipment() == null) return;
        ItemStack show = null;
        for (String id : carried(c)) {
            List<ItemStack> items = items(id);
            if (!items.isEmpty()) { show = items.get(0).clone(); break; }
        }
        mob.getEquipment().setItemInMainHand(show);
        mob.getEquipment().setItemInMainHandDropChance(0f); // весь груз выпадает в onCourierDeath
    }

    private void removeEntity(Courier c) {
        Entity e = c.entity != null ? Bukkit.getEntity(c.entity) : null;
        if (e != null) e.remove();
        c.entity = null;
    }

    @EventHandler
    public void onCourierDeath(EntityDeathEvent event) {
        String courierId = event.getEntity().getPersistentDataContainer().get(keyCourier, PersistentDataType.STRING);
        if (courierId == null) return;
        Courier c = findCourier(courierId);
        if (c == null) return;
        c.entity = null;
        if (c.trip == null) return;

        // Ограбление: весь груз захода падает тегированными стаками (по Trade),
        // судьбу каждой строки решают onItemPickup/onItemDespawn в v1.
        Map<String, String> buyerByTrade = new HashMap<>();
        for (Leg leg : c.trip.legs) for (Planned p : leg.pickups()) buyerByTrade.put(p.line().tradeId(), p.job().buyerUsername());
        for (String id : carried(c)) {
            for (ItemStack it : items(id)) event.getDrops().add(tagged(it, id, buyerByTrade.get(id)));
            store.set(id + ".state", CargoState.DROPPED.name());
            store.set(id + ".items", null);
        }
        save();
        c.pos = event.getEntity().getLocation();
        c.trip = null; // невывезенные строки уйдут в следующий заход
    }

    // ===== вспомогательное =====

    private List<String> carried(Courier c) {
        List<String> out = new ArrayList<>();
        if (c.trip == null) return out;
        for (Leg leg : c.trip.legs) {
            for (Planned p : leg.pickups()) {
                if (state(p.line().tradeId()) == CargoState.CARRYING) out.add(p.line().tradeId());
            }
        }
        return out;
    }

    private boolean carriedByAnyTrip(String tradeId) {
        for (List<Courier> list : couriers.values()) {
            for (Courier c : list) if (carried(c).contains(tradeId)) return true;
        }
        return false;
    }

    private Courier findCourier(String id) {
        for (List<Courier> list : couriers.values()) for (Courier c : list) if (c.id().equals(id)) return c;
        return null;
    }

    private CargoState state(String tradeId) {
        String st = store.getString(tradeId + ".state");
        return st == null ? null : CargoState.valueOf(st);
    }

    private List<ItemStack> items(String tradeId) {
        List<ItemStack> out = new ArrayList<>();
        for (Object o : store.getList(tradeId + ".items", List.of())) if (o instanceof ItemStack it) out.add(it);
        return out;
    }

    private void save() {
        try {
            store.save(storeFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[PhantomCourier] не удалось сохранить " + storeFile.getName() + ": " + e);
        }
    }

    private ItemStack tagged(ItemStack stack, String tradeId, String buyer) {
        ItemStack copy = stack.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(keyTradeId, PersistentDataType.STRING, tradeId);
            if (buyer != null) meta.getPersistentDataContainer().set(keyBuyer, PersistentDataType.STRING, buyer);
            copy.setItemMeta(meta);
        }
        return copy;
    }

    private static Location source(World w, CourierLine l) {
        return new Location(w, l.srcX(), l.srcY(), l.srcZ());
    }

    private static Location dest(World w, CourierJob j) {
        return new Location(w, j.destX(), j.destY(), j.destZ());
    }

    private static String blockKey(Location l) {
        return l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    private static List<Planned> nearest(List<List<Planned>> groups, Location from,
                                         java.util.function.Function<Planned, Location> where) {
        List<Planned> best = groups.get(0);
        double bestD = Double.MAX_VALUE;
        for (List<Planned> g : groups) {
            double d = flatDistance(from, where.apply(g.get(0)));
            if (d < bestD) { bestD = d; best = g; }
        }
        return best;
    }

    private static long legDurationMs(Location from, Location to) {
        return (long) (flatDistance(from, to) / SPEED_BLOCKS_PER_SEC * 1000);
    }

    private static double flatDistance(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Расчётная точка курьера сейчас; Y — по земле (только когда нужен, чанк грузится лишь при игроке рядом). */
    private static Location position(World world, Trip t, long now) {
        Leg leg = t.leg();
        long dur = legDurationMs(t.legFrom, leg.to());
        double k = dur <= 0 ? 1.0 : Math.max(0, Math.min(1, (double) (now - t.legStartMs) / dur));
        double x = t.legFrom.getX() + (leg.to().getX() - t.legFrom.getX()) * k;
        double z = t.legFrom.getZ() + (leg.to().getZ() - t.legFrom.getZ()) * k;
        return new Location(world, x, t.legFrom.getY(), z);
    }

    /** Точка в LOOKAHEAD_BLOCKS впереди от реальной позиции к цели ноги (как в v1 — всегда достижимая цель). */
    private static Location walkTarget(World world, Location here, Location to) {
        double dx = to.getX() - here.getX(), dz = to.getZ() - here.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(LOOKAHEAD_BLOCKS, len);
        double x = len < 0.01 ? to.getX() : here.getX() + dx / len * step;
        double z = len < 0.01 ? to.getZ() : here.getZ() + dz / len * step;
        return new Location(world, x, world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1, z);
    }

    /** Проверка по X/Z — расчётная точка без Y, чанк ради неё не грузим. */
    private static boolean isAnyPlayerNear(World world, Location point) {
        for (Player p : world.getPlayers()) {
            if (flatDistance(p.getLocation(), point) <= VISIBILITY_RADIUS_BLOCKS) return true;
        }
        return false;
    }

    private static void notifyDelayed(World world, Location near) {
        String msg = org.bukkit.ChatColor.GOLD + "🐌 Курьер с доставкой немного задерживается в пути…";
        for (Player p : world.getPlayers()) {
            if (flatDistance(p.getLocation(), near) <= VISIBILITY_RADIUS_BLOCKS) {
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(msg));
            }
        }
    }

    private static World resolveWorld() {
        World w = Bukkit.getWorld("world");
        if (w != null) return w;
        List<World> all = Bukkit.getWorlds();
        return all.isEmpty() ? null : all.get(0);
    }
}
