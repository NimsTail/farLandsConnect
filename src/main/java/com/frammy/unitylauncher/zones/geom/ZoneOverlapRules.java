package com.frammy.unitylauncher.zones.geom;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import com.frammy.unitylauncher.zones.ZoneTypeData;
import org.bukkit.Location;
import org.bukkit.World;
import org.locationtech.jts.geom.Polygon;

import java.util.*;

public final class ZoneOverlapRules {

    private ZoneOverlapRules() {}

    // ------------------- public API -------------------

    /** Совместимы ли зоны: либо не пересекаются, либо оверлап разрешён правилами. */
    public static boolean canZonesCoexist(
            ZoneInfo a,
            ZoneInfo b,
            Map<ZoneType, ZoneTypeData> zoneLimits
    ) {
        if (a == null || b == null) return true;
        if (!zonesIntersect2D(a, b)) return true;
        return isOverlapAllowed(a, b, zoneLimits);
    }

    /** Геометрически пересекаются ли полигоны зон по XZ в рамках одного мира. Высота игнорируется. */
    public static boolean zonesIntersect2D(ZoneInfo a, ZoneInfo b) {
        World wa = (a != null) ? a.getWorld() : null;
        World wb = (b != null) ? b.getWorld() : null;
        if (wa == null || wb == null) return false;
        if (!wa.getUID().equals(wb.getUID())) return false;

        Polygon pa = ZoneGeometry.toJtsPolygon(a);
        Polygon pb = ZoneGeometry.toJtsPolygon(b);
        if (pa == null || pb == null) return true; // fail-closed

        try {
            return pa.intersects(pb);
        } catch (Throwable t) {
            return true; // fail-closed
        }
    }

    /** Разрешение «пересечений»: допускаем только полное вхождение дочерних зон внутрь COUNTRY/COLONY. */
    public static boolean isOverlapAllowed(
            ZoneInfo a,
            ZoneInfo b,
            Map<ZoneType, ZoneTypeData> zoneLimits
    ) {
        if (a == null || b == null) return true;
        if (a.getWorld() == null || b.getWorld() == null) return true;
        if (!a.getWorld().getUID().equals(b.getWorld().getUID())) return true;

        // Родитель ⟷ Дочерняя: разрешаем ТОЛЬКО полное вхождение дочерней в родителя
        if (isCountryOrColony(a) && isChildType(b)) {
            return childInsideParent(b, a);
        }
        if (isCountryOrColony(b) && isChildType(a)) {
            return childInsideParent(a, b);
        }

        // Прочее — по флагам типов
        ZoneTypeData ad = zoneLimits != null ? zoneLimits.get(a.getType()) : null;
        ZoneTypeData bd = zoneLimits != null ? zoneLimits.get(b.getType()) : null;
        boolean aAllow = (ad == null) || ad.allowOverlap();
        boolean bAllow = (bd == null) || bd.allowOverlap();
        return aAllow && bAllow;
    }

    public static boolean isCountryOrColony(ZoneInfo z) {
        if (z == null) return false;
        ZoneType t = z.getType();
        return t == ZoneType.COUNTRY || t == ZoneType.COLONY;
    }

    public static boolean isChildType(ZoneInfo z) {
        if (z == null) return false;
        ZoneType t = z.getType();
        return t == ZoneType.INDUSTRIAL
                || t == ZoneType.PARK
                || t == ZoneType.BANK
                || t == ZoneType.HOSPITAL
                || t == ZoneType.CHURCH
                || t == ZoneType.LIBRARY
                || t == ZoneType.GREENHOUSE
                || t == ZoneType.SHOP;
    }

    public static boolean childInsideParent(ZoneInfo child, ZoneInfo parent) {
        try {
            var childPoly  = ZoneGeometry.toJtsPolygon(child);
            var parentPoly = ZoneGeometry.toJtsPolygon(parent);
            if (childPoly == null || parentPoly == null) return false;
            return parentPoly.contains(childPoly); // именно полное вхождение
        } catch (Throwable t) {
            return false;
        }
    }

    /** Вернёт зону-«родителя» из allowedTypes, если ВСЕ углы pts целиком внутри неё. */
    public static ZoneInfo findSingleContainingZoneOfTypes(
            List<Location> pts,
            Set<ZoneType> allowedTypes,
            Collection<ZoneInfo> allZones
    ) {
        if (pts == null || pts.size() < 3) return null;
        if (allZones == null || allZones.isEmpty()) return null;

        World w0 = pts.getFirst().getWorld();
        for (ZoneInfo parent : allZones) {
            if (parent == null) continue;
            if (!allowedTypes.contains(parent.getType())) continue;
            if (!ZoneGeometry.worldOk(parent.getCorners(), w0)) continue;

            List<Vector2d> polyParent = ZoneGeometry.poly2D(parent.getCorners());
            boolean allInside = true;
            for (Location corner : pts) {
                if (!ZoneGeometry.pointInPolygon(new Vector2d(corner.getX(), corner.getZ()), polyParent)) {
                    allInside = false;
                    break;
                }
            }
            if (allInside) return parent;
        }
        return null;
    }

    /** true, если многоугольник pts пересекает (границей/площадью) хоть одну зону из allowedTypes */
    public static boolean areaIntersectsAnyOfTypes(
            List<Location> pts,
            Set<ZoneType> allowedTypes,
            Collection<ZoneInfo> allZones
    ) {
        if (pts == null || pts.size() < 3) return true;
        if (allZones == null) return false;

        var newPoly = ZoneGeometry.toJtsPolygon(new ZoneInfo(
                ZoneType.PARK, "tmp", "tmp", "tmp", pts, "tmp", org.bukkit.Color.WHITE
        ));
        if (newPoly == null) return true;

        World w0 = pts.getFirst().getWorld();
        for (ZoneInfo z : allZones) {
            if (z == null) continue;
            if (!allowedTypes.contains(z.getType())) continue;
            if (!ZoneGeometry.worldOk(z.getCorners(), w0)) continue;

            var ex = ZoneGeometry.toJtsPolygon(z);
            if (ex == null) continue;

            try {
                if (newPoly.intersects(ex)) return true;
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
    }

    /** true, если создаваемая область pts содержит внутри себя хоть одну существующую зону (любой тип) ИЛИ пересекается с ней */
    public static boolean areaHasAnyZonesInsideOrIntersecting(
            List<Location> pts,
            String ignoreMarkerId,
            Collection<ZoneInfo> allZones
    ) {
        if (pts == null || pts.size() < 3) return true;
        if (allZones == null) return false;

        ZoneInfo tmp = new ZoneInfo(ZoneType.PARK, "tmp", "tmp", "tmp_marker", pts, "tmp_owner", org.bukkit.Color.WHITE);
        var newPoly = ZoneGeometry.toJtsPolygon(tmp);
        if (newPoly == null) return true;

        World w0 = pts.getFirst().getWorld();
        for (ZoneInfo existing : allZones) {
            if (existing == null) continue;
            if (ignoreMarkerId != null && ignoreMarkerId.equals(existing.getMarkerID())) continue;
            if (!ZoneGeometry.worldOk(existing.getCorners(), w0)) continue;

            var exPoly = ZoneGeometry.toJtsPolygon(existing);
            if (exPoly == null) continue;

            try {
                if (newPoly.intersects(exPoly) || newPoly.contains(exPoly) || exPoly.contains(newPoly)) {
                    return true;
                }
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
    }
}
