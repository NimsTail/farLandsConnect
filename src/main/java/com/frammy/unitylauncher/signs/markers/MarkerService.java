package com.frammy.unitylauncher.signs.markers;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.zones.ZoneManager;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.*;

public final class MarkerService {
    private final ZoneManager zoneManager;

    private long cacheUntilMs = 0L;
    private java.util.List<ExtrudeMarker> cachedExtrudes = java.util.List.of();
    private String cachedWorld = null;
    private String cachedSet = null;

    public MarkerService(ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
    }

    public ExtrudeMarker findExtrudeMarker(Location loc, String setName, boolean debug) {
        if (loc == null || loc.getWorld() == null) return null;

        long now = System.currentTimeMillis();
        String worldName = loc.getWorld().getName();

        if (now > cacheUntilMs || !Objects.equals(cachedWorld, worldName) || !Objects.equals(cachedSet, setName)) {
            cachedWorld = worldName;
            cachedSet = setName;
            cacheUntilMs = now + 3000; // 3 секунды
            cachedExtrudes = loadExtrudes(worldName, setName, debug);
        }

        if (cachedExtrudes.isEmpty()) return null;

        var p2 = new Vector2d(loc.getBlockX() + 0.5, loc.getBlockZ() + 0.5);
        double y = loc.getY();

        for (ExtrudeMarker extrude : cachedExtrudes) {
            Shape shape = extrude.getShape();
            double minY = extrude.getShapeMinY();
            double maxY = extrude.getShapeMaxY();

            boolean insidePoly = zoneManager.isPointInsidePolygon(p2, Collections.singletonList(shape.getPoints()));
            boolean insideY = (y >= minY && y <= maxY);

            if (insidePoly && insideY) return extrude;
        }

        return null;
    }

    private java.util.List<ExtrudeMarker> loadExtrudes(String worldName, String setName, boolean debug) {
        Optional<BlueMapAPI> apiOpt = BlueMapAPI.getInstance();
        if (apiOpt.isEmpty()) {
            if (debug) Bukkit.getLogger().info("[Signs] BlueMapAPI not ready");
            return List.of();
        }

        BlueMapAPI api = apiOpt.get();
        Optional<BlueMapMap> mapOpt = api.getMap(worldName);
        if (mapOpt.isEmpty()) {
            if (debug) Bukkit.getLogger().info("[Signs] BlueMap map not found for " + worldName);
            return List.of();
        }

        BlueMapMap map = mapOpt.get();
        MarkerSet set = map.getMarkerSets().get(setName);
        if (set == null) {
            if (debug) Bukkit.getLogger().info("[Signs] MarkerSet '" + setName + "' not found");
            return List.of();
        }

        ArrayList<ExtrudeMarker> out = new ArrayList<>();
        for (Marker m : set.getMarkers().values()) {
            if (m instanceof ExtrudeMarker em) out.add(em);
        }
        return out;
    }
}
