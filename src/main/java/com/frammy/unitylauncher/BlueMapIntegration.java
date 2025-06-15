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
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class BlueMapIntegration {
    private final UnityLauncher plugin;
    private final Logger logger;
    private final File dataFolder;
    public Map<Player, List<Location>> markerPoints = new HashMap<>();
    private final int currentID = 0;

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
                        if (markerSet.getMarkers() != null) {
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
                                                plugin.getAwaitingCorrectCommand().remove(p);
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
                                    marker.setLabel("Табличка о продаже");
                                    marker.setDetail("ID - '" + id + "'");
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
                            // Записываем пустой MarkerSet
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
