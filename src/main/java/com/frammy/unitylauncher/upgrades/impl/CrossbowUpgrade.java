package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.MilitaryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// infra/military-diplomacy-design.md GH#24 идея 4 "Арбалет" — стреляет по
// ближайшим видимым врагам в радиусе от якоря объекта. Слепая зона: цель
// ниже blindSpotDegrees от горизонтали не обстреливается.
//
// GH#29 — переработка по фидбеку:
//  1. Прицел раньше промахивался на "голову выше" — направление считалось от
//     origin (точка якоря), а стрела спавнилась на 0.5 блока выше origin;
//     прямая от смещённой точки СПАВНА в направлении, посчитанном от точки
//     БЕЗ смещения, идёт параллельно нужной линии, но на те же 0.5 блока
//     выше по всей траектории — почти ровно "голова" по хитбоксу игрока.
//     Фикс: направление теперь считается от той же точки, где стрела
//     реально спавнится.
//  2. Раньше стрелял только в одну, ближайшую цель. Теперь — до 3 разных
//     целей одним тиком, по одной стреле на каждую (если целей меньше 3 —
//     соответственно меньше стрел).
//  3. "Усиление" разделено на независимые узлы апгрейдов вместо одного
//     общего (см. militaryCrossbowRate/Effects/EffectChance/Accuracy в
//     seed-данных): скорострельность, слоты эффектов, шанс эффекта и
//     точность — каждый прокачивается независимо.
//  4. (Фидбек раунда 2) — раньше стрела летела точно по прямой на цель,
//     уклониться было можно только теоретически; точность (Accuracy) теперь
//     реальный конус разброса, сужающийся с уровнем.
//  5. (Фидбек раунда 2) — эффект-пикер: страна выбирает КОНКРЕТНЫЙ набор
//     эффектов (см. choices в 3_militaryCrossbowEffects) вместо случайного
//     эффекта из всего пула; если ничего не выбрано — старое поведение
//     (случайный эффект из пула по уровню) как фоллбек.
//  6. (Фидбек раунда 4) — "то сбоку не стреляет, то прямо в упоре": findTargets()
//     проверял LOS/угол/дальность от НЕсмещённого origin, а стрела спавнилась
//     на 1.2 блока выше (см. п.1/раунд 3) — та же расходящаяся пара точек,
//     просто на этапе выбора цели, не наведения. Оба этапа теперь считаются
//     от одной и той же spawnLoc.
public final class CrossbowUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.crossbow");
    private static final double ARROW_SPEED = 3.0;
    private static final int MAX_TARGETS = 3;

    // GH#29 п.1 "Скорострельность" — 3 уровня (0 — база, 1, 2), множитель на
    // период стрельбы из cfg.periodTicks(). Черновые числа — баланс можно
    // подправить конфигом позже, сама механика уже полностью рабочая.
    private static final double[] RATE_MULTIPLIER = {1.0, 0.67, 0.4};

    // GH#29 п.2а "Шанс стрелы с эффектом" — 3 уровня, максимум держим в
    // запрошенном диапазоне (~50-60%), чтобы не каждая стрела летела с
    // эффектом.
    private static final double[] EFFECT_CHANCE = {0.20, 0.35, 0.55};

    // GH#29 п.2 "Эффекты" — 0: эффекта нет вообще; 1: один "слот" (выбор из
    // WEAK_EFFECT_POOL, только для фоллбека — см. EFFECT_PRESETS ниже для
    // выбора страной); 2: три слота (FULL_EFFECT_POOL, тоже фоллбек).
    private static final List<PotionEffectType> WEAK_EFFECT_POOL = List.of(PotionEffectType.SLOWNESS);
    private static final List<PotionEffectType> FULL_EFFECT_POOL =
            List.of(PotionEffectType.SLOWNESS, PotionEffectType.WEAKNESS, PotionEffectType.NAUSEA);
    private static final int EFFECT_DURATION_TICKS = 20 * 4;

    // Фидбек 2026-08-14 (раунд 2) — "я как раз таки и подразумевал пикер
    // эффектов, добавь его" — точный аналог permission-based выбора, что уже
    // используют другие choices-апгрейды (см. computePermissionSets на
    // бэкенде: активному choiceId соответствует ровно одна permission-нода,
    // остальные снимаются). Набор эффектов подобран на моё усмотрение по
    // содержимому seed-данных 3_militaryCrossbowEffects.choices — страна
    // направит, что оставить.
    private record EffectSpec(PotionEffectType type, int amplifier) {}
    private record EffectPreset(String permission, List<EffectSpec> specs) {}
    private static final List<EffectPreset> EFFECT_PRESETS = List.of(
            new EffectPreset("unity.military.crossbow_effect.weak_slow", List.of(new EffectSpec(PotionEffectType.SLOWNESS, 0))),
            new EffectPreset("unity.military.crossbow_effect.weak_weakness", List.of(new EffectSpec(PotionEffectType.WEAKNESS, 0))),
            new EffectPreset("unity.military.crossbow_effect.weak_poison", List.of(new EffectSpec(PotionEffectType.POISON, 0))),
            // GH#29 (фидбек раунд 3) — Тошнота заменена на Темноту: экран
            // Тошноты раскачивается несколько секунд, чтобы дойти до полного
            // эффекта, а EFFECT_DURATION_TICKS (4с) для этого коротковат —
            // на практике эффект почти не успевал проявиться. Темнота
            // работает мгновенно.
            new EffectPreset("unity.military.crossbow_effect.full_control", List.of(
                    new EffectSpec(PotionEffectType.SLOWNESS, 1), new EffectSpec(PotionEffectType.BLINDNESS, 0), new EffectSpec(PotionEffectType.DARKNESS, 0))),
            new EffectPreset("unity.military.crossbow_effect.full_attrition", List.of(
                    new EffectSpec(PotionEffectType.POISON, 0), new EffectSpec(PotionEffectType.WEAKNESS, 1), new EffectSpec(PotionEffectType.HUNGER, 0))),
            new EffectPreset("unity.military.crossbow_effect.full_reveal", List.of(
                    new EffectSpec(PotionEffectType.GLOWING, 0), new EffectSpec(PotionEffectType.SLOWNESS, 0), new EffectSpec(PotionEffectType.MINING_FATIGUE, 0)))
    );

    // GH#29 (фидбек раунд 2) "Точность" — раньше выстрел шёл идеально по
    // прямой (уклониться нельзя было практически). Угол (градусы)
    // максимального случайного отклонения направления стрелы от идеальной
    // прямой на цель — сужается с уровнем.
    // GH#29 (фидбек раунд 3) — "перестарался, теперь почти не попадает,
    // подними в первых двух апгрейдах" — базовый и первый уровень были
    // слишком широкими (9°/4°), заметно урезано.
    private static final double[] INACCURACY_DEGREES = {4.0, 1.5, 0.5};

    private static final String RATE_PERM_BASE = "unity.military.crossbow_rate";
    private static final String EFFECTS_PERM_BASE = "unity.military.crossbow_effects";
    private static final String CHANCE_PERM_BASE = "unity.military.crossbow_chance";
    private static final String ACCURACY_PERM_BASE = "unity.military.crossbow_accuracy";

    // markerId -> когда последний раз стреляли (мс) — один залп за (динамический) период, простейший кулдаун на объект.
    private final Map<String, Long> lastShotByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    // Живой тест 2026-08-22 — метка на стрелах Арбалета (для onArrowHit
    // ниже, отличить их от любых других стрел на сервере) и сила взрыва
    // блока, в который такая стрела воткнулась — небольшая, только чтобы
    // расчистить сам блок и пару соседних, не кратер.
    private static final String ARROW_METADATA_KEY = "military_crossbow_arrow";
    private static final float BLOCK_CLEAR_EXPLOSION_POWER = 1.2f;

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.CrossbowCfg cfg = ctx.config().military().crossbow();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().crossbow();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/Crossbow] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.CrossbowCfg cfg) {
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;

        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            // GH#24 (фидбек 2026-08-14 п.1/4) — только на объекте, реально вкачанном в CROSSBOW.
            if (!subtypeService.isActiveAs(z, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.CROSSBOW)) continue;
            // farlandsconnect GH#32 (раунд 10) — захваченные секторы линии
            // фронта снижают эффективность: вероятностный пропуск залпа
            // вместо переработки формулы скорострельности под конкретный %.
            if (!UnityLauncher.getInstance().militaryEffectivenessCache.rollActive(z.getMarkerID())) continue;

            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            // GH#29 — независимые уровни прокачки вместо одного общего "усиления".
            int rateLevel = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(canonicalCountry, RATE_PERM_BASE, 2);
            int effectsLevel = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(canonicalCountry, EFFECTS_PERM_BASE, 2);
            int chanceLevel = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(canonicalCountry, CHANCE_PERM_BASE, 2);
            int accuracyLevel = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(canonicalCountry, ACCURACY_PERM_BASE, 2);

            // GH#24 (фидбек 2026-08-14 п.4) — якорь обязателен, без него не стреляет вообще.
            Location origin = z.getMilitaryAnchorLocation();
            if (origin == null || origin.getWorld() == null) continue;
            // GH#29 (фидбек раунд 4) — "то сбоку не стреляет, то прямо в
            // упоре" — тот же класс бага, что уже чинили для направления в
            // п.1, только теперь в проверке видимости/дальности: findTargets
            // проверял LOS/угол/дистанцию от НЕсмещённого origin (низ
            // якоря), а стрела реально спавнилась из shoot() на 1.2 блока
            // выше. У любого объекта с парапетом/выступом на этой высоте
            // (а Разведпункт/Арбалет как раз требуют физический блок-якорь)
            // два уровня видят обзор по-разному — то ложно "не видит" цель
            // сбоку (которая реально в прямой видимости с высоты стрелы),
            // то ложно "видит" (а стрела при спавне на 1.2 выше тут же
            // цепляется за что-то рядом — ощущается как "в упор"). Считаем
            // spawnLoc один раз и используем её ВЕЗДЕ — и для проверки, и
            // для самого выстрела, вместо двух разных точек отсчёта.
            //
            // Живой тест 2026-08-22 — эксплойт "поставить блок прямо над
            // колоколом" (стрела спавнится замурованной, физически никуда не
            // летит) чиним НЕ сдвигом точки спавна (фидбек: "не надо менять
            // место выпуска стрелы"), а тем, что стрела при попадании в блок
            // сама расчищает себе путь — см. onArrowHit ниже: блок подрывается,
            // стрела считается потраченной ("сгорела"), а следующий залп уже
            // летит свободно.
            Location spawnLoc = origin.clone().add(0, 1.2, 0);

            // Живой тест 2026-08-22 (раунд 2) — "если блок ставится РОВНО в
            // точку спавна, стрела просто спавнится внутри" — onArrowHit
            // реагирует на ProjectileHitEvent, а стрела, которая уже
            // заспавнилась ВНУТРИ блока, никуда не летит и это событие
            // никогда не получает (нет столкновения — нечему сработать).
            // Прочищаем саму точку спавна заранее, до вызова shoot() —
            // тем же способом, что и onArrowHit (небольшой взрыв), так что
            // следующий же залп в ЭТОТ tick уже летит свободно.
            if (!spawnLoc.getBlock().isPassable()) {
                clearBlockingBlock(spawnLoc, origin);
            }

            List<Player> targets = findTargets(spawnLoc, cfg, z.getCountryName(), MAX_TARGETS);
            if (targets.isEmpty()) continue;

            String markerId = z.getMarkerID();
            long now = System.currentTimeMillis();
            long effectivePeriodTicks = Math.max(1L, Math.round(cfg.periodTicks() * RATE_MULTIPLIER[rateLevel]));
            Long last = lastShotByZone.get(markerId);
            if (last != null && now - last < effectivePeriodTicks * 50L) continue;
            lastShotByZone.put(markerId, now);

            for (Player target : targets) {
                shoot(spawnLoc, origin, target, cfg, canonicalCountry, effectsLevel, chanceLevel, accuracyLevel);
            }
        }
    }

    // GH#24 (вопрос "арбалет стреляет только во врагов, с кем страна в
    // войне?") — цель обязана и не быть гражданином этой страны, и её страна
    // обязана реально воевать с этой (DefensePatrolUpgrade — соседняя
    // оборонительная механика — уже требует именно войны).
    //
    // GH#29 п.2 — раньше искал только одну, ближайшую цель. Теперь собирает
    // всех подходящих в радиусе и возвращает до `max` ближайших.
    private List<Player> findTargets(Location origin, MilitaryCfg.CrossbowCfg cfg, String countryName, int max) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : origin.getWorld().getPlayers()) {
            String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (playerCountry == null) continue; // без страны — не с кем воевать
            if (playerCountry.equalsIgnoreCase(countryName)) continue; // свой — не враг
            if (!UnityLauncher.getInstance().warStatusCache.isAtWar(countryName, playerCountry)) continue; // не воюем — не враг

            double d = p.getLocation().distance(origin);
            if (d > cfg.radius()) continue;
            if (!isWithinFiringAngle(origin, p.getLocation(), cfg.blindSpotDegrees())) continue;
            if (!hasLineOfSight(origin, p)) continue;

            candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble(p -> p.getLocation().distance(origin)));
        return candidates.size() > max ? candidates.subList(0, max) : candidates;
    }

    /** Видимость от якоря до игрока — Location у Player.hasLineOfSight(Block) нет подходящей перегрузки, трассируем блоки сами. */
    private boolean hasLineOfSight(Location origin, Player target) {
        Location eye = target.getEyeLocation();
        Vector dir = eye.toVector().subtract(origin.toVector());
        double dist = dir.length();
        if (dist < 1e-6) return true;
        dir.normalize();
        RayTraceResult hit = origin.getWorld().rayTraceBlocks(origin, dir, dist, FluidCollisionMode.NEVER, true);
        return hit == null;
    }

    // Фидбек 2026-08-22 (живой тест) — "убрать слепую зону, где перестаёт
    // стрелять при 1-2 блоках радиуса (в упор)". Причина: угол = atan2(vertical,
    // horizontal) — при маленьком horizontal (цель почти вплотную) даже
    // небольшая разница высот даёт угол, близкий к -90°, и ложно улетает в
    // "слепую зону снизу", хотя цель физически в упор и явно видна. Ниже
    // этого горизонтального расстояния угол в принципе не может быть надёжным
    // сигналом "снизу ли цель" — просто не проверяем его вообще.
    private static final double BLIND_SPOT_MIN_HORIZONTAL = 3.0;

    /** Слепая зона снизу: угол между направлением на цель и горизонтом не должен быть ниже -blindSpotDegrees. Не проверяется в упор (см. BLIND_SPOT_MIN_HORIZONTAL). */
    private boolean isWithinFiringAngle(Location origin, Location target, double blindSpotDegrees) {
        Vector to = target.toVector().subtract(origin.toVector());
        double horizontal = Math.hypot(to.getX(), to.getZ());
        if (horizontal < BLIND_SPOT_MIN_HORIZONTAL) return true; // в упор — угол ненадёжен, не блокируем
        double verticalAngleDeg = Math.toDegrees(Math.atan2(to.getY(), horizontal));
        return verticalAngleDeg >= -blindSpotDegrees;
    }

    // GH#29 (фидбек раунд 4) — spawnLoc теперь передаётся готовым из tick()
    // (уже с поднятием на 1.2 блока над якорем) — та же точка, от которой
    // findTargets() выше проверял LOS/угол/дальность, не пересчитывается
    // здесь заново. Раньше shoot() сам прибавлял смещение к origin — ЭТО и
    // была причина расхождения (см. тот же класс бага, п.1: два разных
    // отсчёта для "видит" и "стреляет").
    private void shoot(Location spawnLoc, Location anchor, Player target, MilitaryCfg.CrossbowCfg cfg, String canonicalCountry, int effectsLevel, int chanceLevel, int accuracyLevel) {
        Vector direction = target.getEyeLocation().toVector().subtract(spawnLoc.toVector()).normalize();
        // GH#29 (фидбек раунд 2) — реальный разброс вместо идеальной прямой.
        direction = applySpread(direction, INACCURACY_DEGREES[accuracyLevel]);
        Arrow arrow = (Arrow) spawnLoc.getWorld().spawnEntity(spawnLoc, EntityType.ARROW);
        arrow.setVelocity(direction.multiply(ARROW_SPEED));
        arrow.setDamage(cfg.damage());
        // Живой тест 2026-08-22 — метим стрелу, чтобы onArrowHit ниже отличал
        // её от любой другой стрелы на сервере (игрока, скелета и т.д.); в
        // значении несём координаты якоря — onArrowHit восстанавливает
        // именно этот блок, если взрыв случайно его задел (spawnLoc всего в
        // 1.2 блока от анкера, задеть легко).
        arrow.setMetadata(ARROW_METADATA_KEY, new FixedMetadataValue(plugin(), anchor.clone()));

        if (effectsLevel >= 1 && ThreadLocalRandom.current().nextDouble() < EFFECT_CHANCE[chanceLevel]) {
            EffectPreset preset = resolveActivePreset(canonicalCountry);
            if (preset != null) {
                EffectSpec spec = preset.specs().get(ThreadLocalRandom.current().nextInt(preset.specs().size()));
                arrow.addCustomEffect(new PotionEffect(spec.type(), EFFECT_DURATION_TICKS, spec.amplifier()), true);
            } else {
                // Ничего не выбрано (choiceId ещё не установлен) — старое
                // поведение как фоллбек: случайный эффект из пула по уровню.
                List<PotionEffectType> pool = effectsLevel >= 2 ? FULL_EFFECT_POOL : WEAK_EFFECT_POOL;
                PotionEffectType type = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                int amplifier = effectsLevel >= 2 ? 1 : 0;
                arrow.addCustomEffect(new PotionEffect(type, EFFECT_DURATION_TICKS, amplifier), true);
            }
        }
    }

    /**
     * Какой из выбираемых наборов эффектов сейчас активен у страны — первый,
     * чья permission-нода реально выдана (см. класс-javadoc).
     *
     * GH#29 (фидбек раунд 3) — "изменение в дропдауне ничего не делает на
     * сервере". Реальная причина: UpgradeGrantPoller.applyBases ВСЕГДА
     * добавляет ".<level>" к каждой grantBases-ноде, включая choice-ноды —
     * то есть реально выдаётся "unity.military.crossbow_effect.weak_slow.1"
     * (число — уровень самого узла "Эффекты"), а не голое
     * "unity.military.crossbow_effect.weak_slow" без суффикса, которое
     * проверял countryHasNode здесь. Проверка ВСЕГДА проваливалась, код
     * ВСЕГДА уходил в фоллбек (случайный эффект из WEAK_EFFECT_POOL — там
     * один Slowness), что и выглядело как "дропдаун ничего не меняет,
     * всегда даёт то, что было по умолчанию". countryMaxLevel проверяет
     * именно нумерованные ноды — с этим уже находит реально выданную.
     */
    private EffectPreset resolveActivePreset(String canonicalCountry) {
        if (canonicalCountry == null) return null;
        for (EffectPreset preset : EFFECT_PRESETS) {
            if (UpgradeCondition.countryMaxLevel(canonicalCountry, preset.permission(), 5) > 0) return preset;
        }
        return null;
    }

    /** Случайно отклоняет направление в пределах конуса maxDegrees — равномерно по площади (sqrt), не только по углу, иначе выстрелы кучкуются у центра. */
    private Vector applySpread(Vector dir, double maxDegrees) {
        if (maxDegrees <= 0) return dir;
        Vector arbitrary = Math.abs(dir.getY()) < 0.99 ? new Vector(0, 1, 0) : new Vector(1, 0, 0);
        Vector right = dir.clone().crossProduct(arbitrary).normalize();
        Vector up = dir.clone().crossProduct(right).normalize();

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = Math.toRadians(maxDegrees) * Math.sqrt(rnd.nextDouble());
        double rotation = rnd.nextDouble() * 2 * Math.PI;
        double spreadMagnitude = Math.tan(angle);

        Vector offset = right.multiply(Math.cos(rotation) * spreadMagnitude).add(up.multiply(Math.sin(rotation) * spreadMagnitude));
        return dir.clone().add(offset).normalize();
    }

    /**
     * Живой тест 2026-08-22 — "поставить блок над колоколом безнаказанно
     * глушит выстрел" (спавн-точка та же самая, см. javadoc п.6/tick()) —
     * вместо переноса точки спавна стрела сама прочищает себе дорогу:
     * попала в блок — блок подрывается (небольшой ванильный взрыв, не
     * кратер), стрела считается потраченной ("сгорела") и убирается.
     * Следующий залп с той же spawnLoc уже летит свободно. Метка
     * ARROW_METADATA_KEY отсекает чужие стрелы (игроков, скелетов и т.д.) —
     * этот хендлер реагирует только на выстрелы этого класса.
     *
     * Живой тест 2026-08-22 (раунд 2) — "колокол может попасть сам в себя и
     * себя взорвать": spawnLoc всего в 1.2 блока над анкером — взрыв
     * (BLOCK_CLEAR_EXPLOSION_POWER, breakBlocks=true) вполне может задеть и
     * сам блок якоря. Значение метки теперь несёт координаты анкера —
     * восстанавливаем его безусловно после взрыва, тем же приёмом, что и
     * completeSabotage.
     */
    @EventHandler
    public void onArrowHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow arrow)) return;
        if (!arrow.hasMetadata(ARROW_METADATA_KEY)) return;
        Location anchor = (Location) arrow.getMetadata(ARROW_METADATA_KEY).get(0).value();

        Block hitBlock = e.getHitBlock();
        if (hitBlock != null) {
            clearBlockingBlock(hitBlock.getLocation().add(0.5, 0.5, 0.5), anchor);
        }
        arrow.remove();
    }

    /** Небольшой ванильный взрыв, расчищающий путь стрелы — с безусловным восстановлением якоря-колокола, если взрыв случайно его задел (см. onArrowHit/tick()). */
    private void clearBlockingBlock(Location loc, Location anchor) {
        World w = loc.getWorld();
        if (w == null) return;
        w.createExplosion(loc, BLOCK_CLEAR_EXPLOSION_POWER, false, true);
        if (anchor != null && anchor.getWorld() != null && anchor.getBlock().getType() != Material.BELL) {
            anchor.getBlock().setType(Material.BELL);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastShotByZone.clear();
    }
}
