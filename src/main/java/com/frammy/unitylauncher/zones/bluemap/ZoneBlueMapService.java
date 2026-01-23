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

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public final class ZoneBlueMapService {

    private final BlueMapIntegration blueMapIntegration;
    private final Map<ZoneType, ZoneTypeData> zoneLimits;

    public ZoneBlueMapService(BlueMapIntegration blueMapIntegration, Map<ZoneType, ZoneTypeData> zoneLimits) {
        this.blueMapIntegration = blueMapIntegration;
        this.zoneLimits = zoneLimits;
    }

    /** Вставить/обновить маркер зоны в BlueMap. */
    public void upsert(ZoneInfo z, org.bukkit.Color bukkitColor) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        if (z == null || z.getCorners() == null || z.getCorners().isEmpty()) return;
        if (z.getCorners().getFirst().getWorld() == null) return;

        BlueMapAPI.getInstance()
                .flatMap(api -> api.getMap(z.getCorners().getFirst().getWorld().getName()))
                .ifPresent(map -> {

                    String setId = setId(z.getType());
                    MarkerSet set = map.getMarkerSets().computeIfAbsent(setId, MarkerSet::new);
                    set.setLabel("Zones: " + setLabel(z.getType(), zoneLimits));

                    Shape shape = shapeFromCorners(z.getCorners());
                    if (shape == null) return;

                    Marker existing = set.getMarkers().get(z.getMarkerID());

                    boolean parent = (z.getType() == ZoneType.COUNTRY || z.getType() == ZoneType.COLONY);

                    org.bukkit.Color base = (bukkitColor != null ? bukkitColor : org.bukkit.Color.fromRGB(255, 0, 0));

                    Color fill = toBlueMapColor(base, parent ? 0.0f : 0.35f);
                    Color line = toBlueMapColor(base, 1.0f);

                    float minY = -64f;
                    float maxY = 255f;

                    if (existing instanceof ExtrudeMarker em) {
                        em.setLabel(z.getName());
                        em.setShape(shape, minY, maxY);
                        em.setFillColor(fill);
                        em.setLineColor(line);
                        em.setDetail(detailHtml(z));
                    } else {
                        ExtrudeMarker built = ExtrudeMarker.builder()
                                .label(z.getName())
                                .shape(shape, minY, maxY)
                                .detail(detailHtml(z))
                                .build();
                        built.setFillColor(fill);
                        built.setLineColor(line);
                        set.getMarkers().put(z.getMarkerID(), built);
                    }

                    if (blueMapIntegration != null) blueMapIntegration.saveBlueMapMarkers(setId);
                });
    }

    /** Удалить маркер зоны (и marker set, если он пуст). */
    public void remove(ZoneInfo z) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        if (z == null || z.getCorners() == null || z.getCorners().isEmpty()) return;
        if (z.getCorners().getFirst().getWorld() == null) return;

        BlueMapAPI.getInstance()
                .flatMap(api -> api.getMap(z.getCorners().getFirst().getWorld().getName()))
                .ifPresent(map -> {
                    String setId = setId(z.getType());
                    MarkerSet set = map.getMarkerSets().get(setId);
                    if (set != null) {
                        set.getMarkers().remove(z.getMarkerID());
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
        double area = calculateSurfaceArea(z.getCorners());

        return "<b>" + typeName + " «" + z.getName() + "»</b>"
                + "<br><br><b>Владелец:</b> " + owner
                + "<br><b>Страна:</b> " + country
                + "<br><b>Площадь:</b> " + String.format(Locale.US, "%.2f", area) + " блоков²";
    }

    private static Color toBlueMapColor(org.bukkit.Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
