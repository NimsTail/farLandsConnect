package com.frammy.unitylauncher;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class BlueMapIntegration {
    private final UnityLauncher plugin;
    private final Logger logger;
    private final File dataFolder;
    public Map<Player, List<Location>> markerPoints = new HashMap<>();

    public File getDataFolder() {
        return dataFolder;
    }
    public BlueMapIntegration(UnityLauncher plugin, Logger logger, File dataFolder) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataFolder = dataFolder;

    }

    public Logger getLogger() {
        return logger;
    }

    /** Обновлённая функция с корректной проверкой пересечений (XZ + Y-overlap) и фолбэком по высоте */
    public void addBlueMapMarker(String id, Location location, String setID, String setLabel, String markerType, List<Vector3d> extrudePoints, Player p) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().flatMap(blueMapAPI -> blueMapAPI.getMap(location.getWorld().getName())).ifPresent(map -> {
                Map<String, MarkerSet> markerSets = map.getMarkerSets();
                if (markerSets == null) {
                    Bukkit.getLogger().warning("MarkerSets is null for map: " + location.getWorld().getName());
                    return;
                }

                MarkerSet markerSet = markerSets.computeIfAbsent(setID, k -> new MarkerSet(setLabel));
                if (markerSet.getMarkers() == null) {
                    Bukkit.getLogger().warning("Markers map is null for setID: " + setID);
                    return;
                }

                switch (String.valueOf(markerType).toLowerCase(Locale.ROOT)) {
                    case "extrude" -> {
                        if (extrudePoints == null || extrudePoints.size() < 3) {
                            if (p != null) p.sendMessage(ChatColor.RED + "Недостаточно точек для полигона.");
                            return;
                        }

                        // Контур XZ
                        List<Vector2d> basePoints = extrudePoints.stream()
                                .map(v -> new Vector2d(v.getX(), v.getZ()))
                                .collect(Collectors.toList());
                        Shape newShape = new Shape(basePoints);

                        // Высоты Y (фолбэк на 42..152, как было раньше)
                        double minY = extrudePoints.stream().mapToDouble(Vector3d::getY).min().orElse(42);
                        double maxY = extrudePoints.stream().mapToDouble(Vector3d::getY).max().orElse(152);
                        if (minY > maxY) { double t = minY; minY = maxY; maxY = t; }

                        // Проверяем пересечения со всеми существующими ExtrudeMarker в этом наборе
                        for (Marker existingMarker : markerSet.getMarkers().values()) {
                            if (existingMarker instanceof ExtrudeMarker em) {
                                // сначала проверяем по высоте
                                if (!yOverlap(minY, maxY, em.getShapeMinY(), em.getShapeMaxY())) continue;
                                // затем по XZ
                                if (polygonsIntersectXZ(newShape, em.getShape())) {
                                    if (p != null) p.sendMessage(ChatColor.RED + "Маркер пересекается с существующим: " + em.getLabel());
                                    Bukkit.getLogger().warning("Маркер пересекается с существующим: " + em.getLabel());
                                    return; // прекращаем добавление
                                }
                            }
                        }

                        // Добавляем новый маркер (пересечений нет)
                        ExtrudeMarker extrudeMarker = new ExtrudeMarker(id, newShape, (float) minY, (float) maxY);
                        extrudeMarker.setLabel(id);
                        markerSet.getMarkers().put(id, extrudeMarker);

                        if (p != null) p.sendMessage(ChatColor.GREEN + "Торговая точка успешно создана!");
                        markerPoints.clear();
                        plugin.getAwaitingCorrectCommand().remove(p);
                    }

                    case "point_atm" -> {
                        Vector3d position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                        POIMarker marker = new POIMarker("atm_" + id, position);
                        marker.setLabel("ATM");
                        marker.setIcon("assets/atm.png", 8, 8);
                        markerSet.getMarkers().put(String.valueOf(id), marker);
                    }

                    case "point_shop" -> {
                        Vector3d position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                        POIMarker marker = new POIMarker("shop_" + id, position);
                        marker.setLabel("Табличка о продаже");
                        marker.setDetail("ID - '" + id + "'");
                        marker.setIcon("assets/atm.png", 8, 8);
                        markerSet.getMarkers().put(String.valueOf(id), marker);
                    }

                    default -> Bukkit.getLogger().warning("Unknown markerType: " + markerType);
                }
            });
        }
    }

    public void removeBlueMapMarker(String id, String worldName, String markerSetKey) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().flatMap(blueMapAPI -> blueMapAPI.getMap(worldName)).ifPresent(map -> {
                MarkerSet markerSet = map.getMarkerSets().get(markerSetKey);
                if (markerSet != null) {
                    markerSet.getMarkers().remove(id);
                }
            });
        }
    }

    public void saveBlueMapMarkers(String setID) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> blueMapAPI.getMaps().forEach(map -> {
                MarkerSet markerSet = map.getMarkerSets().get(setID);

                if (markerSet != null) {
                    File markerFile = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/" + setID + ".json");

                    if (!markerFile.exists()) {
                        try {
                            markerFile.getParentFile().mkdirs();
                            markerFile.createNewFile();
                        } catch (IOException e) {
                            getLogger().severe("Ошибка при создании файла маркеров: " + markerFile.getAbsolutePath());
                            e.printStackTrace();
                            return;
                        }
                    }

                    try (FileWriter writer = new FileWriter(markerFile)) {
                        MarkerGson.INSTANCE.toJson(markerSet, writer);
                    } catch (IOException ex) {
                        getLogger().severe("Ошибка при сохранении маркеров BlueMap.");
                        ex.printStackTrace();
                    }
                }
            }));
        }
    }

    public void initializeBlueMapMarkerStorage(String setID) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.onEnable(blueMapAPI -> {
                for (BlueMapMap map : blueMapAPI.getMaps()) {
                    File markerFile = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/" + setID + ".json");

                    try {
                        File parent = markerFile.getParentFile();
                        if (!parent.exists() && parent.mkdirs()) {
                            getLogger().info("[BlueMap] Создана директория: " + parent.getAbsolutePath());
                        }

                        if (!markerFile.exists() && markerFile.createNewFile()) {
                            getLogger().info("[BlueMap] Создан пустой файл маркеров: " + markerFile.getAbsolutePath());
                            try (FileWriter writer = new FileWriter(markerFile)) {
                                MarkerGson.INSTANCE.toJson(new MarkerSet(setID), writer);
                            }
                        }
                    } catch (IOException e) {
                        getLogger().severe("Не удалось создать директорию или файл для маркеров: " + markerFile.getAbsolutePath());
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public void loadBlueMapMarkers() {
        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> blueMapAPI.getMaps().forEach(map -> {
            File markerDirectory = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/");
            if (markerDirectory.exists() && markerDirectory.isDirectory()) {
                File[] markerFiles = markerDirectory.listFiles((dir, name) -> name.endsWith(".json"));

                if (markerFiles != null) {
                    for (File markerFile : markerFiles) {
                        try (FileReader reader = new FileReader(markerFile)) {
                            MarkerSet markerSet = MarkerGson.INSTANCE.fromJson(reader, MarkerSet.class);
                            String markerSetName = markerFile.getName().replace(".json", "");
                            map.getMarkerSets().put(markerSetName, markerSet);
                        } catch (IOException ex) {
                            getLogger().severe("Ошибка при загрузке маркеров BlueMap из файла: " + markerFile.getName());
                            ex.printStackTrace();
                        }
                    }
                }
            } else {
                getLogger().warning("Папка с маркерами не найдена или не является директорией: " + markerDirectory.getAbsolutePath());
            }
        }));
    }

    // ===================== ПРОЧНЫЕ ХЕЛПЕРЫ ДЛЯ ПЕРЕСЕЧЕНИЙ =====================

    private static final GeometryFactory JTS = new GeometryFactory();
    private static final double EPS_AREA = 1e-6;

    private static boolean yOverlap(double aMin, double aMax, double bMin, double bMax) {
        return !(aMax < bMin || bMax < aMin);
    }

    /** Преобразуем Shape (BlueMap) -> JTS Polygon (только XZ), с замыканием контура */
    private static Polygon shapeToPolygon(Shape shape) {
        List<Vector2d> pts = Arrays.asList(shape.getPoints());
        if (pts.size() < 3) return null;

        Coordinate[] coords = new Coordinate[pts.size() + 1];
        for (int i = 0; i < pts.size(); i++) {
            coords[i] = new Coordinate(pts.get(i).getX(), pts.get(i).getY()); // XZ => (x, z) -> (x, y) в JTS-2D
        }
        coords[pts.size()] = new Coordinate(coords[0]);

        try {
            LinearRing shell = JTS.createLinearRing(coords);
            return JTS.createPolygon(shell);
        } catch (Exception e) {
            return null;
        }
    }

    /** Пересечение только по XZ-проекции (полигон с полигоном), с порогом площади. */
    private static boolean polygonsIntersectXZ(Shape s1, Shape s2) {
        Polygon p1 = shapeToPolygon(s1);
        Polygon p2 = shapeToPolygon(s2);
        if (p1 == null || p2 == null || p1.isEmpty() || p2.isEmpty()) return false;
        var inter = p1.intersection(p2);
        return !inter.isEmpty() && inter.getArea() > EPS_AREA;
    }

}
