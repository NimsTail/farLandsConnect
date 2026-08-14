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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// infra/military-diplomacy-design.md GH#24 идея 1 "Живой оборонительный
// пост" — в отличие от DefensePatrolUpgrade (постоянный патруль), этот
// работает по триггеру: враг ТОЛЬКО ЧТО пересёк границу объекта (переход
// "не было рядом" -> "стал рядом" на предыдущем/текущем тике, не факт
// нахождения каждый тик) — спавнит одну волну и уходит на кулдаун. Тип/
// сила волны растут с уровнем. Мобы без дропа/опыта — это защита, не ферма.
public final class LiveDefensePostUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.live_defense");
    private static final String META_KEY = "unityLiveDefensePost";

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
            // Сила эффекта — теперь отдельный узел от квоты "сколько объектов
            // можно" (cfg.permBase()), см. MilitaryDefenseSubtype.levelPermBase().
            int level = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(
                    canonicalCountry, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.LIVE_DEFENSE.levelPermBase(), 3);
            if (level < 1) continue;

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            String markerId = z.getMarkerID();
            Set<String> nearbyNow = ConcurrentHashMap.newKeySet();
            for (Player p : center.getWorld().getPlayers()) {
                String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry == null || playerCountry.equalsIgnoreCase(z.getCountryName())) continue; // не враг
                if (p.getLocation().distance(center) > cfg.detectRadius()) continue;
                nearbyNow.add(p.getName());
            }

            Set<String> nearbyBefore = nearbyEnemiesByZone.getOrDefault(markerId, Set.of());
            boolean justCrossed = nearbyNow.stream().anyMatch(name -> !nearbyBefore.contains(name));
            nearbyEnemiesByZone.put(markerId, nearbyNow);

            if (!justCrossed) continue;
            long now = System.currentTimeMillis();
            Long lastTrigger = lastTriggerByZone.get(markerId);
            long cooldownMs = cfg.cooldownTicks() * 50L;
            if (lastTrigger != null && now - lastTrigger < cooldownMs) continue;

            lastTriggerByZone.put(markerId, now);
            spawnWave(center, markerId, level);
        }
    }

    /** Уровень 1 — зомби/скелет по биому (как у Обороны); 2 — добавляется страж (vindicator); 3 — ещё разбойник (pillager). Черновой набор. */
    private void spawnWave(Location center, String markerId, int level) {
        List<EntityType> types = new ArrayList<>();
        types.add(DefensePatrolBiomeMob.forBiome(center));
        if (level >= 2) types.add(EntityType.VINDICATOR);
        if (level >= 3) types.add(EntityType.PILLAGER);

        for (EntityType type : types) {
            Entity e = center.getWorld().spawnEntity(center, type);
            if (e instanceof LivingEntity mob) {
                mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), markerId));
                mob.setRemoveWhenFarAway(true);
            }
        }
    }

    /** Без дропа/опыта — это защита объекта, не источник фарма (GH#24). */
    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getMetadata(META_KEY).isEmpty()) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
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
