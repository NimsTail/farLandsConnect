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
import org.bukkit.configuration.file.YamlConfiguration;
import com.frammy.unitylauncher.signs.features.shop.ShopListUpdater;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *    сайта — каждый повторный тик клал/ронял товар заново. Состояние груза
 *    DELIVERED в phantom-cargo.yml гарантирует ровно одну выдачу, в том
 *    числе после рестарта сервера.
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

    // Груз заказа: реальные стаки, снятые с сундука-источника РОВНО один раз
    // за жизнь заказа, и его состояние. Хранится в phantom-cargo.yml, а не
    // только в памяти: иначе после рестарта сервера сундук продавца списывался
    // бы повторно, а уже доставленный груз выдавался бы ещё раз.
    //   CARRYING  — груз у торговца (виртуально или в руке реальной сущности);
    //   DROPPED   — торговец убит, груз лежит на земле, судьбу решают
    //               onItemPickup/onItemDespawn (торговца больше не спавним);
    //   DELIVERED — груз выдан, отчёт отправлен; ждём, пока сайт уберёт заказ
    //               из активных (повторно не выдаём — защита от дюпа GH#36 B).
    private enum CargoState { CARRYING, DROPPED, DELIVERED }
    private final File cargoFile;
    private final YamlConfiguration cargo;
    // Заказы, по которым в этом процессе уже отправлен отчёт "доставлено" /
    // уже решена судьба выпавшего груза — не шлём повторно на каждый тик/стак.
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final Set<String> decided = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean ticking = new AtomicBoolean();

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
    // GH#36 (2026-08-28, четвёртый раунд) — застревание меряется прогрессом
    // расстояния до НАЗНАЧЕНИЯ между тиками (см. progressOrCorrect), не
    // сырым перемещением сущности — реальный лог показал, что застрявший
    // (упёршийся в препятствие) торговец всё равно каждый тик слегка
    // топчется/дёргается на месте, что маскировало отсутствие прогресса
    // при старой метрике.
    private final Map<String, Double> lastSeenDistToDest = new ConcurrentHashMap<>();
    private static final double STUCK_PROGRESS_THRESHOLD_BLOCKS = 0.4;
    private static final int STUCK_TICKS_LIMIT = 5; // ~5 тиков планировщика (см. start()) без прогресса

    // GH#36 (2026-08-28, четвёртый раунд) — БАГ найден по debug-логам:
    // цель для pathfinder'а раньше бралась как "где торговец ДОЛЖЕН быть
    // по времени" — если он отставал (препятствие), эта цель убегала от
    // него быстрее, чем он мог догнать, разрыв рос без ограничений
    // (реально дошло до ~60 блоков за 20с в одном заказе). Теперь —
    // фиксированное расстояние вперёд по маршруту от РЕАЛЬНОЙ текущей
    // позиции (см. computeWalkTarget) — всегда достижимая цель.
    private static final double LOOKAHEAD_DISTANCE_BLOCKS = 12.0;
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
    private static final boolean DEBUG = false;

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
        this.cargoFile = new File(plugin.getDataFolder(), "phantom-cargo.yml");
        this.cargo = YamlConfiguration.loadConfiguration(cargoFile);
    }

    /** Call once from onEnable, после регистрации Listener'а. periodTicks: 20 = 1с. No-op, если API-мост выключен. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tickAsync, 0L, periodTicks);
    }

    // ===== планировщик =====

    /** Блокирующий HTTP на асинхронном потоке планировщика — сам спавн/деспавн (Bukkit entity API) обязан идти на главном. */
    private void tickAsync() {
        // Тики не накладываются друг на друга (HTTP-таймаут 10с > периода 1с).
        if (!ticking.compareAndSet(false, true)) return;
        List<FarLandsApiClient.PhantomTrade> trades = api.fetchPhantomActiveTrades();
        // Ошибка запроса — не "активных заказов нет": иначе тик ниже убрал бы
        // торговцев и забыл состояние груза, а следующий успешный — выдал заново.
        if (trades == null) { ticking.set(false); return; }
        Bukkit.getScheduler().runTask(plugin, () -> {
            try { tickSync(trades); } finally { ticking.set(false); }
        });
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

            CargoState state = state(trade.id());
            if (state == CargoState.DROPPED) continue; // груз на земле — ждём подбора/деспавна
            if (state == CargoState.DELIVERED) {
                // Отчёт уже ушёл (возможно, до рестарта и не дошёл) — повторяем
                // один раз за процесс; груз второй раз не выдаём.
                if (reported.add(trade.id())) {
                    api.reportPhantomDelivered(trade.id(), cargo.getBoolean(trade.id() + ".droppedNearby"));
                }
                continue;
            }

            Location target = computePosition(world, trade);
            // GH#36 (2026-08-28, третий раунд) — БАГ: "рядом ли игрок" всегда
            // проверялось расстоянием до ИДЕАЛЬНОЙ по времени точки, даже для
            // уже заспавненной сущности. Пока торговец реально идёт (тем более
            // теперь, с lookahead-целью — см. LOOKAHEAD_DISTANCE_BLOCKS), его физическая
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
                    lastSeenDistToDest.remove(trade.id());
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
                    lastSeenDistToDest.remove(trade.id());
                    stuckTicks.remove(trade.id());
                } else if (existing instanceof Mob mob) {
                    progressOrCorrect(world, trade, mob, target);
                }
            } else if (arrived) {
                // Маршрут пройден по времени, а торговца рядом нет (никто не
                // видел его в пути) — выдаём груз без спавна, даже если у
                // точки назначения сейчас нет игроков: иначе сайт закрыл бы
                // заказ по ETA, а покупатель ничего бы не получил.
                debug(shortId(trade.id()) + ": ПРИБЫЛ без сущности, завершаю без спавна");
                completeDelivery(world, trade, null);
            } else if (isAnyPlayerNear(world, target)) {
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
            lastSeenDistToDest.remove(e.getKey());
            stuckTicks.remove(e.getKey());
            return true;
        });
        // Состояние груза по заказам, которых больше нет среди активных.
        boolean changed = false;
        for (String id : cargo.getKeys(false)) {
            if (!activeIds.contains(id)) { cargo.set(id, null); changed = true; }
        }
        if (changed) saveCargo();
        reported.retainAll(activeIds);
        // decided не чистим по activeIds: им пользуются и курьеры v2
        // (PhantomCourierController), чьих заказов нет в этом списке.
        // ponytail: растёт на один id за выпавший груз, до рестарта.
    }

    /**
     * GH#36 (2026-08-28, четвёртый раунд) — НАСТОЯЩАЯ причина оставшейся
     * "телепортации", найденная по логам (debug-диагностика третьего
     * раунда): цель для pathfinder'а бралась как "где торговец ДОЛЖЕН
     * быть через LOOKAHEAD_MS по времени от старта заказа" — если он хоть
     * немного отстаёт (уткнулся в препятствие), такая цель убегает от
     * него быстрее, чем он успевает её догнать, и отставание растёт БЕЗ
     * ОГРАНИЧЕНИЙ. В реальном логе разрыв дошёл до ~60 блоков за 20 секунд
     * — торговец физически топтался на месте (мелкие шевеления ~1-5
     * блоков за тик обходили старый порог "застревания"), а к моменту
     * arrived=true (по времени) стоял в 60 блоках от места назначения.
     * Судя по всему, это и объясняет "два торговца телепортировались в
     * одной точке" — оба маршрута, скорее всего, упёрлись в один и тот же
     * рельеф, а не общий баг состояния между заказами.
     *
     * Цель теперь — фиксированное расстояние (LOOKAHEAD_DISTANCE_BLOCKS)
     * вперёд по направлению источник->назначение от РЕАЛЬНОЙ текущей
     * позиции торговца (computeWalkTarget), не от идеальной по времени —
     * всегда достижимая точка, отставание больше не может накапливаться
     * бесконечно. Итоговое время доставки как считалось по etaAtMs на
     * сайте, так и считается — эта цель влияет только на то, КАК торговец
     * физически идёт, не на то, когда заказ формально завершится.
     */
    private void progressOrCorrect(World world, FarLandsApiClient.PhantomTrade trade, Mob mob, Location idealNow) {
        Location current = mob.getLocation();

        // GH#36 (четвёртый раунд) — БАГ старой версии: застревание мерилось
        // сырым перемещением "за последний тик", но по логам торговец,
        // физически упёршийся в препятствие, всё равно каждый тик слегка
        // топтался (~1-5 блоков дёрганья/коррекции пути) — это маскировало
        // отсутствие реального прогресса. Теперь меряем расстояние по
        // прямой до НАЗНАЧЕНИЯ (не до соседней лукахед-точки) — застревание
        // это когда оно не сокращается несколько тиков подряд, даже если
        // сама сущность немного шевелится на месте.
        double dx = trade.destX() - current.getX();
        double dz = trade.destZ() - current.getZ();
        double distToDest = Math.sqrt(dx * dx + dz * dz);
        Double lastDist = lastSeenDistToDest.get(trade.id());
        double progressed = lastDist != null ? lastDist - distToDest : Double.MAX_VALUE;

        if (progressed < STUCK_PROGRESS_THRESHOLD_BLOCKS) {
            int stuck = stuckTicks.merge(trade.id(), 1, Integer::sum);
            debug(shortId(trade.id()) + ": застревание, счётчик=" + stuck + "/" + STUCK_TICKS_LIMIT
                    + " (прогресс к цели за тик: " + String.format(Locale.ROOT, "%.2f", progressed) + " блоков)");
            if (stuck >= STUCK_TICKS_LIMIT) {
                debug(shortId(trade.id()) + ": уведомляю игроков о задержке (телепорта НЕТ, только сообщение)");
                notifyDelayed(world, idealNow);
                api.reportPhantomDelay(trade.id(), distToDest);
                stuckTicks.put(trade.id(), 0); // не спамим — снова покажем через ещё STUCK_TICKS_LIMIT тиков без прогресса
            }
        } else {
            stuckTicks.remove(trade.id());
        }
        lastSeenDistToDest.put(trade.id(), distToDest);
        lastSeenLoc.put(trade.id(), current);

        Location walkTarget = computeWalkTarget(world, trade, current);
        debug(shortId(trade.id()) + ": moveTo walkTarget=" + fmtLoc(walkTarget) + " (осталось до назначения по прямой: "
                + String.format(Locale.ROOT, "%.1f", distToDest) + " блоков)");
        mob.getPathfinder().moveTo(walkTarget, WALK_SPEED);
    }

    /** См. комментарий у progressOrCorrect — точка на фиксированном расстоянии вперёд по маршруту от РЕАЛЬНОЙ позиции, не от идеальной по времени. */
    private Location computeWalkTarget(World world, FarLandsApiClient.PhantomTrade trade, Location currentPos) {
        double dx = trade.destX() - trade.sourceX();
        double dz = trade.destZ() - trade.sourceZ();
        double routeLen = Math.sqrt(dx * dx + dz * dz);
        if (routeLen < 0.01) return destinationLocation(world, trade);

        double ux = dx / routeLen;
        double uz = dz / routeLen;

        double remainingX = trade.destX() - currentPos.getX();
        double remainingZ = trade.destZ() - currentPos.getZ();
        double remaining = Math.sqrt(remainingX * remainingX + remainingZ * remainingZ);
        double step = Math.min(LOOKAHEAD_DISTANCE_BLOCKS, remaining);

        double aheadX = currentPos.getX() + ux * step;
        double aheadZ = currentPos.getZ() + uz * step;
        int groundY = world.getHighestBlockYAt((int) Math.floor(aheadX), (int) Math.floor(aheadZ));
        return new Location(world, aheadX, groundY + 1, aheadZ);
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
     * GH#36 п.3-4 — реальный обмен товаром: груз — стаки, снятые с
     * сундука-источника один раз за заказ (loadCargo), по прибытии кладутся в
     * сундук назначения. Что не влезло или если сундука нет — падает рядом, и
     * заказ закрывается как доставленный с droppedNearby=true (review C7).
     */
    private void completeDelivery(World world, FarLandsApiClient.PhantomTrade trade, Entity traderEntity) {
        if (traderEntity != null) traderEntity.remove();
        if (state(trade.id()) == CargoState.DELIVERED) return; // уже выдано (GH#36 B)

        Material material = resolveMaterial(trade);
        if (material == null) {
            // Материал не резолвится — выдать нечего. Закрываем как
            // ограбление: покупателю возвращаются деньги (а не "доставлено"
            // без товара), заказ не висит вечно.
            cargo.set(trade.id() + ".state", CargoState.DROPPED.name());
            saveCargo();
            if (decided.add(trade.id())) api.reportPhantomRobbed(trade.id());
            return;
        }

        List<ItemStack> load = loadCargo(world, trade, material);
        Location dest = destinationLocation(world, trade);
        Container container = findContainerNear(dest, DEST_CONTAINER_SEARCH_RADIUS);
        debug(shortId(trade.id()) + ": completeDelivery — " + load.size() + " стак(ов), контейнер "
                + (container == null ? "НЕ найден" : "в " + fmtLoc(container.getInventory().getLocation())));

        // В сундук назначения; что не влезло (или сундука нет) — рядом на землю,
        // уже без тегов заказа: заказ закрыт как доставленный с droppedNearby
        // (контракт сайта, routes/plugin/marketplace.ts phantom-delivered).
        List<ItemStack> rest = new ArrayList<>();
        if (container != null) {
            rest.addAll(container.getInventory().addItem(clones(load)).values());
        } else {
            rest.addAll(List.of(clones(load)));
        }
        for (ItemStack it : rest) world.dropItem(dest, it);
        markDelivered(trade.id(), !rest.isEmpty());
    }

    private void markDelivered(String tradeId, boolean droppedNearby) {
        cargo.set(tradeId + ".state", CargoState.DELIVERED.name());
        cargo.set(tradeId + ".items", null);
        cargo.set(tradeId + ".droppedNearby", droppedNearby);
        saveCargo();
        reported.add(tradeId);
        api.reportPhantomDelivered(tradeId, droppedNearby);
    }

    // ===== груз =====

    private CargoState state(String tradeId) {
        String st = cargo.getString(tradeId + ".state");
        return st == null ? null : CargoState.valueOf(st);
    }

    /** Груз заказа: при первом обращении снимается с сундука-источника и запоминается на диск. */
    private List<ItemStack> loadCargo(World world, FarLandsApiClient.PhantomTrade trade, Material material) {
        if (state(trade.id()) != null) {
            List<ItemStack> out = new ArrayList<>();
            for (Object o : cargo.getList(trade.id() + ".items", List.of())) {
                if (o instanceof ItemStack it) out.add(it);
            }
            return out;
        }
        List<ItemStack> load = withdrawFromSource(world, trade, material);
        cargo.set(trade.id() + ".state", CargoState.CARRYING.name());
        cargo.set(trade.id() + ".items", load);
        saveCargo();
        return load;
    }

    private void saveCargo() {
        try {
            cargo.save(cargoFile);
        } catch (IOException e) {
            plugin.getLogger().severe("[PhantomDelivery] не удалось сохранить " + cargoFile.getName() + ": " + e);
        }
    }

    private static ItemStack[] clones(List<ItemStack> items) {
        return items.stream().map(ItemStack::clone).toArray(ItemStack[]::new);
    }

    /** Копия стака с тегами заказа на самом предмете — переживают отделение от торговца. */
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

    // GH#36 (2026-08-28, третий раунд) — точка на клике по карте у адреса
    // покупателя: X/Z с карты, а Y ("тот же приём, что у pickupPlace" —
    // см. infra/phantom-delivery-design.md) вводится ИГРОКОМ ВРУЧНУЮ. Даже
    // на один блок неверный Y — и точный блок оказывается воздухом рядом с
    // реальным сундуком, а не самим сундуком: "он просто бросает вещи рядом,
    // хотя сундук вот же" — искали строго в упор, без допуска. Небольшой
    // радиус поиска терпим к такой погрешности, не требуя от игрока
    // ювелирной точности координат.
    static final int DEST_CONTAINER_SEARCH_RADIUS = 1;

    private static Container containerAt(Location loc) {
        var state = loc.getBlock().getState();
        return state instanceof Container c ? c : null;
    }

    /** Как containerAt, но терпимо к небольшой погрешности координат — ищет ближайший контейнер в кубе radius вокруг center. */
    static Container findContainerNear(Location center, int radius) {
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
        return resolveMaterial(plugin.getLogger(), trade.itemMaterial(), trade.materialKey(), trade.id());
    }

    /**
     * materialKey — Bukkit-ключ, если сайт его прислал; иначе имя из каталога
     * ("Blue Wool" / "Blue_Wool") — для большинства предметов совпадает с
     * ключом с точностью до пробелов и регистра. null — не резолвится.
     */
    static Material resolveMaterial(java.util.logging.Logger log, String itemMaterial, String materialKey, String orderId) {
        String key = materialKey != null ? materialKey : itemMaterial == null ? "" : itemMaterial.trim().replace(' ', '_');
        Material m = Material.matchMaterial(key);
        if (m == null || !m.isItem()) {
            log.warning("[PhantomDelivery] неизвестный материал '" + key + "' для заказа " + orderId);
            return null;
        }
        return m;
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
        List<ItemStack> load = loadCargo(world, trade, material);
        if (load.isEmpty()) return;

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
        mob.getPersistentDataContainer().set(keyBuyer, PersistentDataType.STRING, trade.buyerUsername());

        // В руке — только витрина (первый стак груза). Весь груз (может быть
        // несколько стаков) выпадает в onTraderDeath, из phantom-cargo.yml.
        EntityEquipment equipment = mob.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(load.get(0).clone());
            equipment.setItemInMainHandDropChance(0f);
        }

        spawned.put(trade.id(), mob.getUniqueId());
        lastSeenLoc.put(trade.id(), at);
        lastSeenDistToDest.remove(trade.id());
        stuckTicks.remove(trade.id());
    }

    /**
     * Снимает с сундука-источника реальные стаки купленного варианта
     * (материал + зачарования + цвет, как в лоте сайта) в количестве заказа.
     * Не хватает (или сундука нет) — недостающее создаётся простыми стаками:
     * деньги покупателя уже списаны, застрявший заказ хуже. Пишется в лог.
     */
    private List<ItemStack> withdrawFromSource(World world, FarLandsApiClient.PhantomTrade trade, Material material) {
        return withdraw(plugin.getLogger(), new Location(world, trade.sourceX(), trade.sourceY(), trade.sourceZ()),
                material, ShopListUpdater.variantSig(trade.enchantSlugs(), trade.colorHex()), trade.quantity(), trade.id());
    }

    /**
     * Снимает с сундука у src реальные стаки купленного варианта (материал +
     * зачарования + цвет, как в лоте сайта) в количестве заказа. Не хватает
     * (или сундука нет) — недостающее создаётся простыми стаками: деньги
     * покупателя уже списаны, застрявший заказ хуже. Пишется в лог.
     */
    static List<ItemStack> withdraw(java.util.logging.Logger log, Location src, Material material, String sig,
                                    int quantity, String orderId) {
        // GH#36 (третий раунд) — тот же допуск по координатам, что у назначения.
        Container container = findContainerNear(src, DEST_CONTAINER_SEARCH_RADIUS);
        int need = Math.max(1, quantity);

        List<ItemStack> load = container == null ? new ArrayList<>()
                : ShopListUpdater.takeVariant(container.getInventory(), material, sig, need, false);
        int got = load.stream().mapToInt(ItemStack::getAmount).sum();
        if (got < need) {
            log.warning("[PhantomDelivery] заказ " + orderId + ": "
                    + (container == null ? "в точке источника нет сундука" : "в сундуке-источнике не хватает товара")
                    + " (" + got + "/" + need + " " + material + (sig.isEmpty() ? "" : " [" + sig + "]")
                    + ") — недостающее создано без списания.");
            for (int left = need - got; left > 0; ) {
                int n = Math.min(left, material.getMaxStackSize());
                load.add(new ItemStack(material, n));
                left -= n;
            }
        }
        return load;
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
        lastSeenDistToDest.remove(tradeId);
        stuckTicks.remove(tradeId);

        if (state(tradeId) != CargoState.CARRYING) return;
        String buyer = event.getEntity().getPersistentDataContainer().get(keyBuyer, PersistentDataType.STRING);
        for (Object o : cargo.getList(tradeId + ".items", List.of())) {
            if (o instanceof ItemStack it) event.getDrops().add(tagged(it, tradeId, buyer));
        }
        cargo.set(tradeId + ".state", CargoState.DROPPED.name());
        cargo.set(tradeId + ".items", null);
        saveCargo();
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
        // Судьбу заказа решает первый подобранный стак (груз может быть из нескольких).
        if (decided.add(tradeId)) {
            if (buyer != null && buyer.equalsIgnoreCase(player.getName())) {
                // Покупатель подобрал свой же груз — не ограбление, обычная
                // досрочная доставка (см. дизайн-документ, раздел про сговор).
                api.reportPhantomDelivered(tradeId, false);
            } else {
                api.reportPhantomRobbed(tradeId);
            }
        }
        // Теги больше не нужны — иначе предмет не складывается с обычными.
        meta.getPersistentDataContainer().remove(keyTradeId);
        meta.getPersistentDataContainer().remove(keyBuyer);
        stack.setItemMeta(meta);
        event.getItem().setItemStack(stack);
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
        if (decided.add(tradeId)) api.reportPhantomRobbed(tradeId);
    }
}
