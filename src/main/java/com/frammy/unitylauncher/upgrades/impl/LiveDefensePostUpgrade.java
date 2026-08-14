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
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// infra/military-diplomacy-design.md GH#24 идея 1 "Живой оборонительный
// пост" — в отличие от DefensePatrolUpgrade (постоянный патруль), этот
// работает по триггеру: враг ТОЛЬКО ЧТО пересёк границу объекта (переход
// "не было рядом" -> "стал рядом" на предыдущем/текущем тике, не факт
// нахождения каждый тик) — спавнит одну волну и уходит на кулдаун. Тип/
// сила волны растут с уровнем. Мобы без дропа/опыта — это защита, не ферма.
//
// GH#30 — доработка по фидбеку:
//  1. Дроп/опыт уже были отключены с самого начала (onDeath ниже) — вопрос
//     "есть ли способ" был скорее уточняющим, чем багом; оставил как есть.
//  2. Мобы волны раньше спавнились все в одной точке (center объекта) —
//     теперь каждый моб получает свою случайную точку внутри реальной
//     территории объекта (rejection sampling по AABB + contains2D), а не
//     одну общую.
//  3. Раньше "враг" для срабатывания = "не гражданин этой страны" (любой
//     чужак), и заспавненные мобы атаковали вообще любого не-своего —
//     теперь и триггер, и таргетинг требуют реальной войны (как и
//     DefensePatrolUpgrade/CrossbowUpgrade) — вне войны пост неактивен.
//  4. "Интенсивность" волны теперь зависит от того, сколько граждан страны
//     сейчас онлайн — некому защищаться самим, значит пост компенсирует
//     дополнительным мобом; чем больше живых защитников на месте, тем
//     меньше нужен буст (но не ниже 1 моба, чтобы совсем не пропадал).
public final class LiveDefensePostUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.live_defense");
    private static final String META_KEY = "unityLiveDefensePost";

    // GH#30 п.4 — черновые числа: при 0 онлайн-защитников волна получает
    // +1 моба сверху; каждый онлайн-защитник после первого снимает по
    // одному мобу с волны, но не ниже MIN_WAVE_SIZE.
    private static final int MIN_WAVE_SIZE = 1;

    // GH#30 п.2 — сколько раз пробовать случайную точку внутри AABB, прежде
    // чем сдаться и упасть на center() (полигон может занимать малую долю
    // своего же bounding box — L-образные/вытянутые территории).
    private static final int RANDOM_POINT_ATTEMPTS = 12;

    // GH#30 (Улучшения п.2) "Время между спавном" — отдельный узел от
    // "усиления" волны (levelPermBase), пара уровней, множитель на
    // cfg.cooldownTicks(). Черновые числа, не сильно драматичные, как и
    // просили.
    private static final String COOLDOWN_PERM_BASE = "unity.military.live_defense_cooldown";
    private static final double[] COOLDOWN_MULTIPLIER = {1.0, 0.75, 0.55};

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    // markerId -> кто из врагов уже засчитан "внутри" на прошлый тик — переход
    // false->true (не было в сете, стал рядом) это и есть "пересечение границы".
    private final Map<String, Set<String>> nearbyEnemiesByZone = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerByZone = new ConcurrentHashMap<>();
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
            // GH#26 (фидбек — "должен уже работать на самом простом уровне,
            // улучшения должны прокачивать, не включать") — сила эффекта
            // (отдельный узел от квоты "сколько объектов можно", cfg.permBase())
            // больше не гейтит срабатывание вообще — isActiveAs выше уже
            // подтверждает базовую покупку типа; level ниже клампится к
            // минимум 1 (spawnWave уже трактует 1 как базовую волну).
            int level = Math.max(1, UpgradeCondition.countryMaxLevel(
                    canonicalCountry, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.LIVE_DEFENSE.levelPermBase(), 3));

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            String countryName = z.getCountryName();
            String markerId = z.getMarkerID();
            // GH#30 п.3 — "враг" теперь требует реальной войны, не просто
            // "не свой гражданин" — вне войны пост никого не засекает и не
            // спавнит волну.
            Set<String> nearbyNow = ConcurrentHashMap.newKeySet();
            for (Player p : center.getWorld().getPlayers()) {
                String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry == null || playerCountry.equalsIgnoreCase(countryName)) continue; // не враг
                if (!UnityLauncher.getInstance().warStatusCache.isAtWar(countryName, playerCountry)) continue; // не воюем — не враг
                if (p.getLocation().distance(center) > cfg.detectRadius()) continue;
                nearbyNow.add(p.getName());
            }

            Set<String> nearbyBefore = nearbyEnemiesByZone.getOrDefault(markerId, Set.of());
            boolean justCrossed = nearbyNow.stream().anyMatch(name -> !nearbyBefore.contains(name));
            nearbyEnemiesByZone.put(markerId, nearbyNow);

            if (!justCrossed) continue;
            long now = System.currentTimeMillis();
            Long lastTrigger = lastTriggerByZone.get(markerId);
            int cooldownLevel = UpgradeCondition.countryMaxLevel(canonicalCountry, COOLDOWN_PERM_BASE, 2);
            long cooldownMs = Math.round(cfg.cooldownTicks() * 50L * COOLDOWN_MULTIPLIER[cooldownLevel]);
            if (lastTrigger != null && now - lastTrigger < cooldownMs) continue;

            lastTriggerByZone.put(markerId, now);
            spawnWave(z, center, markerId, countryName, level);
        }
    }

    /** Уровень 1 — зомби/скелет по биому (как у Обороны); 2 — добавляется страж (vindicator); 3 — ещё разбойник (pillager). Черновой набор. */
    private void spawnWave(ZoneInfo z, Location center, String markerId, String countryName, int level) {
        List<EntityType> types = new ArrayList<>();
        types.add(DefensePatrolBiomeMob.forBiome(center));
        if (level >= 2) types.add(EntityType.VINDICATOR);
        if (level >= 3) types.add(EntityType.PILLAGER);

        // GH#30 п.4 — интенсивность волны обратно пропорциональна числу
        // онлайн-защитников: некому защищаться -> пост усиливается; чем
        // больше живых граждан на связи, тем меньше буст (но не ниже
        // MIN_WAVE_SIZE, чтобы пост не пропадал вовсе).
        int ownersOnline = (int) Bukkit.getOnlinePlayers().stream()
                .filter(p -> countryName.equalsIgnoreCase(UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName())))
                .count();
        if (ownersOnline == 0) {
            types.add(types.get(0)); // никто не защищается — усиление волны на 1 моба
        } else if (ownersOnline > 1) {
            int toRemove = Math.min(types.size() - MIN_WAVE_SIZE, ownersOnline - 1);
            for (int i = 0; i < toRemove; i++) types.remove(types.size() - 1);
        }

        for (EntityType type : types) {
            // GH#30 п.2 — своя случайная точка внутри территории объекта
            // для каждого моба волны, не общий center на всех.
            Location spawnLoc = randomPointIn(z, center);
            Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, type);
            if (e instanceof LivingEntity mob) {
                mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), markerId));
                mob.setRemoveWhenFarAway(true);
            }
        }
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

    /** Без дропа/опыта — это защита объекта, не источник фарма (GH#24). */
    @EventHandler(ignoreCancelled = true)
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
        nearbyEnemiesByZone.clear();
        lastTriggerByZone.clear();
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
