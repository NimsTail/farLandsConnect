package com.frammy.unitylauncher.zones.geom;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneTypeData;
import org.bukkit.Location;
import org.bukkit.World;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public final class ZoneGeometry {

    private ZoneGeometry() {}

    // ---- basic world / polygon helpers ----

    public static boolean worldOk(List<Location> corners, World w) {
        return corners != null && corners.size() >= 3 && Objects.equals(corners.getFirst().getWorld(), w);
    }

    public static List<Vector2d> poly2D(List<Location> corners) {
        return corners.stream()
                .map(l -> new Vector2d(l.getX(), l.getZ()))
                .collect(Collectors.toList());
    }

    public static boolean pointInPolygon(Vector2d p, List<Vector2d> poly) {
        boolean inside = false;
        int j = poly.size() - 1;

        for (int i = 0; i < poly.size(); i++) {
            Vector2d a = poly.get(i), b = poly.get(j);

            boolean inter = ((a.getY() > p.getY()) != (b.getY() > p.getY()))
                    && (p.getX() < (b.getX() - a.getX()) * (p.getY() - a.getY()) / (b.getY() - a.getY()) + a.getX());

            if (inter) inside = !inside;
            j = i;
        }
        return inside;
    }

    public static boolean pointInZone(Location loc, List<Location> corners, double yMin, double yMax) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!worldOk(corners, loc.getWorld())) return false;
        double y = loc.getY();
        if (y < yMin || y > yMax) return false;

        return pointInPolygon(new Vector2d(loc.getX(), loc.getZ()), poly2D(corners));
    }

    // ---- self-intersection checks ----

    private static boolean ccw(Vector2d a, Vector2d b, Vector2d c) {
        return (b.getX() - a.getX()) * (c.getY() - a.getY()) - (b.getY() - a.getY()) * (c.getX() - a.getX()) > 0;
    }

    private static boolean segInter(Vector2d a, Vector2d b, Vector2d c, Vector2d d) {
        return ccw(a, c, d) != ccw(b, c, d) && ccw(a, b, c) != ccw(a, b, d);
    }

    public static boolean hasSelfIntersections(List<Vector2d> pts) {
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            Vector2d a1 = pts.get(i), a2 = pts.get((i + 1) % n);

            for (int j = i + 2; j < n; j++) {
                if (Math.abs(i - j) == 1 || (i == 0 && j == n - 1)) continue;

                Vector2d b1 = pts.get(j), b2 = pts.get((j + 1) % n);
                if (segInter(a1, a2, b1, b2)) return true;
            }
        }
        return false;
    }

    // ---- area checks ----

    public static boolean areaOk(List<Location> pts, ZoneTypeData ztd) {
        double area = calculateSurfaceArea(pts);
        if (pts.size() >= 3 && area < ztd.minSize()) return false;
        return area <= ztd.areaLimit();
    }

    /** During addcorner draft: check only upper bound to keep it snappy. */
    public static boolean areaOkDraft(List<Location> pts, ZoneTypeData ztd) {
        double area = calculateSurfaceArea(pts);
        return area <= ztd.areaLimit();
    }

    // ---- JTS polygon conversion ----

    private static final GeometryFactory GF = new GeometryFactory();

    public static Polygon toJtsPolygon(ZoneInfo z) {
        if (z == null) return null;

        List<Location> pts = z.getCorners();
        if (pts == null || pts.size() < 3) return null;

        Coordinate[] ring = new Coordinate[pts.size() + 1];
        for (int i = 0; i < pts.size(); i++) {
            Location L = pts.get(i);
            ring[i] = new Coordinate(L.getX(), L.getZ());
        }
        ring[ring.length - 1] = new Coordinate(pts.getFirst().getX(), pts.getFirst().getZ());

        try {
            LinearRing shell = new LinearRing(new CoordinateArraySequence(ring), GF);
            if (!shell.isValid()) return null;
            return new Polygon(shell, null, GF);
        } catch (Throwable t) {
            return null;
        }
    }

}
