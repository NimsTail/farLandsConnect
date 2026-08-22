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
 *  2. "Отголосок" (§17.4) — если тот же враг физически под землёй (не
 *     open-to-sky) и не двигается дольше порога, получает периодический
 *     урон "сквозь блоки" (прямой damage(), без снаряда — блоки для него
 *     не преграда) со звуком/партиклами Стража, без спавна самой сущности.
 *     Урон растёт с каждым повторным пульсом, сбрасывается при реальном
 *     движении.
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

            boolean underground = isUnderground(loc, cfg.undergroundMargin());

            rollPityPatrol(p, loc, defenderCountry, defenderCountryName, underground, cfg);
            handleEcho(p, loc, defenderCountry, underground, cfg);
        }

        // Чистим состояние игроков, которые вышли из релевантной территории/офлайн —
        // иначе карта растёт бесконечно на сервере с постоянной сменой состава.
        stateByPlayer.keySet().removeIf(name -> Bukkit.getPlayerExact(name) == null);
    }

    // ---- 1. Pity-патруль (§17.3) ----

    private void rollPityPatrol(Player p, Location loc, String defenderCountry, String defenderCountryName, boolean underground, MilitaryCfg.FrontierDefenseCfg cfg) {
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
            spawnPatrol(loc, defenderCountryName, underground, witherUnlocked);
        } else {
            state.pityChance()[0] = Math.min(PITY_CAP, chance + growth); // накопление к следующей проверке
        }
    }

    private void spawnPatrol(Location near, String defenderCountryName, boolean underground, boolean witherUnlocked) {
        World w = near.getWorld();
        if (w == null) return;

        boolean noDefendersOnline = onlineCitizens(defenderCountryName) == 0;
        List<EntityType> composition = new ArrayList<>();
        if (underground) {
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
            Location spawnLoc = randomPointNear(near, underground);
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

    /** Точка рядом с игроком: на поверхности — по highestBlockYAt, под землёй — на его же уровне Y с проверкой на проходимость. */
    private Location randomPointNear(Location base, boolean underground) {
        World w = base.getWorld();
        if (w == null) return base;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++) {
            double radius = underground ? (4 + rnd.nextDouble() * 5) : (5 + rnd.nextDouble() * 8);
            double angle = rnd.nextDouble() * Math.PI * 2;
            double x = base.getX() + Math.cos(angle) * radius;
            double z = base.getZ() + Math.sin(angle) * radius;

            if (!underground) {
                int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
                return new Location(w, x, y, z);
            }

            double y = base.getY() + rnd.nextInt(3) - 1; // тот же уровень +-1
            Location candidate = new Location(w, x, y, z);
            Material at = candidate.getBlock().getType();
            Material above = candidate.clone().add(0, 1, 0).getBlock().getType();
            if (!at.isSolid() && !above.isSolid()) return candidate;
        }
        return base; // не нашли проходимую точку — спавним прямо у игрока
    }

    // ---- 2. "Отголосок" (§17.4) ----

    private void handleEcho(Player p, Location loc, String defenderCountry, boolean underground, MilitaryCfg.FrontierDefenseCfg cfg) {
        EngagementState state = stateByPlayer.get(p.getName());
        if (state == null) return; // rollPityPatrol уже создал состояние в этом же тике — но на всякий случай

        long now = System.currentTimeMillis();
        double dx = loc.getX() - state.lastX()[0];
        double dy = loc.getY() - state.lastY()[0];
        double dz = loc.getZ() - state.lastZ()[0];
        boolean moved = (dx * dx + dy * dy + dz * dz) > IDLE_MOVE_EPSILON_SQ;

        if (moved || !underground) {
            state.lastX()[0] = loc.getX();
            state.lastY()[0] = loc.getY();
            state.lastZ()[0] = loc.getZ();
            state.lastMoveAtMs()[0] = now;
            state.echoPulses()[0] = 0; // реальное движение (или вышел на поверхность) — полный сброс эскалации
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
        double damage = cfg.pulseBaseDamage() + pulseIndex * (ECHO_DAMAGE_STEP_HEARTS[echoLevel] * 2.0);

        // "Сквозь блоки" — прямой damage()/playSound() игроку, без снарядов и
        // без проверки видимости: звук/урон приходят независимо от того, что
        // между источником и игроком (та же сигнатурная черта Стража).
        p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 2.5f, 1.0f);
        p.spawnParticle(Particle.SONIC_BOOM, p.getLocation().add(0, 1, 0), 1);
        p.damage(damage);
    }

    private boolean isUnderground(Location loc, int margin) {
        World w = loc.getWorld();
        if (w == null) return false;
        int highest = w.getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        return loc.getBlockY() < highest - margin;
    }

    /** Без дропа/опыта — тот же паттерн, что у Живого поста/обычного патруля Обороны. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getMetadata(META_KEY).isEmpty()) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
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
