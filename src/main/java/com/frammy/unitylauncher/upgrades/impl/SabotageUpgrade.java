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
 * этого держим у блока искусственно увеличенное время ломания: копится,
 * пока игрок реально долбит блок без остановки (BlockDamageEvent старт /
 * BlockDamageAbortEvent — прервался), тает (decay, НЕ мгновенный сброс),
 * если остановился. Долгий процесс намеренно — один долбит, другой
 * прикрывает от фонового патруля Обороны/собственного эффекта объекта,
 * никакого искусственного headcount-гейта. Майлстоуны на пути бьют по
 * атакующей стороне: 33% — отталкивание, 66% — тьма, 75%+ — периодически
 * занимающийся вокруг огонь (нарастающая цена простоя рядом). 100% —
 * партиклы/звук/толчок + шрам из обсидиана/камня/магмы на месте анкера
 * (реальный физический слом происходит ЗДЕСЬ, программно, не через
 * ванильный BlockBreakEvent — тот у врага всегда отменён) — дальше уже
 * обычный поток (CONTESTED/War Score/иммунитет/платный ремонт,
 * lib/militaryZones.ts), этот класс лишь один раз шлёт репорт.
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

    // Черновые числа §17.10 — под правку по живому тесту, как и весь баланс документа.
    // Живой тест 2026-08-22 (раунд 3) — "задержка ощущается": прогресс/actionbar/
    // пинг активности обновлялись раз в 2с — заметная ступенька на глаз при
    // непрерывной долбёжке. growthPerTick пересчитывается от самого
    // TICK_PERIOD_TICKS (см. tick() ниже), поэтому итоговая скорость роста
    // (100% за CHANNEL_SECONDS) не меняется от смены периода — просто более
    // мелкие, частые шаги вместо редких крупных.
    private static final long TICK_PERIOD_TICKS = 10L; // 0.5с
    private static final double CHANNEL_SECONDS = 120.0; // "долгий процесс" — 2 минуты полного НЕПРЕРЫВНОГО копания

    // "Декай нужен, но держится явно меньше", чем на линии фронта (§17.2/§17.8:
    // там 7 мин/0.4×) — это активный ближний бой, а не пассивная площадь.
    private static final long DECAY_GRACE_MS = 10_000L; // 10с без атакующего рядом — и начинает таять
    private static final double DECAY_RATE = 0.8; // от скорости роста

    // Живой тест 2026-08-22 (раунд 2) — ни BlockDamageEvent, ни
    // BlockDamageAbortEvent НЕ надёжны как единственный источник правды о
    // "копает ли игрок прямо сейчас": abort прилетает только при
    // ДОСРОЧНОМ отпускании, а штатное завершение долбёжки (дошёл до конца
    // анимации трещин) вообще не шлёт ни одного из двух событий — просто
    // BlockBreakEvent, который мы отменяем.
    //
    // Живой тест 2026-08-22 (раунд 2, первая попытка, ОШИБОЧНАЯ) — общий
    // "пинг с таймаутом" на КАЖДОЕ событие сломан для реального долгого
    // держания: BlockDamageEvent шлётся РОВНО ОДИН РАЗ на весь сеанс
    // долбёжки (не повторяется каждый тик, пока держишь кнопку) — то есть
    // между стартом и штатным завершением/отменой может пройти много
    // секунд БЕЗ единого нового события вообще. Таймаут короче реального
    // времени долбёжки (что для колокола рукой — не мгновенно) обнулял
    // прогресс ПРЯМО ПОСЕРЕДИНЕ честного непрерывного держания.
    //
    // Правильная модель: сам dig-start НЕ протухает сам по себе (держит
    // diggingActive=true бессрочно) — единственные события, что-то
    // решающие: onAnchorDigAbort (точно отпустил раньше времени — сбрасываем
    // немедленно) и onAnchorBreakAttempt (штатно "доломал", отменили —
    // неоднозначно: либо реально отпустил в этот момент без abort, либо
    // продолжает держать и клиент вот-вот сам пришлёт новый dig-start).
    // Второй случай решаем коротким окном ожидания awaitingRestartSince —
    // если новый dig-start/break-attempt не подтвердит продолжение в
    // течение RESTART_GRACE_MS, вот тогда и только тогда diggingActive
    // сбрасывается.
    private static final long RESTART_GRACE_MS = 2_500L;

    private static final double MILESTONE_KNOCKBACK_AT = 33.0;
    private static final double MILESTONE_DARKNESS_AT = 66.0;
    private static final double MILESTONE_FIRE_AT = 75.0;
    private static final double KNOCKBACK_STRENGTH = 0.5;
    private static final int DARKNESS_TICKS = 20 * 3; // 3с

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
        // != 0, когда штатное завершение долбёжки (onAnchorBreakAttempt)
        // ждёт подтверждения, что игрок продолжает держать — см.
        // RESTART_GRACE_MS выше.
        long awaitingRestartSince;
        // Фидбек 2026-08-22 (второй раунд) — "первые 30% ничего не
        // происходит" — таймер лёгкой периодической обратной связи, не
        // привязанной к майлстоунам.
        long lastFeedbackAt;
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

    private void tick() {
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;
        var countryRegistry = UnityLauncher.getInstance().countryRegistryJdbc;
        var warCache = UnityLauncher.getInstance().warStatusCache;
        long now = System.currentTimeMillis();
        double growthPerTick = 100.0 * (TICK_PERIOD_TICKS * 50.0 / 1000.0) / CHANNEL_SECONDS;

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

            // Штатное завершение долбёжки ждало RESTART_GRACE_MS подтверждения,
            // что игрок продолжает держать (см. onAnchorBreakAttempt) — время
            // вышло без нового dig-start/break-attempt, значит правда отпустил.
            if (state.awaitingRestartSince != 0 && now - state.awaitingRestartSince > RESTART_GRACE_MS) {
                state.diggingActive = false;
                state.awaitingRestartSince = 0;
            }

            // Фидбек 2026-08-22 — рост только пока игрок РЕАЛЬНО копает
            // (диспетчер онлайн, флаг не сброшен BlockDamageAbortEvent'ом/
            // истёкшим окном ожидания выше), не просто стоит рядом.
            boolean digging = state.diggingActive && state.diggingPlayerName != null
                    && org.bukkit.Bukkit.getPlayerExact(state.diggingPlayerName) != null;
            if (digging) {
                state.progress = Math.min(100.0, state.progress + growthPerTick);
                state.lastGrowthAt = now;
                state.attackerCountryName = countryRegistry.getCountryOfPlayer(state.diggingPlayerName);
            } else if (state.progress > 0 && now - state.lastGrowthAt > DECAY_GRACE_MS) {
                state.progress = Math.max(0.0, state.progress - growthPerTick * DECAY_RATE);
            }

            // Фидбек 2026-08-22 — "добавить в тайтлбар (над хотбаром) отображение
            // прогресса". Показываем и пока копает (растёт), и сразу после
            // остановки, пока держится decay-отсрочка (падает) — чтобы было
            // видно, что прогресс реально утекает, если бросил раньше времени.
            if (state.progress > 0) {
                Player digger = org.bukkit.Bukkit.getPlayerExact(state.diggingPlayerName);
                if (digger != null) {
                    NamedTextColor color = digging ? NamedTextColor.GOLD : NamedTextColor.RED;
                    String verb = digging ? "Диверсия" : "Диверсия (остывает)";
                    digger.sendActionBar(Component.text("⚒ " + verb + ": " + Math.round(state.progress) + "%", color));
                }
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

        statesByZone.keySet().removeIf(id -> zones().getAllZonesSnapshot().stream().noneMatch(z -> id.equals(z.getMarkerID())));
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
            // Настоящий взрыв — частицы/звук/урон+отталкивание нанесёт сам,
            // не нужно вручную. fire=false (свой контролируемый огонь уже
            // есть, см. igniteAround), breakBlocks=true — реально срывает
            // блоки вокруг радиусом ~3-4 (та самая "рассыпчатость" бесплатно).
            w.createExplosion(anchor, EXPLOSION_POWER, false, true);

            // Взрыв мог случайно задеть и сам блок анкера — восстанавливаем
            // его безусловно, он "просто становится неактивным", не исчезает.
            anchor.getBlock().setType(Material.BELL);

            // Доп. партиклы/звук поверх ванильного взрыва — пар/сталь, не дублируем сам взрыв.
            w.spawnParticle(Particle.CLOUD, anchor, 60, 1.2, 1.0, 1.2, 0.06);
            w.playSound(anchor, Sound.BLOCK_LAVA_EXTINGUISH, 3.0f, 1.0f);

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
        state.awaitingRestartSince = 0;
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

    /** Начало копания (ЛКМ по блоку) — если это чужой якорь DEFENSE-объекта под Диверсией, помечаем прогресс как растущий. */
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
        state.awaitingRestartSince = 0; // подтверждённое продолжение — снимаем ожидание, если было
    }

    /** Игрок отпустил ЛКМ раньше, чем блок реально сломался (мы и не даём ему сломаться обычным способом, см. onAnchorBreakAttempt) — копание прервано, дальше decay в tick(). Единственный НАДЁЖНЫЙ сигнал "точно остановился" — сбрасываем сразу, не дожидаясь окна ожидания. */
    @EventHandler(ignoreCancelled = true)
    public void onAnchorDigAbort(BlockDamageAbortEvent e) {
        if (e.getBlock().getType() != Material.BELL) return;
        ZoneInfo zone = findZoneByAnchor(e.getBlock().getLocation());
        if (zone == null) return;
        SabotageState state = statesByZone.get(zone.getMarkerID());
        if (state != null && e.getPlayer().getName().equals(state.diggingPlayerName)) {
            state.diggingActive = false;
            state.awaitingRestartSince = 0;
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
     * Живой тест 2026-08-22 (раунд 1) — баг "нажал один раз, и дальше
     * безвозвратно продолжается": BlockDamageAbortEvent прилетает, только
     * если игрок прервал долбёжку РАНЬШЕ, чем она штатно завершилась бы
     * (отпустил ПКМ до конца анимации трещин). Штатное завершение
     * (мгновенный слом в креативе, быстрый инструмент, либо додержал до
     * финальной трещины) не шлёт вообще ни одного из двух событий — только
     * этот BlockBreakEvent, который мы отменяем.
     *
     * Живой тест 2026-08-22 (раунд 2) — первая попытка фикса (жёсткий сброс
     * diggingActive=false прямо здесь) породила противоположный баг:
     * "остывает и не сбить" — после ОДНОГО такого срабатывания рост
     * навсегда переставал возобновляться, даже когда игрок реально начинал
     * долбить заново.
     *
     * Итоговая модель (раунд 3) — само событие неоднозначно: либо игрок
     * реально отпустил именно в этот момент (без отдельного abort — так
     * бывает при штатном завершении), либо продолжает держать и клиент
     * вот-вот сам пришлёт новый BlockDamageEvent. Не решаем это здесь сразу:
     * просто отмечаем awaitingRestartSince, а tick() через RESTART_GRACE_MS
     * без подтверждения (новый dig-start снимает флаг, см.
     * onAnchorDigStart) сам сбросит diggingActive.
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
        state.diggingPlayerName = e.getPlayer().getName();
        state.diggingActive = true; // ещё не сбрасываем — ждём RESTART_GRACE_MS в tick()
        state.awaitingRestartSince = System.currentTimeMillis();
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
