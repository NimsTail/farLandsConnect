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
    private static final long TICK_PERIOD_TICKS = 40L; // 2с
    private static final double CHANNEL_SECONDS = 120.0; // "долгий процесс" — 2 минуты полного НЕПРЕРЫВНОГО копания

    // "Декай нужен, но держится явно меньше", чем на линии фронта (§17.2/§17.8:
    // там 7 мин/0.4×) — это активный ближний бой, а не пассивная площадь.
    private static final long DECAY_GRACE_MS = 10_000L; // 10с без атакующего рядом — и начинает таять
    private static final double DECAY_RATE = 0.8; // от скорости роста

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
    // угодно. Радиус 1-2 блока, включая воздух (не только заменяет солид).
    private static final Material[] SCAR_MATERIALS = {Material.OBSIDIAN, Material.STONE, Material.MAGMA_BLOCK};
    private static final int SCAR_RADIUS = 2;

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

            // Фидбек 2026-08-22 — рост только пока игрок РЕАЛЬНО копает
            // (диспетчер онлайн + флаг ещё не сброшен BlockDamageAbortEvent'ом
            // — см. onAnchorDigStart/onAnchorDigAbort ниже), не просто стоит рядом.
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

    private void completeSabotage(ZoneInfo zone, Location anchor, String markerId, SabotageState state) {
        World w = anchor.getWorld();
        if (w != null) {
            // Партиклы пара + звук + толчок + шрам (§17.10, решено 2026-08-22).
            w.spawnParticle(Particle.CLOUD, anchor, 60, 1.2, 1.0, 1.2, 0.06);
            w.playSound(anchor, Sound.BLOCK_LAVA_EXTINGUISH, 3.0f, 1.0f);
            w.playSound(anchor, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f);

            for (Player p : anchor.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(anchor) > 8.0 * 8.0) continue;
                Vector away = p.getLocation().toVector().subtract(anchor.toVector());
                if (away.lengthSquared() < 1e-4) continue;
                p.setVelocity(away.normalize().multiply(KNOCKBACK_STRENGTH * 1.6).setY(0.35));
            }

            // Шрам — обычные ломаемые блоки, радиус 1-2, включая воздух
            // (безусловная замена, не только "если было солид").
            for (int dx = -SCAR_RADIUS; dx <= SCAR_RADIUS; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -SCAR_RADIUS; dz <= SCAR_RADIUS; dz++) {
                        if (dx * dx + dy * dy + dz * dz > SCAR_RADIUS * SCAR_RADIUS) continue;
                        Material mat = SCAR_MATERIALS[ThreadLocalRandom.current().nextInt(SCAR_MATERIALS.length)];
                        anchor.clone().add(dx, dy, dz).getBlock().setType(mat);
                    }
                }
            }
        }

        // Анкер физически похоронен под шрамом — сбрасываем регистрацию,
        // владелец должен расчистить и поставить новый колокол (§17.10:
        // "для починки владелец должен прийти, расчистить, чтобы дойти до
        // него" — сама механика починки за пределами анкера пока открытый
        // вопрос, см. дизайн-док).
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
        state.attackerCountryName = null;
        state.diggingPlayerName = null;
        state.diggingActive = false;
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
    }

    /** Игрок отпустил ЛКМ раньше, чем блок реально сломался (мы и не даём ему сломаться обычным способом, см. onAnchorBreakAttempt) — копание прервано, дальше decay в tick(). */
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
