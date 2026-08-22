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
 *     закапываться" в другую сторону: если враг забрался на изолированный
 *     столб высоко над реальной землёй (не часть широкой конструкции —
 *     башни/стены не триггерят, см. isPillared) и не двигается дольше
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

    // Фидбек 2026-08-22 — "столб вверх, чтобы афкшить". Черновые числа:
    // 20+ блоков над реальной землёй (сэмплируется вокруг игрока, не под
    // ним — под ним стоит его же столб) — обычная поверхностная драка,
    // включая стены/башни разумной высоты, ниже порога почти всегда.
    // Изоляция — почти всё вокруг на уровне игрока воздух (радиус 2), то
    // есть именно тонкий столб, не платформа/стена/крыша башни.
    private static final int PILLAR_HEIGHT_THRESHOLD = 20;
    private static final int PILLAR_GROUND_SAMPLE_RADIUS = 8;
    private static final int PILLAR_ISOLATION_RADIUS = 2;
    private static final int PILLAR_MAX_SOLID_NEARBY = 1; // допуск на шум/случайный блок рядом
    private static final int PHANTOM_MAX_PER_PULSE = 4;

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
    private BukkitTask task;

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
            spawnPatrol(loc, defenderCountryName, shielded, witherUnlocked);
        } else {
            state.pityChance()[0] = Math.min(PITY_CAP, chance + growth); // накопление к следующей проверке
        }
    }

    private void spawnPatrol(Location near, String defenderCountryName, boolean shielded, boolean witherUnlocked) {
        World w = near.getWorld();
        if (w == null) return;

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

        for (EntityType type : composition) {
            Location spawnLoc = randomPointNear(near, shielded);
            Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
            if (!(e instanceof LivingEntity mob)) continue;
            mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), defenderCountryName));
            mob.setRemoveWhenFarAway(true);
            mob.setCustomName(MOB_NAME);
            mob.setCustomNameVisible(true);
        }
    }

    private int onlineCitizens(String countryName) {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (countryName.equalsIgnoreCase(UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName()))) n++;
        }
        return n;
    }

    // Фидбек 2026-08-22 — "если места нет — разрушать блоки рядом (разрешённый
    // список), не тупо не спавнить/спавнить в одной точке". Только природный
    // диггабельный камень/грунт — НЕ бедрок, НЕ обсидиан/магма (это теперь
    // шрам от Диверсии, см. дизайн-док §17.10 — не должны сами же его портить),
    // НЕ что-либо похожее на постройку игрока.
    private static final java.util.Set<Material> DIGGABLE_UNDERGROUND = java.util.EnumSet.of(
            Material.STONE, Material.DEEPSLATE, Material.DIRT, Material.GRAVEL, Material.SAND,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF, Material.CALCITE,
            Material.CLAY, Material.NETHERRACK, Material.END_STONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.DRIPSTONE_BLOCK, Material.COBBLED_DEEPSLATE
    );

    /** Точка рядом с игроком: на поверхности — по highestBlockYAt, под землёй — на его же уровне Y с проверкой на проходимость (или прокопкой, если места совсем нет). */
    private Location randomPointNear(Location base, boolean shielded) {
        World w = base.getWorld();
        if (w == null) return base;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        Location lastDiggableCandidate = null;
        for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++) {
            double radius = shielded ? (4 + rnd.nextDouble() * 5) : (5 + rnd.nextDouble() * 8);
            double angle = rnd.nextDouble() * Math.PI * 2;
            double x = base.getX() + Math.cos(angle) * radius;
            double z = base.getZ() + Math.sin(angle) * radius;

            if (!shielded) {
                int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
                return new Location(w, x, y, z);
            }

            double y = base.getY() + rnd.nextInt(3) - 1; // тот же уровень +-1
            Location candidate = new Location(w, x, y, z);
            Material at = candidate.getBlock().getType();
            Material above = candidate.clone().add(0, 1, 0).getBlock().getType();
            if (!at.isSolid() && !above.isSolid()) return candidate;

            // Запоминаем последнего "диггабельного" кандидата — если ни одна
            // точка так и не оказалась свободной (тесная порода), прокопаем
            // именно его, а не первый попавшийся (мог быть бедрок/чужая постройка).
            if (lastDiggableCandidate == null && DIGGABLE_UNDERGROUND.contains(at) && DIGGABLE_UNDERGROUND.contains(above)) {
                lastDiggableCandidate = candidate;
            }
        }

        if (lastDiggableCandidate != null) {
            lastDiggableCandidate.getBlock().setType(Material.AIR);
            lastDiggableCandidate.clone().add(0, 1, 0).getBlock().setType(Material.AIR);
            return lastDiggableCandidate;
        }
        return base; // не нашли даже диггабельную точку (бедрок/чужая постройка вокруг) — спавним прямо у игрока
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
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Location spawnAt = at.clone().add(rnd.nextInt(7) - 3, rnd.nextInt(4), rnd.nextInt(7) - 3);
            Entity e = w.spawnEntity(spawnAt, EntityType.PHANTOM);
            if (!(e instanceof LivingEntity mob)) continue;
            mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), defenderCountryName));
            mob.setRemoveWhenFarAway(true);
            mob.setCustomName(MOB_NAME);
            mob.setCustomNameVisible(true);
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
     * Фидбек 2026-08-22 — "столб вверх, чтобы афкшить". Два независимых
     * условия, оба обязательны:
     *  1. Высота — минимум из высоты рельефа, сэмплированной ВОКРУГ игрока
     *     (не под ним — под ним его же столб, highestBlockYAt там всегда
     *     вернёт верх столба, бессмысленно) на радиусе
     *     PILLAR_GROUND_SAMPLE_RADIUS в 4 стороны. Игрок должен быть выше
     *     этого на PILLAR_HEIGHT_THRESHOLD блоков — обычная поверхностная
     *     драка (в том числе у стен/на невысоких постройках) почти всегда
     *     ниже порога сама по себе, без доп. условий.
     *  2. Изоляция — на уровне игрока в радиусе PILLAR_ISOLATION_RADIUS
     *     почти всё воздух (допуск PILLAR_MAX_SOLID_NEARBY). Отсекает
     *     легитимные широкие постройки (башня/стена с площадкой) — там
     *     соседних солид-блоков заведомо больше, чем у голого 1×1 столба.
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
        if (y - groundEstimate < PILLAR_HEIGHT_THRESHOLD) return false;

        int solidNearby = 0;
        for (int dx = -PILLAR_ISOLATION_RADIUS; dx <= PILLAR_ISOLATION_RADIUS; dx++) {
            for (int dz = -PILLAR_ISOLATION_RADIUS; dz <= PILLAR_ISOLATION_RADIUS; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (w.getBlockAt(x + dx, y, z + dz).getType().isSolid()) solidNearby++;
                if (solidNearby > PILLAR_MAX_SOLID_NEARBY) return false; // ранний выход — не столб
            }
        }
        return true;
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
     * реального боя не успевало произойти. Иммунитет к возгоранию только для
     * META_KEY-мобов этого класса (не трогает обычных фантомов/зомби/скелетов
     * в мире) — вся тема Отголоска/фантомов на столбе про "заставить среагировать",
     * не про "повезёт, если ночь".
     */
    @EventHandler(ignoreCancelled = true)
    public void onCombust(org.bukkit.event.entity.EntityCombustEvent e) {
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
    }
}
