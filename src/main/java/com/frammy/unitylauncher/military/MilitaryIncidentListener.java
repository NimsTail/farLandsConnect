package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * infra/military-diplomacy-design.md §2.2.1/§13 Фаза 4 — feeds the site's
 * weighted incident log backing the "нападение на гражданина" Casus Belli.
 * Always-on (not gated by any upgrade — the log itself is a base mechanic),
 * reports via FarLandsApiClient.reportMilitaryIncident, resolved into
 * country ids server-side (routes/plugin.ts).
 */
public class MilitaryIncidentListener implements Listener {

    // Damage-without-death dedup: one incident per (attacker, victim) pair
    // per window, not one per hit — §2.2.1 doesn't actually specify "per
    // hit" and a real fight would otherwise flood the log with dozens of
    // entries for a single encounter.
    private static final long DAMAGE_DEDUP_MS = 5 * 60 * 1000;
    private final Map<String, Long> lastDamageReport = new ConcurrentHashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer == null) return;

        var api = UnityLauncher.getInstance().getFarLandsApi();
        if (api != null) api.reportMilitaryIncident("pvp_kill", killer.getName(), victim.getName());

        // A death already covered by the kill above shouldn't also count as
        // a separate "damage" incident — clear any pending dedup entry.
        lastDamageReport.remove(dedupKey(killer.getName(), victim.getName()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (e.getFinalDamage() <= 0) return;

        String key = dedupKey(attacker.getName(), victim.getName());
        long now = System.currentTimeMillis();
        Long last = lastDamageReport.get(key);
        if (last != null && now - last < DAMAGE_DEDUP_MS) return;
        lastDamageReport.put(key, now);

        var api = UnityLauncher.getInstance().getFarLandsApi();
        if (api != null) api.reportMilitaryIncident("pvp_damage", attacker.getName(), victim.getName());
    }

    private static String dedupKey(String attacker, String victim) {
        return attacker.toLowerCase(java.util.Locale.ROOT) + "->" + victim.toLowerCase(java.util.Locale.ROOT);
    }
}
