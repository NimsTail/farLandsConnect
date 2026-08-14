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
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.FluidCollisionMode;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// infra/military-diplomacy-design.md GH#24 идея 4 "Арбалет" — стреляет по
// ближайшему видимому врагу в радиусе от якоря объекта (или центра, если
// якоря нет — реализация не требует именно колокола, "якорь-зона" общая
// концепция). Слепая зона: цель ниже blindSpotDegrees от горизонтали не
// обстреливается. На макс. уровне (2) стрелы несут эффект (Слабость).
public final class CrossbowUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.crossbow");
    private static final double ARROW_SPEED = 3.0;

    // markerId -> когда последний раз стреляли (мс) — один выстрел за period, простейший кулдаун на объект.
    private final Map<String, Long> lastShotByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; }

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

            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            int level = canonicalCountry == null ? 0 : UpgradeCondition.countryMaxLevel(
                    canonicalCountry, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.CROSSBOW.levelPermBase(), 2);
            if (level < 1) continue;

            // "Якорь-зона" (твоя формулировка) — переиспользует тот же
            // общий anchor-слот, что и Колокол разведки (у зоны только одна
            // текущая специализация активна за раз, так что слот свободен),
            // с фоллбеком на центр зоны, если якоря физически нет.
            Location origin = z.getMilitaryAnchorLocation();
            if (origin == null) origin = z.getCenter();
            if (origin == null || origin.getWorld() == null) continue;

            Player target = findTarget(origin, cfg, z.getCountryName());
            if (target == null) continue;

            String markerId = z.getMarkerID();
            long now = System.currentTimeMillis();
            Long last = lastShotByZone.get(markerId);
            if (last != null && now - last < cfg.periodTicks() * 50L) continue;
            lastShotByZone.put(markerId, now);

            shoot(origin, target, cfg, level);
        }
    }

    private Player findTarget(Location origin, MilitaryCfg.CrossbowCfg cfg, String countryName) {
        Player best = null;
        double bestDist = cfg.radius();
        for (Player p : origin.getWorld().getPlayers()) {
            String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (playerCountry != null && playerCountry.equalsIgnoreCase(countryName)) continue; // не враг

            double d = p.getLocation().distance(origin);
            if (d > bestDist) continue;
            if (!isWithinFiringAngle(origin, p.getLocation(), cfg.blindSpotDegrees())) continue;
            if (!hasLineOfSight(origin, p)) continue;

            bestDist = d;
            best = p;
        }
        return best;
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

    /** Слепая зона снизу: угол между направлением на цель и горизонтом не должен быть ниже -blindSpotDegrees. */
    private boolean isWithinFiringAngle(Location origin, Location target, double blindSpotDegrees) {
        Vector to = target.toVector().subtract(origin.toVector());
        double horizontal = Math.hypot(to.getX(), to.getZ());
        if (horizontal < 1e-6) return true; // цель прямо под/над якорем — угол не определён, не блокируем
        double verticalAngleDeg = Math.toDegrees(Math.atan2(to.getY(), horizontal));
        return verticalAngleDeg >= -blindSpotDegrees;
    }

    private void shoot(Location origin, Player target, MilitaryCfg.CrossbowCfg cfg, int level) {
        Vector direction = target.getEyeLocation().toVector().subtract(origin.toVector()).normalize();
        Arrow arrow = (Arrow) origin.getWorld().spawnEntity(origin.clone().add(0, 0.5, 0), EntityType.ARROW);
        arrow.setVelocity(direction.multiply(ARROW_SPEED));
        arrow.setDamage(cfg.damage());
        if (level >= 2) {
            arrow.addCustomEffect(new PotionEffect(PotionEffectType.WEAKNESS, 20 * 5, 0), true);
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
