package com.frammy.unitylauncher.military;

import com.flowpowered.math.vector.Vector3d;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.auth.FarLandsApiClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * GH #27 "Географические объекты" — infra/geographic-landmarks-design.md
 * §8 п.4. Landmarks are website-authoritative (no ZoneRequest round-trip
 * for creation, unlike every other zone type) — this is the other
 * direction of that: the plugin periodically PULLS the current list and
 * mirrors it onto BlueMap, read-only. Same shape as WarStatusCache
 * (Bukkit.getScheduler().runTaskTimerAsynchronously polling a GET), just
 * driving BlueMap markers instead of an in-memory war-pairs set.
 */
public final class LandmarkSyncService {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final BlueMapIntegration blueMap;
    private final Logger log;

    public LandmarkSyncService(JavaPlugin plugin, FarLandsApiClient api, BlueMapIntegration blueMap, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.blueMap = blueMap;
        this.log = log;
    }

    /** Call once from onEnable. periodTicks: 20 ticks = 1 second — this doesn't need to be fast, landmarks change rarely. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::syncOnce, 0L, periodTicks);
    }

    private void syncOnce() {
        List<FarLandsApiClient.LandmarkDto> landmarks = api.fetchLandmarks();
        Set<String> currentIds = new HashSet<>();

        for (var lm : landmarks) {
            World world = Bukkit.getWorld(lm.world());
            if (world == null) continue;
            currentIds.add(lm.id());

            String label = ChatColor.stripColor(lm.officialName() != null ? lm.officialName() : "???");

            if ("POINT".equalsIgnoreCase(lm.markerKind())) {
                if (lm.px() == null || lm.py() == null || lm.pz() == null) continue;
                Location loc = new Location(world, lm.px(), lm.py(), lm.pz());
                blueMap.applyLandmarkPointMarker(lm.id(), loc, label);
            } else if ("AREA".equalsIgnoreCase(lm.markerKind())) {
                List<Vector3d> points = new ArrayList<>();
                // BlueMap Shape только XZ — Y здесь для blueMapShapeFromExtrude
                // не важен (см. BlueMapIntegration.applyLandmarkAreaMarker),
                // берём высоту мира по умолчанию как заглушку.
                double y = world.getSpawnLocation().getY();
                for (double[] p : lm.points()) points.add(new Vector3d(p[0], y, p[1]));
                for (List<double[]> shape : lm.extraShapes()) {
                    // Мульти-полигон площадных геообъектов пока не поддержан
                    // маркером (один ShapeMarker = одна фигура) — доп. фигуры
                    // просто не рисуются, не критично для первой версии.
                    if (shape.isEmpty()) continue;
                }
                blueMap.applyLandmarkAreaMarker(lm.id(), lm.world(), points, label);
            }
        }

        blueMap.pruneLandmarkMarkers(currentIds);
    }
}
