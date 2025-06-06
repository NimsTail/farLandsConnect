package com.frammy.unitylauncher;
import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.math.Shape;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.*;
import de.bluecolored.bluemap.api.markers.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static com.frammy.unitylauncher.UnityLauncher.calculateSurfaceArea;

public class ZoneManager {
    private final UnityLauncher unityLauncher;
    private final File zonesFile;
    private final YamlConfiguration zonesConfig;
    private final Map<UUID, List<Location>> zonePoints = new HashMap<>();
    private static class ZoneTypeData {
        private final String displayName;
        private final double areaLimit;
        private final int index;
        private final double minSize;
        private final boolean allowOverlap;


        public ZoneTypeData(String displayName, double areaLimit, int index, double minSize, boolean allowOverlap) {
            this.displayName = displayName;
            this.areaLimit = areaLimit;
            this.minSize = minSize;
            this.allowOverlap = allowOverlap;
            this.index = index;
        }

        public String getDisplayName() {
            return displayName;
        }

        public double getAreaLimit() {
            return areaLimit;
        }

        public int getIndex() {
            return index;
        }
    }

    private final Map<String, ZoneTypeData> zoneLimits = new HashMap<>() {{
        put("shop", new ZoneTypeData("Торговая точка", 500.0, 2, 3.0, false));
        put("bank", new ZoneTypeData("Банк", 300.0,2, 20.0, false));
        put("hospital", new ZoneTypeData("Госпиталь", 700.0, 2, 15.0, false));
        put("industrial", new ZoneTypeData("Промышленная зона", 1000.0, 2, 30.0, false));
        put("region", new ZoneTypeData("Регион", 10000.0, 1, 300.0, true));
        put("country", new ZoneTypeData("Государство", 30000.0, 0, 100.0, true));
    }};

    // Карта для хранения последней посещённой зоны игрока
    private final Map<UUID, ZoneInfo> playerLastZone = new HashMap<>();

    // Вспомогательный класс для хранения информации о зоне
    private static class ZoneInfo {
        String zoneType;
        String zoneID;
        String zoneName;
        String markerID;
        List<Location> zoneCorners;

        public ZoneInfo(String zoneType, String zoneID, String zoneName, String markerID, List<Location> zoneCorners) {
            this.zoneType = zoneType;
            this.zoneID = zoneID;
            this.zoneName = zoneName;
            this.markerID = markerID;
            this.zoneCorners = zoneCorners;
        }
    }

    public ZoneManager(UnityLauncher launcher, File dataFolder) {
        this.unityLauncher = launcher;
        zonesFile = new File(dataFolder, "zones.yml");
        zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
    }

    public void handleCommand(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(ChatColor.RED + "Использование: /ul zone <addcorner/removecorner/build/update> ...");
            return;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "addcorner":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /ul zone addcorner <zoneType>");
                    return;
                }
                addCorner(player, args[1].toLowerCase());
                break;

            case "removecorner":
                removeCorner(player);
                break;

            case "build":
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Использование: /ul zone build <zoneType> <zoneName>");
                    return;
                }
                buildZone(player, args[1].toLowerCase(), args[2]);
                break;

            case "update":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /ul zone update <corners/name> <значение>");
                    return;
                }
                updateZone(player, args[1].toLowerCase(), args.length > 2 ? args[2] : "");
                break;
            case "remove":
                removeZone(player);
                break;
            case "confirmremove":
                confirmRemoveZone(player);
                break;
            case "cancelremove":
                cancelRemoveZone(player);
                break;
            default:
                player.sendMessage(ChatColor.RED + "Неизвестная команда!");
        }
    }

    private void addCorner(Player player, String zoneType) {
        if (!zoneLimits.containsKey(zoneType)) {
            player.sendMessage(ChatColor.RED + "Неверный тип зоны!");
            return;
        }

        UUID playerId = player.getUniqueId();
        zonePoints.putIfAbsent(playerId, new ArrayList<>());
        List<Location> points = zonePoints.get(playerId);
        ZoneTypeData zoneData = zoneLimits.get(zoneType);
        double maxArea = zoneData.getAreaLimit();

        // Проверка на пересечение точки с существующим и зонами
        if (isPointInOtherZone(player.getLocation(), player.getName(), zoneType, null)) {
            if (!zoneData.allowOverlap) {
                player.sendMessage(ChatColor.RED + "Нельзя добавить точку, она пересекается с уже существующей зоной!");
                return;
            }
        }
        // Временное добавление точки для проверки площади
        List<Location> tempPoints = new ArrayList<>(points);
        tempPoints.add(player.getLocation().clone());
        double newArea = calculateSurfaceArea(tempPoints);

        if (newArea < zoneData.minSize && tempPoints.size() >= 3) {
            player.sendMessage(ChatColor.GRAY + "Зона слишком маленькая: " + ChatColor.RED + newArea + ChatColor.GRAY + " < " + ChatColor.YELLOW + "1");
            return;
        }
        if (newArea > maxArea) {
            player.sendMessage(ChatColor.GRAY + "Площадь превышает лимит: " + ChatColor.RED + newArea + ChatColor.GRAY + " / " + ChatColor.YELLOW + maxArea);
            return;
        }
        List<Vector2d> tempPoints2D = tempPoints.stream()
                .map(loc -> new Vector2d(loc.getX(), loc.getZ())) // Используем только X и Z
                .collect(Collectors.toList());
        if (hasSelfIntersections(tempPoints2D)) {
            player.sendMessage(ChatColor.RED + "Точки пересекаются - фигура имеет неверную форму.");
            return;
        }
// Если проверка пройдена — добавляем точку в основной список
        points.add(player.getLocation().clone());
        player.sendMessage(ChatColor.GOLD + "[" + points.size() + "]" + ChatColor.YELLOW + " Добавлена точка! Текущая площадь: " + ChatColor.GOLD + newArea);
    }

    private void removeCorner(Player player) {
        UUID playerId = player.getUniqueId();
        List<Location> points = zonePoints.get(playerId);

        if (points == null || points.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Нет точек для удаления!");
            return;
        }

        points.remove(points.size() - 1);
        player.sendMessage(ChatColor.GRAY + "Удалена последняя точка.");
    }

    private void buildZone(Player player, String zoneType, String zoneName) {
        UUID playerId = player.getUniqueId();
        List<Location> points = zonePoints.get(playerId);
        if (points == null || points.size() < 3) {
            player.sendMessage(ChatColor.RED + "Необходимо минимум 3 точки!");
            return;
        }

        // Проверяем каждую точку новой зоны на пересечение с уже существующими
        for (Location loc : points) {
            if (isPointInOtherZone(loc, player.getName(), zoneType, null)) {
                player.sendMessage(ChatColor.RED + "Нельзя создать зону, точка " + loc.toVector().toString() + " пересекается с существующей зоной!");
                return;
            }
        }

        String playerName = player.getName();
        String zoneID = generateZoneID(zoneType, playerName);
        String markerID = "marker_" + UUID.randomUUID();

        List<Map<String, Object>> serializedPoints = points.stream()
                .map(Location::serialize)
                .collect(Collectors.toList());

        zonesConfig.set(zoneType + "." + playerName + "." + zoneID + ".name", zoneName);
        zonesConfig.set(zoneType + "." + playerName + "." + zoneID + ".marker_ID", markerID);
        zonesConfig.set(zoneType + "." + playerName + "." + zoneID + ".corners", serializedPoints);

        saveZonesConfig();
        addBlueMapMarker(zoneType, markerID, points, zoneName);
        player.sendMessage(ChatColor.GREEN + "Зона " + zoneName + " успешно создана!");
        zonePoints.remove(playerId);
    }

    private String generateZoneID(String zoneType, String playerName) {
        String baseKey = zoneType + "." + playerName;
        ConfigurationSection section = zonesConfig.getConfigurationSection(baseKey);
        int count = (section != null) ? section.getKeys(false).size() : 0;
        return "zone_" + (count + 1);
    }
    public static void applyEffectIfInZone(Player player, boolean isInZone, PotionEffectType effectType, int duration, int amplifier) {
        if (isInZone) {
            // Накладываем эффект, если игрок в зоне
            player.addPotionEffect(new PotionEffect(effectType, duration * 20, amplifier));
        } else {
            // Убираем эффект, если игрок вышел из зоны
            player.removePotionEffect(effectType);
        }
    }

    public void updateZone(Player player, String updateType, String newValue) {
        Location playerLoc = player.getLocation();
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        ZoneInfo zoneInfo = playerLastZone.get(playerId);
        if (zoneInfo == null) {
            // Попытка найти зону, в которой находится игрок
            outer:
            for (String zoneType : zonesConfig.getKeys(false)) {
                ConfigurationSection playerZones = zonesConfig.getConfigurationSection(zoneType + "." + playerName);
                if (playerZones == null) continue;

                for (String zoneID : playerZones.getKeys(false)) {
                    List<Map<String, Object>> serializedCorners = (List<Map<String, Object>>) playerZones.getList(zoneID + ".corners");
                    if (serializedCorners == null) continue;

                    List<Location> corners = serializedCorners.stream()
                            .map(map -> Location.deserialize((Map<String, Object>) map))
                            .collect(Collectors.toList());

                    if (isPlayerInZone(playerLoc, corners)) {
                        zoneInfo = new ZoneInfo(zoneType, zoneID, playerZones.getString(zoneID + ".name"),
                                playerZones.getString(zoneID + ".marker_ID"), corners);
                        break outer;
                    }
                }
            }
        }

        if (zoneInfo == null) {
            player.sendMessage(ChatColor.RED + "Вы не находитесь в своей зоне!");
            return;
        }

        // Обновляем информацию о последней посещённой зоне
        playerLastZone.put(playerId, zoneInfo);

        switch (updateType.toLowerCase()) {
            case "corners":
                if (newValue.equals("+")) {
                    // Проверка пересечения с другими зонами
                    if (isPointInOtherZone(playerLoc, playerName, zoneInfo.zoneType, zoneInfo.zoneID)) {
                        player.sendMessage(ChatColor.RED + "Нельзя добавить точку, она пересекается с существующей зоной!");
                        return;
                    }

                    // Временное добавление новой точки
                    List<Location> tempPoints = new ArrayList<>(zoneInfo.zoneCorners);
                    tempPoints.add(playerLoc.clone());

                    // Проверка на самопересечения
                    List<Vector2d> tempPoints2D = tempPoints.stream()
                            .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                            .collect(Collectors.toList());

                    if (hasSelfIntersections(tempPoints2D)) {
                        player.sendMessage(ChatColor.GRAY + "Точки пересекаются, фигура имеет неверную форму.");
                        return;
                    }

                    // Проверка на лимит площади
                    double newArea = calculateSurfaceArea(tempPoints);
                    ZoneTypeData zoneData = zoneLimits.get(zoneInfo.zoneType);
                    double maxArea = zoneData.getAreaLimit();

                    if (newArea > maxArea) {
                        player.sendMessage(ChatColor.GRAY + "Площадь превышает лимит: " + ChatColor.RED + newArea + ChatColor.GRAY + " / " + ChatColor.YELLOW + maxArea);
                        return;
                    }

                    // Если всё нормально — добавляем точку в основную зону
                    zoneInfo.zoneCorners.add(playerLoc.clone());
                    player.sendMessage(ChatColor.GOLD + "[" + zoneInfo.zoneCorners.size() + "]" + ChatColor.YELLOW + " Добавлена точка! Текущая площадь: " + ChatColor.GOLD + newArea);

                } else if (newValue.equals("-")) {
                    if (zoneInfo.zoneCorners.size() > 3) {
                        zoneInfo.zoneCorners.remove(zoneInfo.zoneCorners.size() - 1);
                        player.sendMessage(ChatColor.GRAY + "Удалена последняя точка.");
                    } else {
                        player.sendMessage(ChatColor.RED + "В зоне должно быть минимум 3 точки!");
                        return;
                    }
                } else {
                    player.sendMessage(ChatColor.YELLOW + "Используйте '/ul zone update corners +' или '-' для добавления или удаления точек.");
                    return;
                }

                // Обновление конфигурации зон
                List<Map<String, Object>> serializedCorners = zoneInfo.zoneCorners.stream()
                        .map(Location::serialize)
                        .collect(Collectors.toList());
                zonesConfig.set(zoneInfo.zoneType + "." + playerName + "." + zoneInfo.zoneID + ".corners", serializedCorners);

                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, zoneInfo.zoneName, player);
                break;

            case "name":
                zonesConfig.set(zoneInfo.zoneType + "." + playerName + "." + zoneInfo.zoneID + ".name", newValue);
                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, newValue, player);
                player.sendMessage(ChatColor.GREEN + "Название зоны обновлено!");
                zoneInfo.zoneName = newValue;
                break;

            default:
                player.sendMessage(ChatColor.RED + "Некорректный параметр обновления!");
                return;
        }

        saveZonesConfig();
    }

    private void removeZone(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        Location playerLoc = player.getLocation();

        ZoneInfo zoneInfo = playerLastZone.get(playerId);
        if (zoneInfo == null) {
            for (String zoneType : zonesConfig.getKeys(false)) {
                ConfigurationSection playerZones = zonesConfig.getConfigurationSection(zoneType + "." + playerName);
                if (playerZones == null) continue;

                for (String zoneID : playerZones.getKeys(false)) {
                    List<Map<String, Object>> serializedCorners = (List<Map<String, Object>>) playerZones.getList(zoneID + ".corners");
                    if (serializedCorners == null) continue;

                    List<Location> corners = serializedCorners.stream()
                            .map(map -> Location.deserialize((Map<String, Object>) map))
                            .collect(Collectors.toList());

                    if (isPlayerInZone(playerLoc, corners)) {
                        zoneInfo = new ZoneInfo(zoneType, zoneID, playerZones.getString(zoneID + ".name"),
                                playerZones.getString(zoneID + ".marker_ID"), corners);
                        break;
                    }
                }
            }
        }

        if (zoneInfo == null) {
            player.sendMessage(ChatColor.RED + "Вы не находитесь в своей зоне!");
            return;
        }

        TextComponent confirm = new TextComponent(ChatColor.GREEN + "[Подтвердить удаление]");
        confirm.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul zone confirmremove " + zoneInfo.zoneID));
        TextComponent cancel = new TextComponent(ChatColor.RED + "[Отмена]");
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul zone cancelremove"));

        player.spigot().sendMessage(new TextComponent(ChatColor.YELLOW + "Вы уверены, что хотите удалить зону " + ChatColor.GOLD + zoneInfo.zoneName + ChatColor.YELLOW + "? "), confirm, new TextComponent(" "), cancel);
        playerLastZone.put(playerId, zoneInfo);
    }

    public void confirmRemoveZone(Player player) {
        UUID playerId = player.getUniqueId();
        ZoneInfo zoneInfo = playerLastZone.get(playerId);

        if (zoneInfo == null) {
            player.sendMessage(ChatColor.RED + "Нет зоны для удаления!");
            return;
        }

        String path = zoneInfo.zoneType + "." + player.getName() + "." + zoneInfo.zoneID;
        zonesConfig.set(path, null);
        saveZonesConfig();
        removeBlueMapMarker(zoneInfo);

        player.sendMessage(ChatColor.GREEN + "Зона " + zoneInfo.zoneName + " удалена!");
        playerLastZone.remove(playerId);
    }

    public void cancelRemoveZone(Player player) {
        player.sendMessage(ChatColor.YELLOW + "Удаление зоны отменено.");
        playerLastZone.remove(player.getUniqueId());
    }
    // Метод для проверки, находится ли точка (не обновляемой зоны) внутри какой-либо другой зоны
    // currentZoneID может быть null, если проверка проводится для новой зоны
    private boolean isPointInOtherZone(Location loc, String ownerName, String currentZoneType, String currentZoneID) {
        for (String zoneType : zonesConfig.getKeys(false)) {
            ConfigurationSection playerZones = zonesConfig.getConfigurationSection(zoneType + "." + ownerName);
            if (playerZones == null) continue;

            for (String zoneID : playerZones.getKeys(false)) {
                // Если обновляем текущую зону, пропускаем её
                if (zoneType.equals(currentZoneType) && currentZoneID != null && zoneID.equals(currentZoneID))
                    continue;
                List<Map<String, Object>> serializedCorners = (List<Map<String, Object>>) playerZones.getList(zoneID + ".corners");
                if (serializedCorners == null) continue;
                List<Location> corners = serializedCorners.stream()
                        .map(map -> Location.deserialize((Map<String, Object>) map))
                        .collect(Collectors.toList());
                if (isPlayerInZone(loc, corners)) {
                    return true;
                }
            }
        }
        // Также проверяем зоны других игроков
        for (String zoneType : zonesConfig.getKeys(false)) {
            for (String playerName : zonesConfig.getConfigurationSection(zoneType).getKeys(false)) {
                // Если владелец совпадает с текущим, пропускаем (так как уже проверили выше)
                if (playerName.equals(ownerName)) continue;
                ConfigurationSection playerZones = zonesConfig.getConfigurationSection(zoneType + "." + playerName);
                if (playerZones == null) continue;
                for (String zoneID : playerZones.getKeys(false)) {
                    List<Map<String, Object>> serializedCorners = (List<Map<String, Object>>) playerZones.getList(zoneID + ".corners");
                    if (serializedCorners == null) continue;
                    List<Location> corners = serializedCorners.stream()
                            .map(map -> Location.deserialize((Map<String, Object>) map))
                            .collect(Collectors.toList());
                    if (isPlayerInZone(loc, corners)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isPlayerInZone(Location loc, List<Location> zoneCorners) {
        if (zoneCorners == null || zoneCorners.size() < 3) return false;

        double minY = 42;
        double maxY = 255;

        if (loc.getY() < minY || loc.getY() > maxY) {
            return false; // Если высота не подходит, сразу выходим
        }

        // Алгоритм "Ray-Casting" для проверки попадания в многоугольник (по XZ)
        boolean inside = false;
        int j = zoneCorners.size() - 1;

        for (int i = 0; i < zoneCorners.size(); i++) {
            double xi = zoneCorners.get(i).getX(), zi = zoneCorners.get(i).getZ();
            double xj = zoneCorners.get(j).getX(), zj = zoneCorners.get(j).getZ();

            boolean intersect = ((zi > loc.getZ()) != (zj > loc.getZ())) &&
                    (loc.getX() < (xj - xi) * (loc.getZ() - zi) / (zj - zi) + xi);
            if (intersect) inside = !inside;
            j = i;
        }

        return inside;
    }

    private final Map<UUID, Boolean> playerZoneStatus = new HashMap<>();

    public void checkPlayerZone(Player player) {
        Location playerLoc = player.getLocation();
        UUID playerId = player.getUniqueId();

        Map<Integer, ZoneInfo> zonesByIndex = new TreeMap<>(Collections.reverseOrder()); // Храним зоны по убыванию index
        boolean isInsideZone = false;

        for (String zoneType : zonesConfig.getKeys(false)) {
            ConfigurationSection playerZones = zonesConfig.getConfigurationSection(zoneType + "." + player.getName());
            if (playerZones == null) continue;

            for (String zoneID : playerZones.getKeys(false)) {
                List<Map<?, ?>> rawCorners = playerZones.getMapList(zoneID + ".corners");
                if (rawCorners == null || rawCorners.isEmpty()) continue;

                List<Location> corners = rawCorners.stream()
                        .map(map -> Location.deserialize((Map<String, Object>) map))
                        .collect(Collectors.toList());

                if (isPlayerInZone(playerLoc, corners)) {
                    ZoneTypeData zoneTypeData = zoneLimits.get(zoneType);
                    if (zoneTypeData == null) continue;

                    isInsideZone = true;
                    zonesByIndex.put(zoneTypeData.getIndex(), new ZoneInfo(
                            zoneType, zoneID,
                            playerZones.getString(zoneID + ".name", "Неизвестная зона"),
                            playerZones.getString(zoneID + ".marker_ID"),
                            corners
                    ));
                }
            }
        }

        if (!zonesByIndex.isEmpty()) {
            ZoneInfo highestPriorityZone = zonesByIndex.values().iterator().next(); // Берём зону с максимальным index
            playerLastZone.put(playerId, highestPriorityZone);

            ZoneTypeData zoneTypeData = zoneLimits.get(highestPriorityZone.zoneType);
            if (zoneTypeData != null) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(ChatColor.GREEN + "Зона: " + ChatColor.GOLD + zoneTypeData.getDisplayName() + " \"" + highestPriorityZone.zoneName + "\""));
                applyEffectIfInZone(player, true, PotionEffectType.REGENERATION, 4, 1);
            }
        } else {
            if (playerZoneStatus.getOrDefault(playerId, false)) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "Вы покинули зону"));
                applyEffectIfInZone(player, false, PotionEffectType.REGENERATION, 0, 0);
            }
        }

        playerZoneStatus.put(playerId, isInsideZone);
    }

    private void addBlueMapMarker(String zoneType, String markerID, List<Location> locations, String zoneName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            Location location = locations.get(0);
            blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                String markerSetID = "zones_" + zoneType;
                MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(markerSetID, k -> new MarkerSet(markerSetID));
                List<Vector2d> basePoints = locations.stream()
                        .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                        .collect(Collectors.toList());

                ExtrudeMarker.Builder markerBuilder = ExtrudeMarker.builder()
                        .label(zoneName) // Заголовок маркера
                        .shape(new Shape(basePoints), 42, 255) // Контур зоны
                        .detail("<b>" + zoneLimits.get(zoneType).displayName + " \"" + zoneName + "\"</b><br><br><i> Владелец:</i> " + getZoneOwner(zoneType, markerID) + "<br><i>Площадь:</i> " + calculateSurfaceArea(locations)); // 📌 Добавляем описание
                markerSet.getMarkers().put(markerID, markerBuilder.build());
                unityLauncher.saveBlueMapMarkers(markerSetID);
            });
        });
    }
    private void removeBlueMapMarker(ZoneInfo zoneInfo) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                blueMapAPI.getMap(zoneInfo.zoneCorners.get(0).getWorld().getName()).ifPresent(map -> {
                    MarkerSet markerSet = map.getMarkerSets().get("zones_" + zoneInfo.zoneType);
                    if (markerSet != null) {
                        markerSet.getMarkers()
                                .remove(zoneInfo.markerID);
                    }
                });
            });
        }
    }

    private void updateBlueMapMarker(String zoneType, String markerID, List<Location> locations, String zoneName, Player p) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            Location location = locations.get(0);
            blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                String markerSetID = "zones_" + zoneType;
                MarkerSet markerSet = map.getMarkerSets().get(markerSetID);

                if (markerSet != null && markerSet.getMarkers().containsKey(markerID)) {
                    ExtrudeMarker marker = (ExtrudeMarker) markerSet.getMarkers().get(markerID);
                    List<Vector2d> basePoints = locations.stream()
                            .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                            .collect(Collectors.toList());

                    marker.setShape(new Shape(basePoints), 42, 255);
                    marker.setLabel(zoneName);
                    marker.setDetail("<b>" + zoneLimits.get(zoneType).displayName + " \"" + zoneName + "\"</b><br><br><i> Владелец:</i> " + getZoneOwner(zoneType, markerID) + "<br><i>Площадь:</i> " + calculateSurfaceArea(locations)); // 📌 Добавляем описание
                    unityLauncher.saveBlueMapMarkers(markerSetID);
                }
            });
        });
    }

    private String getZoneOwner(String zoneType, String markerID) {
        ConfigurationSection zoneSection = zonesConfig.getConfigurationSection(zoneType);
        if (zoneSection == null) return "Неизвестный";

        for (String playerName : zoneSection.getKeys(false)) {
            ConfigurationSection playerZones = zoneSection.getConfigurationSection(playerName);
            if (playerZones == null) continue;

            for (String zoneID : playerZones.getKeys(false)) {
                String storedMarkerID = playerZones.getString(zoneID + ".marker_ID");
                if (storedMarkerID != null && storedMarkerID.equals(markerID)) {
                    return playerName; // Нашли владельца зоны
                }
            }
        }
        return "Неизвестный";
    }
    public static boolean segmentsIntersect(Vector2d a, Vector2d b, Vector2d c, Vector2d d) {
        return ccw(a, c, d) != ccw(b, c, d) && ccw(a, b, c) != ccw(a, b, d);
    }

    // Проверка на против часовой стрелки
    private static boolean ccw(Vector2d a, Vector2d b, Vector2d c) {
        return (b.getX() - a.getX()) * (c.getY() - a.getY()) - (b.getY() - a.getY()) * (c.getX() - a.getX()) > 0;
    }

    private boolean hasSelfIntersections(List<Vector2d> points) {
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Vector2d a1 = points.get(i);
            Vector2d a2 = points.get((i + 1) % n);

            for (int j = i + 2; j < n; j++) {
                // Пропускаем соседние рёбра и граничные случаи
                if (Math.abs(i - j) == 1 || (i == 0 && j == n - 1) || (j == 0 && i == n - 1)) {
                    continue;
                }

                Vector2d b1 = points.get(j);
                Vector2d b2 = points.get((j + 1) % n);

                if (segmentsIntersect(a1, a2, b1, b2)) {
                    return true; // Найдено пересечение
                }
            }
        }
        return false; // Нет пересечений
    }

    private void saveZonesConfig() {
        try {
            zonesConfig.save(zonesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

