package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.auth.WarStatusCache;
import com.frammy.unitylauncher.military.MilitaryDefenseSubtype;
import com.frammy.unitylauncher.military.MilitaryDefenseSubtypeService;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * infra/military-diplomacy-design.md §17.10 (2026-08-22) — "Диверсия",
 * единственный способ нейтрализовать оборонительное сооружение (Живой пост/
 * Ореол/Жгучий/Арбалет). Заменяет полностью убранный PVE_WAVES-стрик
 * (см. DefensePatrolUpgrade) и мгновенное BREAK_ANCHOR (см.
 * MilitaryAnchorService.onBreak — для DEFENSE-объектов больше не репортит).
 *
 * Схема (фидбек 2026-08-22 — "не просто стоять рядом, а именно физически
 * ломать"): враг обязан РЕАЛЬНО долбить якорь-колокол (требование анкера
 * введено для всех четырёх подтипов DEFENSE, не только Арбалета — см.
 * MilitaryAnchorService), не просто стоять поблизости. Обычный ванильный
 * слом колокола врагом всегда отменяется (onAnchorBreakAttempt) — вместо
 * этого прогресс копится ДИСКРЕТНО, за каждый реально доломанный до конца
 * блок (раунд 4 живого теста — "не по угадайке сколько времени будет
 * ломать, а по фактическому количеству сколько раз блок был сломанным"):
 * штатное завершение долбёжки = один "слом" = случайные 3-4%; бросил
 * раньше (BlockDamageAbortEvent) — 0%, не засчитано. Тает (decay), если
 * давно не было ни одного засчитанного слома. Майлстоуны на пути бьют по
 * атакующей стороне: 33% — отталкивание, 66% — тьма, 75%+ — периодически
 * занимающийся вокруг огонь (нарастающая цена простоя рядом). 100% —
 * партиклы/звук/толчок + шрам из обсидиана/камня/магмы на месте анкера
 * (реальный физический слом происходит ЗДЕСЬ, программно, не через
 * ванильный BlockBreakEvent — тот у врага всегда отменён) — дальше уже
 * обычный поток (CONTESTED/War Score/иммунитет/платный ремонт,
 * lib/militaryZones.ts), этот класс лишь один раз шлёт репорт.
 *
 * Прогресс показывается actionbar-баром из "|" (цвет по состоянию, серый —
 * незаполненная часть, число % справа) игрокам физически внутри границ
 * этого военного объекта ИЛИ в NEARBY_RADIUS от самого якоря (не от центра
 * зоны — колокол может стоять где угодно внутри неё) — не всему серверу.
 * Если рядом активны сразу несколько сеансов (соседние военные объекты) —
 * показывается статус БЛИЖАЙШЕГО якоря, см. broadcastNearestProgress.
 *
 * Свой собственный колокол владелец по-прежнему ломает/переносит как
 * обычно — перехват работает только для игроков вражеской (по войне)
 * страны, см. onAnchorDigStart/onAnchorBreakAttempt.
 *
 * Без анкера объект физически не нейтрализуется вообще — прямое следствие
 * "если сейчас не требуется, ввести требование" (владелец обязан держать
 * колокол, иначе его сооружение неуязвимо, но и сам он теряет доступ к
 * тому, что раньше требовало анкер — Разведке/Арбалету).
 */
public final class SabotageUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.sabotage");

    // Живой тест 2026-08-22 (раунд 3) — "задержка ощущается" на статусе/
    // партиклах, ниже период тика.
    private static final long TICK_PERIOD_TICKS = 10L; // 0.5с

    // Живой тест 2026-08-22 (раунд 4) — "не по угадайке сколько времени
    // будет ломать игрок, а по фактическому количеству сколько раз блок был
    // сломанным": прогресс раньше копился НЕПРЕРЫВНО за время удержания
    // (сколько ощущается — не проверить). Теперь дискретно: каждый раз,
    // когда игрок реально доламывает якорь до конца (BlockBreakEvent —
    // штатное завершение, которое мы отменяем, см. onAnchorBreakAttempt),
    // засчитывается один "слом" — случайные 3-4%. Если бросил раньше
    // (BlockDamageAbortEvent — см. onAnchorDigAbort) — 0%, не засчитано.
    private static final double PROGRESS_PER_BREAK_MIN = 3.0;
    private static final double PROGRESS_PER_BREAK_MAX = 4.0;

    // "Декай нужен, но держится явно меньше", чем на линии фронта (§17.2/§17.8:
    // там 7 мин/0.4×) — это активный ближний бой, а не пассивная площадь.
    private static final long DECAY_GRACE_MS = 10_000L; // 10с без ЗАСЧИТАННОГО слома — и начинает таять
    private static final double DECAY_PERCENT_PER_SECOND = 0.5; // черновое число, как и весь баланс §17.10

    // Живой тест 2026-08-22 (раунд 4) — "текст превратить в палочки с
    // цветным прогрессом, число % справа. Показывать только в зоне объекта,
    // не всему серверу". PROGRESS_BAR_LENGTH — число символов "|" в баре.
    private static final int PROGRESS_BAR_LENGTH = 20;

    private static final double MILESTONE_KNOCKBACK_AT = 33.0;
    private static final double MILESTONE_DARKNESS_AT = 66.0;
    private static final double MILESTONE_FIRE_AT = 75.0;
    private static final double KNOCKBACK_STRENGTH = 0.5;
    private static final int DARKNESS_TICKS = 20 * 3; // 3с

    // Живой тест 2026-08-22 (раунд 6) — "партиклы пара/angry villager раз в
    // несколько разрушений" — лёгкий, но заметно нарастающий фидбек прямо
    // на месте засчитанных сломов (не путать с EARLY_FEEDBACK_INTERVAL_MS
    // ниже — тот по времени и легче, этот по факту разрушений и гуще).
    //
    // Живой тест 2026-08-22 (раунд 7) — "рандомно раз в 2-5 разрушений, а
    // не каждый 3" — фиксированный модуль давал предсказуемый, механический
    // ритм (ровно на 3, 6, 9...). Порог теперь перебрасывается случайно в
    // диапазоне [MIN, MAX] после каждого срабатывания — см.
    // SabotageState.nextFeedbackBurstThreshold.
    private static final int FEEDBACK_BURST_MIN_BREAKS = 2;
    private static final int FEEDBACK_BURST_MAX_BREAKS = 5;

    // Живой тест 2026-08-22 (раунд 6) — "при финальном взрыве сильнее
    // откидывать игроков". Ванильный createExplosion уже толкает всех в
    // радиусе сам по себе, это ДОПОЛНИТЕЛЬНЫЙ явный импульс поверх него —
    // тот же приём, что и у майлстоуна 33% (KNOCKBACK_STRENGTH), только
    // заметно сильнее и в большем радиусе — это кульминация, а не промежуточный щелчок.
    private static final double FINAL_KNOCKBACK_RADIUS = 8.0;
    private static final double FINAL_KNOCKBACK_STRENGTH = 1.6;

    private static final int FIRE_RADIUS = 2;
    private static final long FIRE_INTERVAL_MS = 4_000L;
    private static final int FIRE_BLOCKS_PER_BURST = 2;

    // "Блоки ставятся как обычные, как шрам" (решено 2026-08-22) — не
    // спец-неразрушимые, обычный OBSIDIAN/STONE/MAGMA_BLOCK, ломаются как
    // угодно.
    private static final Material[] SCAR_MATERIALS = {Material.OBSIDIAN, Material.STONE, Material.MAGMA_BLOCK};

    // Фидбек 2026-08-22 (второй раунд) — "взрыв буквальный, срывать блоки
    // вокруг (радиус до 4 блоков) + наносить урон". Настоящий ванильный
    // взрыв (World.createExplosion) — даёт частицы/звук/урон/отталкивание
    // и естественно рассыпчатое разрушение блоков бесплатно, без ручного
    // кода на каждый из этих пунктов по отдельности. fire=false (у нас уже
    // есть свой контролируемый огонь на 75%+, не нужен второй бесконтрольный
    // пожар), breakBlocks=true.
    private static final float EXPLOSION_POWER = 3.5f; // ~3-4 блока радиуса разрушения, как заряженный крипер

    // Фидбек — "структура более рассыпная, с пиком в центре, а не как
    // столб". Конус: максимальная высота ровно над анкером, тает к краю;
    // density тоже падает к краю — не сплошная заливка, а рассыпчатая куча.
    private static final int MOUND_RADIUS = 3;
    private static final int MOUND_MAX_HEIGHT = 3;

    // Фидбек — "первые 30% ничего не происходит, кроме статуса в баре".
    // Лёгкая, но заметная обратная связь каждые несколько секунд копания,
    // не привязанная к майлстоунам (те начинаются только с 33%).
    private static final long EARLY_FEEDBACK_INTERVAL_MS = 3_000L;

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    // Всегда активна для любого DEFENSE-объекта с назначенным типом — это не
    // отдельно покупаемый апгрейд, а базовое правило "как вообще ломаются
    // оборонительные сооружения", поэтому отдельного enabledByConfig-флага
    // в MilitaryCfg не заводим (в отличие от остальных апгрейдов файла).
    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        return true;
    }

    private static final class SabotageState {
        double progress;
        long lastGrowthAt;
        boolean milestone33;
        boolean milestone66;
        long lastFireAt;
        String attackerCountryName;
        // Фидбек 2026-08-22 — "не просто стоять рядом, а именно физически
        // ломать". Кто сейчас реально долбит якорь (BlockDamageEvent без
        // BlockDamageAbortEvent между) — прогресс растёт, только пока это
        // true, а не просто пока враг где-то рядом.
        String diggingPlayerName;
        boolean diggingActive;
        // Фидбек 2026-08-22 (второй раунд) — "первые 30% ничего не
        // происходит" — таймер лёгкой периодической обратной связи, не
        // привязанной к майлстоунам.
        long lastFeedbackAt;
        // Фидбек 2026-08-22 (раунд 6/7) — "партиклы пара/angry villager
        // рандомно раз в 2-5 разрушений". breaksSinceLastBurst считает
        // ЗАСЧИТАННЫЕ сломы (см. onAnchorBreakAttempt) с последнего залпа
        // партиклов; nextFeedbackBurstThreshold — случайный порог
        // [FEEDBACK_BURST_MIN_BREAKS, MAX], 0 значит "ещё не брошен".
        int breaksSinceLastBurst;
        int nextFeedbackBurstThreshold;
    }

    private final Map<String, SabotageState> statesByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    protected void onEnable() {
        task = org.bukkit.Bukkit.getScheduler().runTaskTimer(plugin(), this::tick, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS);
        if (C().core().debug()) {
            plugin().getLogger().info("[Military/Sabotage] started period=" + TICK_PERIOD_TICKS);
        }
    }

    /** Один активный (progress > 0) сеанс Диверсии — собирается за проход по зонам, показывается игрокам ПОСЛЕ него, см. broadcastNearestProgress. */
    private record ActiveSabotage(ZoneInfo zone, Location anchor, SabotageState state, boolean digging) {}

    private void tick() {
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;
        var countryRegistry = UnityLauncher.getInstance().countryRegistryJdbc;
        var warCache = UnityLauncher.getInstance().warStatusCache;
        long now = System.currentTimeMillis();
        double decayPerTick = DECAY_PERCENT_PER_SECOND * (TICK_PERIOD_TICKS * 50.0 / 1000.0);
        java.util.List<ActiveSabotage> active = new java.util.ArrayList<>();

        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;

            MilitaryDefenseSubtype subtype = activeSubtypeOf(z, subtypeService);
            if (subtype == null) continue; // не DEFENSE / тип не назначен / не куплен

            Location anchor = z.getMilitaryAnchorLocation();
            if (anchor == null || anchor.getWorld() == null) continue; // нет якоря — не нейтрализуется вообще (см. class javadoc)

            String ownerCountryName = z.getCountryName();
            if (ownerCountryName == null) continue;

            String markerId = z.getMarkerID();
            SabotageState state = statesByZone.computeIfAbsent(markerId, k -> new SabotageState());

            // "digging" здесь чисто косметика для actionbar (цвет/глагол) и
            // раннего фидбека — реальный прогресс больше НЕ копится по
            // тикам, только дискретно за засчитанные сломы (см.
            // onAnchorBreakAttempt). Держится true, пока не прилетит
            // onAnchorDigAbort (бросил раньше) или сам сброс после
            // засчитанного слома — новый цикл долбёжки снова выставит true.
            boolean digging = state.diggingActive && state.diggingPlayerName != null
                    && org.bukkit.Bukkit.getPlayerExact(state.diggingPlayerName) != null;

            // Decay — только если давно не было ни одного ЗАСЧИТАННОГО слома
            // (lastGrowthAt обновляется исключительно в onAnchorBreakAttempt).
            if (state.progress > 0 && now - state.lastGrowthAt > DECAY_GRACE_MS) {
                state.progress = Math.max(0.0, state.progress - decayPerTick);
            }

            // Фидбек 2026-08-22 (раунд 5) — не шлём actionbar прямо здесь:
            // рядом может быть другая военная зона с ТОЖЕ активной Диверсией
            // (см. broadcastNearestProgress) — нужно сперва собрать ВСЕ
            // активные сеансы за проход и разрулить между ними после.
            if (state.progress > 0) {
                active.add(new ActiveSabotage(z, anchor, state, digging));
            }

            // Фидбек 2026-08-22 (второй раунд) — "первые 30% даже не понятно,
            // что ты копаешь" — лёгкая частица/звук раз в EARLY_FEEDBACK_INTERVAL_MS
            // ПОКА РЕАЛЬНО КОПАЕТ, независимо от майлстоунов (те стартуют с 33%).
            if (digging && anchor.getWorld() != null && now - state.lastFeedbackAt >= EARLY_FEEDBACK_INTERVAL_MS) {
                state.lastFeedbackAt = now;
                anchor.getWorld().spawnParticle(Particle.CRIT, anchor.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.02);
                anchor.getWorld().playSound(anchor, Sound.BLOCK_BELL_USE, 1.2f, 0.7f);
            }

            applyMilestones(anchor, ownerCountryName, state, now, countryRegistry, warCache);

            if (state.progress >= 100.0) {
                completeSabotage(z, anchor, markerId, state);
            }
        }

        broadcastNearestProgress(active);

        statesByZone.keySet().removeIf(id -> zones().getAllZonesSnapshot().stream().noneMatch(z -> id.equals(z.getMarkerID())));
    }

    // Фидбек 2026-08-22 (раунд 5) — "статус показывать не только в зоне, но
    // и тем, кто в минимальном радиусе — и это должен быть ближайший
    // статус. Сами колокола могут быть не по центру зоны". Радиус — от
    // САМОГО ЯКОРЯ (физической точки колокола), не от зоны/её центра, ровно
    // по этой причине. Черновое число, как и весь баланс §17.10.
    private static final double NEARBY_RADIUS = 24.0;

    /** true, если игроку положено видеть статус Диверсии этой зоны: либо физически внутри её границ, либо в NEARBY_RADIUS от самого якоря (не от центра зоны — якорь может стоять где угодно внутри неё). */
    private boolean isVisibleTo(ZoneInfo z, Location anchor, Player p) {
        Location loc = p.getLocation();
        if (loc.getWorld() == null || anchor.getWorld() == null) return false;
        if (z.contains2D(loc)) return true;
        if (!loc.getWorld().getUID().equals(anchor.getWorld().getUID())) return false;
        return loc.distanceSquared(anchor) <= NEARBY_RADIUS * NEARBY_RADIUS;
    }

    /**
     * Фидбек 2026-08-22 (раунд 5) — "иногда могут быть две зоны рядом и у
     * обоих происходит Диверсия — надо отображать верный". Один игрок может
     * одновременно попадать под видимость НЕСКОЛЬКИХ активных сеансов
     * (например стоит в зоне A, а якорь зоны B рядом за забором, в её
     * NEARBY_RADIUS) — шлём только ОДИН бар, статус БЛИЖАЙШЕГО по факту
     * якоря, а не первый попавшийся/случайный по порядку обхода зон.
     */
    private void broadcastNearestProgress(java.util.List<ActiveSabotage> active) {
        if (active.isEmpty()) return;
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            ActiveSabotage nearest = null;
            double nearestDistSq = Double.MAX_VALUE;
            for (ActiveSabotage a : active) {
                if (!isVisibleTo(a.zone(), a.anchor(), p)) continue;
                double d = p.getLocation().getWorld().getUID().equals(a.anchor().getWorld().getUID())
                        ? p.getLocation().distanceSquared(a.anchor()) : Double.MAX_VALUE;
                if (d < nearestDistSq) {
                    nearestDistSq = d;
                    nearest = a;
                }
            }
            if (nearest != null) sendProgressBar(p, nearest.state(), nearest.digging());
        }
    }

    /**
     * Фидбек 2026-08-22 (раунд 4) — "текст превратить в ||||||||| палочками с
     * цветным прогрессом. Справа число %." Заполненная часть — цвет по
     * состоянию (золото — идёт реальная долбёжка, красный — тает без
     * атаки), незалитая часть — серая.
     */
    private void sendProgressBar(Player p, SabotageState state, boolean digging) {
        if (state.progress <= 0) return; // мог обнулиться этим же тиком (completeSabotage) — не шлём стрелку в никуда

        NamedTextColor filledColor = digging ? NamedTextColor.GOLD : NamedTextColor.RED;
        int filled = (int) Math.round(state.progress / 100.0 * PROGRESS_BAR_LENGTH);
        filled = Math.max(0, Math.min(PROGRESS_BAR_LENGTH, filled));

        Component text = Component.text("⚒ ", filledColor)
                .append(Component.text("|".repeat(filled), filledColor))
                .append(Component.text("|".repeat(PROGRESS_BAR_LENGTH - filled), NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + Math.round(state.progress) + "%", NamedTextColor.WHITE));

        p.sendActionBar(text);
    }

    private MilitaryDefenseSubtype activeSubtypeOf(ZoneInfo z, MilitaryDefenseSubtypeService subtypeService) {
        for (MilitaryDefenseSubtype s : MilitaryDefenseSubtype.values()) {
            if (subtypeService.isActiveAs(z, s)) return s;
        }
        return null;
    }

    private void applyMilestones(Location anchor, String ownerCountryName, SabotageState state, long now, CountryRegistryJdbc countryRegistry, WarStatusCache warCache) {
        // 33% — лёгкое отталкивание (разово на пересечении порога).
        if (state.progress >= MILESTONE_KNOCKBACK_AT && !state.milestone33) {
            state.milestone33 = true;
            for (Player p : nearbyEnemies(anchor, ownerCountryName, 4.0, countryRegistry, warCache)) {
                Vector away = p.getLocation().toVector().subtract(anchor.toVector());
                if (away.lengthSquared() < 1e-4) away = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5, 0, ThreadLocalRandom.current().nextDouble() - 0.5);
                p.setVelocity(away.normalize().multiply(KNOCKBACK_STRENGTH).setY(0.2));
            }
        } else if (state.progress < MILESTONE_KNOCKBACK_AT) {
            state.milestone33 = false; // разрешает повторное срабатывание при новой попытке после отступления
        }

        // 66% — тьма на пару секунд в радиусе.
        if (state.progress >= MILESTONE_DARKNESS_AT && !state.milestone66) {
            state.milestone66 = true;
            for (Player p : nearbyEnemies(anchor, ownerCountryName, 6.0, countryRegistry, warCache)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, DARKNESS_TICKS, 0));
            }
        } else if (state.progress < MILESTONE_DARKNESS_AT) {
            state.milestone66 = false;
        }

        // 75%+ — периодически занимающийся физический огонь вокруг анкера,
        // нарастающая цена простоя рядом, пока не дожал до 100%.
        if (state.progress >= MILESTONE_FIRE_AT && state.progress < 100.0) {
            if (now - state.lastFireAt >= FIRE_INTERVAL_MS) {
                state.lastFireAt = now;
                igniteAround(anchor, FIRE_RADIUS, FIRE_BLOCKS_PER_BURST);
            }
        }
    }

    private java.util.List<Player> nearbyEnemies(Location anchor, String ownerCountryName, double radius, CountryRegistryJdbc countryRegistry, WarStatusCache warCache) {
        java.util.List<Player> out = new java.util.ArrayList<>();
        double r2 = radius * radius;
        for (Player p : anchor.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(anchor) > r2) continue;
            String playerCountry = countryRegistry.getCountryOfPlayer(p.getName());
            if (playerCountry == null || playerCountry.equalsIgnoreCase(ownerCountryName)) continue;
            if (!warCache.isAtWar(ownerCountryName, playerCountry)) continue;
            out.add(p);
        }
        return out;
    }

    /** Физический ванильный огонь — не обязан держаться долго (сгорит по своим правилам), просто регулярные всплески, пока идёт диверсия. */
    private void igniteAround(Location anchor, int radius, int count) {
        World w = anchor.getWorld();
        if (w == null) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int dx = rnd.nextInt(radius * 2 + 1) - radius;
            int dz = rnd.nextInt(radius * 2 + 1) - radius;
            Location loc = anchor.clone().add(dx, 0, dz);
            if (loc.getBlock().getType() == Material.AIR) {
                loc.getBlock().setType(Material.FIRE);
            }
        }
    }

    /**
     * Фидбек 2026-08-22 (второй раунд):
     *  - "блок колокола никуда не пропадает — просто становится неактивным
     *    и покрывается сверху" — сам блок анкера НИКОГДА не входит в
     *    рассыпаемую кучу (явно пропускается), восстанавливается на месте
     *    сразу после взрыва, если тот случайно его задел.
     *  - "взрыв буквальный — срывать блоки вокруг (радиус до 4) + урон" —
     *    настоящий World.createExplosion: частицы/звук/урон/отталкивание
     *    и рассыпчатое разрушение окрестности — всё бесплатно, одним вызовом.
     *  - "структура более рассыпная, с пиком в центре, не как столб" —
     *    после взрыва конусом насыпается куча шрам-материала: максимум
     *    высоты и плотности прямо над анкером, тает к MOUND_RADIUS, с
     *    пропусками (не сплошная заливка).
     */
    private void completeSabotage(ZoneInfo zone, Location anchor, String markerId, SabotageState state) {
        World w = anchor.getWorld();
        if (w != null) {
            // Живой тест 2026-08-22 — ДЮП колокола: ванильный взрыв с
            // breakBlocks=true реально ломает блок анкера, если тот попадает
            // в радиус (а он в самом центре взрыва — попадает всегда) — это
            // настоящий блок-брейк, роняющий физический item колокола в мир.
            // Мы следом БЕЗУСЛОВНО ставили блок BELL обратно — получался и
            // дропнутый item, и восстановленный блок одновременно, то есть
            // колокол буквально дублировался. Убираем блок анкера ДО взрыва
            // (просто setType, не через BlockBreakEvent — без дропа), взрыв
            // всё равно центрируется в той же точке.
            anchor.getBlock().setType(Material.AIR);

            // Настоящий взрыв — частицы/звук/урон+отталкивание нанесёт сам,
            // не нужно вручную. fire=false (свой контролируемый огонь уже
            // есть, см. igniteAround), breakBlocks=true — реально срывает
            // блоки вокруг радиусом ~3-4 (та самая "рассыпчатость" бесплатно).
            w.createExplosion(anchor, EXPLOSION_POWER, false, true);

            // Анкер "просто становится неактивным", не исчезает — теперь без дропа.
            anchor.getBlock().setType(Material.BELL);

            // Доп. партиклы/звук поверх ванильного взрыва — пар/сталь, не дублируем сам взрыв.
            w.spawnParticle(Particle.CLOUD, anchor, 60, 1.2, 1.0, 1.2, 0.06);
            w.playSound(anchor, Sound.BLOCK_LAVA_EXTINGUISH, 3.0f, 1.0f);

            // Живой тест 2026-08-22 (раунд 6) — "при финальном взрыве
            // сильнее откидывать игроков". Ванильный createExplosion уже
            // толкает всех в радиусе сам по себе — это ДОПОЛНИТЕЛЬНЫЙ явный
            // импульс поверх него, тот же приём, что и у майлстоуна 33%
            // (KNOCKBACK_STRENGTH), только заметно сильнее и в большем
            // радиусе — кульминация, а не промежуточный щелчок.
            var countryRegistry = UnityLauncher.getInstance().countryRegistryJdbc;
            var warCache = UnityLauncher.getInstance().warStatusCache;
            for (Player p : nearbyEnemies(anchor, zone.getCountryName(), FINAL_KNOCKBACK_RADIUS, countryRegistry, warCache)) {
                Vector away = p.getLocation().toVector().subtract(anchor.toVector());
                if (away.lengthSquared() < 1e-4) away = new Vector(ThreadLocalRandom.current().nextDouble() - 0.5, 0, ThreadLocalRandom.current().nextDouble() - 0.5);
                p.setVelocity(away.normalize().multiply(FINAL_KNOCKBACK_STRENGTH).setY(0.6));
            }

            // Рассыпчатая куча-конус: пик высоты/плотности над анкером, тает к краю.
            for (int dx = -MOUND_RADIUS; dx <= MOUND_RADIUS; dx++) {
                for (int dz = -MOUND_RADIUS; dz <= MOUND_RADIUS; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    if (dist > MOUND_RADIUS) continue;
                    double falloff = 1.0 - dist / MOUND_RADIUS; // 1 в центре -> 0 на краю
                    int peakHeight = (int) Math.round(MOUND_MAX_HEIGHT * falloff);
                    double density = Math.max(0.15, falloff); // рассыпчато — не сплошная заливка, гуще к центру

                    for (int dy = -1; dy <= peakHeight; dy++) {
                        if (dx == 0 && dz == 0 && dy == 0) continue; // сам блок анкера не трогаем никогда
                        if (ThreadLocalRandom.current().nextDouble() > density) continue;
                        Material mat = SCAR_MATERIALS[ThreadLocalRandom.current().nextInt(SCAR_MATERIALS.length)];
                        anchor.clone().add(dx, dy, dz).getBlock().setType(mat);
                    }
                }
            }
        }

        // Анкер физически похоронен под шрамом (сам блок жив, просто
        // недоступен под кучей) — сбрасываем регистрацию, владелец должен
        // расчистить и поставить новый колокол (§17.10: "для починки
        // владелец должен прийти, расчистить, чтобы дойти до него" — сама
        // механика починки за пределами анкера пока открытый вопрос, см.
        // дизайн-док).
        zone.setMilitaryAnchorLocation(null);
        zones().saveZonesToConfig();

        if (state.attackerCountryName != null) {
            var api = UnityLauncher.getInstance().getFarLandsApi();
            if (api != null) api.reportMilitaryNeutralize(markerId, state.attackerCountryName);
        }

        state.progress = 0;
        state.milestone33 = false;
        state.milestone66 = false;
        state.lastFireAt = 0;
        state.lastFeedbackAt = 0;
        state.attackerCountryName = null;
        state.diggingPlayerName = null;
        state.diggingActive = false;
        state.breaksSinceLastBurst = 0;
        state.nextFeedbackBurstThreshold = 0;
    }

    // ---- Реальное копание якоря (фидбек 2026-08-22) ----

    /** Зона, чей ЖИВОЙ (не сброшенный) якорь стоит ровно в этом блоке — та же логика поиска, что у MilitaryAnchorService.findZoneByAnchor, но своя копия: разные пакеты, тянуть ради одного метода не стоит. */
    private ZoneInfo findZoneByAnchor(Location broken) {
        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            Location anchor = z.getMilitaryAnchorLocation();
            if (anchor == null || anchor.getWorld() == null) continue;
            if (!anchor.getWorld().getUID().equals(broken.getWorld().getUID())) continue;
            if (anchor.getBlockX() == broken.getBlockX() && anchor.getBlockY() == broken.getBlockY() && anchor.getBlockZ() == broken.getBlockZ()) return z;
        }
        return null;
    }

    /** true, если этот игрок — реальный враг (по войне) владельца зоны, не сам владелец/союзник/нейтрал. Свой колокол ломается/переносится как обычно, без вмешательства этого класса. */
    private boolean isEnemyDigger(ZoneInfo zone, Player p) {
        var countryRegistry = UnityLauncher.getInstance().countryRegistryJdbc;
        String diggerCountry = countryRegistry.getCountryOfPlayer(p.getName());
        String ownerCountry = zone.getCountryName();
        if (diggerCountry == null || ownerCountry == null || diggerCountry.equalsIgnoreCase(ownerCountry)) return false;
        return UnityLauncher.getInstance().warStatusCache.isAtWar(ownerCountry, diggerCountry);
    }

    /** Начало копания (ЛКМ по блоку) — если это чужой якорь DEFENSE-объекта под Диверсией, помечаем как реально копающего (чисто для actionbar/раннего фидбека — сам прогресс за это НЕ начисляется, см. onAnchorBreakAttempt). */
    @EventHandler(ignoreCancelled = true)
    public void onAnchorDigStart(BlockDamageEvent e) {
        if (e.getBlock().getType() != Material.BELL) return;
        ZoneInfo zone = findZoneByAnchor(e.getBlock().getLocation());
        if (zone == null) return;
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;
        if (activeSubtypeOf(zone, subtypeService) == null) return; // не DEFENSE-объект под Диверсией
        if (!isEnemyDigger(zone, e.getPlayer())) return; // свой/не при войне — не наше дело

        SabotageState state = statesByZone.computeIfAbsent(zone.getMarkerID(), k -> new SabotageState());
        state.diggingPlayerName = e.getPlayer().getName();
        state.diggingActive = true;
    }

    /** Игрок отпустил ЛКМ раньше, чем блок реально сломался — сам "слом" НЕ засчитан (см. class javadoc: "если не сломалось до конца — не засчитываем"), никакого прогресса не даёт, только гасит косметический indicator долбёжки. */
    @EventHandler(ignoreCancelled = true)
    public void onAnchorDigAbort(BlockDamageAbortEvent e) {
        if (e.getBlock().getType() != Material.BELL) return;
        ZoneInfo zone = findZoneByAnchor(e.getBlock().getLocation());
        if (zone == null) return;
        SabotageState state = statesByZone.get(zone.getMarkerID());
        if (state != null && e.getPlayer().getName().equals(state.diggingPlayerName)) {
            state.diggingActive = false;
        }
    }

    /**
     * Обычный ванильный слом якоря врагом — всегда отменяется. LOW-приоритет,
     * чтобы событие было уже отменено к моменту, когда MilitaryAnchorService.onBreak
     * (обычный приоритет, ignoreCancelled=true) до него дойдёт — свой колокол
     * та логика по-прежнему обрабатывает как раньше, чужой теперь не ломается
     * вообще без 100% прогресса. Реальный слом/шрам происходит программно в
     * completeSabotage, не через это событие.
     *
     * Живой тест 2026-08-22 (раунды 1-3) — непрерывный time-based рост
     * ("копится, пока держишь") оказался хрупким относительно протокола
     * майнкрафта (BlockDamageEvent шлётся один раз на весь сеанс, не каждый
     * тик) — несколько раундов фиксов гонялись за границей "точно
     * остановился / ещё держит".
     *
     * Живой тест 2026-08-22 (раунд 4) — "не угадайка сколько времени будет
     * ломать, а по фактическому количеству сколько раз блок был сломанным":
     * это событие — и есть тот самый факт "блок был сломан" (штатное
     * завершение долбёжки, которое мы лишь отменяем результат для реального
     * мира). Больше не нужно гадать, продолжает ли игрок держать — сам факт
     * долбёжки ДО КОНЦА один раз надёжно засчитывается прямо здесь,
     * случайные PROGRESS_PER_BREAK_MIN..MAX%. Аборт (см. onAnchorDigAbort)
     * по-прежнему не даёт ничего.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAnchorBreakAttempt(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.BELL) return;
        ZoneInfo zone = findZoneByAnchor(e.getBlock().getLocation());
        if (zone == null) return;
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;
        if (activeSubtypeOf(zone, subtypeService) == null) return;
        if (!isEnemyDigger(zone, e.getPlayer())) return; // свой — пусть ломает как обычно

        e.setCancelled(true);

        SabotageState state = statesByZone.computeIfAbsent(zone.getMarkerID(), k -> new SabotageState());
        double gain = PROGRESS_PER_BREAK_MIN + ThreadLocalRandom.current().nextDouble() * (PROGRESS_PER_BREAK_MAX - PROGRESS_PER_BREAK_MIN);
        state.progress = Math.min(100.0, state.progress + gain);
        state.lastGrowthAt = System.currentTimeMillis();
        state.attackerCountryName = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(e.getPlayer().getName());

        state.diggingPlayerName = e.getPlayer().getName();
        // Этот конкретный слом засчитан и закончен — если игрок продолжает
        // держать ПКМ, клиент сам почти сразу пришлёт новый BlockDamageEvent
        // (см. onAnchorDigStart), который снова выставит true.
        state.diggingActive = false;

        // Живой тест 2026-08-22 (раунд 6/7) — "партиклы пара/angry villager
        // рандомно раз в 2-5 разрушений" — считаем буквально по факту
        // засчитанных сломов, не по времени; порог перебрасывается заново
        // после каждого срабатывания, а не фиксированный модуль (тот давал
        // механический ритм ровно на 3, 6, 9...).
        if (state.nextFeedbackBurstThreshold <= 0) {
            state.nextFeedbackBurstThreshold = FEEDBACK_BURST_MIN_BREAKS
                    + ThreadLocalRandom.current().nextInt(FEEDBACK_BURST_MAX_BREAKS - FEEDBACK_BURST_MIN_BREAKS + 1);
        }
        state.breaksSinceLastBurst++;
        if (state.breaksSinceLastBurst >= state.nextFeedbackBurstThreshold) {
            state.breaksSinceLastBurst = 0;
            state.nextFeedbackBurstThreshold = 0; // перебросится заново при следующем сломе

            World w = e.getBlock().getWorld();
            Location anchor = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
            w.spawnParticle(Particle.CLOUD, anchor, 20, 0.4, 0.4, 0.4, 0.05);
            w.spawnParticle(Particle.ANGRY_VILLAGER, anchor, 6, 0.4, 0.4, 0.4, 0.0);
            w.playSound(anchor, Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        statesByZone.clear();
    }
}
