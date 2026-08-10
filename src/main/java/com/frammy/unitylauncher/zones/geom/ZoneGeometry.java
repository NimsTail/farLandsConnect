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

    // ---- multi-shape (мульти-полигон) helpers ----

    /** true, если loc попадает в мир+Y-диапазон+хотя бы ОДНУ из фигур зоны. */
    public static boolean pointInAnyShape(Location loc, List<List<Location>> shapes, double yMin, double yMax) {
        if (shapes == null) return false;
        for (List<Location> shape : shapes) {
            if (pointInZone(loc, shape, yMin, yMax)) return true;
        }
        return false;
    }

    /** true, если ВСЕ фигуры принадлежат указанному миру (используется вместо worldOk для мульти-полигона). */
    public static boolean worldOkAny(List<List<Location>> shapes, World w) {
        if (shapes == null || shapes.isEmpty()) return false;
        for (List<Location> shape : shapes) {
            if (!worldOk(shape, w)) return false;
        }
        return true;
    }

    /** Суммарная площадь по всем фигурам зоны — используется для лимитов/биллинга многофигурных зон. */
    public static double totalArea(List<List<Location>> shapes) {
        double sum = 0.0;
        if (shapes == null) return 0.0;
        for (List<Location> shape : shapes) sum += calculateSurfaceArea(shape);
        return sum;
    }

    private static double distPointToSegment2D(double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq == 0) return Math.hypot(px - ax, pz - az);
        double t = ((px - ax) * dx + (pz - az) * dz) / lenSq;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }

    /** Минимальное расстояние между двумя (не пересекающимися) полигонами по XZ — по рёбрам/вершинам. */
    public static double minDistanceBetweenPolygons(List<Location> a, List<Location> b) {
        if (a == null || b == null || a.size() < 3 || b.size() < 3) return Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        int na = a.size(), nb = b.size();
        for (int i = 0; i < na; i++) {
            Location a1 = a.get(i), a2 = a.get((i + 1) % na);
            for (int j = 0; j < nb; j++) {
                Location bp = b.get(j);
                double d = distPointToSegment2D(bp.getX(), bp.getZ(), a1.getX(), a1.getZ(), a2.getX(), a2.getZ());
                if (d < min) min = d;
            }
        }
        for (int j = 0; j < nb; j++) {
            Location b1 = b.get(j), b2 = b.get((j + 1) % nb);
            for (int i = 0; i < na; i++) {
                Location ap = a.get(i);
                double d = distPointToSegment2D(ap.getX(), ap.getZ(), b1.getX(), b1.getZ(), b2.getX(), b2.getZ());
                if (d < min) min = d;
            }
        }
        return min;
    }

    /** Одна JTS-полигон-фигура на каждый элемент shapes (null-фигуры/невалидные пропускаются). */
    public static List<Polygon> toJtsPolygons(ZoneInfo z) {
        List<Polygon> out = new java.util.ArrayList<>();
        if (z == null) return out;
        for (List<Location> shape : z.getShapes()) {
            Polygon p = toJtsPolygon(shape);
            if (p != null) out.add(p);
        }
        return out;
    }

    /**
     * Минимальный зазор между отдельными фигурами одной зоны — меньше этого
     * расстояния фигуры считаются "по сути одной" (спорная граница, риск
     * пересечения одного и того же чанка при биллинге). Половина чанка.
     */
    public static final double MIN_GAP_BETWEEN_SHAPES = 8.0;

    /**
     * Максимальное расстояние от новой фигуры до ближайшей из уже существующих
     * частей той же зоны. Не даёт "раскидать" одну зону по всей карте —
     * куски одной зоны должны быть в одном районе. Значение выбрано с запасом
     * (даже для самых больших лимитов площади зон, ~1000 блоков², сторона
     * квадрата ~32 блока — 300 блоков это уже "через несколько кварталов").
     */
    public static final double MAX_SHAPE_SPREAD = 170.0;

    /**
     * Проверяет зазор между newShape и БЛИЖАЙШЕЙ существующей фигурой зоны.
     * Возвращает null, если всё ок, иначе — сообщение об ошибке для игрока.
     */
    public static String checkShapeSpacing(List<Location> newShape, List<List<Location>> existingShapes) {
        if (existingShapes == null || existingShapes.isEmpty()) return null;

        double nearest = Double.MAX_VALUE;
        for (List<Location> other : existingShapes) {
            double d = minDistanceBetweenPolygons(newShape, other);
            if (d < nearest) nearest = d;
        }

        if (nearest < MIN_GAP_BETWEEN_SHAPES) {
            return "Новая фигура слишком близко к существующей части зоны (минимум "
                    + (int) MIN_GAP_BETWEEN_SHAPES + " блоков зазора) — если они должны соприкасаться, "
                    + "объедините их в одну фигуру вместо этого.";
        }
        if (nearest > MAX_SHAPE_SPREAD) {
            return "Новая фигура слишком далеко от остальной части зоны (максимум "
                    + (int) MAX_SHAPE_SPREAD + " блоков до ближайшей части) — части одной зоны должны находиться рядом.";
        }
        return null;
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
        return z != null ? toJtsPolygon(z.getCorners()) : null;
    }

    public static Polygon toJtsPolygon(List<Location> pts) {
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

    /**
     * true, если newCorners целиком покрывают oldCorners (расширение без потери
     * старой территории/изменения формы уже существующей части). Используем
     * covers(), а не contains() — общие рёбра/точки границы допустимы, это
     * норма при "дорисовывании" новой площади к старой.
     */
    public static boolean isExpansionOnly(List<Location> oldCorners, List<Location> newCorners) {
        Polygon oldPoly = toJtsPolygon(oldCorners);
        Polygon newPoly = toJtsPolygon(newCorners);
        if (oldPoly == null || newPoly == null) return false;
        try {
            return newPoly.covers(oldPoly);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- overlap vs. touching ----

    /**
     * Минимальная площадь пересечения (блоков²), начиная с которой считаем, что зоны
     * НАСТОЯЩЕ пересекаются по площади. Ниже этого порога — это просто касание общей
     * гранью/вершиной (например, зона снаппнута вплотную к соседней или ровно к
     * границе своей страны), что пересечением НЕ считается.
     */
    private static final double MIN_TRUE_OVERLAP_AREA = 0.05;

    /**
     * true, только если полигоны пересекаются НАСТОЯЩЕЙ площадью, а не просто касаются
     * границей/вершиной. В отличие от голого {@code Polygon#intersects}, который
     * возвращает true и для двух зон, соприкасающихся ребро-в-ребро без наложения.
     */
    public static boolean trueAreaOverlap(Polygon a, Polygon b) {
        if (a == null || b == null) return false;
        try {
            if (!a.intersects(b)) return false;
            Geometry inter = a.intersection(b);
            return inter != null && !inter.isEmpty() && inter.getArea() > MIN_TRUE_OVERLAP_AREA;
        } catch (Throwable t) {
            return true; // fail-closed
        }
    }

}
