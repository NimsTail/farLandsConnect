package com.frammy.unitylauncher.zones.geom;

import com.frammy.unitylauncher.zones.CountryNameUtil;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import com.frammy.unitylauncher.zones.ZoneTypeData;
import org.bukkit.Location;
import org.bukkit.World;
import org.locationtech.jts.geom.Geometry;
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

    /**
     * Геометрически пересекаются ли полигоны зон по XZ в рамках одного мира (НАСТОЯЩИМ
     * наложением площади, а не просто касанием границы/вершины — см. {@link ZoneGeometry#trueAreaOverlap}).
     * Высота игнорируется. Учитывает ВСЕ фигуры обеих зон (мульти-полигон) — пересечение
     * хотя бы одной пары фигур считается пересечением зон.
     */
    public static boolean zonesIntersect2D(ZoneInfo a, ZoneInfo b) {
        World wa = (a != null) ? a.getWorld() : null;
        World wb = (b != null) ? b.getWorld() : null;
        if (wa == null || wb == null) return false;
        if (!wa.getUID().equals(wb.getUID())) return false;

        List<Polygon> pas = ZoneGeometry.toJtsPolygons(a);
        List<Polygon> pbs = ZoneGeometry.toJtsPolygons(b);
        if (pas.isEmpty() || pbs.isEmpty()) return true; // fail-closed

        try {
            for (Polygon pa : pas) {
                for (Polygon pb : pbs) {
                    if (ZoneGeometry.trueAreaOverlap(pa, pb)) return true;
                }
            }
            return false;
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

        // Государство ⟷ [дочерняя зона этой же страны | личная зона лидера,
        // кроме Колонии]: разрешаем оверлап без требования полного вхождения —
        // граница страны может просто проходить через уже существующую зону.
        if (a.getType() == ZoneType.COUNTRY && isCountryOverlapExempt(b, a.getName(), a.getOwner())) return true;
        if (b.getType() == ZoneType.COUNTRY && isCountryOverlapExempt(a, b.getName(), b.getOwner())) return true;

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

    /**
     * parent (COUNTRY/COLONY) может состоять из НЕСКОЛЬКИХ отдельных фигур (эксклавы
     * территории Государства) — child может состоять из нескольких фигур тоже; каждая
     * фигура child должна целиком лежать внутри ОБЪЕДИНЕНИЯ всех фигур parent (не
     * обязательно в одной конкретной фигуре — покрытие суммой достаточно).
     * Используем covers(), а не contains() — дочерняя зона может делить рёбра/точки
     * границы с территорией страны (например, стоять вплотную к её краю) и это всё
     * равно считается полным вхождением, а не пересечением.
     */
    public static boolean childInsideParent(ZoneInfo child, ZoneInfo parent) {
        try {
            Geometry parentGeom = unionOfShapes(parent);
            if (parentGeom == null) return false;
            List<Polygon> childPolys = ZoneGeometry.toJtsPolygons(child);
            if (childPolys.isEmpty()) return false;
            for (Polygon childPoly : childPolys) {
                if (!parentGeom.covers(childPoly)) return false; // полное вхождение (границы могут совпадать)
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Геометрия-объединение ВСЕХ фигур зоны (для COUNTRY/COLONY с несколькими эксклавами территории). */
    private static Geometry unionOfShapes(ZoneInfo z) {
        List<Polygon> polys = ZoneGeometry.toJtsPolygons(z);
        if (polys.isEmpty()) return null;
        Geometry union = polys.get(0);
        for (int i = 1; i < polys.size(); i++) {
            try {
                union = union.union(polys.get(i));
            } catch (Throwable ignored) {
                // не смогли объединить конкретную пару (вырожденная геометрия) — пропускаем её,
                // остальные фигуры всё равно останутся учтены в union
            }
        }
        return union;
    }

    /**
     * Допуск (в блоках) для точек рядом с границей родителя. Координаты одной и той же
     * "визуально общей" точки у двух разных зон могут не совпадать побитово как double —
     * они проходят через сайт → PHP/БД → плагин, и округление/сериализация может внести
     * микроскопическую погрешность. Строгий covers() тогда иногда считает точку чуть-чуть
     * снаружи. Значение согласовано с допуском снапа на сайте (zoneManager.html, eps=0.5).
     */
    private static final double PARENT_BOUNDARY_TOLERANCE = 0.5;

    /**
     * Вернёт зону-«родителя» из allowedTypes, если весь полигон pts целиком лежит внутри неё.
     * ВАЖНО: раньше тут проверялись только вершины pts (covers(point) на каждый угол) — этого
     * недостаточно на невыпуклой границе страны: ребро между двумя вершинами, каждая из которых
     * лежит внутри страны, могло всё равно "выгибаться" наружу через вогнутый участок границы,
     * и такая фигура ложно принималась как полностью внутри. Теперь строим полигон из pts и
     * проверяем covers() ПО ВСЕЙ ФИГУРЕ (как уже делает childInsideParent для пост-фактум
     * проверки сосуществования зон), а не только по вершинам.
     * Буфер допуска (PARENT_BOUNDARY_TOLERANCE) компенсирует погрешность округления координат
     * при прохождении через сайт/БД — без него covers() мог бы ложно отбраковывать фигуру,
     * снаппнутую вплотную к границе родителя.
     */
    public static ZoneInfo findSingleContainingZoneOfTypes(
            List<Location> pts,
            Set<ZoneType> allowedTypes,
            Collection<ZoneInfo> allZones
    ) {
        if (pts == null || pts.size() < 3) return null;
        if (allZones == null || allZones.isEmpty()) return null;

        World w0 = pts.getFirst().getWorld();

        Polygon childPoly = ZoneGeometry.toJtsPolygon(pts);
        if (childPoly == null) return null;

        for (ZoneInfo parent : allZones) {
            if (parent == null) continue;
            if (!allowedTypes.contains(parent.getType())) continue;
            if (!ZoneGeometry.worldOkAny(parent.getShapes(), w0)) continue;

            // COUNTRY/COLONY может состоять из нескольких эксклавов — объединяем их в
            // одну геометрию, pts должны целиком лежать внутри ЭТОГО объединения
            // (необязательно все в одном конкретном эксклаве)
            Geometry parentGeom = unionOfShapes(parent);
            if (parentGeom == null) continue;

            try {
                Geometry parentBuffered = parentGeom.buffer(PARENT_BOUNDARY_TOLERANCE);
                if (parentBuffered.covers(childPoly)) return parent;
            } catch (Throwable t) {
                // fail-closed: геометрическая ошибка не считается "внутри"
            }
        }
        return null;
    }

    /** true, если многоугольник pts пересекает НАСТОЯЩЕЙ площадью (не просто касается границей) хоть одну зону из allowedTypes */
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
            if (!ZoneGeometry.worldOkAny(z.getShapes(), w0)) continue;

            // мульти-полигон (COUNTRY/COLONY с несколькими эксклавами) — пересечение
            // хотя бы с ОДНОЙ фигурой уже считается пересечением
            for (Polygon ex : ZoneGeometry.toJtsPolygons(z)) {
                if (ZoneGeometry.trueAreaOverlap(newPoly, ex)) return true;
            }
        }
        return false;
    }

    /** true, если создаваемая область pts содержит внутри себя хоть одну существующую зону (любой тип) ИЛИ пересекается с ней НАСТОЯЩЕЙ площадью */
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

            // мульти-полигон: проверяем КАЖДУЮ фигуру existing-зоны отдельно
            for (List<Location> shape : existing.getShapes()) {
                if (!ZoneGeometry.worldOk(shape, w0)) continue;

                var exPoly = ZoneGeometry.toJtsPolygon(shape);
                if (exPoly == null) continue;

                try {
                    // contains() тут намеренно оставлен строгим (не covers()): если новая граница
                    // целиком проглатывает чужую зону (или наоборот) — это блокируем всегда, а вот
                    // простое касание общей гранью/вершиной пересечением не считаем (trueAreaOverlap)
                    if (ZoneGeometry.trueAreaOverlap(newPoly, exPoly) || newPoly.contains(exPoly) || exPoly.contains(newPoly)) {
                        return true;
                    }
                } catch (Throwable t) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Зоны, оверлап которых с территорией Государства разрешён явно, помимо
     * обычного "дочерняя зона внутри родителя": дочерняя зона ЭТОЙ ЖЕ страны
     * (даже если граница страны просто проходит через неё не целиком её
     * поглощая — де-факто она и так родная территория) и любая зона, лично
     * принадлежащая лидеру страны, КРОМЕ Колонии (колония — намеренно
     * отдельная территория даже у одного и того же лидера).
     */
    public static boolean isCountryOverlapExempt(ZoneInfo other, String selfCountryName, String leaderUsername) {
        if (other == null) return false;
        if (isChildType(other)) {
            String otherCountry = other.getCountryName();
            if (otherCountry != null && selfCountryName != null
                    && CountryNameUtil.normalizeCountry(otherCountry).equals(CountryNameUtil.normalizeCountry(selfCountryName))) {
                return true;
            }
        }
        if (other.getType() != ZoneType.COLONY && leaderUsername != null
                && ZoneManager.NameUtil.eqCi(other.getOwner(), leaderUsername)) {
            return true;
        }
        return false;
    }

    /**
     * Как {@link #areaHasAnyZonesInsideOrIntersecting}, но для изменения
     * территории Государства: зоны, разрешённые правилом
     * {@link #isCountryOverlapExempt}, пропускаются вместо блокировки —
     * см. ZoneValidationService.validateUpdateCornersDraft/validateAddShapeDraft
     * (тип COUNTRY).
     */
    public static ZoneInfo findConflictingZoneForCountryChange(
            List<Location> pts,
            String ignoreMarkerId,
            Collection<ZoneInfo> allZones,
            String selfCountryName,
            String leaderUsername
    ) {
        if (pts == null || pts.size() < 3) return null;
        if (allZones == null) return null;

        ZoneInfo tmp = new ZoneInfo(ZoneType.COUNTRY, "tmp", "tmp", "tmp_marker", pts, leaderUsername, org.bukkit.Color.WHITE);
        var newPoly = ZoneGeometry.toJtsPolygon(tmp);
        if (newPoly == null) return null;

        World w0 = pts.getFirst().getWorld();
        for (ZoneInfo existing : allZones) {
            if (existing == null) continue;
            if (ignoreMarkerId != null && ignoreMarkerId.equals(existing.getMarkerID())) continue;
            if (isCountryOverlapExempt(existing, selfCountryName, leaderUsername)) continue;

            for (List<Location> shape : existing.getShapes()) {
                if (!ZoneGeometry.worldOk(shape, w0)) continue;
                var exPoly = ZoneGeometry.toJtsPolygon(shape);
                if (exPoly == null) continue;
                try {
                    if (ZoneGeometry.trueAreaOverlap(newPoly, exPoly) || newPoly.contains(exPoly) || exPoly.contains(newPoly)) {
                        return existing;
                    }
                } catch (Throwable t) {
                    return existing;
                }
            }
        }
        return null;
    }
}
