package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.HospitalCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class BloodGiftUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("hospital.blood_gift");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private static final class State {
        long expiryMs;
        boolean announced;
        State(long expiryMs) { this.expiryMs = expiryMs; }
    }

    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private BukkitTask cleanupTask;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        HospitalCfg.BloodGiftCfg cfg = ctx.config().hospital().bloodGift();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin(), this::cleanup, 20L * 5, 20L * 5);
    }

    @Override
    protected void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        states.clear();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onLeaveBed(PlayerBedLeaveEvent e) {
        Player p = e.getPlayer();
        Location bedLoc = e.getBed().getLocation();

        if (!UpgradeCondition.isInsideZoneTypeRaw(bedLoc, ZoneType.HOSPITAL)) return;

        String country = UpgradeCondition.locationCountryOwner(bedLoc);
        if (country == null || country.isBlank()) return;

        var cfg = C().hospital().bloodGift();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        applyOrExtend(p, cfg);
    }

    private void applyOrExtend(Player p, HospitalCfg.BloodGiftCfg cfg) {
        long now = System.currentTimeMillis();
        long addMs = Math.max(1, cfg.durationMinutes()) * 60_000L;
        long newExpiry = now + addMs;

        State st = states.get(p.getUniqueId());
        if (st == null) {
            st = new State(newExpiry);
            states.put(p.getUniqueId(), st);
        } else {
            st.expiryMs = Math.max(st.expiryMs, newExpiry);
        }

        long remainingMs = Math.max(0L, st.expiryMs - now);
        int remainingTicks = (int) Math.min(Integer.MAX_VALUE, Math.max(20L, remainingMs / 50L));

        if (cfg.absorptionEnabled()) {
            int amp = Math.max(0, cfg.absorptionAmplifier());
            if (amp > 0) amp = 0; // не больше 2 сердец
            UpgradeCondition.applyPotionSmart(
                    p,
                    PotionEffectType.ABSORPTION,
                    remainingTicks,
                    amp,
                    true,
                    false,
                    true
            );

        }

        if (cfg.regenEnabled()) {
            int rt = Math.max(20, cfg.regenTicks());
            int ra = Math.max(0, cfg.regenAmplifier());
            UpgradeCondition.applyPotionSmart(
                    p,
                    PotionEffectType.REGENERATION,
                    rt,
                    ra,
                    true,
                    false,
                    true
            );

        }

        if (!st.announced) {
            st.announced = true;
            String msg = cfg.msgStart();
            if (msg == null || msg.isBlank()) msg = "§a✚ Дар крови активирован на §e%d§a мин.";
            p.sendMessage(String.format(msg, cfg.durationMinutes()));
        }
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        var cfg = C().hospital().bloodGift();

        Iterator<Map.Entry<UUID, State>> it = states.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            State st = en.getValue();
            if (st.expiryMs > now) continue;

            UUID uuid = en.getKey();
            it.remove();

            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            if (cfg.absorptionEnabled()) {
                p.removePotionEffect(PotionEffectType.ABSORPTION);
            }

            String msg = cfg.msgEnd();
            if (msg == null || msg.isBlank()) msg = "§eЭффект 'Дар крови' закончился";
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }
}
