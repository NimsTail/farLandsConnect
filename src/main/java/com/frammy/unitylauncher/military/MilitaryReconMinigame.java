package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import io.papermc.paper.event.block.BellRingEvent;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * infra/military-diplomacy-design.md §4.1 п.3, GH#24 п.1/вопрос №15 — the
 * Колокол-timing minigame that decides recon's quality bonus (0-5%).
 *
 * Started by the site via ZoneRequestPoller (action START_RECON_MINIGAME) —
 * see start(). Over ENGAGEMENT_WINDOW_MS (40s, MUST match the backend's
 * militaryRecon.ts ENGAGEMENT_WINDOW_MS) the object's anchor Колокол fires
 * SIGNAL_COUNT signals at irregular intervals; ringing it within
 * RESPONSE_WINDOW_MS of a signal earns credit for that signal (full credit
 * if fast, partial if slower-but-still-in-window), summed and capped at
 * MAX_BONUS_PERCENT. When the window ends, reports the total to the site
 * (FarLandsApiClient.reportReconQuality) — that call is what actually rolls
 * the recon dice server-side (see routes/plugin.ts's
 * /plugin/military/recon-quality).
 *
 * Only ONE global BellRingEvent listener regardless of how many sessions are
 * active (should never be more than a handful server-wide) — every ring on
 * every bell everywhere still reaches onRing(), but the active-session map
 * is small and the per-ring work is O(active sessions), not O(all blocks).
 */
public final class MilitaryReconMinigame implements Listener {

    // §4.1 п.3 — must match backend militaryRecon.ts's ENGAGEMENT_WINDOW_MS.
    public static final long ENGAGEMENT_WINDOW_MS = 40_000L;
    private static final int SIGNAL_COUNT = 5;
    private static final long FIRST_SIGNAL_MIN_MS = 2_000L;
    private static final long FIRST_SIGNAL_MAX_MS = 4_000L;
    private static final long SIGNAL_GAP_MIN_MS = 6_000L;
    private static final long SIGNAL_GAP_MAX_MS = 10_000L;
    private static final long RESPONSE_WINDOW_MS = 2_000L;
    private static final long FULL_CREDIT_MS = 500L;
    private static final double CREDIT_PER_SIGNAL_FULL = 1.0; // % — 5 signals × 1% = the agreed +5% ceiling
    private static final double CREDIT_PER_SIGNAL_PARTIAL = 0.5;

    private final MilitarySpecializationService specializationService;
    private final Random random = new Random();
    // markerId -> session. A zone can only ever have one recon in flight at
    // a time (site-side cooldown already enforces this), so markerId is a
    // safe key — no risk of two sessions racing on the same map entry.
    private final Map<String, Session> activeByMarkerId = new ConcurrentHashMap<>();

    private static final class Session {
        final String sessionId;
        final ZoneInfo zone;
        final Location anchor;
        volatile long currentSignalFiredAt = -1;
        volatile int currentSignalIndex = -1;
        volatile int creditedSignalIndex = -1;
        volatile double bonus = 0;

        Session(String sessionId, ZoneInfo zone, Location anchor) {
            this.sessionId = sessionId;
            this.zone = zone;
            this.anchor = anchor;
        }
    }

    public MilitaryReconMinigame(MilitarySpecializationService specializationService) {
        this.specializationService = specializationService;
    }

    /**
     * Called from ZoneRequestPoller when the site starts a recon attempt.
     * Defense in depth — the site already checked specialization/anchor
     * against its own (slightly-behind) mirror before asking for this, but
     * plugin state is the actual truth, so re-check here too; a "no" here
     * just means the site's session never gets a quality report (falls
     * back to base chance with no bonus, not a hard failure — see
     * lib/militaryRecon.ts's module comment on unresolved sessions).
     */
    public boolean start(ZoneInfo zone, String sessionId) {
        if (zone == null || zone.getType() != ZoneType.MILITARY) return false;
        if (specializationService.current(zone) != MilitarySpecialization.RECON) return false;
        Location anchor = zone.getMilitaryAnchorLocation();
        if (anchor == null || anchor.getWorld() == null) return false;
        if (!MilitaryAnchorService.isExposed(anchor)) return false; // GH#24 — buried after being placed, no longer counts

        Session session = new Session(sessionId, zone, anchor);
        activeByMarkerId.put(zone.getMarkerID(), session);
        scheduleSignals(session);
        UnityLauncher.getInstance().getServer().getScheduler().runTaskLater(
                UnityLauncher.getInstance(), () -> finish(session), ticksFor(ENGAGEMENT_WINDOW_MS));
        return true;
    }

    private void scheduleSignals(Session session) {
        long offset = FIRST_SIGNAL_MIN_MS + (long) (random.nextDouble() * (FIRST_SIGNAL_MAX_MS - FIRST_SIGNAL_MIN_MS));
        List<Long> offsets = new ArrayList<>(SIGNAL_COUNT);
        for (int i = 0; i < SIGNAL_COUNT; i++) {
            offsets.add(offset);
            offset += SIGNAL_GAP_MIN_MS + (long) (random.nextDouble() * (SIGNAL_GAP_MAX_MS - SIGNAL_GAP_MIN_MS));
        }

        var scheduler = UnityLauncher.getInstance().getServer().getScheduler();
        for (int i = 0; i < offsets.size(); i++) {
            int signalIndex = i;
            long delayMs = offsets.get(i);
            if (delayMs >= ENGAGEMENT_WINDOW_MS) continue; // jitter pushed it past the window — just skip, not worth reshuffling
            scheduler.runTaskLater(UnityLauncher.getInstance(), () -> fireSignal(session, signalIndex), ticksFor(delayMs));
        }
    }

    private void fireSignal(Session session, int signalIndex) {
        if (!activeByMarkerId.containsKey(session.zone.getMarkerID())) return; // session already finished/removed
        session.currentSignalFiredAt = System.currentTimeMillis();
        session.currentSignalIndex = signalIndex;
        session.anchor.getWorld().playSound(session.anchor, Sound.BLOCK_BELL_RESONATE, 1.2f, 1.0f);
        session.anchor.getWorld().spawnParticle(Particle.NOTE, session.anchor.clone().add(0.5, 1.2, 0.5), 8, 0.3, 0.3, 0.3);
    }

    @EventHandler(ignoreCancelled = true)
    public void onRing(BellRingEvent e) {
        if (activeByMarkerId.isEmpty()) return; // cheap early-exit — see class javadoc re: load
        Location loc = e.getBell().getLocation();
        Entity ringer = e.getEntity();

        for (Session s : activeByMarkerId.values()) {
            if (!sameBlock(s.anchor, loc)) continue;
            registerHit(s, ringer);
            return;
        }
    }

    private void registerHit(Session s, Entity ringer) {
        if (s.currentSignalFiredAt < 0) return; // rung before the first signal — no window open yet
        if (s.creditedSignalIndex == s.currentSignalIndex) return; // this signal already credited, extra rings do nothing
        long elapsed = System.currentTimeMillis() - s.currentSignalFiredAt;
        if (elapsed > RESPONSE_WINDOW_MS) return; // too late for this signal

        double credit = elapsed <= FULL_CREDIT_MS ? CREDIT_PER_SIGNAL_FULL : CREDIT_PER_SIGNAL_PARTIAL;
        s.bonus += credit;
        s.creditedSignalIndex = s.currentSignalIndex;

        if (ringer != null) {
            s.anchor.getWorld().playSound(s.anchor, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.5f);
        }
    }

    private void finish(Session session) {
        activeByMarkerId.remove(session.zone.getMarkerID());
        double bonus = Math.min(5.0, session.bonus);
        var api = UnityLauncher.getInstance().getFarLandsApi();
        if (api != null) api.reportReconQuality(session.sessionId, bonus);
    }

    private static boolean sameBlock(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private static long ticksFor(long ms) {
        return Math.max(1L, ms / 50L);
    }
}
