package com.frammy.unitylauncher.marketplace;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.auth.FarLandsApiClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * infra/phantom-delivery-design.md — Странствующий торговец, реально
 * идущий по маршруту заказа с настоящим товаром в инвентаре. Позиция —
 * чистая функция времени (см. computePosition); реальная сущность
 * (WanderingTrader) существует только пока рядом есть игрок — спавнится
 * сразу в расчётной точке, деспавнится без остатка, когда зрителей не
 * остаётся (виртуальная позиция как считалась по времени, так и
 * продолжает считаться, ничего не нужно восстанавливать).
 *
 * Уязвимый (не invulnerable) — торговца можно ограбить, убив по дороге:
 * товар — настоящий предмет в его руке (dropChance=1.0), а не абстракция.
 * Судьба заказа после смерти торговца решается не по факту смерти самой
 * по себе, а по факту того, что случилось с выпавшим предметом — см.
 * onItemPickup/onItemDespawn ниже, оба тега (tradeId + buyer) лежат прямо
 * в PDC самого предмета, переживают отделение от сущности.
 */
public final class PhantomDeliveryController implements Listener {

    private final UnityLauncher plugin;
    private final FarLandsApiClient api;
    private final NamespacedKey keyTradeId;
    private final NamespacedKey keyBuyer;

    // tradeId -> UUID заспавненной сущности. Только в памяти — не переживает
    // рестарт плагина, и не должен: позиция всё равно пересчитывается по
    // времени с нуля при каждом обращении, спавн/деспавн — просто следствие.
    private final Map<String, UUID> spawned = new ConcurrentHashMap<>();

    // Дальше этого радиуса от любого игрока сущность не спавнится/деспавнится.
    private static final double VISIBILITY_RADIUS_BLOCKS = 48.0;

    public PhantomDeliveryController(UnityLauncher plugin, FarLandsApiClient api) {
        this.plugin = plugin;
        this.api = api;
        this.keyTradeId = new NamespacedKey(plugin, "phantom.tradeId");
        this.keyBuyer = new NamespacedKey(plugin, "phantom.buyer");
    }

    /** Call once from onEnable, после регистрации Listener'а. periodTicks: 20 = 1с. No-op, если API-мост выключен. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tickAsync, 0L, periodTicks);
    }

    // ===== планировщик =====

    /** Блокирующий HTTP на асинхронном потоке планировщика — сам спавн/деспавн (Bukkit entity API) обязан идти на главном. */
    private void tickAsync() {
        List<FarLandsApiClient.PhantomTrade> trades = api.fetchPhantomActiveTrades();
        Bukkit.getScheduler().runTask(plugin, () -> tickSync(trades));
    }

    private void tickSync(List<FarLandsApiClient.PhantomTrade> trades) {
        World world = resolveWorld();
        if (world == null) return;

        Set<String> activeIds = new HashSet<>();
        for (FarLandsApiClient.PhantomTrade trade : trades) {
            activeIds.add(trade.id());

            Location target = computePosition(world, trade);
            boolean playerNearby = isAnyPlayerNear(world, target);

            UUID entityId = spawned.get(trade.id());
            Entity existing = entityId != null ? Bukkit.getEntity(entityId) : null;

            if (existing != null && existing.isValid()) {
                if (!playerNearby) {
                    existing.remove();
                    spawned.remove(trade.id());
                } else if (existing instanceof Mob mob) {
                    // Пока виден — ведёт настоящий ванильный pathfinder к
                    // свежерасчитанной точке (сам обходит лаву/обрывы), не
                    // ручная телепортация по прямой. Переотправляем цель
                    // каждый тик планировщика — "догоняет" виртуальную
                    // позицию, а не идёт к одной далёкой точке.
                    mob.getPathfinder().moveTo(target, 1.0);
                }
            } else if (playerNearby) {
                spawnTrader(world, trade, target);
            }
        }

        // Сущности, чей заказ уже не в активном списке (доставлен/ограблен по
        // вебхуку, или снят sweep-job'ой по таймауту) — убрать, если вдруг
        // ещё живы (обычно к этому моменту их уже нет — умерли/деспавнились
        // сами, раз именно это и закрыло заказ).
        spawned.entrySet().removeIf(e -> {
            if (activeIds.contains(e.getKey())) return false;
            Entity leftover = Bukkit.getEntity(e.getValue());
            if (leftover != null) leftover.remove();
            return true;
        });
    }

    private World resolveWorld() {
        World w = Bukkit.getWorld("world");
        if (w != null) return w;
        List<World> all = Bukkit.getWorlds();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Чистая функция времени — см. дизайн-документ, "Идея в двух словах". Y — снаппинг к самому верхнему твёрдому блоку. */
    private Location computePosition(World world, FarLandsApiClient.PhantomTrade trade) {
        long now = System.currentTimeMillis();
        long total = trade.etaAtMs() - trade.startedAtMs();
        double t = total <= 0 ? 1.0 : clamp01((double) (now - trade.startedAtMs()) / (double) total);
        double x = lerp(trade.sourceX(), trade.destX(), t);
        double z = lerp(trade.sourceZ(), trade.destZ(), t);
        int groundY = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
        return new Location(world, x, groundY + 1, z);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private boolean isAnyPlayerNear(World world, Location point) {
        double radiusSq = VISIBILITY_RADIUS_BLOCKS * VISIBILITY_RADIUS_BLOCKS;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(point) <= radiusSq) return true;
        }
        return false;
    }

    private void spawnTrader(World world, FarLandsApiClient.PhantomTrade trade, Location at) {
        Material material;
        try {
            // itemMaterial приходит с сайта в формате каталога Item.name
            // ("Blue_Wool", см. backend/src/routes/plugin.ts) — не в
            // формате Bukkit-enum ("BLUE_WOOL"). Тот же .toUpperCase(),
            // что уже используется везде в плагине при конвертации ключа
            // в Material (ZonesEconomyConfig, UpgradeCondition и т.д.).
            material = Material.valueOf(trade.itemMaterial().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[PhantomDelivery] неизвестный материал '" + trade.itemMaterial() + "' для заказа " + trade.id());
            return;
        }

        WanderingTrader mob = (WanderingTrader) world.spawnEntity(at, org.bukkit.entity.EntityType.WANDERING_TRADER);
        // Не персистентный — если контроллер вдруг перестанет тикать
        // (рестарт/ошибка), ванильные правила деспавна сами уберут сироту,
        // а не оставят его висеть в мире вечно.
        mob.setPersistent(false);
        mob.getPersistentDataContainer().set(keyTradeId, PersistentDataType.STRING, trade.id());

        ItemStack cargo = new ItemStack(material, Math.max(1, Math.min(trade.quantity(), material.getMaxStackSize())));
        ItemMeta meta = cargo.getItemMeta();
        if (meta != null) {
            // Оба тега — на самом ПРЕДМЕТЕ, не только на сущности: переживают
            // отделение от торговца (выпадение при смерти), см. onItemPickup.
            meta.getPersistentDataContainer().set(keyTradeId, PersistentDataType.STRING, trade.id());
            meta.getPersistentDataContainer().set(keyBuyer, PersistentDataType.STRING, trade.buyerUsername());
            cargo.setItemMeta(meta);
        }

        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(cargo);
            equipment.setItemInMainHandDropChance(1.0f);
        }

        spawned.put(trade.id(), mob.getUniqueId());
    }

    // ===== события =====

    @EventHandler
    public void onTraderDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof WanderingTrader)) return;
        String tradeId = event.getEntity().getPersistentDataContainer().get(keyTradeId, PersistentDataType.STRING);
        if (tradeId == null) return;
        // Только бухгалтерия локального спавна — судьбу заказа (доставлено/
        // ограблено) решает не сама смерть, а то, что случится с выпавшим
        // грузом (см. ниже) — груз мог и не выпасть отдельно от смерти,
        // если игрок оглушил и добил не сразу, тег всё равно останется на
        // предмете и сработает, когда предмет реально появится в мире.
        spawned.remove(tradeId);
    }

    @EventHandler
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack stack = event.getItem().getItemStack();
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) return;
        String tradeId = meta.getPersistentDataContainer().get(keyTradeId, PersistentDataType.STRING);
        if (tradeId == null) return;

        String buyer = meta.getPersistentDataContainer().get(keyBuyer, PersistentDataType.STRING);
        if (buyer != null && buyer.equalsIgnoreCase(player.getName())) {
            // Покупатель подобрал свой же груз — не ограбление, обычная
            // досрочная доставка (см. дизайн-документ, раздел про сговор).
            api.reportPhantomDelivered(tradeId);
        } else {
            api.reportPhantomRobbed(tradeId);
        }
    }

    @EventHandler
    public void onItemDespawn(ItemDespawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta == null) return;
        String tradeId = meta.getPersistentDataContainer().get(keyTradeId, PersistentDataType.STRING);
        if (tradeId == null) return;
        // Никто не подобрал за всё время жизни дропа — товар физически
        // потерян, тот же исход, что и настоящее ограбление.
        api.reportPhantomRobbed(tradeId);
    }
}
