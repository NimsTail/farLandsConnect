package com.frammy.unitylauncher.upgrades.impl;

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
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// infra/military-diplomacy-design.md §3.3/§13 Фаза 2, §14.2/§14.4 "Оборона".
// Только сама примета (живой асинхронный патруль) — нейтрализация/War
// Score/CONTESTED остаются Фазе 4 (нужен статус WAR, которого пока нет).
public final class DefensePatrolUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.defense");
    private static final String META_KEY = "unityMilitaryGuardCountry";

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    // markerId зоны -> живые мобы патруля этой зоны (в памяти — переживать
    // рестарт плагина не обязано, как и остальные in-memory кеши апгрейдов).
    private final Map<String, List<UUID>> guardsByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.DefensePatrolCfg cfg = ctx.config().military().defensePatrol();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().defensePatrol();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/DefensePatrol] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.DefensePatrolCfg cfg) {
        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;

            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            if (canonicalCountry == null) continue;
            if (UpgradeCondition.countryMaxLevel(canonicalCountry, cfg.permBase(), 1) < 1) continue;

            List<UUID> alive = guardsByZone.computeIfAbsent(z.getMarkerID(), k -> new ArrayList<>());
            alive.removeIf(id -> {
                Entity e = Bukkit.getEntity(id);
                return e == null || !e.isValid();
            });
            if (alive.size() >= cfg.maxAlive()) continue;

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            int toSpawn = Math.min(cfg.mobsPerWave(), cfg.maxAlive() - alive.size());
            for (int i = 0; i < toSpawn; i++) {
                LivingEntity mob = (LivingEntity) center.getWorld().spawnEntity(center, patrolTypeFor(center));
                mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), canonicalCountry));
                mob.setRemoveWhenFarAway(true);
                alive.add(mob.getUniqueId());
            }
        }
    }

    /** Скелет/зомби по биому — только ванильные мобы, ничего нового (§14.2). */
    private static EntityType patrolTypeFor(Location loc) {
        Biome biome = loc.getBlock().getBiome();
        String name = biome.name();
        if (name.contains("DESERT")) return EntityType.HUSK;
        if (name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE")) return EntityType.STRAY;
        return Math.random() < 0.5 ? EntityType.ZOMBIE : EntityType.SKELETON;
    }

    // Враждебны к чужакам, не к своим (§14.2: "не из страны/союза" — союзники
    // сюда пока не входят, см. CountryRegistryJdbc.getPlayerViewMilitaryPermission).
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        List<org.bukkit.metadata.MetadataValue> meta = e.getEntity().getMetadata(META_KEY);
        if (meta.isEmpty()) return;

        String guardCountry = meta.get(0).asString();
        String playerCountry = UpgradeCondition.playerCountryCanonical(p.getName());
        if (guardCountry.equals(playerCountry)) {
            e.setCancelled(true);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (List<UUID> alive : guardsByZone.values()) {
            for (UUID id : alive) {
                Entity e = Bukkit.getEntity(id);
                if (e != null && e.isValid()) e.remove();
            }
        }
        guardsByZone.clear();
    }
}
