package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.MilitaryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * infra/military-diplomacy-design.md §17.3/§17.4 (2026-08-22) — дефолтное
 * состояние апгрейда "Оборона" (unity.military.defense, см. DefensePatrolUpgrade
 * для его отдельного объектного патруля) на линии фронта (§16, GH#32):
 *
 *  1. Pity-патруль (§17.3) — пока враг физически стоит в территории
 *     страны, с которой у него активная война, идёт периодический бросок
 *     шанса заспавнить рядом небольшой отряд. Неудача повышает шанс
 *     следующей проверки (pity), успех сбрасывает к базовому. Под землёй
 *     состав тяжелее, чем на поверхности.
 *  2. "Отголосок" (§17.4) — если тот же враг физически под землёй/в
 *     закрытой коробке (§17.7 — не open-to-sky, либо обложен сплошными
 *     блоками со всех сторон) и не двигается дольше порога, получает
 *     периодический урон "сквозь блоки" (прямой damage(), без снаряда —
 *     блоки для него не преграда) со звуком/партиклами Стража, без спавна
 *     самой сущности. Урон растёт с каждым повторным пульсом, сбрасывается
 *     при реальном движении.
 *  3. Фантомы на столбе (фидбек 2026-08-22) — тот же принцип "неудобно
 *     закапываться" в другую сторону: если враг забрался высоко над
 *     реальной землёй (столб ИЛИ платформа — см. isPillared, изоляция по
 *     ширине убрана по фидбеку с живого теста) и не двигается дольше
 *     порога, периодически спавнятся фантомы — единственные ванильные
 *     мобы, которые долетят до него, раз наземный патруль физически не
 *     может. НЕ применяется к обычной поверхностной драке — высота почти
 *     всегда исключает её саму по себе.
 *
 * Эквивалентность "в каждом секторе" (дизайн-док §17.3) и "где угодно в
 * территории страны, пока идёт война" (код ниже): вся территория страны на
 * сайте и есть объединение всех секторов Frontier Pressure (§16) — секторы
 * это просто триангуляция ТОЙ ЖЕ территории, не отдельная площадь. Спавн
 * привязан к позиции самого игрока, поэтому точные границы сектора здесь
 * не нужны — где бы враг ни стоял внутри страны, он стоит в каком-то
 * секторе. Эта логика намеренно НЕ повторяет backend'ную триангуляцию
 * (lib/frontierPressure.ts) — она не нужна для решения "спавнить рядом с
 * игроком или нет", а дублировать авторитетную военную математику (fill
 * rate/War Score/decay) на плагине было бы лишней связностью между
 * репозиториями. Та математика остаётся целиком на сайте.
 *
 * In-memory состояние (pity-шанс, эскалация Отголоска) — не переживает
 * рестарт плагина, как и остальные in-memory кеши апгрейдов (см.
 * LiveDefensePostUpgrade/DefensePatrolUpgrade) — это фоновый контент, не
 * авторитетное военное состояние (то живёт на сайте, см. FrontierPresenceReporter).
 */
public final class FrontierDefenseUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.frontier_defense");
    private static final String META_KEY = "unityFrontierDefensePatrol";
    private static final String MOB_NAME = "§4⚔ Пограничный дозор";

    // §17.6 — 2_militaryFrontierChance (0 куплено = индекс 0).
    private static final double[] BASE_CHANCE = {0.16, 0.20, 0.24, 0.28};
    // §17.6 — 2_militaryFrontierPity.
    private static final double[] PITY_GROWTH = {0.05, 0.07, 0.09};
    private static final double PITY_CAP = 0.75;

    // §17.6 — 2_militaryFrontierEcho: время до первого пульса (сек) и
    // прирост урона за пульс (в "сердцах", *2 для перевода в единицы Bukkit).
    private static final int[] ECHO_IDLE_SECONDS = {30, 25, 20};
    private static final double[] ECHO_DAMAGE_STEP_HEARTS = {2.0, 2.5, 3.0};

    private static final double IDLE_MOVE_EPSILON_SQ = 0.35 * 0.35; // §17.4 — "без входов" — считаем неподвижным ниже этого смещения за тик
    private static final int RANDOM_POINT_ATTEMPTS = 8;

    // Фидбек 2026-08-22 — "столб вверх, чтобы афкшить". Черновое число:
    // 20+ блоков над реальной землёй (сэмплируется вокруг игрока, не под
    // ним — под ним стоит его же столб) — обычная поверхностная драка,
    // включая стены/башни разумной высоты, ниже порога почти всегда.
    // Раньше был ещё доп. чек "изоляция" (столб vs платформа) — убран по
    // фидбеку живого теста п.3, см. javadoc isPillared.
    private static final int PILLAR_HEIGHT_THRESHOLD = 20;
    private static final int PILLAR_GROUND_SAMPLE_RADIUS = 8;
    private static final int PHANTOM_MAX_PER_PULSE = 4;

    // Фидбек 2026-08-22 п.7 — "минимальный радиус от игрока до 3 блоков,
    // чтобы не спавнить мобов ему на лицо". Общий для наземного/подземного
    // патруля и фантомов.
    private static final double MIN_SPAWN_RADIUS = 3.0;
    // Фидбек п.2/1 — проверяем 3 блока по вертикали (не 2), с запасом:
    // Иссушающий скелет выше обычного (2.4 блока), 2 клетки ему впритык.
    private static final int SPAWN_HEIGHT_CHECK = 3;
    // Фидбек п.4 — "буквально может спавнить их тысячами". Общий потолок
    // живых мобов этого класса НА ОДНОГО атакующего игрока (не на зону —
    // этот класс не привязан к конкретному объекту) — наземный патруль и
    // фантомы делят один и тот же лимит.
    private static final int MAX_ALIVE_PER_PLAYER = 6;

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.FrontierDefenseCfg cfg = ctx.config().military().frontierDefense();
        return cfg != null && cfg.enabled();
    }

    /** По игроку (username) — состояние pity-охоты и неподвижности/эскалации Отголоска. */
    private record EngagementState(
            double[] pityChance, // мутируемый бокс на 1 элемент — проще, чем менять record на каждый тик
            long[] lastMoveAtMs,
            double[] lastX, double[] lastY, double[] lastZ,
            int[] echoPulses,
            long[] lastPulseAtMs
    ) {
        static EngagementState fresh(double baseChance, Location loc) {
            return new EngagementState(
                    new double[]{baseChance}, new long[]{System.currentTimeMillis()},
                    new double[]{loc.getX()}, new double[]{loc.getY()}, new double[]{loc.getZ()},
                    new int[]{0}, new long[]{0}
            );
        }
    }

    private final Map<String, EngagementState> stateByPlayer = new ConcurrentHashMap<>();
    // Фидбек 2026-08-22 п.4 — живые мобы этого класса на игрока (наземный
    // патруль + фантомы вместе), для MAX_ALIVE_PER_PLAYER. Раньше вообще не
    // считалось — при подходящих условиях каждый успешный pity-бросок/пульс
    // просто добавлял ещё мобов без потолка.
    private final Map<String, List<java.util.UUID>> aliveByPlayer = new ConcurrentHashMap<>();
    private BukkitTask task;

    /** Чистит протухшие записи и возвращает, сколько ещё можно заспавнить этому игроку (0, если лимит уже выбран). */
    private int freeSlotsFor(String playerName) {
        List<java.util.UUID> alive = aliveByPlayer.computeIfAbsent(playerName, k -> new ArrayList<>());
        alive.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        return Math.max(0, MAX_ALIVE_PER_PLAYER - alive.size());
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().frontierDefense();
        long period = Math.max(20L, cfg.periodTicks());
        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);
        if (C().core().debug()) {
            plugin().getLogger().info("[Military/FrontierDefense] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.FrontierDefenseCfg cfg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            String attackerCountry = UpgradeCondition.playerCountryCanonical(p.getName());
            if (attackerCountry == null) continue;

            Location loc = p.getLocation();
            ZoneInfo countryZone = zones().getCountryZoneAt(loc);
            if (countryZone == null) continue; // не на территории никакой страны

            String defenderCountry = UpgradeCondition.zoneCountryCanonical(countryZone);
            if (defenderCountry == null || defenderCountry.equals(attackerCountry)) continue; // своя земля
            String defenderCountryName = countryZone.getCountryName();
            if (defenderCountryName == null) continue;

            // isAtWar принимает отображаемые имена стран, не canonical id — тот же
            // паттерн, что у DefensePatrolUpgrade.enemyInCountryTerritory.
            String attackerCountryName = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (attackerCountryName == null || !UnityLauncher.getInstance().warStatusCache.isAtWar(defenderCountryName, attackerCountryName)) continue;

            // Оборона доступна только если защищающаяся страна вообще купила базовый узел.
            if (UpgradeCondition.countryMaxLevel(defenderCountry, "unity.military.defense", 1) < 1) continue;

            // GH#32 (фидбек 2026-08-22) — "коробка вокруг себя с открытым
            // верхом" обходила чистую проверку "под землёй" (open-to-sky =
            // формально не underground), хотя патрульным мобам всё равно
            // некуда зайти — тот же эксплойт, что и закапывание, просто
            // сверху вместо снизу. isUnderground остаётся (быстрый общий
            // случай), но триггер теперь шире — "укрыт" в принципе: либо
            // ниже уровня земли, либо обложен сплошными блоками со всех
            // 4 сторон на уровне ног И головы (реальная коробка), вне
            // зависимости от того, открыта крыша или нет.
            boolean shielded = isUnderground(loc, cfg.undergroundMargin()) || isEnclosed(loc);
            // Физически не могут быть true одновременно (нельзя одновременно быть
            // глубоко под землёй/в коробке и высоко над землёй) — считаем оба
            // отдельно, не elif, просто для ясности кода, не оптимизации ради.
            boolean pillared = !shielded && isPillared(loc);

            // Наземный pity-патруль бессмыслен против игрока на столбе — сухопутные
            // мобы физически не долетят/не долезут, только зря спавнились бы.
            if (!pillared) rollPityPatrol(p, loc, defenderCountry, defenderCountryName, shielded, cfg);
            handleIdleReaction(p, loc, defenderCountry, defenderCountryName, shielded, pillared, cfg);
        }

        // Чистим состояние игроков, которые вышли из релевантной территории/офлайн —
        // иначе карта растёт бесконечно на сервере с постоянной сменой состава.
        stateByPlayer.keySet().removeIf(name -> Bukkit.getPlayerExact(name) == null);
        aliveByPlayer.keySet().removeIf(name -> Bukkit.getPlayerExact(name) == null);
    }

    // ---- 1. Pity-патруль (§17.3) ----

    private void rollPityPatrol(Player p, Location loc, String defenderCountry, String defenderCountryName, boolean shielded, MilitaryCfg.FrontierDefenseCfg cfg) {
        int chanceLevel = UpgradeCondition.countryMaxLevel(defenderCountry, cfg.baseChancePermBase(), BASE_CHANCE.length - 1);
        int pityLevel = UpgradeCondition.countryMaxLevel(defenderCountry, cfg.pityPermBase(), PITY_GROWTH.length - 1);
        double baseChance = BASE_CHANCE[chanceLevel];
        double growth = PITY_GROWTH[pityLevel];

        EngagementState state = stateByPlayer.computeIfAbsent(p.getName(), k -> EngagementState.fresh(baseChance, loc));
        double chance = Math.min(PITY_CAP, Math.max(baseChance, state.pityChance()[0]));

        if (ThreadLocalRandom.current().nextDouble() < chance) {
            state.pityChance()[0] = baseChance; // сброс к базовому после успеха
            // §17.6 — 2_militaryFrontierWither, max_level 1: тот же countryMaxLevel(prefix, 1)
            // паттерн, что и у остальных одноуровневых проверок в этом файле/GEAR_PERM_BASE и т.п.
            boolean witherUnlocked = UpgradeCondition.countryMaxLevel(defenderCountry, cfg.witherPermission(), 1) > 0;
            spawnPatrol(p.getName(), loc, defenderCountryName, shielded, witherUnlocked);
        } else {
            state.pityChance()[0] = Math.min(PITY_CAP, chance + growth); // накопление к следующей проверке
        }
    }

    private void spawnPatrol(String attackerName, Location near, String defenderCountryName, boolean shielded, boolean witherUnlocked) {
        World w = near.getWorld();
        if (w == null) return;

        int freeSlots = freeSlotsFor(attackerName);
        if (freeSlots <= 0) return; // фидбек п.4 — лимит живых мобов на игрока уже выбран

        boolean noDefendersOnline = onlineCitizens(defenderCountryName) == 0;
        List<EntityType> composition = new ArrayList<>();
        if (shielded) {
            composition.add(EntityType.ZOMBIE);
            composition.add(EntityType.SKELETON);
            if (witherUnlocked) composition.add(EntityType.WITHER_SKELETON);
            else composition.add(EntityType.SKELETON); // без Иссушающих — просто плотнее обычный состав
        } else {
            composition.add(EntityType.ZOMBIE);
            composition.add(EntityType.SKELETON);
        }
        if (noDefendersOnline) composition.add(composition.get(0)); // GH#30-стиль усиление — некому защищаться
        while (composition.size() > freeSlots) composition.remove(composition.size() - 1);

        List<java.util.UUID> alive = aliveByPlayer.computeIfAbsent(attackerName, k -> new ArrayList<>());
        for (EntityType type : composition) {
            Location spawnLoc = randomPointNear(near, shielded);
            if (spawnLoc == null) continue; // фидбек 2026-08-22 (раунд 2) — тесно вокруг, не спавним вообще
            Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
            if (!(e instanceof LivingEntity mob)) continue;
            mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), defenderCountryName));
            mob.setRemoveWhenFarAway(true);
            mob.setCustomName(MOB_NAME);
            mob.setCustomNameVisible(true);
            alive.add(mob.getUniqueId());
        }
    }

    private int onlineCitizens(String countryName) {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (countryName.equalsIgnoreCase(UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName()))) n++;
        }
        return n;
    }

    /** heightBlocks клеток вертикально от (x,y,z) — все не-солид (воздух/вода/т.п.). SPAWN_HEIGHT_CHECK=3 — с запасом даже под Иссушающего скелета (2.4 блока), 2 клетки ему впритык (фидбек п.2 "мобы задыхаются"). */
    private boolean isPassableColumn(World w, int x, int y, int z, int heightBlocks) {
        for (int i = 0; i < heightBlocks; i++) {
            if (w.getBlockAt(x, y + i, z).getType().isSolid()) return false;
        }
        return true;
    }

    // Фидбек 2026-08-22 (третий раунд) — блоки, которые НЕЛЬЗЯ ломать даже
    // соседним с найденным воздухом: бедрок физически неломаем, обсидиан/
    // магма — шрам от Диверсии (§17.10), не должны сами же его портить.
    private static final java.util.Set<Material> UNBREAKABLE_UNDERGROUND =
            java.util.EnumSet.of(Material.BEDROCK, Material.OBSIDIAN, Material.MAGMA_BLOCK);

    /**
     * Точка рядом с игроком: на поверхности — по highestBlockYAt (с
     * проверкой на реальную проходимость — навес/листва не должны душить
     * моба, фидбек п.2).
     *
     * Под землёй — фидбек 2026-08-22 (третий раунд): "искать воздух рядом
     * с игроком, и рядом с этим блоком воздуха ЛОМАТЬ блок и спавнить на
     * его место". Не полноценный туннель (раунд 2 — отменён) и не "просто
     * не спавнить, если тесно" (тоже раунд 2) — средний вариант: находим
     * УЖЕ существующую воздушную клетку рядом с игроком (значит, она точно
     * связана с его пространством), ломаем ОДИН её солид-сосед и спавним
     * моба туда — минимальное вмешательство в мир, гарантированная связность
     * (клетка вплотную примыкает к подтверждённому воздуху).
     */
    private Location randomPointNear(Location base, boolean shielded) {
        World w = base.getWorld();
        if (w == null) return null;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (!shielded) {
            for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++) {
                double radius = MIN_SPAWN_RADIUS + rnd.nextDouble() * 8;
                double angle = rnd.nextDouble() * Math.PI * 2;
                double x = base.getX() + Math.cos(angle) * radius;
                double z = base.getZ() + Math.sin(angle) * radius;
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                int groundY = w.getHighestBlockYAt(bx, bz);
                // Bukkit-конвенция highestBlockYAt плавает между версиями —
                // проверяем оба варианта ("сам groundY свободен" и "groundY+1
                // свободен") вместо того чтобы гадать, что именно вернул API.
                if (isPassableColumn(w, bx, groundY, bz, SPAWN_HEIGHT_CHECK)) return new Location(w, x, groundY, z);
                if (isPassableColumn(w, bx, groundY + 1, bz, SPAWN_HEIGHT_CHECK)) return new Location(w, x, groundY + 1, z);
            }
            // Ничего свободного не нашли (частый лес/навес) — берём
            // последнюю попытку как есть, groundY+1 — обычно safest guess.
            double angle = rnd.nextDouble() * Math.PI * 2;
            double x = base.getX() + Math.cos(angle) * MIN_SPAWN_RADIUS;
            double z = base.getZ() + Math.sin(angle) * MIN_SPAWN_RADIUS;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            return new Location(w, x, w.getHighestBlockYAt(bx, bz) + 1, z);
        }

        for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++) {
            double radius = rnd.nextDouble() * 3.0; // 0-3 блока — "в упоре"
            double angle = rnd.nextDouble() * Math.PI * 2;
            int bx = (int) Math.floor(base.getX() + Math.cos(angle) * radius);
            int bz = (int) Math.floor(base.getZ() + Math.sin(angle) * radius);
            int by = base.getBlockY() + rnd.nextInt(3) - 1; // тот же уровень +-1

            if (w.getBlockAt(bx, by, bz).getType() != Material.AIR) continue; // ищем именно УЖЕ воздух
            Location dug = breakAdjacentAndSpawn(w, bx, by, bz);
            if (dug != null) return dug;
        }
        return null; // рядом вообще нет воздуха (или все соседи неломаемые) — не спавним в этот раз
    }

    /** Ломает один случайный солид-блок, примыкающий к уже подтверждённому воздуху, и (если и он солид) клетку над ним для роста — спавнит моба в эту новую полость. */
    private Location breakAdjacentAndSpawn(World w, int ax, int ay, int az) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        List<int[]> order = new ArrayList<>(List.of(dirs));
        java.util.Collections.shuffle(order, ThreadLocalRandom.current());

        for (int[] d : order) {
            int nx = ax + d[0], ny = ay + d[1], nz = az + d[2];
            Material m = w.getBlockAt(nx, ny, nz).getType();
            if (m == Material.AIR || UNBREAKABLE_UNDERGROUND.contains(m)) continue;

            w.getBlockAt(nx, ny, nz).setType(Material.AIR);
            org.bukkit.block.Block above = w.getBlockAt(nx, ny + 1, nz);
            if (above.getType().isSolid() && !UNBREAKABLE_UNDERGROUND.contains(above.getType())) {
                above.setType(Material.AIR); // немного роста в высоту, чтобы моб реально помещался
            }
            return new Location(w, nx + 0.5, ny, nz + 0.5);
        }
        return null; // все соседи этой воздушной клетки — либо уже воздух, либо неломаемые
    }

    // ---- 2. "Отголосок" (§17.4) / 3. Фантомы на столбе ----

    /**
     * Общая неподвижность-детекция для обеих реакций — какая именно
     * сработает (пульс Отголоска или спавн фантомов), решает, что из
     * shielded/pillared true (взаимоисключающе по физике). Раньше это
     * называлось handleEcho и создавало EngagementState только косвенно
     * (через уже отработавший rollPityPatrol) — с фантомами на столбе
     * rollPityPatrol теперь может НЕ вызываться вовсе (см. tick()), так
     * что состояние создаётся здесь же, если его ещё нет.
     */
    private void handleIdleReaction(Player p, Location loc, String defenderCountry, String defenderCountryName, boolean shielded, boolean pillared, MilitaryCfg.FrontierDefenseCfg cfg) {
        if (!shielded && !pillared) {
            // На обычной поверхности/в бою — просто держим позицию свежей, без
            // создания состояния впустую для игроков, которые никогда не попадут
            // ни в одну из двух ситуаций (state создаётся лениво ниже при первом
            // реальном попадании в shielded/pillared).
            EngagementState existing = stateByPlayer.get(p.getName());
            if (existing != null) {
                existing.lastX()[0] = loc.getX();
                existing.lastY()[0] = loc.getY();
                existing.lastZ()[0] = loc.getZ();
                existing.lastMoveAtMs()[0] = System.currentTimeMillis();
                existing.echoPulses()[0] = 0;
            }
            return;
        }

        EngagementState state = stateByPlayer.computeIfAbsent(p.getName(), k -> EngagementState.fresh(BASE_CHANCE[0], loc));

        long now = System.currentTimeMillis();
        double dx = loc.getX() - state.lastX()[0];
        double dy = loc.getY() - state.lastY()[0];
        double dz = loc.getZ() - state.lastZ()[0];
        boolean moved = (dx * dx + dy * dy + dz * dz) > IDLE_MOVE_EPSILON_SQ;

        if (moved) {
            state.lastX()[0] = loc.getX();
            state.lastY()[0] = loc.getY();
            state.lastZ()[0] = loc.getZ();
            state.lastMoveAtMs()[0] = now;
            state.echoPulses()[0] = 0; // реальное движение — полный сброс эскалации
            return;
        }

        int echoLevel = UpgradeCondition.countryMaxLevel(defenderCountry, cfg.echoPermBase(), ECHO_IDLE_SECONDS.length - 1);
        long idleThresholdMs = ECHO_IDLE_SECONDS[echoLevel] * 1000L;
        long idleMs = now - state.lastMoveAtMs()[0];
        if (idleMs < idleThresholdMs) return;

        long sinceLastPulse = now - state.lastPulseAtMs()[0];
        if (sinceLastPulse < cfg.pulsePeriodTicks() * 50L) return;

        state.lastPulseAtMs()[0] = now;
        int pulseIndex = state.echoPulses()[0]++;

        if (pillared) {
            // Фидбек 2026-08-22 — фантомы вместо урона сквозь блоки: игрок в
            // открытом небе, "сквозь стены" тут ни при чём, зато летающий моб
            // реально долетит и заставит либо драться, либо слезать. Эскалация
            // тем же индексом пульса — больше фантомов с каждым разом, как и у
            // Отголоска с уроном, тот же принцип "чем дольше сидишь, тем хуже".
            int count = Math.min(PHANTOM_MAX_PER_PULSE, 1 + pulseIndex);
            spawnPhantoms(p, defenderCountryName, count);
            return;
        }

        double damage = cfg.pulseBaseDamage() + pulseIndex * (ECHO_DAMAGE_STEP_HEARTS[echoLevel] * 2.0);

        // Фидбек 2026-08-22 ("удар вардена вообще не появляется") — урон
        // теперь идёт ПЕРВЫМ, звук/партиклы — best-effort в try/catch следом:
        // раньше, если звук/партикл-константа не резолвилась на конкретной
        // версии API (NoSuchFieldError на конкретном сервере — не
        // воспроизвести локально без доступа к боевому окружению), исключение
        // прерывало метод ДО damage() — и удара не было вообще, хотя
        // остальная логика (детект/эскалация) явно отрабатывала. Теперь урон
        // применяется гарантированно, аудио-визуал — по возможности.
        p.damage(damage);
        try {
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.5f, 1.0f);
            p.spawnParticle(Particle.SONIC_BOOM, p.getLocation().add(0, 1, 0), 1);
        } catch (Throwable t) {
            plugin().getLogger().warning("[Military/FrontierDefense] Отголосок: звук/партикл не сработали (" + t + "), урон всё равно применён");
        }
    }

    /** Фантомы у столба — те же анти-фарм атрибуты (без дропа/опыта, именованы, целятся только в реального врага по войне), что и наземный патруль — onDeath/onTarget ниже общие для всех META_KEY-мобов. onCombust ниже — иммунитет к дневному горению, иначе фантомы гибли от солнца раньше, чем успевали атаковать. */
    private void spawnPhantoms(Player target, String defenderCountryName, int count) {
        Location at = target.getLocation();
        World w = at.getWorld();
        if (w == null) return;

        int freeSlots = freeSlotsFor(target.getName());
        if (freeSlots <= 0) return; // фидбек п.4
        count = Math.min(count, freeSlots);

        List<java.util.UUID> alive = aliveByPlayer.computeIfAbsent(target.getName(), k -> new ArrayList<>());
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            // Фидбек 2026-08-22 п.7 — раньше dx/dz могли оба выйти в 0
            // (спавн прямо в игроке "на лицо"). Полярные координаты с
            // минимальным радиусом — тот же приём, что у randomPointNear.
            double radius = MIN_SPAWN_RADIUS + rnd.nextDouble() * 4;
            double angle = rnd.nextDouble() * Math.PI * 2;
            double x = at.getX() + Math.cos(angle) * radius;
            double z = at.getZ() + Math.sin(angle) * radius;
            // Фидбек 2026-08-22 (раунд 2) — "фантомов спавнить ниже, 7-8+
            // блоков от земли": раньше спавнились у самой высоты игрока
            // (rnd.nextInt(4) над ним) — на высоком столбе это ощущалось
            // как мгновенное появление в упор. Теперь высота считается от
            // РЕЛЬЕФА, не от игрока — фантому нужно долететь снизу, давая
            // видимое/слышимое предупреждение вместо мгновенного появления
            // рядом на любой высоте столба.
            int groundY = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
            double y = groundY + 7 + rnd.nextInt(4); // 7-10 блоков от земли
            Location spawnAt = new Location(w, x, y, z);
            Entity e = w.spawnEntity(spawnAt, EntityType.PHANTOM);
            if (!(e instanceof LivingEntity mob)) continue;
            mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), defenderCountryName));
            mob.setRemoveWhenFarAway(true);
            mob.setCustomName(MOB_NAME);
            mob.setCustomNameVisible(true);
            alive.add(mob.getUniqueId());
            // Фидбек 2026-08-22 ("кружат и не атакуют") — заспавненный через
            // spawnEntity фантом НЕ получает цель автоматически (ванильная
            // AI-цель фантома завязана на "бессонницу" игрока при природном
            // спавне — плагинный спавн этот триггер не проходит вообще).
            // Без явной цели фантом просто летает по своему обычному
            // блужданию рядом, никогда не атакуя — задаём цель напрямую.
            if (mob instanceof org.bukkit.entity.Mob m) m.setTarget(target);
        }
    }

    private boolean isUnderground(Location loc, int margin) {
        World w = loc.getWorld();
        if (w == null) return false;
        int highest = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return loc.getBlockY() < highest - margin;
    }

    /**
     * GH#32 (фидбек 2026-08-22) — "коробка вокруг себя с открытым верхом"
     * обходит isUnderground (небо над головой открыто, значит формально
     * не под землёй), но обычные наземные мобы патруля физически не могут
     * дойти до игрока за стенами — та же ситуация, что и закапывание,
     * просто сверху. Проверяет сплошной блок в 1 блоке по всем 4 сторонам
     * света на уровне ног И головы — то есть реально замкнутая коробка, не
     * просто "стоит у одной стены" (иначе ложные срабатывания у любой
     * постройки/забора рядом). Крыша намеренно не проверяется — именно её
     * отсутствие и есть эксплойт, который это закрывает.
     */
    private boolean isEnclosed(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;

        int x = loc.getBlockX();
        int feetY = loc.getBlockY();
        int headY = feetY + 1;
        int z = loc.getBlockZ();

        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            Material feet = w.getBlockAt(x + side[0], feetY, z + side[1]).getType();
            Material head = w.getBlockAt(x + side[0], headY, z + side[1]).getType();
            if (!feet.isSolid() || !head.isSolid()) return false; // хотя бы одна сторона открыта — не коробка
        }
        return true;
    }

    /**
     * Фидбек 2026-08-22 — "столб вверх, чтобы афкшить". Изначально требовал
     * ЕЩЁ и изоляцию (почти только воздух в радиусе 2) — по факту это
     * ловило только голый 1×1 столб и пропускало платформу (3×3+), хотя
     * платформа — та же самая AFK-стратегия наверху, просто пошире.
     *
     * Живой тест (фидбек 2026-08-22, п.3) — "сделай менее чувствительным":
     * изоляция убрана целиком, остаётся только высота. Достаточно сама по
     * себе — обычная поверхностная драка (в том числе у стен/на невысоких
     * постройках) почти всегда ниже PILLAR_HEIGHT_THRESHOLD, без доп.
     * условий; настоящие закрытые постройки (комната с крышей и стенами)
     * всё равно уходят в isEnclosed раньше (shielded проверяется первым в
     * tick() — pillared считается только если НЕ shielded), так что боязнь
     * "поймать легитимную башню" тут не о том же случае.
     */
    private boolean isPillared(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;

        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        int groundEstimate = Integer.MAX_VALUE;
        int[][] samples = {{PILLAR_GROUND_SAMPLE_RADIUS, 0}, {-PILLAR_GROUND_SAMPLE_RADIUS, 0}, {0, PILLAR_GROUND_SAMPLE_RADIUS}, {0, -PILLAR_GROUND_SAMPLE_RADIUS}};
        for (int[] s : samples) {
            groundEstimate = Math.min(groundEstimate, w.getHighestBlockYAt(x + s[0], z + s[1]));
        }
        return y - groundEstimate >= PILLAR_HEIGHT_THRESHOLD;
    }

    /** Без дропа/опыта — тот же паттерн, что у Живого поста/обычного патруля Обороны. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getMetadata(META_KEY).isEmpty()) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    /**
     * Фидбек 2026-08-22 ("афк над землёй практически не срабатывает...
     * фантомы сгорают ещё до момента как успеют атаковать") — фантомы, как и
     * прочая нежить, горят на солнце; заспавненные днём рядом со столбом (в
     * открытом небе, по определению) вспыхивали и гибли за пару секунд,
     * реального боя не успевало произойти. Иммунитет — ТОЛЬКО фантомам
     * (фидбек, живой тест 2026-08-22 п.5 — "зомби и скелетам оставить",
     * им дневное горение — нормальная ванильная часть боя, не баг). Не
     * трогает обычных фантомов/зомби/скелетов в мире — только META_KEY-фантомов
     * этого класса.
     */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.Phantom)) return;
        if (e.getEntity().getMetadata(META_KEY).isEmpty()) return;
        e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player target)) return;
        List<org.bukkit.metadata.MetadataValue> meta = e.getEntity().getMetadata(META_KEY);
        if (meta.isEmpty()) return;

        String defenderCountryName = meta.get(0).asString();
        String targetCountryName = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(target.getName());
        boolean isEnemyAtWar = targetCountryName != null
                && !targetCountryName.equalsIgnoreCase(defenderCountryName)
                && UnityLauncher.getInstance().warStatusCache.isAtWar(defenderCountryName, targetCountryName);
        if (!isEnemyAtWar) e.setCancelled(true);
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        stateByPlayer.clear();
        aliveByPlayer.clear();
    }
}
