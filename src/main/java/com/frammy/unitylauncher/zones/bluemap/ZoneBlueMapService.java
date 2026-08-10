package com.frammy.unitylauncher.zones.bluemap;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import com.frammy.unitylauncher.zones.ZoneTypeData;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ZoneBlueMapService {

    private final BlueMapIntegration blueMapIntegration;
    private final Map<ZoneType, ZoneTypeData> zoneLimits;

    public ZoneBlueMapService(BlueMapIntegration blueMapIntegration, Map<ZoneType, ZoneTypeData> zoneLimits) {
        this.blueMapIntegration = blueMapIntegration;
        this.zoneLimits = zoneLimits;
    }

    /** ID маркера для фигуры зоны: основная (индекс 0) сохраняет "голый" markerID для полной обратной совместимости. */
    private static String markerIdFor(ZoneInfo z, int shapeIndex) {
        return shapeIndex == 0 ? z.getMarkerID() : (z.getMarkerID() + "_" + shapeIndex);
    }

    /** Вставить/обновить маркеры зоны в BlueMap — по одному ExtrudeMarker на каждую фигуру (мульти-полигон). */
    public void upsert(ZoneInfo z, org.bukkit.Color bukkitColor) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        if (z == null) return;
        List<List<Location>> shapes = z.getShapes();
        if (shapes.isEmpty() || shapes.get(0).isEmpty()) return;
        if (shapes.get(0).getFirst().getWorld() == null) return;

        BlueMapAPI.getInstance()
                .flatMap(api -> api.getMap(shapes.get(0).getFirst().getWorld().getName()))
                .ifPresent(map -> {

                    String setId = setId(z.getType());
                    MarkerSet set = map.getMarkerSets().computeIfAbsent(setId, MarkerSet::new);
                    set.setLabel("Zones: " + setLabel(z.getType(), zoneLimits));

                    boolean parent = (z.getType() == ZoneType.COUNTRY || z.getType() == ZoneType.COLONY);
                    org.bukkit.Color base = (bukkitColor != null ? bukkitColor : org.bukkit.Color.fromRGB(255, 0, 0));
                    Color fill = toBlueMapColor(base, parent ? 0.0f : 0.35f);
                    Color line = toBlueMapColor(base, 1.0f);
                    float minY = -64f;
                    float maxY = 255f;
                    String detail = detailHtml(z);
                    String label = shapes.size() > 1 ? (z.getName() + " (частей: " + shapes.size() + ")") : z.getName();

                    for (int i = 0; i < shapes.size(); i++) {
                        List<Location> corners = shapes.get(i);
                        if (corners == null || corners.size() < 3 || corners.getFirst().getWorld() == null) continue;

                        Shape shape = shapeFromCorners(corners);
                        if (shape == null) continue;

                        String markerId = markerIdFor(z, i);
                        Marker existing = set.getMarkers().get(markerId);

                        if (existing instanceof ExtrudeMarker em) {
                            em.setLabel(label);
                            em.setShape(shape, minY, maxY);
                            em.setFillColor(fill);
                            em.setLineColor(line);
                            em.setDetail(detail);
                        } else {
                            ExtrudeMarker built = ExtrudeMarker.builder()
                                    .label(label)
                                    .shape(shape, minY, maxY)
                                    .detail(detail)
                                    .build();
                            built.setFillColor(fill);
                            built.setLineColor(line);
                            set.getMarkers().put(markerId, built);
                        }
                    }

                    // подчистить "хвостовые" маркеры фигур, которых больше нет (зона стала меньше фигур, чем раньше)
                    int idx = shapes.size();
                    while (set.getMarkers().remove(markerIdFor(z, idx)) != null) idx++;

                    if (blueMapIntegration != null) blueMapIntegration.saveBlueMapMarkers(setId);
                });
    }

    /** Удалить ВСЕ маркеры зоны (все фигуры), и marker set, если он опустел. */
    public void remove(ZoneInfo z) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        if (z == null) return;
        List<List<Location>> shapes = z.getShapes();
        if (shapes.isEmpty() || shapes.get(0).isEmpty()) return;
        if (shapes.get(0).getFirst().getWorld() == null) return;

        BlueMapAPI.getInstance()
                .flatMap(api -> api.getMap(shapes.get(0).getFirst().getWorld().getName()))
                .ifPresent(map -> {
                    String setId = setId(z.getType());
                    MarkerSet set = map.getMarkerSets().get(setId);
                    if (set != null) {
                        // удаляем основную + все возможные доп.-фигурные маркеры (индекс не ограничен shapes.size(),
                        // т.к. это могло быть вызвано ПОСЛЕ уменьшения кол-ва фигур)
                        set.getMarkers().remove(markerIdFor(z, 0));
                        int idx = 1;
                        while (set.getMarkers().remove(markerIdFor(z, idx)) != null) idx++;
                        if (set.getMarkers().isEmpty()) {
                            map.getMarkerSets().remove(setId);
                        }
                    }
                    if (blueMapIntegration != null) blueMapIntegration.saveBlueMapMarkers(setId);
                });
    }

    /** Стабильный ID marker set'а. */
    public static String setId(ZoneType type) {
        return "zones_" + type.name().toLowerCase(Locale.ROOT);
    }

    /** Человекочитаемый label. */
    public static String setLabel(ZoneType type, Map<ZoneType, ZoneTypeData> zoneLimits) {
        ZoneTypeData ztd = (zoneLimits != null) ? zoneLimits.get(type) : null;
        return (ztd != null && ztd.displayName() != null && !ztd.displayName().isBlank())
                ? ztd.displayName()
                : type.name();
    }

    // ---- helpers ----

    private static Shape shapeFromCorners(List<Location> corners) {
        if (corners == null || corners.size() < 3) return null;

        try {
            ArrayList<Vector2d> pts = new ArrayList<>(corners.size());
            for (Location l : corners) {
                if (l == null) continue;
                pts.add(new Vector2d(l.getX(), l.getZ()));
            }
            if (pts.size() < 3) return null;
            return new Shape(pts);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[Zones] Failed to build BlueMap Shape: " + t);
            return null;
        }
    }

    private String detailHtml(ZoneInfo z) {
        String owner = z.getOwner() != null ? z.getOwner() : "—";
        String country = z.getCountryName() != null ? z.getCountryName() : "—";
        ZoneTypeData ztd = (zoneLimits != null) ? zoneLimits.get(z.getType()) : null;
        String typeName = (ztd != null ? ztd.displayName() : z.getType().name());
        double area = com.frammy.unitylauncher.zones.geom.ZoneGeometry.totalArea(z.getShapes());
        String areaLabel = z.getShapeCount() > 1
                ? String.format(Locale.US, "%.2f", area) + " блоков² (" + z.getShapeCount() + " фигур)"
                : String.format(Locale.US, "%.2f", area) + " блоков²";

        return "<b>" + typeName + " «" + z.getName() + "»</b>"
                + "<br><br><b>Владелец:</b> " + owner
                + "<br><b>Страна:</b> " + country
                + "<br><b>Площадь:</b> " + areaLabel;
    }

    private static Color toBlueMapColor(org.bukkit.Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
