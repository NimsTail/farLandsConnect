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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// infra/military-diplomacy-design.md GH#24 идея 1 "Живой оборонительный
// пост" — в отличие от DefensePatrolUpgrade (постоянный патруль), этот
// работает по триггеру: враг ТОЛЬКО ЧТО пересёк границу объекта (переход
// "не было рядом" -> "стал рядом" на предыдущем/текущем тике, не факт
// нахождения каждый тик) — спавнит одну волну и уходит на кулдаун.
//
// GH#30 — доработка по фидбеку (раунд 1):
//  1. Дроп/опыт уже были отключены с самого начала (onDeath ниже).
//  2. Мобы волны получают каждый свою случайную точку внутри реальной
//     территории объекта (rejection sampling по AABB + contains2D).
//  3. И триггер, и таргетинг заспавненных мобов требуют реальной войны.
//  4. "Интенсивность" волны зависит от числа онлайн-защитников страны.
//
// GH#30 (раунд 2) — по фидбеку "мобы дают дроп и опыт" (пункт 2, при том что
// onDeath их явно чистит) и "накидай пачки мобов сам":
//  5. Состав волны — теперь настоящий выбор страны (см. 3_militaryLiveDefensePack
//     choices в seed-данных): страна выбирает конкретную "пачку" мобов,
//     разблокированную уровнем узла; без выбора — старый фоллбек (1 моб по
//     биому). Наборы — на моё усмотрение по примеру, который дал пользователь
//     в фидбеке (зомби/скелеты на 1 уровне, усиленные варианты на 2-3).
//  6. onDeath поднят до EventPriority.HIGHEST — если другой плагин слушает
//     EntityDeathEvent на более раннем приоритете и что-то добавляет в
//     drops/exp уже ПОСЛЕ нашей чистки, это шло бы вразрез с "без дропа/
//     опыта"; на HIGHEST мы почти наверняка последние. Код самой очистки не
//     менялся — он был технически верным с самого начала, так что если баг
//     воспроизводился именно на СВЕЖЕМ джарнике (не предыдущем, ещё не
//     учитывающем фиксы этой сессии), тут может быть именно конфликт с
//     другим плагином/приоритетом, а не логическая ошибка в самой очистке.
//  7. Мобы поста теперь именованы (видимый CustomName) — видно, что это
//     защита объекта, не случайный моб.
//
// GH#30 (раунд 3) — по фидбеку "не понятно как триггерится... время между
// волнами будто не существует", "шанс на лучшую экипировку", "мобы должны
// не гореть (шапка любого тира)":
//  8. Детекция врага переведена с "радиус вокруг центра" (detectRadius,
//     дефолт всего 6 блоков — почти всегда мимо реальных границ объекта) на
//     реальную территорию объекта (z.contains2D), тот же полигон, что уже
//     использует randomPointIn(). Заодно ушла вся модель "только момент
//     пересечения границы": пока враг физически внутри территории, волна
//     теперь просто повторяется на кулдауне — раньше, застряв в маленьком
//     detectRadius без выхода/входа, новая волна не приходила вообще
//     (именно это и читалось как "кулдаун не существует").
//  9. Зомби/скелеты волны ВСЕГДА получают шлем случайного тира (кожа →
//     незерит, взвешенно по редкости) — защита от сгорания на солнце,
//     безусловно, не зависит от апгрейда экипировки ниже.
//  10. Новый параметрический апгрейд "шанс лучшей экипировки"
//     (3_militaryLiveDefenseGear) — на успешный бросок мобу дополнительно
//     достаётся полный комплект брони одного тира (перекрывает шлем п.9) +
//     зачарованное оружие в основную руку. Per-pack armorChance убран
//     совсем (был зафиксирован по пачке, теперь общий параметр апгрейда) —
//     пачка "zombie_armored" заменена на "mixed_patrol" (фидбек: "убрать те,
//     что с шансом на броню, заменить чем-нибудь").
//
// GH#30 (раунд 4) — по свежему фидбеку:
//  11. Скелеты почти всегда получали меч вместо лука (шанс экипировки
//     безусловно перезаписывал основную руку) — теперь лук остаётся
//     основным оружием скелета, меч — редкое исключение (~35%), а не
//     наоборот.
//  12. Лимит одновременно живых мобов поста (MAX_ALIVE) —
//     раньше волны накапливались без ограничения, если их не выбивали
//     достаточно быстро ("быстро превращается в хаос"). Тот же принцип,
//     что у DefensePatrolUpgrade.maxAlive, просто для волнового спавна:
//     новая волна не спавнится сверх лимита, размер обрезается до
//     оставшегося места.
//  13. Кулдаун между волнами больше не фиксированное число — зависит от
//     "мощности" пачки (состав + уровень апгрейда экипировки): тяжелее
//     пачка/лучше броня → дольше нужно ждать следующую волну. Апгрейд
//     "время между спавном" по-прежнему режет этот кулдаун в процентах —
//     просто теперь от переменной базы, а не от одной и той же цифры
//     для любой пачки.
//  14. Шанс "лучшей экипировки" переработан в три вложенных порога вместо
//     одного "всё или ничего": хотя бы 1 доп. слот брони (не считая
//     шлем) / все 3 доп. слота / все 3 доп. слота гарантированно топового
//     тира (алмаз/незерит) — вместо единого шанса на "полный комплект
//     случайного тира" сразу на 75%, что ощущалось как слишком щедро.
public final class LiveDefensePostUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.live_defense");
    private static final String META_KEY = "unityLiveDefensePost";
    private static final String MOB_NAME = "§c⚔ Оборона поста";

    // GH#30 п.4 — черновые числа: при 0 онлайн-защитников волна получает
    // +1 моба сверху; каждый онлайн-защитник после первого снимает по
    // одному мобу с волны, но не ниже MIN_WAVE_SIZE.
    private static final int MIN_WAVE_SIZE = 1;

    // GH#30 п.2 — сколько раз пробовать случайную точку внутри AABB, прежде
    // чем сдаться и упасть на center() (полигон может занимать малую долю
    // своего же bounding box — L-образные/вытянутые территории).
    private static final int RANDOM_POINT_ATTEMPTS = 12;

    // GH#30 (Улучшения п.2) "Время между спавном" — независимый
    // параметрический апгрейд, множитель % сверху динамической базы (см.
    // раунд 4, п.13 — база теперь зависит от мощности пачки, не от одной
    // и той же cfg.cooldownTicks() для любой пачки).
    private static final String COOLDOWN_PERM_BASE = "unity.military.live_defense_cooldown";
    private static final double[] COOLDOWN_MULTIPLIER = {1.0, 0.75, 0.55};

    // GH#30 (раунд 2, Улучшения п.1) "Состав волны" — permission-выбор, тот
    // же приём, что и у CrossbowUpgrade.EFFECT_PRESETS (см. её javadoc).
    // Наборы подобраны по примеру из фидбека: 1 уровень — простые
    // зомби/скелеты, 2 — усиленные варианты, 3 — ведьмы/разбойники.
    // GH#30 (раунд 3) — armorChance убран из MobSpec: экипировка теперь
    // общий параметрический апгрейд (см. GEAR_PERM_BASE ниже), не свойство
    // конкретной пачки. "zombie_armored" (была отличима только шансом на
    // броню) заменена на "mixed_patrol" — смешанный состав как отличие.
    private record MobSpec(EntityType type, int count, boolean baby) {}
    private record Pack(String permission, List<MobSpec> mobs) {}
    private static final List<Pack> PACKS = List.of(
            new Pack("unity.military.live_defense_pack.zombie_pack", List.of(new MobSpec(EntityType.ZOMBIE, 3, false))),
            new Pack("unity.military.live_defense_pack.skeleton_small", List.of(new MobSpec(EntityType.SKELETON, 2, false))),
            new Pack("unity.military.live_defense_pack.skeleton_large", List.of(new MobSpec(EntityType.SKELETON, 4, false))),
            new Pack("unity.military.live_defense_pack.mixed_patrol", List.of(
                    new MobSpec(EntityType.ZOMBIE, 2, false), new MobSpec(EntityType.SKELETON, 2, false))),
            new Pack("unity.military.live_defense_pack.witch_duo", List.of(
                    new MobSpec(EntityType.WITCH, 2, false), new MobSpec(EntityType.ZOMBIE, 2, false))),
            new Pack("unity.military.live_defense_pack.raid_mix", List.of(
                    new MobSpec(EntityType.WITCH, 1, false), new MobSpec(EntityType.PILLAGER, 2, false), new MobSpec(EntityType.ZOMBIE, 3, true)))
    );

    // GH#30 (раунд 3→4, Улучшения п.2) "Шанс лучшей экипировки" —
    // независимый параметрический апгрейд поверх пачки. Раунд 4: вместо
    // одного шанса на "всё или ничего" — три вложенных порога (см. п.14
    // класс-javadoc). Индекс массива — уровень апгрейда (0 = не куплено).
    // Пороги НЕ складываются — это один бросок 0..100, ниже atLeastOne%
    // ничего, ниже three% — минимум 1 доп. слот, ниже full% — минимум 3,
    // иначе (< full%) — 3 доп. слота гарантированно топового тира.
    private static final String GEAR_PERM_BASE = "unity.military.live_defense_gear";
    private static final double[] GEAR_AT_LEAST_ONE_CHANCE = {0.0, 0.35, 0.55, 0.75};
    private static final double[] GEAR_THREE_CHANCE = {0.0, 0.12, 0.25, 0.40};
    private static final double[] GEAR_FULL_CHANCE = {0.0, 0.03, 0.08, 0.15};

    // GH#30 (раунд 4, п.13) — кулдаун между волнами больше не фиксированная
    // cfg.cooldownTicks() — база теперь зависит от "мощности" пачки (состав
    // + уровень экипировки), апгрейд "время между спавном" режет её в %
    // сверху, как и раньше. Черновые числа — баланс подбирается по факту.
    private static final double POWER_ZOMBIE = 1.0;
    private static final double POWER_SKELETON = 1.0;
    private static final double POWER_WITCH = 2.5;
    private static final double POWER_PILLAGER = 2.0;
    private static final double POWER_PER_GEAR_LEVEL = 3.0; // "если появится броня — растёт кулдаун"
    private static final double BASE_COOLDOWN_SECONDS = 12.0;
    private static final double POWER_TO_SECONDS = 1.3;

    // GH#30 (раунд 4, п.2) — "лимит по которому пост не спавнит больше
    // мобов, пока N ещё живое". Считает по максимально возможному составу
    // волны (raid_mix, 6 юнитов) плюс небольшой запас — не жёсткий потолок
    // ровно под одну волну, чтобы не резать её саму себя.
    private static final int MAX_ALIVE = 8;

    // GH#30 (раунд 3, п.3) — "мобы должны гореть с шапкой... хоть кожаной,
    // хоть незеритовой" — веса убывают с редкостью тира, но шанс никогда 0.
    private static final Material[] ARMOR_TIER_HELMET = {
            Material.LEATHER_HELMET, Material.GOLDEN_HELMET, Material.CHAINMAIL_HELMET,
            Material.IRON_HELMET, Material.DIAMOND_HELMET, Material.NETHERITE_HELMET,
    };
    private static final Material[] ARMOR_TIER_CHEST = {
            Material.LEATHER_CHESTPLATE, Material.GOLDEN_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE,
            Material.IRON_CHESTPLATE, Material.DIAMOND_CHESTPLATE, Material.NETHERITE_CHESTPLATE,
    };
    private static final Material[] ARMOR_TIER_LEGS = {
            Material.LEATHER_LEGGINGS, Material.GOLDEN_LEGGINGS, Material.CHAINMAIL_LEGGINGS,
            Material.IRON_LEGGINGS, Material.DIAMOND_LEGGINGS, Material.NETHERITE_LEGGINGS,
    };
    private static final Material[] ARMOR_TIER_BOOTS = {
            Material.LEATHER_BOOTS, Material.GOLDEN_BOOTS, Material.CHAINMAIL_BOOTS,
            Material.IRON_BOOTS, Material.DIAMOND_BOOTS, Material.NETHERITE_BOOTS,
    };
    private static final double[] ARMOR_TIER_WEIGHTS = {40, 25, 15, 12, 6, 2}; // редкость растёт слева направо

    private static int pickArmorTierIndex() {
        double total = 0;
        for (double w : ARMOR_TIER_WEIGHTS) total += w;
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        double acc = 0;
        for (int i = 0; i < ARMOR_TIER_WEIGHTS.length; i++) {
            acc += ARMOR_TIER_WEIGHTS[i];
            if (roll <= acc) return i;
        }
        return ARMOR_TIER_WEIGHTS.length - 1;
    }

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private final Map<String, Long> lastTriggerByZone = new ConcurrentHashMap<>();
    // GH#30 (раунд 4, п.2) — живые мобы текущей/прошлых волн этого объекта,
    // тот же приём, что и guardsByZone у DefensePatrolUpgrade.
    private final Map<String, List<java.util.UUID>> aliveByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.LiveDefenseCfg cfg = ctx.config().military().liveDefense();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().liveDefense();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/LiveDefensePost] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.LiveDefenseCfg cfg) {
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;

        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            // GH#24 (фидбек 2026-08-14 п.1/4) — раньше запускался на ЛЮБОЙ
            // DEFENSE-зоне страны, купившей этот тип, разом со всеми
            // остальными; теперь только на той, что реально вкачана именно
            // в LIVE_DEFENSE (см. MilitaryDefenseSubtypeService).
            if (!subtypeService.isActiveAs(z, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.LIVE_DEFENSE)) continue;

            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            if (canonicalCountry == null) continue; // объект без страны — быть не должно, но на всякий случай

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            String countryName = z.getCountryName();
            String markerId = z.getMarkerID();
            // GH#30 (раунд 3, п.8) — "враг" требует реальной войны (как и
            // раньше), но теперь проверяется реальное нахождение в
            // территории объекта (contains2D), не расстояние до центра —
            // detectRadius (дефолт 6 блоков) почти всегда не совпадал с
            // реальными границами полигона объекта, отсюда "вход-выход не
            // работает" из прошлого раунда фидбека.
            boolean enemyPresent = false;
            for (Player p : center.getWorld().getPlayers()) {
                if (!z.contains2D(p.getLocation())) continue;
                String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry == null || playerCountry.equalsIgnoreCase(countryName)) continue; // не враг
                if (!UnityLauncher.getInstance().warStatusCache.isAtWar(countryName, playerCountry)) continue; // не воюем — не враг
                enemyPresent = true;
                break;
            }
            if (!enemyPresent) continue;

            // GH#30 (раунд 4, п.2) — не спавним поверх ещё не выбитой волны
            // сверх лимита; сначала чистим протухшие записи (мобы, которых
            // уже нет/убиты), потом проверяем остаток места.
            List<java.util.UUID> alive = aliveByZone.computeIfAbsent(markerId, k -> new ArrayList<>());
            alive.removeIf(id -> {
                var e = Bukkit.getEntity(id);
                return e == null || !e.isValid();
            });
            int freeSlots = MAX_ALIVE - alive.size();
            if (freeSlots <= 0) continue;

            // GH#30 (раунд 4, п.13) — состав пачки нужен уже здесь: кулдаун
            // зависит от её "мощности", не только от cfg/апгрейда.
            int gearLevel = UpgradeCondition.countryMaxLevel(canonicalCountry, GEAR_PERM_BASE, GEAR_AT_LEAST_ONE_CHANCE.length - 1);
            List<MobSpec> mobs = resolveActivePack(canonicalCountry);
            if (mobs == null) {
                // Ничего не выбрано (узел не куплен вообще, или куплен но
                // выбор ещё не сделан) — старый фоллбек: 1 моб по биому.
                mobs = List.of(new MobSpec(DefensePatrolBiomeMob.forBiome(center), 1, false));
            }

            // GH#30 (раунд 3, п.8) — раньше волна спавнилась ТОЛЬКО в
            // момент "только что пересёк границу" (сработавший 1 раз, пока
            // враг не покинул detectRadius и не вошёл заново) — на практике
            // читалось как "кулдаун между волнами не существует", раз враг,
            // сражаясь с постом, обычно с объекта никуда не уходит. Теперь —
            // обычный периодический повтор на кулдауне, пока враг физически
            // в территории, тот же принцип, что у общего DefensePatrolUpgrade.
            long now = System.currentTimeMillis();
            Long lastTrigger = lastTriggerByZone.get(markerId);
            int cooldownLevel = UpgradeCondition.countryMaxLevel(canonicalCountry, COOLDOWN_PERM_BASE, 2);
            double dynamicBaseSeconds = BASE_COOLDOWN_SECONDS + POWER_TO_SECONDS * (packPower(mobs) + gearLevel * POWER_PER_GEAR_LEVEL);
            long cooldownMs = Math.round(dynamicBaseSeconds * 1000 * COOLDOWN_MULTIPLIER[cooldownLevel]);
            if (lastTrigger != null && now - lastTrigger < cooldownMs) continue;

            lastTriggerByZone.put(markerId, now);
            List<java.util.UUID> spawned = spawnWave(z, center, markerId, countryName, mobs, gearLevel, freeSlots);
            alive.addAll(spawned);
        }
    }

    /** GH#30 (раунд 4, п.13) — суммарная "мощность" пачки, определяет базовый кулдаун до применения % апгрейда. */
    private double packPower(List<MobSpec> mobs) {
        double power = 0;
        for (MobSpec spec : mobs) {
            double unit = switch (spec.type()) {
                case WITCH -> POWER_WITCH;
                case PILLAGER -> POWER_PILLAGER;
                case SKELETON -> POWER_SKELETON;
                default -> POWER_ZOMBIE;
            };
            power += unit * spec.count();
        }
        return power;
    }

    private List<java.util.UUID> spawnWave(ZoneInfo z, Location center, String markerId, String countryName, List<MobSpec> mobs, int gearLevel, int capThisWave) {
        // Разворачиваем спецификацию пачки в плоский список отдельных мобов —
        // дальше интенсивность по онлайну применяется к нему целиком, не
        // заботясь о том, откуда список взялся (пачка или фоллбек).
        List<MobSpec> flatUnits = new ArrayList<>();
        for (MobSpec spec : mobs) {
            for (int i = 0; i < spec.count(); i++) flatUnits.add(new MobSpec(spec.type(), 1, spec.baby()));
        }

        // GH#30 п.4 — интенсивность волны обратно пропорциональна числу
        // онлайн-защитников: некому защищаться -> пост усиливается; чем
        // больше живых граждан на связи, тем меньше буст (но не ниже
        // MIN_WAVE_SIZE, чтобы пост не пропадал вовсе).
        int ownersOnline = (int) Bukkit.getOnlinePlayers().stream()
                .filter(p -> countryName.equalsIgnoreCase(UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName())))
                .count();
        if (ownersOnline == 0) {
            flatUnits.add(flatUnits.get(0)); // никто не защищается — усиление волны на 1 моба
        } else if (ownersOnline > 1) {
            int toRemove = Math.min(flatUnits.size() - MIN_WAVE_SIZE, ownersOnline - 1);
            for (int i = 0; i < toRemove; i++) flatUnits.remove(flatUnits.size() - 1);
        }

        // GH#30 (раунд 4, п.2) — обрезаем волну под оставшееся место
        // (MAX_ALIVE минус уже живые), а не только по интенсивности выше.
        while (flatUnits.size() > capThisWave) flatUnits.remove(flatUnits.size() - 1);

        List<java.util.UUID> spawned = new ArrayList<>(flatUnits.size());
        for (MobSpec spec : flatUnits) {
            // GH#30 п.2 — своя случайная точка внутри территории объекта
            // для каждого моба волны, не общий center на всех.
            Location spawnLoc = randomPointIn(z, center);
            Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, spec.type());
            if (!(e instanceof LivingEntity mob)) continue;

            mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), markerId));
            mob.setRemoveWhenFarAway(true);
            // GH#30 (раунд 2, п.3) — видимое имя, чтобы отличать от случайных мобов.
            mob.setCustomName(MOB_NAME);
            mob.setCustomNameVisible(true);
            spawned.add(mob.getUniqueId());

            if (spec.baby() && mob instanceof Zombie zombie) zombie.setBaby(true);

            // GH#30 (раунд 3, п.9) — зомби/скелеты обязательно в шапке
            // (любого тира) — без неё горят на солнце. Безусловно, не
            // завязано на апгрейд экипировки ниже.
            boolean burnsInDaylight = spec.type() == EntityType.ZOMBIE || spec.type() == EntityType.SKELETON;
            if (burnsInDaylight) equipHelmet(mob, pickArmorTierIndex());

            // GH#30 (раунд 4, п.14) — три вложенных порога вместо одного
            // "всё или ничего": роль одного случайного числа против трёх
            // возрастающе строгих планок (см. константы выше).
            if (gearLevel > 0) {
                double roll = ThreadLocalRandom.current().nextDouble();
                if (roll < GEAR_AT_LEAST_ONE_CHANCE[gearLevel]) {
                    boolean guaranteedTopTier = roll < GEAR_FULL_CHANCE[gearLevel]; // top nested tier — implies "three" too
                    int extraPieces = roll < GEAR_THREE_CHANCE[gearLevel] ? 3 : 1;
                    int tierIndex = guaranteedTopTier ? pickTopArmorTierIndex() : pickArmorTierIndex();
                    equipExtraArmor(mob, tierIndex, extraPieces);
                    equipEnchantedWeapon(mob, spec.type());
                }
            }

            if (spec.type() == EntityType.PILLAGER) {
                EntityEquipment eq = mob.getEquipment();
                if (eq != null) eq.setItemInMainHand(new ItemStack(Material.CROSSBOW));
            }
        }
        return spawned;
    }

    /**
     * Какая "пачка" сейчас выбрана страной — первая, чья permission-нода
     * реально выдана (см. класс-javadoc). Null, если ничего не выбрано/не
     * куплено.
     *
     * GH#29/#30 (тот же баг раунда 3, что и у CrossbowUpgrade — см. её
     * resolveActivePreset) — UpgradeGrantPoller.applyBases всегда добавляет
     * ".<level>" к choice-нодам тоже, countryHasNode(голая строка) поэтому
     * никогда не находил реально выданную; countryMaxLevel проверяет
     * нумерованные варианты и находит.
     */
    private List<MobSpec> resolveActivePack(String canonicalCountry) {
        if (canonicalCountry == null) return null;
        for (Pack pack : PACKS) {
            if (UpgradeCondition.countryMaxLevel(canonicalCountry, pack.permission(), 5) > 0) return pack.mobs();
        }
        return null;
    }

    /** GH#30 (раунд 3, п.9) — только шлем, случайный тир по индексу (см. pickArmorTierIndex). */
    private void equipHelmet(LivingEntity mob, int tierIndex) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setHelmet(new ItemStack(ARMOR_TIER_HELMET[tierIndex]));
    }

    /**
     * GH#30 (раунд 4, п.14) — 1 или 3 доп. слота брони (сверх обязательного
     * шлема, который уже стоит своего тира — перекрывается тут тем же
     * tierIndex для визуальной цельности комплекта). 1 слот — нагрудник
     * (самая заметная защита); 3 — весь корпус (нагрудник+поножи+ботинки).
     */
    private void equipExtraArmor(LivingEntity mob, int tierIndex, int extraPieces) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setHelmet(new ItemStack(ARMOR_TIER_HELMET[tierIndex]));
        eq.setChestplate(new ItemStack(ARMOR_TIER_CHEST[tierIndex]));
        if (extraPieces >= 3) {
            eq.setLeggings(new ItemStack(ARMOR_TIER_LEGS[tierIndex]));
            eq.setBoots(new ItemStack(ARMOR_TIER_BOOTS[tierIndex]));
        }
    }

    // GH#30 (раунд 4, п.14) — "фулл сет" гарантированно топ-2 тира
    // (алмаз/незерит), а не по общей взвешенной редкости — отличает эту
    // планку от простого "3 доп. слота" случайного тира качественно, не
    // только по количеству. Индексы 4 (алмаз)/5 (незерит) в ARMOR_TIER_*.
    private static final double[] TOP_TIER_WEIGHTS = {70, 30};

    private static int pickTopArmorTierIndex() {
        // Индексы 4 (алмаз) и 5 (незерит) в ARMOR_TIER_* массивах.
        double roll = ThreadLocalRandom.current().nextDouble() * (TOP_TIER_WEIGHTS[0] + TOP_TIER_WEIGHTS[1]);
        return roll < TOP_TIER_WEIGHTS[0] ? 4 : 5;
    }

    /**
     * GH#30 (раунд 3, п.10 → раунд 4, п.1) — зачарованное оружие в основную
     * руку. Скелет по умолчанию и без нас спавнится с луком — раньше это
     * безусловно перезаписывалось мечом ("скелеты с мечами это круто, но
     * сделать так, чтобы в основном спавнились с луками"): теперь лук —
     * основной вариант (~65%), меч — редкое исключение (~35%). Остальные
     * типы (зомби/ведьма) как и раньше — меч.
     */
    private static final double SKELETON_SWORD_CHANCE = 0.35;

    private void equipEnchantedWeapon(LivingEntity mob, EntityType type) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;

        boolean giveSword = type != EntityType.SKELETON || ThreadLocalRandom.current().nextDouble() < SKELETON_SWORD_CHANCE;
        if (!giveSword) {
            ItemStack bow = new ItemStack(Material.BOW);
            bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, 1 + ThreadLocalRandom.current().nextInt(2));
            eq.setItemInMainHand(bow);
            return;
        }

        ItemStack sword = new ItemStack(Material.IRON_SWORD);
        sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.SHARPNESS, 1 + ThreadLocalRandom.current().nextInt(2));
        if (ThreadLocalRandom.current().nextBoolean()) {
            sword.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.KNOCKBACK, 1);
        }
        eq.setItemInMainHand(sword);
    }

    /** Случайная точка внутри реальной территории зоны (rejection sampling по AABB), с фоллбеком на center при неудаче/маленьком полигоне. */
    private Location randomPointIn(ZoneInfo z, Location fallbackCenter) {
        World w = z.getWorld();
        if (w == null) return fallbackCenter;

        BoundingBox bb = z.getBoundingBoxXZ();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++) {
            double x = bb.getMinX() + rnd.nextDouble() * (bb.getMaxX() - bb.getMinX());
            double zc = bb.getMinZ() + rnd.nextDouble() * (bb.getMaxZ() - bb.getMinZ());
            int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(zc));
            Location candidate = new Location(w, x, y, zc);
            if (z.contains2D(candidate)) return candidate;
        }
        return fallbackCenter;
    }

    /** Без дропа/опыта — это защита объекта, не источник фарма (GH#24). HIGHEST — см. класс-javadoc п.6. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getMetadata(META_KEY).isEmpty()) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    // GH#30 п.3 — мобы поста атакуют только игроков страны, с которой у
    // владельца объекта реально идёт война (не просто "не свой").
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        List<org.bukkit.metadata.MetadataValue> meta = e.getEntity().getMetadata(META_KEY);
        if (meta.isEmpty()) return;

        String markerId = meta.get(0).asString();
        String guardCountry = zones().getAllZonesSnapshot().stream()
                .filter(z -> markerId.equals(z.getMarkerID()))
                .findFirst()
                .map(ZoneInfo::getCountryName)
                .orElse(null);
        if (guardCountry == null) return;

        String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
        boolean isEnemyAtWar = playerCountry != null
                && !playerCountry.equalsIgnoreCase(guardCountry)
                && UnityLauncher.getInstance().warStatusCache.isAtWar(guardCountry, playerCountry);
        if (!isEnemyAtWar) {
            e.setCancelled(true);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastTriggerByZone.clear();
        aliveByZone.clear();
    }

    /** Тот же биомный выбор, что у DefensePatrolUpgrade (§14.2) — вынесено сюда, чтобы не тянуть зависимость между двумя upgrade-классами. */
    private static final class DefensePatrolBiomeMob {
        static EntityType forBiome(Location loc) {
            String name = loc.getBlock().getBiome().name();
            if (name.contains("DESERT")) return EntityType.HUSK;
            if (name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE")) return EntityType.STRAY;
            return Math.random() < 0.5 ? EntityType.ZOMBIE : EntityType.SKELETON;
        }
    }
}
