package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.ParkCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class BenchesUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("park.benches");
    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        ParkCfg.BenchesCfg cfg = ctx.config().park().benches();
        return cfg != null && cfg.enabled();
    }

    // сколько надо "стоять" (в тиках сервера), как и раньше: 40 тиков = 2 секунды
    private static final int REQUIRED_STILL_TICKS = 40;
    private static final long REQUIRED_STILL_MS = REQUIRED_STILL_TICKS * 50L;

    private static final long COOLDOWN_MS = 12_000;

    // насколько можно "ёрзать" по координатам
    private static final double EPS_XZ = 0.10; // мягче, чем было 0.08
    private static final double EPS_Y  = 0.15;

    private record Pos(double x, double y, double z) { }

    private final Map<UUID, Pos> lastPos = new ConcurrentHashMap<>();
    private final Map<UUID, Long> stillMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> nextProcAtMs = new ConcurrentHashMap<>();

    @Override
    protected void onEnable() {
        ParkCfg.BenchesCfg cfg = C().park().benches();
        long periodTicks = Math.max(10L, cfg.periodTicks());
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg, periodTicks), periodTicks, periodTicks));
    }

    private void tick(ParkCfg.BenchesCfg cfg, long periodTicks) {
        final int rHard = Math.min(Math.max(0, cfg.nearRadiusBlocks()), 16);
        final int dur = Math.max(40, cfg.regenDurationTicks());
        final int amp = Math.max(0, cfg.regenAmplifier());
        final long now = System.currentTimeMillis();
        final long addMs = periodTicks * 50L;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p == null || !p.isOnline() || p.isDead()) continue;

            final UUID uid = p.getUniqueId();
            final Location loc = p.getLocation();

            // 1) только PARK
            ZoneInfo z = UpgradeCondition.zoneAt(loc);
            if (z == null || z.getType() != ZoneType.PARK) {
                reset(uid);
                continue;
            }

            // 2) страна + перм
            String country = UpgradeCondition.zoneCountryCanonical(z);
            if (country == null || country.isBlank()) { reset(uid); continue; }

            if (countryMaxLevel(country, cfg.permBase(), 1) < 1) { reset(uid); continue; }

            // 3) рядом с лавкой
            if (!isNearBench(loc, rHard)) { reset(uid); continue; }

            // 4) почти не двигался
            if (!isStillEnough(p, loc)) {
                stillMs.remove(uid); // только накопление, без тотального сброса
                continue;
            }

            long st = stillMs.getOrDefault(uid, 0L) + addMs;
            if (st > 60_000) st = 60_000; // потолок, чтобы карта не росла бесконечно
            stillMs.put(uid, st);

            if (st < REQUIRED_STILL_MS) continue;

            // 5) кулдаун
            long next = nextProcAtMs.getOrDefault(uid, 0L);
            if (now < next) continue;
            nextProcAtMs.put(uid, now + COOLDOWN_MS);

            // 6) эффект
            cleanseNegative(p);
            giveRandomPositive(p, dur, amp);
        }
    }

    private boolean isStillEnough(Player p, Location nowLoc) {
        if (p.isSprinting()) return false;
        if (p.isGliding()) return false;
        if (p.isInWater() || p.isSwimming()) return false;

        UUID uid = p.getUniqueId();

        Pos prev = lastPos.put(uid, new Pos(nowLoc.getX(), nowLoc.getY(), nowLoc.getZ()));
        if (prev == null) return false;

        if (p.isInsideVehicle()) {
            int bxNow = nowLoc.getBlockX(), byNow = nowLoc.getBlockY(), bzNow = nowLoc.getBlockZ();

            int bxPrev = (int) Math.floor(prev.x);
            int byPrev = (int) Math.floor(prev.y);
            int bzPrev = (int) Math.floor(prev.z);

            return bxNow == bxPrev && byNow == byPrev && bzNow == bzPrev;
        }

        // Обычный режим: мягкие eps по координатам
        double dx = Math.abs(nowLoc.getX() - prev.x);
        double dy = Math.abs(nowLoc.getY() - prev.y);
        double dz = Math.abs(nowLoc.getZ() - prev.z);

        return dx <= EPS_XZ && dz <= EPS_XZ && dy <= EPS_Y;
    }

    private void reset(UUID uid) {
        lastPos.remove(uid);
        stillMs.remove(uid);
        nextProcAtMs.remove(uid);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { if (e != null) reset(e.getPlayer().getUniqueId()); }
    @EventHandler public void onKick(PlayerKickEvent e) { if (e != null) reset(e.getPlayer().getUniqueId()); }

    private static void cleanseNegative(Player p) {
        PotionEffectType[] bad = {
                PotionEffectType.POISON,
                PotionEffectType.WITHER,
                PotionEffectType.HUNGER,
                PotionEffectType.SLOWNESS,
                PotionEffectType.WEAKNESS,
                PotionEffectType.BLINDNESS,
                PotionEffectType.NAUSEA,
                PotionEffectType.MINING_FATIGUE,
                PotionEffectType.DARKNESS
        };
        for (PotionEffectType t : bad) {
            if (t != null && p.hasPotionEffect(t)) p.removePotionEffect(t);
        }
    }

    private static void giveRandomPositive(Player p, int dur, int amp) {
        int a = Math.min(amp, 1);
        PotionEffectType[] good = {
                PotionEffectType.JUMP_BOOST,
                PotionEffectType.NIGHT_VISION,
                PotionEffectType.ABSORPTION
        };
        PotionEffectType pick = good[ThreadLocalRandom.current().nextInt(good.length)];
        UpgradeCondition.applyPotionSmart(p, pick, dur, a, true, false, false);
    }

    private static boolean isNearBench(Location loc, int radius) {
        if (radius <= 0) return true;
        World w = loc.getWorld();
        if (w == null) return false;

        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();
        int r2 = radius * radius;

        // дешёвый быстрый чек вокруг
        for (int dy = -1; dy <= 1; dy++) {
            Material m = w.getBlockAt(cx, cy + dy, cz).getType();
            if (Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m)) return true;
        }

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx*dx + dz*dz > r2) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    Material m = w.getBlockAt(cx + dx, cy + dy, cz + dz).getType();
                    if (Tag.STAIRS.isTagged(m) || Tag.SLABS.isTagged(m)) return true;
                }
            }
        }
        return false;
    }
}
