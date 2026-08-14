package com.frammy.unitylauncher;

import com.flowpowered.math.vector.Vector3d;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.zones.ZoneInfo;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

public class BlueMapIntegration {
    private final UnityLauncher plugin;
    private final Logger logger;
    private final File dataFolder;
    public final Map<UUID, List<Location>> markerPoints = new HashMap<>();

    public File getDataFolder() { return dataFolder; }

    public BlueMapIntegration(UnityLauncher plugin, Logger logger, File dataFolder) {
        this.plugin = plugin;
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    public Logger getLogger() { return logger; }

    /* ===================== ЛЁГКИЕ ХЕЛПЕРЫ ДЛЯ LAZY LOADER ===================== */

    /** Гарантирует наличие набора маркеров на всех картах BlueMap. */
    public void initializeBlueMapMarkerStorage(String setId) {
        Objects.requireNonNull(setId, "setId");
        BlueMapAPI.getInstance().ifPresent(api -> {
            for (BlueMapMap map : api.getMaps()) {
                Map<String, MarkerSet> sets = map.getMarkerSets();
                if (sets == null) continue;
                sets.computeIfAbsent(setId, k -> {
                    MarkerSet s = new MarkerSet("Markers");
                    s.setLabel("Markers");
                    return s;
                });
            }
        });
    }

    /**
     * Применяет зону как EXTRUDE-маркер(ы). Использует готовые углы зоны (никаких тяжёлых расчётов).
     * Мульти-полигон: по одному маркеру на КАЖДУЮ фигуру зоны — id основной фигуры
     * остаётся "голым" markerID (обратная совместимость), доп. фигуры получают
     * суффикс "_1", "_2" ... — та же схема, что и в ZoneBlueMapService.upsert().
     */
    public void applyZoneMarker(ZoneInfo z) {
        if (z == null) return;
        List<List<Location>> shapes = z.getShapes();
        if (shapes.isEmpty()) return;

        String name = ChatColor.stripColor(safe(z.getName())).trim();
        String typeName = (z.getType() != null ? z.getType().name() : "ZONE");
        String setIdByType = "zones_" + typeName.toLowerCase(Locale.ROOT);
        String baseTitle = !name.isEmpty() ? name : (typeName + " #" + shortId(z.getID()));
        final String title = shapes.size() > 1 ? (baseTitle + " (частей: " + shapes.size() + ")") : baseTitle;

        for (int i = 0; i < shapes.size(); i++) {
            List<Location> corners = shapes.get(i);
            if (corners == null || corners.size() < 3) continue;

            Location base = corners.getFirst();
            if (base == null || base.getWorld() == null) continue;

            List<Vector3d> extrude = new ArrayList<>(corners.size());
            for (Location c : corners) {
                if (c != null) extrude.add(new Vector3d(c.getX(), c.getY(), c.getZ()));
            }
            if (extrude.size() < 3) continue;

            String markerId = (i == 0) ? z.getMarkerID() : (z.getMarkerID() + "_" + i);

            String detail =
                    "<b>" + escapeHtml(title) + "</b><br>" +
                            "<b>Type:</b> " + escapeHtml(typeName) + "<br>" +
                            "<b>ID:</b> " + escapeHtml(z.getID()) + "<br>" +
                            "<b>World:</b> " + escapeHtml(base.getWorld().getName()) + "<br>" +
                            "<b>Points:</b> " + extrude.size();

            addBlueMapMarker(markerId, base, setIdByType, title, "extrude", extrude, null, z.getFillColor());

            BlueMapAPI.getInstance().flatMap(api -> api.getMap(base.getWorld().getName())).ifPresent(map -> {
                MarkerSet set = map.getMarkerSets().get(setIdByType);
                if (set == null) return;
                Marker m = set.getMarkers().get(markerId);
                if (m instanceof ExtrudeMarker em) {
                    em.setDetail(detail);
                }
            });
        }
    }

    /** Ставит простой POI-маркер для таблички в отдельном наборе. Совместим с текущей сигнатурой POIMarker. */
    public void applySignMarker(Location loc, SignVariables vars) {
        if (loc == null || loc.getWorld() == null) return;

        BlueMapAPI.getInstance().flatMap(api -> api.getMap(loc.getWorld().getName())).ifPresent(map -> {
            Map<String, MarkerSet> sets = map.getMarkerSets();
            if (sets == null) return;

            final String setId = "zones_signs";
            final String setLabel = "Signs";
            MarkerSet set = sets.computeIfAbsent(setId, k -> new MarkerSet("Markers"));
            set.setLabel(setLabel);

            final String id = "sign_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
            Vector3d pos = new Vector3d(loc.getX(), loc.getY(), loc.getZ());

            POIMarker poi = new POIMarker(id, pos); // без iconAddress
            String label = (vars != null && vars.getSignCategory() != null) ? vars.getSignCategory().name() : "Sign";
            poi.setLabel(label);

            set.getMarkers().put(id, poi);
        });
    }

    // GH #27 "Географические объекты" — infra/geographic-landmarks-design.md
    // §8 п.4. Website-authoritative (see LandmarkSyncService) — this class
    // only mirrors whatever the site currently has onto BlueMap, one marker
    // set for the whole feature, refreshed wholesale on each sync (small
    // dataset, simplest-correct beats incremental diffing here).
    private static final String LANDMARK_SET_ID = "geo_landmarks";

    /** Точечный геообъект (пик и т.п.) — обычный POI-маркер, тот же паттерн, что и таблички. */
    public void applyLandmarkPointMarker(String id, Location loc, String label) {
        if (loc == null || loc.getWorld() == null || !Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        BlueMapAPI.getInstance().flatMap(api -> api.getMap(loc.getWorld().getName())).ifPresent(map -> {
            Map<String, MarkerSet> sets = map.getMarkerSets();
            if (sets == null) return;
            MarkerSet set = sets.computeIfAbsent(LANDMARK_SET_ID, k -> new MarkerSet("Markers"));
            set.setLabel("Геообъекты");

            POIMarker poi = new POIMarker(id, new Vector3d(loc.getX(), loc.getY(), loc.getZ()));
            poi.setLabel(label);
            set.getMarkers().put(id, poi);
        });
    }

    /**
     * Площадной геообъект (река/океан/хребет) — плоский контур без объёма
     * (в отличие от зон, ExtrudeMarker), почти без заливки/линии — это
     * подпись, не территория (design doc §1: "не блокирует", "ничья").
     */
    public void applyLandmarkAreaMarker(String id, String world, List<Vector3d> points, String label) {
        if (world == null || points == null || points.size() < 3 || !Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        BlueMapAPI.getInstance().flatMap(api -> api.getMap(world)).ifPresent(map -> {
            Map<String, MarkerSet> sets = map.getMarkerSets();
            if (sets == null) return;
            MarkerSet set = sets.computeIfAbsent(LANDMARK_SET_ID, k -> new MarkerSet("Markers"));
            set.setLabel("Геообъекты");

            Shape shape = blueMapShapeFromExtrude(points);
            if (shape == null) return;
            float y = (float) points.getFirst().getY();
            ShapeMarker marker = new ShapeMarker(id, shape, y);
            marker.setLabel(label);
            marker.setLineColor(new Color(244, 207, 78, 0.5f));
            marker.setFillColor(new Color(244, 207, 78, 0.03f));
            set.getMarkers().put(id, marker);
        });
    }

    /** Убирает маркеры geo_landmarks, которых больше нет на сайте (переименовали/удалили) — вызывается перед повторной проливкой актуального списка. */
    public void pruneLandmarkMarkers(java.util.Set<String> currentIds) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        BlueMapAPI.getInstance().ifPresent(api -> {
            for (BlueMapMap map : api.getMaps()) {
                Map<String, MarkerSet> sets = map.getMarkerSets();
                if (sets == null) continue;
                MarkerSet set = sets.get(LANDMARK_SET_ID);
                if (set == null || set.getMarkers() == null) continue;
                set.getMarkers().keySet().removeIf(id -> !currentIds.contains(id));
            }
        });
    }

    /* ===================== ОСНОВНОЙ МЕТОД ДОБАВЛЕНИЯ МАРКЕРОВ ===================== */

    /** Обновлённая функция с проверкой пересечений (XZ + Y-overlap) и фолбэком по высоте. */
    public void addBlueMapMarker(String id, Location location, String setID, String setLabel, String markerType,
                                 List<Vector3d> extrudePoints, Player p) {
        addBlueMapMarker(id, location, setID, setLabel, markerType, extrudePoints, p, null);
    }

    /**
     * Тот же метод, но для "extrude"-маркеров зон позволяет передать реальный
     * цвет зоны (zi.getFillColor()) вместо generic-цвета по типу
     * (colorsForZoneSetId) — без этого applyZoneMarker() красил все зоны
     * одного типа одинаково, а не в выбранный игроком цвет, и после
     * следующего ZoneManager-апдейта (blueMapService.upsert, который цвет
     * учитывает всегда) маркер визуально перекрашивался.
     */
    public void addBlueMapMarker(String id, Location location, String setID, String setLabel, String markerType,
                                 List<Vector3d> extrudePoints, Player p, org.bukkit.Color realZoneColor) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().flatMap(blueMapAPI -> blueMapAPI.getMap(location.getWorld().getName())).ifPresent(map -> {
            Map<String, MarkerSet> markerSets = map.getMarkerSets();
            if (markerSets == null) {
                Bukkit.getLogger().warning("MarkerSets is null for map: " + location.getWorld().getName());
                return;
            }

            MarkerSet markerSet = markerSets.computeIfAbsent(setID, k -> new MarkerSet("Markers"));
            // label набора должен быть стабильным, а не названием конкретной зоны
            markerSet.setLabel(niceSetLabel(setID));

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

                    // Контур XZ (ВАЖНО: Shape строим через BlueMap ClassLoader)
                    Shape newShape = blueMapShapeFromExtrude(extrudePoints);
                    if (newShape == null) {
                        if (p != null) p.sendMessage(ChatColor.RED + "Не удалось построить форму полигона (BlueMap Shape).");
                        return;
                    }

                    // Зона — плоский XZ-контур, а не 3D-объём: Y у самих
                    // угловых точек случаен (высота игрока в момент клика,
                    // дефолт с сайта и т.п.), поэтому вместо min/max из
                    // extrudePoints всегда берём реальные границы мира —
                    // иначе при minY≈maxY около потолка маркер визуально
                    // "тянется вверх до лимита" сразу после создания, а
                    // после перезагрузки (когда маркеры собираются заново)
                    // получает нормальный диапазон.
                    double minY = location.getWorld().getMinHeight();
                    double maxY = location.getWorld().getMaxHeight();

                    // Добавляем новый полигон
                    ExtrudeMarker extrudeMarker = new ExtrudeMarker(id, newShape, (float) minY, (float) maxY);

                    String label = (setLabel != null && !setLabel.isEmpty()) ? setLabel : ("Зона #" + shortId(id));
                    label = ChatColor.stripColor(label);
                    extrudeMarker.setLabel(label);

                    // красивое описание при клике
                    String setPretty = prettySetName(setID);

                    String detail =
                            "<b>" + escapeHtml(label) + "</b><br>" +
                                    "<b>Тип:</b> " + escapeHtml(setPretty) + "<br>" +
                                    "<b>Высота Y:</b> " + ((int) minY) + " .. " + ((int) maxY) + "<br>" +
                                    "<b>Точек:</b> " + extrudePoints.size();

                    extrudeMarker.setDetail(detail);

                    // Реальный цвет зоны, если передан, иначе fallback по типу.
                    ZoneColors colors = (realZoneColor != null)
                            ? new ZoneColors(toBlueMapColor(realZoneColor, 0.22f), toBlueMapColor(realZoneColor, 0.95f))
                            : colorsForZoneSetId(setID);
                    extrudeMarker.setFillColor(colors.fill());
                    extrudeMarker.setLineColor(colors.line());

                    markerSet.getMarkers().put(id, extrudeMarker);

                    if (p != null) {
                        p.sendMessage(ChatColor.GREEN + "Торговая точка успешно создана!");
                        plugin.getAwaitingCorrectCommand().remove(p.getUniqueId());
                    }
                    markerPoints.clear();

                }

                case "point_atm" -> {
                    Vector3d position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                    String mid = "atm_" + id;
                    POIMarker marker = new POIMarker(mid, position);
                    marker.setLabel("ATM");
                    marker.setIcon("assets/atm.png", 8, 8);
                    markerSet.getMarkers().put(mid, marker);
                }

                case "point_shop" -> {
                    Vector3d position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                    String mid = "shop_" + id;
                    POIMarker marker = new POIMarker(mid, position);
                    marker.setLabel("Табличка о продаже");
                    marker.setDetail("ID - '" + id + "'");
                    marker.setIcon("assets/atm.png", 8, 8);
                    markerSet.getMarkers().put(mid, marker);
                }

                default -> Bukkit.getLogger().warning("Unknown markerType: " + markerType);
            }
        });
    }

    private static String prettySetName(String setID) {
        if (setID == null || setID.isBlank()) return "—";

        String key = setID.startsWith("zones_")
                ? setID.substring("zones_".length())
                : setID;

        key = key.trim().toLowerCase(Locale.ROOT);

        return switch (key) {
            case "country"     -> "Государство";
            case "colony"      -> "Колония";
            case "bank"        -> "Банк";
            case "hospital"    -> "Госпиталь";
            case "industrial"  -> "Промышленная зона";
            case "park"        -> "Парк";
            case "church"      -> "Церковь";
            case "library"     -> "Библиотека";
            case "greenhouse"  -> "Теплица";
            case "shop"        -> "Магазин";
            default -> {
                // fallback: просто красиво капитализируем
                if (key.isEmpty()) yield "—";
                yield key.substring(0, 1).toUpperCase(Locale.ROOT) + key.substring(1);
            }
        };
    }

    /**
     * GH #21 point 3: fills in a POI marker's label/detail after creation —
     * point_atm/point_shop's addBlueMapMarker case only ever set a generic
     * fixed label ("ATM") with no detail at all, the caller-side context
     * (which country, which sign) never made it into what the visitor
     * actually sees on click. markerKey must be the PREFIXED key the marker
     * was actually stored under (e.g. "atm_" + markerID for point_atm — see
     * that case's `mid` — not the bare id the caller thinks of it as).
     */
    public void setPoiMarkerDetail(String markerSetKey, String markerKey, String worldName, String label, String detailHtml) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        BlueMapAPI.getInstance().flatMap(api -> api.getMap(worldName)).ifPresent(map -> {
            MarkerSet set = map.getMarkerSets().get(markerSetKey);
            if (set == null) return;
            Marker m = set.getMarkers().get(markerKey);
            if (m instanceof POIMarker poi) {
                if (label != null) poi.setLabel(label);
                if (detailHtml != null) poi.setDetail(detailHtml);
            }
        });
    }

    public void removeBlueMapMarker(String id, String worldName, String markerSetKey) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        BlueMapAPI.getInstance().flatMap(blueMapAPI -> blueMapAPI.getMap(worldName)).ifPresent(map -> {
            MarkerSet markerSet = map.getMarkerSets().get(markerSetKey);
            if (markerSet != null) markerSet.getMarkers().remove(id);
        });
    }

    public void saveAllBlueMapMarkersByPrefix(String prefix) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(api -> api.getMaps().forEach(map -> {
            for (String setId : new ArrayList<>(map.getMarkerSets().keySet())) {
                if (setId != null && setId.startsWith(prefix)) {
                    saveBlueMapMarkers(setId);
                }
            }
        }));
    }

    public void saveBlueMapMarkers(String setID) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> blueMapAPI.getMaps().forEach(map -> {
            MarkerSet markerSet = map.getMarkerSets().get(setID);
            if (markerSet == null) return;

            File markerFile = new File(getDataFolder().getParentFile(),
                    "BMMarker/customData/" + map.getId() + "/" + setID + ".json");

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

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(markerFile), StandardCharsets.UTF_8)) {
                MarkerGson.INSTANCE.toJson(markerSet, writer);
            } catch (IOException ex) {
                getLogger().severe("Ошибка при сохранении маркеров BlueMap.");
                ex.printStackTrace();
            }
        }));
    }

    private static Shape blueMapShapeFromExtrude(List<Vector3d> extrudePoints) {
        if (extrudePoints == null || extrudePoints.size() < 3) return null;

        try {
            // Берём ClassLoader от Shape (т.е. от BlueMap), чтобы Vector2d был "того же мира"
            ClassLoader cl = Shape.class.getClassLoader();

            Class<?> vecCls = Class.forName("com.flowpowered.math.vector.Vector2d", true, cl);
            var vecCtor = vecCls.getConstructor(double.class, double.class);

            ArrayList<Object> pts = new ArrayList<>(extrudePoints.size());
            for (Vector3d v : extrudePoints) {
                if (v == null) continue;
                // XZ -> (x, z). В Vector2d второй компонент называется Y, но это Z мира.
                pts.add(vecCtor.newInstance(v.getX(), v.getZ()));
            }
            if (pts.size() < 3) return null;

            var shapeCtor = Shape.class.getConstructor(Collection.class);
            return shapeCtor.newInstance(pts);

        } catch (Throwable t) {
            Bukkit.getLogger().warning("[BlueMapIntegration] Failed to build BlueMap Shape: " + t);
            return null;
        }
    }

    /* ===================== ПРОЧНЫЕ ХЕЛПЕРЫ ДЛЯ ПЕРЕСЕЧЕНИЙ ===================== */

    private record ZoneColors(Color fill, Color line) {}

    private static Color toBlueMapColor(org.bukkit.Color c, float a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    private static ZoneColors colorsForZoneSetId(String setID) {
        // setID ожидается вида "zones_industrial", "zones_country", ...
        String type = "";
        if (setID != null && setID.startsWith("zones_")) type = setID.substring("zones_".length()).toLowerCase(Locale.ROOT);

        // fill = полупрозрачный, line = плотный
        return switch (type) {
            case "industrial" -> new ZoneColors(new Color(0xF59E0B, 0.22f), new Color(0xF59E0B, 0.95f)); // amber
            case "bank"       -> new ZoneColors(new Color(0x22C55E, 0.22f), new Color(0x22C55E, 0.95f)); // green
            case "shop"       -> new ZoneColors(new Color(0x3B82F6, 0.22f), new Color(0x3B82F6, 0.95f)); // blue
            case "country"    -> new ZoneColors(new Color(0xA855F7, 0.18f), new Color(0xA855F7, 0.95f)); // purple
            case "colony"     -> new ZoneColors(new Color(0xEF4444, 0.18f), new Color(0xEF4444, 0.95f)); // red
            default           -> new ZoneColors(new Color(0x9CA3AF, 0.15f), new Color(0x9CA3AF, 0.90f)); // gray
        };
    }

    private static String niceSetLabel(String setID) {
        if (setID == null) return "Zones";
        if (!setID.startsWith("zones_")) return setID;

        String t = setID.substring("zones_".length()).toLowerCase(Locale.ROOT);
        String pretty = t.isEmpty() ? "Zones" : (t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1));
        return "Zones: " + pretty;
    }

    private static String shortId(String id) {
        if (id == null) return "null";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

}
