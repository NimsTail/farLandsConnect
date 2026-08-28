package com.frammy.unitylauncher.marketplace;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.auth.FarLandsApiClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.Collections;
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
 *
 * GH#36 (2026-08-28, баг-репорт по факту первого теста) — пять правок:
 * 1-2. Движение дёргано, застревает в тупиках/проваливается сквозь блоки.
 * 3. Товар брался из ниоткуда, не из сундука продавца.
 * 4. Доставка не завершалась по прибытии.
 * 5. С торговцем можно было торговать.
 * См. комментарии по месту ниже — каждый пункт закрыт отдельно.
 *
 * GH#36 (2026-08-28, второй раунд, "в разы лучше!" + два новых бага) —
 * две правки поверх первого раунда:
 * A. Телепорт-коррекция при застревании убрана — иногда телепортировала
 *    торговца в непригодное место (вода без дна и т.п.), и он "испарялся"
 *    навсегда. Заменена на честное сообщение игрокам рядом (notifyDelayed).
 * B. Дюп предметов: completeDelivery могла сработать несколько раз подряд
 *    для одного заказа, пока асинхронный вебхук "доставлено" долетал до
 *    сайта — каждый повторный тик клал/ронял товар заново. completedTrades
 *    гарантирует ровно одно исполнение за жизнь процесса.
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

    // GH#36 п.3 — товар списывается с реального сундука-источника РОВНО
    // один раз за всю жизнь заказа (в момент первого физического спавна),
    // не при каждом респавне сущности (её могут спавнить/деспавнить много
    // раз за путь — по присутствию игроков рядом). Только в памяти, как и
    // spawned — на рестарт плагина эффект тот же, что и раньше (заказ
    // просто продолжит считаться по времени, повторного списания не будет,
    // т.к. tradeId уже не будет в этом множестве, но это не хуже прежнего
    // поведения "то ли выдали, то ли нет").
    private final Set<String> pickedUpFromSource = ConcurrentHashMap.newKeySet();

    // GH#36 (2026-08-28, второй раунд) — БАГ: reportPhantomDelivered — это
    // fire-and-forget POST (см. FarLandsApiClient.send), сайт помечает заказ
    // не-IN_TRANSIT не мгновенно. Пока webhook долетает и обрабатывается,
    // api.fetchPhantomActiveTrades() продолжает возвращать этот же заказ ещё
    // несколько тиков планировщика (раз в секунду) — без этой защёлки
    // completeDelivery срабатывала повторно КАЖДУЮ секунду, пока сайт не
    // догонит, и товар клался в сундук назначения (или ронялся) заново на
    // каждый такой тик — дюп. Один tradeId — ровно одно исполнение эффектов
    // (выдача товара + вебхук) за жизнь процесса плагина.
    private final Set<String> completedTrades = ConcurrentHashMap.newKeySet();

    // GH#36 (2026-08-28, второй раунд) — БАГ первой версии этого же фикса:
    // если сущность не двигалась несколько тиков подряд, код телепортировал
    // её на "идеальную" по времени точку — и торговец иногда буквально
    // испарялся навсегда. Идеальная точка — просто снаппинг к самому
    // верхнему блоку под X/Z, без всякой проверки на пригодность (вода без
    // дна под ней, обрыв и т.п.) — телепорт мог закинуть его в дыру, откуда
    // либо не выбраться, либо он тонул/погибал вне поля зрения игрока. Сам
    // пользователь верно диагностировал причину: "если опаздывает —
    // телепортируется" — по его просьбе телепорт убран целиком. Вместо
    // магической коррекции — честное сообщение игрокам рядом, что курьер
    // задерживается (см. notifyDelayed ниже), торговец просто продолжает
    // идти сам, как может.
    private final Map<String, Location> lastSeenLoc = new ConcurrentHashMap<>();
    private final Map<String, Integer> stuckTicks = new ConcurrentHashMap<>();
    private static final double STUCK_THRESHOLD_BLOCKS = 0.4;
    private static final int STUCK_TICKS_LIMIT = 5; // ~5 тиков планировщика (см. start()) без прогресса

    // GH#36 п.1 — цель для pathfinder'а берётся не "ровно сейчас" (там
    // сущность почти сразу её достигает на полной скорости и потом просто
    // стоит до следующего тика планировщика — то самое "шаг-стоп-шаг"),
    // а на несколько секунд ВПЕРЁД по маршруту — так у неё всегда есть
    // расстояние, которое реально нужно пройти, и путь не приходится
    // пересчитывать с нуля каждую секунду ради лишних пары блоков.
    private static final long LOOKAHEAD_MS = 3000L;
    // Обычная скорость ходьбы (не спринт) — значение 1.0 у Pathfinder.moveTo
    // читается как "спринт-подобный" множитель для WanderingTrader и на
    // практике и давало эффект слишком резких рывков.
    private static final double WALK_SPEED = 0.5;

    // Дальше этого радиуса от любого игрока сущность не спавнится/деспавнится.
    private static final double VISIBILITY_RADIUS_BLOCKS = 48.0;

    // GH#36 (2026-08-28, четвёртый раунд) — пользователь всё ещё видит
    // телепортацию (двух торговцев сразу, в одной точке) даже после фикса
    // "мерить от реальной позиции" третьего раунда — временная подробная
    // диагностика по его же просьбе, вместо очередной догадки вслепую.
    // Пишет в лог сервера каждое решение планировщика по каждому активному
    // заказу — spawn/despawn (с причиной и координатами), дрейф реальной
    // позиции от идеальной, срабатывания notifyDelayed, завершение доставки.
    // Убрать (или выключить DEBUG=false) после того, как причина найдена —
    // на каждый тик планировщика (раз в секунду) на каждый активный заказ,
    // может быть многословно при нескольких одновременных доставках.
    private static final boolean DEBUG = true;

    private void debug(String msg) {
        if (DEBUG) plugin.getLogger().info("[PhantomDelivery][DEBUG] " + msg);
    }

    private static String shortId(String tradeId) {
        return tradeId == null ? "null" : tradeId.length() <= 8 ? tradeId : tradeId.substring(0, 8);
    }

    private static String fmtLoc(Location loc) {
        if (loc == null) return "null";
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", loc.getX(), loc.getY(), loc.getZ());
    }

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
        if (DEBUG && !trades.isEmpty()) {
            debug("=== тик, активных заказов: " + trades.size() + " ===");
        }
        for (FarLandsApiClient.PhantomTrade trade : trades) {
            activeIds.add(trade.id());

            Location target = computePosition(world, trade);
            // GH#36 (2026-08-28, третий раунд) — БАГ: "рядом ли игрок" всегда
            // проверялось расстоянием до ИДЕАЛЬНОЙ по времени точки, даже для
            // уже заспавненной сущности. Пока торговец реально идёт (тем более
            // теперь, с lookahead-целью — см. LOOKAHEAD_MS), его физическая
            // позиция закономерно отстаёт от идеальной, и это расхождение
            // растёт. Если оно превышало VISIBILITY_RADIUS_BLOCKS, планировщик
            // решал "игрока рядом нет" и удалял сущность, СТОЯЩУЮ буквально
            // рядом с игроком — а на следующем тике игрок всё ещё рядом с
            // (уже другой) идеальной точкой, и торговец спавнился заново там
            // — то самое "телепортирование" на глазах у игрока. Для уже
            // существующей сущности нужно мерить от её РЕАЛЬНОЙ позиции, не
            // от идеальной — идеальная точка годится только для решения
            // "спавнить ли", раз реальной позиции ещё нет.
            boolean arrived = System.currentTimeMillis() >= trade.etaAtMs();

            UUID entityId = spawned.get(trade.id());
            Entity existing = entityId != null ? Bukkit.getEntity(entityId) : null;

            if (DEBUG) {
                double distEntityToTarget = existing != null ? existing.getLocation().distance(target) : -1;
                debug(shortId(trade.id()) + ": target=" + fmtLoc(target) + " arrived=" + arrived
                        + " entityId=" + (entityId == null ? "none" : entityId.toString().substring(0, 8))
                        + " entityValid=" + (existing != null && existing.isValid())
                        + " entityLoc=" + (existing != null ? fmtLoc(existing.getLocation()) : "n/a")
                        + " |entity-target|=" + (distEntityToTarget >= 0 ? String.format(Locale.ROOT, "%.1f", distEntityToTarget) : "n/a"));
            }

            if (existing != null && existing.isValid()) {
                // GH#36 п.4 — доехал до места назначения: выдаём груз (в
                // сундук, если он там есть, иначе роняем на месте — тем же
                // тегированным предметом, который дальше подхватывают уже
                // существующие onItemPickup/onItemDespawn) и закрываем заказ,
                // вместо того чтобы торговец просто стоял истуканом.
                if (arrived) {
                    debug(shortId(trade.id()) + ": ПРИБЫЛ, завершаю доставку (была реальная сущность в " + fmtLoc(existing.getLocation()) + ")");
                    completeDelivery(world, trade, existing);
                    spawned.remove(trade.id());
                    lastSeenLoc.remove(trade.id());
                    stuckTicks.remove(trade.id());
                    continue;
                }
                boolean nearReal = isAnyPlayerNear(world, existing.getLocation());
                if (!nearReal) {
                    debug(shortId(trade.id()) + ": ДЕСПАВН — ни одного игрока в радиусе " + VISIBILITY_RADIUS_BLOCKS
                            + " от РЕАЛЬНОЙ позиции сущности " + fmtLoc(existing.getLocation()));
                    existing.remove();
                    spawned.remove(trade.id());
                    lastSeenLoc.remove(trade.id());
                    stuckTicks.remove(trade.id());
                } else if (existing instanceof Mob mob) {
                    progressOrCorrect(world, trade, mob, target);
                }
            } else if (isAnyPlayerNear(world, target)) {
                if (arrived) {
                    // Никто не видел торговца всю дорогу, а к моменту, когда
                    // игрок наконец оказался рядом, маршрут уже пройден по
                    // времени — не спавним истукана специально чтобы тут же
                    // его убрать, просто сразу завершаем доставку.
                    debug(shortId(trade.id()) + ": ПРИБЫЛ ещё до первого спавна (никто не видел всю дорогу), завершаю без спавна");
                    completeDelivery(world, trade, null);
                    continue;
                }
                debug(shortId(trade.id()) + ": СПАВН у " + fmtLoc(target));
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
            debug(shortId(e.getKey()) + ": заказ больше не в активном списке сайта — убираю " + (leftover != null ? "живую сущность в " + fmtLoc(leftover.getLocation()) : "(сущности уже не было)"));
            if (leftover != null) leftover.remove();
            lastSeenLoc.remove(e.getKey());
            stuckTicks.remove(e.getKey());
            return true;
        });
        // pickedUpFromSource/completedTrades переживают даже заказы,
        // которых не было в spawned вовсе (доставлены с первого тика,
        // торговца никто не видел — см. completeDelivery(..., null)) —
        // чистим по activeIds отдельно, иначе эти записи никогда бы не
        // убрались.
        pickedUpFromSource.removeIf(id -> !activeIds.contains(id));
        completedTrades.removeIf(id -> !activeIds.contains(id));
    }

    /**
     * GH#36 (2026-08-28, второй раунд) — ведёт сущность к точке с запасом
     * на несколько секунд вперёд по маршруту (не к "ровно сейчас"), реже
     * дёргая pathfinder — это осталось от первой версии фикса и по отзыву
     * реально помогло ("в разы лучше"). Коррекция телепортом убрана (см.
     * комментарий у STUCK_TICKS_LIMIT) — застревание теперь только честно
     * показывается ближайшим игрокам (notifyDelayed), торговец никуда не
     * прыгает сам.
     */
    private void progressOrCorrect(World world, FarLandsApiClient.PhantomTrade trade, Mob mob, Location idealNow) {
        Location current = mob.getLocation();
        Location lastSeen = lastSeenLoc.get(trade.id());
        double movedSince = (lastSeen != null && lastSeen.getWorld() == current.getWorld()) ? lastSeen.distance(current) : -1;
        if (lastSeen != null && lastSeen.getWorld() == current.getWorld()
                && lastSeen.distance(current) < STUCK_THRESHOLD_BLOCKS) {
            int stuck = stuckTicks.merge(trade.id(), 1, Integer::sum);
            debug(shortId(trade.id()) + ": застревание, счётчик=" + stuck + "/" + STUCK_TICKS_LIMIT + " (сдвинулся на " + movedSince + " за тик)");
            if (stuck >= STUCK_TICKS_LIMIT) {
                debug(shortId(trade.id()) + ": уведомляю игроков о задержке (телепорта НЕТ, только сообщение)");
                notifyDelayed(world, idealNow);
                stuckTicks.put(trade.id(), 0); // не спамим — снова покажем через ещё STUCK_TICKS_LIMIT тиков без прогресса
            }
        } else {
            stuckTicks.remove(trade.id());
        }
        lastSeenLoc.put(trade.id(), current);

        Location lookahead = computePositionAt(world, trade, System.currentTimeMillis() + LOOKAHEAD_MS);
        debug(shortId(trade.id()) + ": moveTo lookahead=" + fmtLoc(lookahead) + " (сдвинулся с прошлого тика на "
                + (movedSince >= 0 ? String.format(Locale.ROOT, "%.1f", movedSince) : "n/a") + ")");
        mob.getPathfinder().moveTo(lookahead, WALK_SPEED);
    }

    /** GH#36 (2026-08-28, второй раунд) — честно, вместо телепорта: игрокам рядом с точкой, где торговец должен быть, видно, что он задерживается. */
    private void notifyDelayed(World world, Location near) {
        String msg = org.bukkit.ChatColor.GOLD + "🐌 Курьер с доставкой немного задерживается в пути…";
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(near) <= VISIBILITY_RADIUS_BLOCKS * VISIBILITY_RADIUS_BLOCKS) {
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(msg));
            }
        }
    }

    /**
     * GH#36 п.3-4 — реальный обмен товаром вместо "берёт из ниоткуда,
     * никуда не кладёт": сундук-источник списывается один раз за заказ (при
     * первом физическом спавне сущности, см. pickedUpFromSource), сундук
     * назначения пополняется по прибытии. Если в точке назначения контейнера
     * нет — груз падает на землю тем же тегированным предметом, что и при
     * ограблении/смерти, и его дальше ведут уже существующие
     * onItemPickup/onItemDespawn (не дублируем логику начисления денег
     * здесь). Если контейнер есть — предмет уходит прямо в него, и отчёт
     * "доставлено" шлётся сразу (тот же вебхук, что и при личном подборе
     * груза покупателем — с точки зрения сайта это тот же самый исход).
     */
    private void completeDelivery(World world, FarLandsApiClient.PhantomTrade trade, Entity traderEntity) {
        if (!completedTrades.add(trade.id())) {
            debug(shortId(trade.id()) + ": completeDelivery — уже выполнено раньше, пропускаю повтор (сайт ещё не убрал заказ из активных)");
            // Уже выполнено раньше в этом же процессе — сайт просто ещё не
            // успел убрать заказ из активных (см. комментарий у
            // completedTrades выше). Товар второй раз не выдаём, сущность
            // на всякий случай всё равно убираем, если она ещё жива.
            if (traderEntity != null) traderEntity.remove();
            return;
        }

        Material material = resolveMaterial(trade);
        int amount = material != null ? clampToStack(trade.quantity(), material) : 0;
        debug(shortId(trade.id()) + ": completeDelivery — material=" + material + " amount=" + amount);

        // GH#36 п.3 (дыра в первой версии этого же фикса) — если торговца
        // вообще ни разу не видели за весь путь (игрок оказался рядом уже
        // ПОСЛЕ etaAtMs), spawnTrader ни разу не вызывался — а значит и
        // списание с сундука-источника тоже. Гарантируем списание здесь же,
        // тем же pickedUpFromSource.add(...) — идемпотентно, если spawnTrader
        // уже списал раньше, второй раз не спишет.
        if (material != null && pickedUpFromSource.add(trade.id())) {
            withdrawFromSource(world, trade, material);
        }

        Location dest = destinationLocation(world, trade);
        Container container = findContainerNear(dest, DEST_CONTAINER_SEARCH_RADIUS);
        debug(shortId(trade.id()) + ": назначение=" + fmtLoc(dest) + " контейнер="
                + (container == null ? "НЕ найден (роняю на землю)" : "найден в " + fmtLoc(container.getInventory().getLocation())));

        if (traderEntity != null) traderEntity.remove();

        if (material == null) {
            // Материал так и не резолвится (тот же случай, что в
            // spawnTrader) — ничего физически выдать нельзя, просто
            // закрываем заказ как доставленный, чтобы не завис навечно.
            api.reportPhantomDelivered(trade.id());
            return;
        }

        if (container != null) {
            ItemStack cargo = new ItemStack(material, amount);
            Map<Integer, ItemStack> leftovers = container.getInventory().addItem(cargo);
            if (!leftovers.isEmpty()) {
                // Сундук назначения переполнен — остаток роняем рядом,
                // тегированным предметом (та же обвязка, что ниже), чтобы
                // хотя бы не терялся молча.
                for (ItemStack rest : leftovers.values()) {
                    dropTaggedCargo(world, trade, dest, rest.getAmount(), material);
                }
            }
            api.reportPhantomDelivered(trade.id());
        } else {
            // Контейнера в точке назначения нет — роняем груз на месте с
            // теми же PDC-тегами, что и раньше при смерти торговца:
            // подберёт покупатель — сработает onItemPickup (доставлено),
            // не подберёт никто — onItemDespawn (ограблено/потеряно). Не
            // шлём вебхук сами — эти два обработчика уже это делают.
            dropTaggedCargo(world, trade, dest, amount, material);
        }
    }

    private void dropTaggedCargo(World world, FarLandsApiClient.PhantomTrade trade, Location at, int amount, Material material) {
        if (amount <= 0) return;
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(keyTradeId, PersistentDataType.STRING, trade.id());
            meta.getPersistentDataContainer().set(keyBuyer, PersistentDataType.STRING, trade.buyerUsername());
            stack.setItemMeta(meta);
        }
        world.dropItem(at, stack);
    }

    // GH#36 (2026-08-28, третий раунд) — точка на клике по карте у адреса
    // покупателя: X/Z с карты, а Y ("тот же приём, что у pickupPlace" —
    // см. infra/phantom-delivery-design.md) вводится ИГРОКОМ ВРУЧНУЮ. Даже
    // на один блок неверный Y — и точный блок оказывается воздухом рядом с
    // реальным сундуком, а не самим сундуком: "он просто бросает вещи рядом,
    // хотя сундук вот же" — искали строго в упор, без допуска. Небольшой
    // радиус поиска терпим к такой погрешности, не требуя от игрока
    // ювелирной точности координат.
    private static final int DEST_CONTAINER_SEARCH_RADIUS = 1;

    private static Container containerAt(Location loc) {
        var state = loc.getBlock().getState();
        return state instanceof Container c ? c : null;
    }

    /** Как containerAt, но терпимо к небольшой погрешности координат — ищет ближайший контейнер в кубе radius вокруг center. */
    private static Container findContainerNear(Location center, int radius) {
        Container exact = containerAt(center);
        if (exact != null) return exact;

        Container best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue; // уже проверили выше
                    Location candidate = center.clone().add(dx, dy, dz);
                    Container c = containerAt(candidate);
                    if (c == null) continue;
                    double distSq = candidate.distanceSquared(center);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    private Location destinationLocation(World world, FarLandsApiClient.PhantomTrade trade) {
        return new Location(world, trade.destX(), trade.destY(), trade.destZ());
    }

    private World resolveWorld() {
        World w = Bukkit.getWorld("world");
        if (w != null) return w;
        List<World> all = Bukkit.getWorlds();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Чистая функция времени — см. дизайн-документ, "Идея в двух словах". Y — снаппинг к самому верхнему твёрдому блоку. */
    private Location computePosition(World world, FarLandsApiClient.PhantomTrade trade) {
        return computePositionAt(world, trade, System.currentTimeMillis());
    }

    /** Как computePosition, но для произвольного момента времени — используется для lookahead-цели pathfinder'а. */
    private Location computePositionAt(World world, FarLandsApiClient.PhantomTrade trade, long atMs) {
        long total = trade.etaAtMs() - trade.startedAtMs();
        double t = total <= 0 ? 1.0 : clamp01((double) (atMs - trade.startedAtMs()) / (double) total);
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

    private Material resolveMaterial(FarLandsApiClient.PhantomTrade trade) {
        try {
            // itemMaterial приходит с сайта в формате каталога Item.name
            // ("Blue_Wool", см. backend/src/routes/plugin.ts) — не в
            // формате Bukkit-enum ("BLUE_WOOL"). Тот же .toUpperCase(),
            // что уже используется везде в плагине при конвертации ключа
            // в Material (ZonesEconomyConfig, UpgradeCondition и т.д.).
            return Material.valueOf(trade.itemMaterial().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[PhantomDelivery] неизвестный материал '" + trade.itemMaterial() + "' для заказа " + trade.id());
            return null;
        }
    }

    private static int clampToStack(int quantity, Material material) {
        return Math.max(1, Math.min(quantity, material.getMaxStackSize()));
    }

    private void spawnTrader(World world, FarLandsApiClient.PhantomTrade trade, Location at) {
        Material material = resolveMaterial(trade);
        if (material == null) return;

        // GH#36 п.3 — реально списываем товар с сундука-источника, если он
        // там есть, РОВНО один раз за жизнь заказа (не на каждый респавн
        // сущности — её могут спавнить/деспавнить много раз за путь). Если
        // сундука там нет или в нём не хватает товара — не блокируем
        // доставку (деньги с покупателя уже списаны на сайте при покупке),
        // просто предупреждаем в лог — груз всё равно материализуется в
        // руке торговца, как и раньше, это лучше, чем застрявший заказ.
        if (pickedUpFromSource.add(trade.id())) {
            withdrawFromSource(world, trade, material);
        }

        WanderingTrader mob = (WanderingTrader) world.spawnEntity(at, org.bukkit.entity.EntityType.WANDERING_TRADER);
        // Не персистентный — если контроллер вдруг перестанет тикать
        // (рестарт/ошибка), ванильные правила деспавна сами уберут сироту,
        // а не оставят его висеть в мире вечно.
        mob.setPersistent(false);
        // GH#36 п.5 — не даём с ним торговать: пустой список сделок делает
        // диалог бесполезным сам по себе, PlayerInteractEntityEvent ниже
        // блокирует открытие диалога вообще, второй слой на случай, если
        // что-то другое (другой плагин, будущий рефакторинг) откроет его
        // напрямую через Merchant API в обход клика.
        mob.setRecipes(Collections.emptyList());
        mob.getPersistentDataContainer().set(keyTradeId, PersistentDataType.STRING, trade.id());

        int amount = clampToStack(trade.quantity(), material);
        ItemStack cargo = new ItemStack(material, amount);
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
        lastSeenLoc.put(trade.id(), at);
        stuckTicks.remove(trade.id());
    }

    private void withdrawFromSource(World world, FarLandsApiClient.PhantomTrade trade, Material material) {
        Location src = new Location(world, trade.sourceX(), trade.sourceY(), trade.sourceZ());
        // GH#36 (2026-08-28, третий раунд) — тот же допуск, что и у
        // findContainerNear для назначения: у ручных лотов источник — тоже
        // ручной клик по карте + ручной Y, подвержен той же погрешности.
        // У авто-лотов координаты точные (из ShopChestItem), точное
        // совпадение находится сразу, цикл поиска не выполняется.
        Container container = findContainerNear(src, DEST_CONTAINER_SEARCH_RADIUS);
        if (container == null) {
            plugin.getLogger().warning("[PhantomDelivery] в точке источника заказа " + trade.id() + " нет сундука — товар материализован без списания.");
            return;
        }

        int need = clampToStack(trade.quantity(), material);
        int have = 0;
        for (ItemStack it : container.getInventory().getContents()) {
            if (it != null && it.getType() == material) have += it.getAmount();
        }
        if (have < need) {
            plugin.getLogger().warning("[PhantomDelivery] в сундуке-источнике заказа " + trade.id()
                    + " не хватает товара (" + have + "/" + need + ") — списано сколько было, остальное материализовано.");
        }

        int toRemove = Math.min(need, have);
        ItemStack template = new ItemStack(material, toRemove);
        if (toRemove > 0) container.getInventory().removeItem(template);
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
        lastSeenLoc.remove(tradeId);
        stuckTicks.remove(tradeId);
    }

    // GH#36 п.5 — с гружёным торговцем нельзя торговать: открытие его
    // диалога отменяется целиком, не только сделки внутри.
    @EventHandler
    public void onTraderInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof WanderingTrader trader)) return;
        String tradeId = trader.getPersistentDataContainer().get(keyTradeId, PersistentDataType.STRING);
        if (tradeId == null) return;
        event.setCancelled(true);
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
