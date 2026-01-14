package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.CountryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class ChurchUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("church.progress");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private static final Set<PotionEffectType> NEGATIVE_EFFECTS = Set.of(
            PotionEffectType.BAD_OMEN,
            PotionEffectType.BLINDNESS,
            PotionEffectType.DARKNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.LEVITATION,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.NAUSEA,
            PotionEffectType.POISON,
            PotionEffectType.SLOWNESS,
            PotionEffectType.UNLUCK,
            PotionEffectType.WEAKNESS,
            PotionEffectType.WITHER
    );

    private final Map<UUID, ChurchProgress> churchProgress = new ConcurrentHashMap<>();
    private final Map<String, Long> bellCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Long>> pilgrimCooldownUntil = new ConcurrentHashMap<>();

    private final Map<UUID, Long> actionbarCooldownUntil = new ConcurrentHashMap<>();
    private static final long ACTIONBAR_THROTTLE_MS = 2000L;

    private record ChurchProgress(String zoneId, long enteredAtMs) {}

    @Override
    public boolean enabledByConfig(com.frammy.unitylauncher.upgrades.core.UpgradeContext ctx) {
        CountryCfg.ChurchCfg c = ctx.config().country().church();
        return c != null && c.enabled();
    }

    @Override
    protected void onEnable() {
        // Раз в секунду проверяем прогресс тем, кто уже "отслеживается".
        // Это убирает требование "шевельнуться", чтобы получить эффект.
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin(), () -> {
            if (churchProgress.isEmpty()) return;

            // Берём снапшот ключей, чтобы не словить ConcurrentModification
            for (UUID id : new ArrayList<>(churchProgress.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.isOnline()) {
                    churchProgress.remove(id);
                    actionbarCooldownUntil.remove(id);
                    continue;
                }
                trackChurchProgress(p);
            }
        }, 20L, 20L));
    }

    @Override
    protected void onDisable() {
        churchProgress.clear();
        bellCooldownUntil.clear();
        pilgrimCooldownUntil.clear();
        actionbarCooldownUntil.clear();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (sameBlock(e)) return;
        trackChurchProgress(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin(), () -> trackChurchProgress(e.getPlayer()), 20L));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin(), () -> trackChurchProgress(e.getPlayer()), 20L));
    }

    private boolean sameBlock(PlayerMoveEvent e) {
        return e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ();
    }

    private void trackChurchProgress(Player p) {
        if (p == null || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;

        CountryCfg.ChurchCfg cfg = C().country().church();
        if (cfg == null || !cfg.enabled()) {
            churchProgress.remove(p.getUniqueId());
            return;
        }

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null || z.getType() != ZoneType.CHURCH) {
            churchProgress.remove(p.getUniqueId());
            return;
        }

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        String zc = UpgradeCondition.zoneCountryCanonical(z);
        if (pc == null || !pc.equals(zc)) {
            churchProgress.remove(p.getUniqueId());
            return;
        }

        boolean bellEnabled = cfg.bell() != null
                && cfg.bell().enabled()
                && countryMaxLevel(pc, cfg.bell().permBase(), 1) >= 1;

        boolean pilgEnabled = cfg.pilgrimage() != null
                && cfg.pilgrimage().enabled()
                && countryMaxLevel(pc, cfg.pilgrimage().permBase(), 1) >= 1;

        if (!bellEnabled && !pilgEnabled) {
            churchProgress.remove(p.getUniqueId());
            return;
        }

        String zoneId = z.getID();
        long now = System.currentTimeMillis();
        ChurchProgress cur = churchProgress.get(p.getUniqueId());

        if (cur == null || !Objects.equals(cur.zoneId, zoneId)) {
            churchProgress.put(p.getUniqueId(), new ChurchProgress(zoneId, now));
            return;
        }

        long stayedMs = now - cur.enteredAtMs;

        // ===== Мирный колокол =====
        if (bellEnabled) {
            CountryCfg.BellCfg bell = cfg.bell();
            long needMs = Math.max(0, bell.stayMinutes()) * 60_000L;

            if (stayedMs >= needMs) {
                long until = bellCooldownUntil.getOrDefault(zoneId, 0L);

                if (now >= until) {
                    clearNegativeEffects(p);

                    if (bell.sfx()) {
                        var pos = p.getLocation().clone().add(0, 1.5, 0);
                        p.getWorld().playSound(pos, Sound.BLOCK_BELL_RESONATE, 1.0f, 1.0f);
                        p.getWorld().spawnParticle(Particle.NOTE, pos, 8, 0.6, 0.6, 0.6, 0.0);
                    }

                    long cdMs = Math.max(0, bell.cooldownMinutes()) * 60_000L;
                    bellCooldownUntil.put(zoneId, now + cdMs);

                    churchProgress.put(p.getUniqueId(), new ChurchProgress(zoneId, now));
                } else {
                    long leftMs = until - now;
                    sendCooldownActionbar(p, "§eКолокол: кулдаун §6" + formatMmSs(leftMs));
                }
            }

        }

        // ===== Паломничество =====
        if (pilgEnabled) {
            CountryCfg.PilgrimageCfg pil = cfg.pilgrimage();
            long needMs = Math.max(0, pil.stayMinutes()) * 60_000L;

            if (stayedMs >= needMs) {
                long cdMs = Math.max(0, pil.cooldownMinutes()) * 60_000L;
                long nowMs = System.currentTimeMillis();

                Map<UUID, Long> perPlayer = pilgrimCooldownUntil.computeIfAbsent(zoneId, k -> new ConcurrentHashMap<>());
                long until = perPlayer.getOrDefault(p.getUniqueId(), 0L);

                if (cdMs == 0 || nowMs >= until) {
                    applyPilgrimageBuff(p, pil);
                    if (cdMs > 0) perPlayer.put(p.getUniqueId(), nowMs + cdMs);

                    if (pil.sfx()) {
                        var pos = p.getLocation().clone().add(0, 1.0, 0);
                        p.getWorld().playSound(pos, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f);
                        p.getWorld().spawnParticle(Particle.SPLASH, pos, 20, 0.5, 0.6, 0.5, 0.0);
                    }

                    churchProgress.put(p.getUniqueId(), new ChurchProgress(zoneId, nowMs));
                } else {
                    long leftMs = until - nowMs;
                    sendCooldownActionbar(p, "§bПаломничество: кулдаун §3" + formatMmSs(leftMs));
                }

            }
        }
    }

    private void clearNegativeEffects(Player p) {
        try {
            for (PotionEffectType t : NEGATIVE_EFFECTS) {
                if (t == null) continue;
                p.removePotionEffect(t);
            }
        } catch (Throwable ignored) {}

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/ChurchBell] cleared negatives for " + p.getName());
    }

    private void applyPilgrimageBuff(Player p, CountryCfg.PilgrimageCfg pil) {
        if (pil.effects() == null || pil.effects().isEmpty()) return;

        List<String> list = new ArrayList<>(pil.effects());
        String pick = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        PotionEffectType type = PotionEffectType.getByName(pick);
        if (type == null) return;

        int amp = Math.max(0, pil.amplifier());
        int durTicks = Math.max(20, pil.buffMinutes() * 60 * 20);

        p.addPotionEffect(new PotionEffect(type, durTicks, amp, true, true, true));

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/Pilgrimage] " + p.getName()
                + " got " + type.getName() + " for " + pil.buffMinutes() + "m @amp=" + amp);
    }

    private void sendCooldownActionbar(Player p, String msg) {
        long now = System.currentTimeMillis();
        long until = actionbarCooldownUntil.getOrDefault(p.getUniqueId(), 0L);
        if (now < until) return;

        actionbarCooldownUntil.put(p.getUniqueId(), now + ACTIONBAR_THROTTLE_MS);

        try {
            p.sendActionBar(Component.text(msg));
        } catch (Throwable t) {
            // На случай если Adventure не доступен/сломался
            try {
                p.sendMessage(msg);
            } catch (Throwable ignored) {}
        }
    }

    private String formatMmSs(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = (ms + 999) / 1000; // округляем вверх, чтобы игрок видел “честно”
        long mm = totalSec / 60;
        long ss = totalSec % 60;
        return String.format("%d:%02d", mm, ss);
    }

}
