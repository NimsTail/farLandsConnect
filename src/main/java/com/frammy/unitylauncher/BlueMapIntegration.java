package com.frammy.unitylauncher;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.ZoneManager;
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
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.logging.Logger;

public class BlueMapIntegration {
    private final UnityLauncher plugin;
    private final Logger logger;
    private final File dataFolder;
    private UnityLauncher unityLauncher;
    private ZoneManager zoneManager;
    public Map<Player, List<Location>> markerPoints = new HashMap<>();
    private int currentID = 0;

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
    public void addBlueMapMarker(String id, Location location, String setID, String setLabel, String markerType, List<Vector3d> extrudePoints, Player p) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                    // Проверяем наличие MarkerSet
                    Map<String, MarkerSet> markerSets = map.getMarkerSets();
                    if (markerSets != null) {
                        MarkerSet markerSet = markerSets.computeIfAbsent(setID, k -> new MarkerSet(setLabel));
                        if (markerSet != null && markerSet.getMarkers() != null) {
                            switch (markerType) {
                                case "extrude":
                                    // Преобразуем extrudePoints в Vector2d для новой фигуры
                                    List<Vector2d> basePoints = extrudePoints.stream()
                                            .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                                            .collect(Collectors.toList());
                                    Shape newShape = new Shape(basePoints);

                                    // Проверяем пересечение с существующими ExtrudeMarker
                                    for (Marker existingMarker : markerSet.getMarkers().values()) {
                                        if (existingMarker instanceof ExtrudeMarker) {
                                            ExtrudeMarker extrudeMarker = (ExtrudeMarker) existingMarker;
                                            if (shapesIntersect(newShape, extrudeMarker.getShape())) {
                                                p.sendMessage(ChatColor.RED + "На этой территории уже создан другой магазин.");
                                                Bukkit.getLogger().warning("Маркер пересекается с существующим: " + extrudeMarker.getLabel());
                                                return; // Прекращаем добавление нового маркера
                                            } else {
                                                //saveShopData(p, id);
                                                p.sendMessage(ChatColor.GREEN + "Торговая точка успешно создана!");
                                                markerPoints.clear();
                                                unityLauncher.awaitingCorrectCommand.remove(p);
                                            }
                                        }
                                    }

                                    // Добавляем новый маркер, если пересечений нет
                                    ExtrudeMarker extrudeMarker = new ExtrudeMarker(id, newShape, 42, 152);
                                    extrudeMarker.setLabel(id);
                                    markerSet.getMarkers().put(id, extrudeMarker);
                                    break;

                                case "point_atm":
                                    // Создаём POI-маркер
                                    Vector3d position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                                    POIMarker marker = new POIMarker("atm_" + id, position);
                                    marker.setLabel("ATM");
                                    marker.setIcon("assets/atm.png", 8, 8);
                                    markerSet.getMarkers().put(String.valueOf(id), marker);
                                    break;
                                case "point_shop":
                                    // Создаём POI-маркер
                                    position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                                    marker = new POIMarker("shop_" + id, position);
                                    marker.setLabel("ShopSign");
                                    marker.setIcon("assets/atm.png", 8, 8);
                                    markerSet.getMarkers().put(String.valueOf(id), marker);
                                    break;

                                default:
                                    Bukkit.getLogger().warning("Unknown markerType: " + markerType);
                                    break;
                            }
                        } else {
                            Bukkit.getLogger().warning("MarkerSet or Markers map is null for setID: " + setID);
                        }
                    } else {
                        Bukkit.getLogger().warning("MarkerSets is null for map: " + location.getWorld().getName());
                    }
                });
            });
        }
    }

    public void removeBlueMapMarker(String id, String worldName, String markerSetKey) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                blueMapAPI.getMap(worldName).ifPresent(map -> {
                    MarkerSet markerSet = map.getMarkerSets().get(markerSetKey);
                    if (markerSet != null) {
                        markerSet.getMarkers().remove(id);
                    }
                });
            });
        }
    }

    public void saveBlueMapMarkers(String setID) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                // Перебираем все доступные карты (мира)
                blueMapAPI.getMaps().forEach(map -> {
                    MarkerSet markerSet = map.getMarkerSets().get(setID);

                    if (markerSet != null) {
                        // Определяем путь к файлу маркеров
                        File markerFile = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/" + setID + ".json");

                        // Проверка существования файла; если не существует - создаем
                        if (!markerFile.exists()) {
                            try {
                                markerFile.getParentFile().mkdirs(); // Создаем родительскую директорию, если необходимо
                                markerFile.createNewFile(); // Создаем файл
                            } catch (IOException e) {
                                getLogger().severe("Ошибка при создании файла маркеров: " + markerFile.getAbsolutePath());
                                e.printStackTrace();
                                return; // Остановить выполнение, если не удалось создать файл
                            }
                        }

                        // Сохранение маркеров в файл
                        try (FileWriter writer = new FileWriter(markerFile)) {
                            MarkerGson.INSTANCE.toJson(markerSet, writer);
                        } catch (IOException ex) {
                            getLogger().severe("Ошибка при сохранении маркеров BlueMap.");
                            ex.printStackTrace();
                        }
                    }
                });
            });
        }
    }
    public void loadBlueMapMarkers() {
        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            // Перебираем все доступные карты (мира)
            blueMapAPI.getMaps().forEach(map -> {
                // Указываем путь к папке, где хранятся маркеры
                File markerDirectory = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/");
                if (markerDirectory.exists() && markerDirectory.isDirectory()) {
                    // Перебираем все файлы в директории
                    File[] markerFiles = markerDirectory.listFiles((dir, name) -> name.endsWith(".json"));

                    if (markerFiles != null) {
                        for (File markerFile : markerFiles) {
                            try (FileReader reader = new FileReader(markerFile)) {
                                MarkerSet markerSet = MarkerGson.INSTANCE.fromJson(reader, MarkerSet.class);
                                // Используем имя файла (без расширения) в качестве ключа для MarkerSet
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
            });
        });
    }

    public ExtrudeMarker isSignWithinMarker(Location signLocation) {
        // System.out.println("[DEBUG] Проверка таблички на маркеры: " + signLocation);

        Optional<BlueMapAPI> apiOptional = BlueMapAPI.getInstance();
        if (apiOptional.isPresent()) {
            BlueMapAPI api = apiOptional.get();
            //  System.out.println("[DEBUG] BlueMapAPI получен");

            Optional<BlueMapMap> mapOptional = api.getMap(signLocation.getWorld().getName());
            if (mapOptional.isPresent()) {
                BlueMapMap map = mapOptional.get();
                // System.out.println("[DEBUG] Карта найдена: " + signLocation.getWorld().getName());

                MarkerSet markerSet = map.getMarkerSets().get("zones_shop");
                if (markerSet != null) {
                    // System.out.println("[DEBUG] Найден MarkerSet с ID 'zones_shops'. Кол-во маркеров: " + markerSet.getMarkers().size());

                    for (Marker marker : markerSet.getMarkers().values()) {
                        if (marker instanceof ExtrudeMarker) {
                            ExtrudeMarker extrudeMarker = (ExtrudeMarker) marker;
                            Shape baseShape = extrudeMarker.getShape();
                            double minHeight = extrudeMarker.getShapeMinY();
                            double maxHeight = extrudeMarker.getShapeMaxY();
                            String label = extrudeMarker.getLabel();

                            // System.out.println("[DEBUG] Проверка ExtrudeMarker '" + label + "'");
                            // System.out.println(" - Высота: " + minHeight + " до " + maxHeight);
                            // System.out.println(" - Форма: " + baseShape.getPoints().length + " точек");

                            Vector2d signPos2D = new Vector2d(signLocation.getX(), signLocation.getZ());
                            double y = signLocation.getY();

                            boolean insidePolygon = zoneManager.isPointInsidePolygon(signPos2D, Arrays.asList(baseShape.getPoints()));
                            boolean insideHeight = y >= minHeight && y <= maxHeight;

                            //System.out.println(" - Позиция таблички 2D: " + signPos2D + " (Y: " + y + ")");
                            // System.out.println(" - Внутри полигона? " + insidePolygon);
                            //System.out.println(" - В пределах высоты? " + insideHeight);

                            if (insidePolygon && insideHeight) {
                                System.out.println("[DEBUG] Табличка попала внутрь маркера: " + label);
                                return extrudeMarker;
                            }
                        }
                    }

                    System.out.println("[DEBUG] Табличка не попала ни в один маркер в MarkerSet 'shops'");
                } else {
                    System.out.println("[DEBUG] MarkerSet с ID 'shops' не найден.");
                }
            } else {
                System.out.println("[DEBUG] Карта не найдена для мира: " + signLocation.getWorld().getName());
            }
        } else {
            System.out.println("[DEBUG] BlueMapAPI не инициализирован!");
        }
        return null;
    }
    private boolean shapesIntersect(Shape shape1, Shape shape2) {
        GeometryFactory geometryFactory = new GeometryFactory();

        // Преобразуем точки в массив координат и замыкаем линию
        Coordinate[] coords1 = ensureClosed(Arrays.asList(shape1.getPoints()));
        Coordinate[] coords2 = ensureClosed(Arrays.asList(shape2.getPoints()));

        // Создаем полигоны
        Polygon polygon1 = geometryFactory.createPolygon(coords1);
        Polygon polygon2 = geometryFactory.createPolygon(coords2);

        // Проверяем пересечение
        return polygon1.intersects(polygon2);
    }

    /**
     * Проверяет, что линии замкнуты, и добавляет первый элемент в конец массива, если необходимо.
     */
    private Coordinate[] ensureClosed(List<Vector2d> points) {
        List<Coordinate> coordinates = points.stream()
                .map(point -> new Coordinate(point.getX(), point.getY())) // Замените на ваши реальные поля
                .collect(Collectors.toList());

        // Если первый и последний координаты не совпадают, добавляем замыкающую точку
        if (!coordinates.get(0).equals(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(new Coordinate(coordinates.get(0)));
        }

        return coordinates.toArray(new Coordinate[0]);
    }
}
