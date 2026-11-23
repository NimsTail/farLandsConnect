package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.CountryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class CountryEffectsUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("country.effects");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private final Map<UUID, Long> soundTapUntil = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastApplied = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        CountryCfg.EffectsCfg e = ctx.config().country().effects();
        return e != null && e.enabled();
    }

    @Override
    protected void onDisable() {
        lastApplied.clear();

    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent e) {
        if (sameBlock(e)) return;
        Player p = e.getPlayer();

        CountryCfg.EffectsCfg cfg = C().country().effects();
        long now = System.currentTimeMillis();
        Long last = lastApplied.get(p.getUniqueId());
        if (last != null && (now - last) < cfg.reapplyCooldownMs()) return;
        checkAndApply(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin(), () -> checkAndApply(e.getPlayer()), 20L));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin(), () -> checkAndApply(p), 20L));
        CountryCfg.EffectsCfg cfg = C().country().effects();
    }

    private boolean sameBlock(PlayerMoveEvent e) {
        return e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ();
    }

    private void checkAndApply(Player p) {
        if (p == null || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;

        CountryCfg.EffectsCfg cfg = C().country().effects();

        long now = System.currentTimeMillis();
        Long last = lastApplied.get(p.getUniqueId());
        if (last != null && (now - last) < cfg.reapplyCooldownMs()) return;

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) { lastApplied.put(p.getUniqueId(), now); return; }

        String lc = UpgradeCondition.locationCountryOwner(p.getLocation());
        if (lc == null || !pc.equals(lc)) { lastApplied.put(p.getUniqueId(), now); return; }

        int hasteLvl = countryMaxLevel(pc, cfg.permHaste(), cfg.effectsMaxLevel());
        int speedLvl = countryMaxLevel(pc, cfg.permSpeed(), cfg.effectsMaxLevel());
        int resLvl   = countryMaxLevel(pc, cfg.permResist(), cfg.effectsMaxLevel());

        applyOrRemove(p, PotionEffectType.HASTE,      hasteLvl > 0 ? hasteLvl - 1 : -1, cfg.effectTicks());
        applyOrRemove(p, PotionEffectType.SPEED,      speedLvl > 0 ? speedLvl - 1 : -1, cfg.effectTicks());
        applyOrRemove(p, PotionEffectType.RESISTANCE, resLvl   > 0 ? resLvl   - 1 : -1, cfg.effectTicks());

        applyDustProtectionIfNeeded(p);

        lastApplied.put(p.getUniqueId(), now);
    }

    private void applyOrRemove(Player p, PotionEffectType type, int amplifier, int durationTicks) {
        UpgradeCondition.applyOrRemovePotionSmart(
                p,
                type,
                amplifier,
                durationTicks,
                true,
                false,
                true
        );
    }

    private void applyDustProtectionIfNeeded(Player p) {
        CountryCfg.EffectsCfg cfg = C().country().effects();
        CountryCfg.DustProtectionCfg dp = cfg.dustProtection();
        if (dp == null || !dp.enabled()) return;
        if (dp.permBase() == null || dp.permBase().isBlank()) return;

        Location loc = p.getLocation();
        if (loc.getBlockY() >= dp.minY()) return;

        ZoneInfo z = UpgradeCondition.zoneAt(loc);
        if (z == null || z.getType() != ZoneType.INDUSTRIAL) return;

        String pc = UpgradeCondition.playerCountryCanonical(p.getName());
        String zc = UpgradeCondition.zoneCountryCanonical(z);
        if (pc == null || !pc.equals(zc)) return;

        if (countryMaxLevel(pc, dp.permBase(), 1) < 1) return;

        UpgradeCondition.applyPotionSmart(
                p,
                PotionEffectType.NIGHT_VISION,
                dp.durationTicks(),
                0,
                true,
                false,
                true
        );

        if (C().core().debug()) plugin().getLogger().info("[Upgrades/DustProtection] NIGHT_VISION for " + p.getName() + " at " + loc);
    }
}
