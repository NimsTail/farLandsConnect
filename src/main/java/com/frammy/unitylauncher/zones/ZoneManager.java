package com.frammy.unitylauncher.zones;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import com.frammy.unitylauncher.signs.ItemData;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.signs.SignVariables;
import de.bluecolored.bluemap.api.math.Shape;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.block.Sign;
import org.bukkit.block.Block;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.*;
import de.bluecolored.bluemap.api.markers.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public class ZoneManager {
    private final UnityLauncher unityLauncher;
    private SignManager signManager;
    private BlueMapIntegration blueMapIntegration;
    private ActivityTracker activityTracker;
    private final File zonesFile;
    private YamlConfiguration zonesConfig;
    private final Map<UUID, List<Location>> zonePoints = new HashMap<>();

    public HashMap<String, ZoneInfo> zoneList = new HashMap<>();

    public ZoneManager(UnityLauncher plugin, SignManager signManager, BlueMapIntegration blueMapIntegration, ActivityTracker activityTracker) {
        this.unityLauncher = plugin;
        this.signManager = signManager;
        this.blueMapIntegration = blueMapIntegration;
        this.activityTracker = activityTracker;

        this.zonesFile = new File(plugin.getDataFolder(), "zones.yml"); // <-- создаём файл в папке плагина
        this.zonesConfig = YamlConfiguration.loadConfiguration(zonesFile); // загружаем конфиг
    }
    public void setSignManager(SignManager signManager) {
        this.signManager = signManager;
    }

    private final Map<ZoneType, ZoneTypeData> zoneLimits = new HashMap<>() {{
        put(ZoneType.SHOP, new ZoneTypeData("Торговая точка", 500.0, 2, 3.0, false, 1));
        put(ZoneType.BANK, new ZoneTypeData("Банк", 300.0,2, 20.0, false, 1));
        put(ZoneType.HOSPITAL, new ZoneTypeData("Госпиталь", 700.0, 2, 15.0, false, 1));
        put(ZoneType.INDUSTRIAL, new ZoneTypeData("Промышленная зона", 1000.0, 2, 30.0, false, 1));
        put(ZoneType.REGION, new ZoneTypeData("Регион", 10000.0, 1, 300.0, true, 0.85));
        put(ZoneType.COUNTRY, new ZoneTypeData("Государство", 30000.0, 0, 100.0, true, 0.7));
    }};


    // Карта для хранения последней посещённой зоны игрока
    private final Map<UUID, ZoneInfo> playerLastZone = new HashMap<>();

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
                addCorner(player, ZoneType.valueOf(args[1].toUpperCase()));
                break;

            case "removecorner":
                removeCorner(player);
                break;

            case "build":
                if (args.length < 3) {
                    player.sendMessage(ChatColor.RED + "Использование: /ul zone build <zoneType> <zoneName>");
                    return;
                }
                buildZone(player, ZoneType.valueOf(args[1].toUpperCase()), args[2]);
                break;

            case "update":
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "Использование: /ul zone update <corners/name> <значение>");
                    return;
                }
                updateZone(player, args[1].toLowerCase(), args.length > 2 ? args[2] : "");
                break;
            case "price":
                ZoneInfo zoneInfo = playerLastZone.get(player.getUniqueId());
                if (zoneInfo != null) {
                    for (ZoneInfo zone : zoneList.values()) {
                        if (zone.getOwner().equals(player.getName())) {
                            double cost = zoneInfo.getCachedCost(() -> {
                                return calculateZoneDailyCost(
                                        zoneInfo,
                                        activityTracker.getChunkStatsMap(),
                                        activityTracker.getWeights()
                                );
                            });

                            player.sendMessage("Цена: " + cost);

                        }

                    }
                }
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

    private void addCorner(Player player, ZoneType zoneType) {
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
            if (!zoneData.getAllowOverlap()) {
                player.sendMessage(ChatColor.RED + "Нельзя добавить точку, она пересекается с уже существующей зоной!");
                return;
            }
        }
        // Временное добавление точки для проверки площади
        List<Location> tempPoints = new ArrayList<>(points);
        tempPoints.add(player.getLocation().clone());
        double newArea = calculateSurfaceArea(tempPoints);

        if (newArea < zoneData.getMinSize() && tempPoints.size() >= 3) {
            player.sendMessage(ChatColor.GRAY + "Зона слишком маленькая: " + ChatColor.RED + newArea + ChatColor.GRAY + " < " + ChatColor.YELLOW + zoneData.getMinSize());
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

    private void buildZone(Player player, ZoneType zoneType, String zoneName) {
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
        String randomUUID = String.valueOf(UUID.randomUUID());
        String zoneID = "zone_" + randomUUID;
        String markerID = "marker_" + randomUUID;

        List<Map<String, Object>> serializedPoints = points.stream()
                .map(Location::serialize)
                .collect(Collectors.toList());

        zonesConfig.set(zoneType + "." + playerName + "." + zoneID + ".name", zoneName);
        zonesConfig.set(zoneType + "." + playerName + "." + zoneID + ".marker_ID", markerID);
        zonesConfig.set(zoneType + "." + playerName + "." + zoneID + ".corners", serializedPoints);

       // saveZonesConfig();
        zoneList.put(markerID, new ZoneInfo(zoneType, zoneID, zoneName, markerID, points, playerName));
        addBlueMapMarker(zoneType, markerID, points, zoneName);
        player.sendMessage(ChatColor.GREEN + "Зона " + zoneName + " успешно создана!");
        zonePoints.remove(playerId);
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
            for (ZoneInfo zone : zoneList.values()) {
                if (!zone.getOwner().equals(playerName)) continue;
                if (isPlayerInZone(playerLoc, zone.getCorners())) {
                    zoneInfo = zone;
                    break;
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
                    player.sendMessage(ChatColor.GRAY + "Используй" + ChatColor.YELLOW + "'/ul zone update corners +'" + ChatColor.GRAY + "или" + ChatColor.YELLOW + "'-'" + ChatColor.GRAY + " для добавления или удаления точек.");
                    return;
                }

                // Обновление конфигурации зон
               // List<Map<String, Object>> serializedCorners = zoneInfo.zoneCorners.stream()
                //        .map(Location::serialize)
                //        .collect(Collectors.toList());
               // zonesConfig.set(zoneInfo.zoneType + "." + playerName + "." + zoneInfo.zoneID + ".corners", serializedCorners);

                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, zoneInfo.zoneName, player);
                break;

            case "name":
               // zonesConfig.set(zoneInfo.zoneType + "." + playerName + "." + zoneInfo.zoneID +
                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, newValue, player);
                player.sendMessage(ChatColor.GREEN + "Название зоны обновлено!");
                zoneInfo.zoneName = newValue;
                for (Location loc : signManager.genericSignList.keySet()) {
                    Vector2d signPos = new Vector2d(loc.getX(), loc.getZ());
                    List<Vector2d> corners = zoneInfo.zoneCorners.stream()
                            .map(cornerLoc -> new Vector2d(cornerLoc.getX(), cornerLoc.getZ()))
                            .collect(Collectors.toList());
                    if (isPointInsidePolygon(signPos, corners)) {
                        String newLine0 = "Торговая точка [ " + newValue + " ]";
                        List<String> initial = signManager.genericSignList.get(loc).getSignText();
                        signManager.genericSignList.get(loc).setSignText(Arrays.asList(newLine0, initial.get(1), initial.get(2), initial.get(3)));
                        Sign sign = (Sign) loc.getBlock().getState();

                        signManager.stopScrollingTask(loc);
                        HashMap<Integer, String> scrollLines = new HashMap<>();
                        scrollLines.put(0, newLine0);
                        signManager.makeSignScrollingLines(loc, scrollLines, 6, 13);

                        sign.setLine(0 , newLine0);
                        sign.update();
                       // signManager.resumeScrolling();
                    }
                }

                break;

            default:
                player.sendMessage(ChatColor.RED + "Некорректный параметр обновления!");
                return;
        }

        //saveZonesConfig();
    }

    private void removeZone(Player player) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        Location playerLoc = player.getLocation();

        ZoneInfo zoneInfo = playerLastZone.get(playerId);
        if (zoneInfo == null) {
            for (ZoneInfo zone : zoneList.values()) {
                if (!zone.zoneOwner.equalsIgnoreCase(playerName)) continue;
                if (isPlayerInZone(playerLoc, zone.zoneCorners)) {
                    zoneInfo = zone;
                    break;
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
       // String path = zoneInfo.zoneType + "." + player.getName() + "." + zoneInfo.zoneID;
        //zonesConfig.set(path, null);
       // saveZonesConfig();
        zoneList.remove(zoneInfo.markerID);
        blueMapIntegration.removeBlueMapMarker(
                zoneInfo.markerID,
                zoneInfo.zoneCorners.get(0).getWorld().getName(),
                "zones_" + zoneInfo.zoneType
        );

        player.sendMessage(ChatColor.GREEN + "Зона " + zoneInfo.zoneName + " удалена!");
        playerLastZone.remove(playerId);
    }

    public void cancelRemoveZone(Player player) {
        player.sendMessage(ChatColor.YELLOW + "Удаление зоны отменено.");
        playerLastZone.remove(player.getUniqueId());
    }
    // Метод для проверки, находится ли точка (не обновляемой зоны) внутри какой-либо другой зоны
    // currentZoneID может быть null, если проверка проводится для новой зоны
    private boolean isPointInOtherZone(Location loc, String ownerName, ZoneType currentZoneType, String currentZoneID) {
        for (ZoneInfo zone : zoneList.values()) {
            // Пропускаем текущую зону (по типу и ID)
            if (zone.zoneType == currentZoneType && zone.zoneID.equals(currentZoneID))
                continue;

            // Пропускаем зону, если она принадлежит текущему игроку и уже была проверена ранее
            if (zone.zoneOwner.equals(ownerName) && zone.zoneType == currentZoneType)
                continue;

            // Проверка, находится ли точка в другой зоне
            if (isPlayerInZone(loc, zone.zoneCorners)) {
                return true;
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

        Map<Integer, ZoneInfo> zonesByIndex = new TreeMap<>(Collections.reverseOrder());
        boolean isInsideZone = false;

        for (ZoneInfo zone : zoneList.values()) {
            // Раскомментировать при необходимости:
            // if (!zone.getOwner().equals(player.getName())) continue;

            if (isPlayerInZone(playerLoc, zone.getCorners())) {
                ZoneTypeData zoneTypeData = zoneLimits.get(zone.getType());
                if (zoneTypeData == null) {
                    continue;
                }
                isInsideZone = true;
                zonesByIndex.put(zoneTypeData.getIndex(), zone);
            }
        }

        if (!zonesByIndex.isEmpty()) {
            ZoneInfo highestPriorityZone = zonesByIndex.values().iterator().next();
            playerLastZone.put(playerId, highestPriorityZone);

            ZoneTypeData zoneTypeData = zoneLimits.get(highestPriorityZone.zoneType);
            if (zoneTypeData != null) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        new TextComponent(ChatColor.GREEN + "Зона: " + ChatColor.GOLD + zoneTypeData.getDisplayName() + " \"" + highestPriorityZone.zoneName + "\""));
            }
        } else {
            if (playerZoneStatus.getOrDefault(playerId, false)) {
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "Вы покинули зону"));
            }
        }
        playerZoneStatus.put(playerId, isInsideZone);
    }

    // Утилита для читаемого отображения локации
    private String locToStr(Location loc) {
        return String.format("(%s: %.1f, %.1f, %.1f)", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
    }
    public Map<Chunk, Double> getChunkFractions(List<Location> corners) {
        Map<Chunk, Double> result = new HashMap<>();

        if (corners.size() < 3) return result;

        // Преобразуем в 2D
        List<Vector2d> polygon = corners.stream()
                .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                .collect(Collectors.toList());

        World world = corners.get(0).getWorld(); // предполагаем одна зона = один мир

        // Определяем границы
        double minX = polygon.stream().mapToDouble(p -> p.getX()).min().orElse(0);
        double maxX = polygon.stream().mapToDouble(p -> p.getX()).max().orElse(0);
        double minZ = polygon.stream().mapToDouble(p -> p.getY()).min().orElse(0);
        double maxZ = polygon.stream().mapToDouble(p -> p.getY()).max().orElse(0);

        // Количество шагов в чанке (разрешение)
        int step = 4;
        double stepSize = 16.0 / step;

        int chunkMinX = (int) Math.floor(minX / 16);
        int chunkMaxX = (int) Math.floor(maxX / 16);
        int chunkMinZ = (int) Math.floor(minZ / 16);
        int chunkMaxZ = (int) Math.floor(maxZ / 16);

        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                int inside = 0;
                int total = step * step;

                for (int i = 0; i < step; i++) {
                    for (int j = 0; j < step; j++) {
                        double x = cx * 16 + i * stepSize + stepSize / 2;
                        double z = cz * 16 + j * stepSize + stepSize / 2;

                        if (isPointInsidePolygon(new Vector2d(x, z), polygon)) {
                            inside++;
                        }
                    }
                }

                if (inside > 0) {
                    double fraction = (double) inside / total;
                    Chunk chunk = world.getChunkAt(cx, cz);
                    result.put(chunk, fraction);
                }
            }
        }

        return result;
    }
    public double calculateWeeklyCost(ZoneInfo zone, Map<String, ChunkStats> statsMap, ActivityWeights weights, double multiplierPerPoint) {
        double daily = calculateZoneDailyCost(zone, statsMap, weights);
        return daily * multiplierPerPoint * 7; // умножаем на 7 дней
    }


    public double calculateZoneDailyCost(ZoneInfo zone, Map<String, ChunkStats> statsMap, ActivityWeights weights) {
        Bukkit.getLogger().info("=== [ZoneCost Debug] ===");
        Bukkit.getLogger().info("Зона: " + zone.getName() + " (" + zone.getID() + ")");
        Bukkit.getLogger().info("Количество углов: " + zone.getCorners().size());

        Map<Chunk, Double> chunkFractions = getChunkFractions(zone.getCorners());
        Bukkit.getLogger().info("Найдено чанков в зоне: " + chunkFractions.size());

        double totalWeightedValue = 0;
        double totalWeight = 0;

        for (Map.Entry<Chunk, Double> entry : chunkFractions.entrySet()) {
            Chunk chunk = entry.getKey();
            double fraction = entry.getValue();

            String key = chunk.getWorld().getName() + ":" + chunk.getX() + "," + chunk.getZ();
            ChunkStats stats = statsMap.get(key);

            if (stats == null) {
                Bukkit.getLogger().info("Чанк " + key + " → нет данных в statsMap");
                continue;
            }

            double dailyAvg = stats.getDailyAverage(weights); // <-- ВАЖНО: используем новый метод

            Bukkit.getLogger().info("Чанк " + key + " → fraction=" + fraction +
                    ", dailyAvg=" + dailyAvg +
                    ", contrib=" + (dailyAvg * fraction));

            totalWeightedValue += dailyAvg * fraction;
            totalWeight += fraction;
        }

        double result = totalWeight > 0 ? totalWeightedValue / totalWeight : 0;
        double typeMultiplier = zoneLimits.get(zone.getType()).getPriceMultiplier();
        Bukkit.getLogger().info("Сумма значений: " + totalWeightedValue);
        Bukkit.getLogger().info("Сумма весов: " + totalWeight);
        Bukkit.getLogger().info("Результат: " + result);
        Bukkit.getLogger().info("=========================");

        return result * typeMultiplier;
    }

    private void addBlueMapMarker(ZoneType zoneType, String markerID, List<Location> locations, String zoneName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            Location location = locations.get(0);
            blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                String markerSetID = "zones_" + zoneType.name().toLowerCase();
                MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(markerSetID, k -> new MarkerSet(markerSetID));
                List<Vector2d> basePoints = locations.stream()
                        .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                        .collect(Collectors.toList());

                ExtrudeMarker.Builder markerBuilder = ExtrudeMarker.builder()
                        .label(zoneName) // Заголовок маркера
                        .shape(new Shape(basePoints), 42, 255) // Контур зоны
                        .detail("<b>" + zoneLimits.get(zoneType).getDisplayName() + " \"" + zoneName + "\"</b><br><br><i> Владелец:</i> " + zoneList.get(markerID).getOwner() + "<br><i>Площадь:</i> " + calculateSurfaceArea(locations)); // 📌 Добавляем описание
                markerSet.getMarkers().put(markerID, markerBuilder.build());
                blueMapIntegration.saveBlueMapMarkers(markerSetID);
            });
        });
    }

    private void updateBlueMapMarker(ZoneType zoneType, String markerID, List<Location> locations, String zoneName, Player p) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            Location location = locations.get(0);
            blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                String markerSetID = "zones_" + zoneType.name().toLowerCase();
                System.out.println(map.getMarkerSets().get(markerSetID).getMarkers().size());
                System.out.println(map.getMarkerSets().get(markerSetID));
                MarkerSet markerSet = map.getMarkerSets().get(markerSetID);


                if (markerSet != null && markerSet.getMarkers().containsKey(markerID)) {
                    ExtrudeMarker marker = (ExtrudeMarker) markerSet.getMarkers().get(markerID);
                    List<Vector2d> basePoints = locations.stream()
                            .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                            .collect(Collectors.toList());

                    marker.setShape(new Shape(basePoints), 42, 255);
                    marker.setLabel(zoneName);
                    marker.setDetail("<b>" + zoneLimits.get(zoneType).getDisplayName() + " \"" + zoneName + "\"</b><br><br><i> Владелец:</i> " + zoneList.get(markerID).getOwner() + "<br><i>Площадь:</i> " + calculateSurfaceArea(locations)); // 📌 Добавляем описание
                    blueMapIntegration.saveBlueMapMarkers(markerSetID);
                }
            });
        });
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

    public void loadZonesFromConfig() {
        zoneList.clear(); // очистить на случай перезапуска

        for (String typeKey : zonesConfig.getKeys(false)) {
            ZoneType zoneType;
            try {
                zoneType = ZoneType.valueOf(typeKey.toUpperCase());
            } catch (IllegalArgumentException e) {
                continue; // пропустить неизвестные типы
            }

            ConfigurationSection ownersSection = zonesConfig.getConfigurationSection(typeKey);
            if (ownersSection == null) continue;

            for (String owner : ownersSection.getKeys(false)) {
                ConfigurationSection zonesSection = ownersSection.getConfigurationSection(owner);
                if (zonesSection == null) continue;

                for (String zoneId : zonesSection.getKeys(false)) {
                    ConfigurationSection zoneData = zonesSection.getConfigurationSection(zoneId);
                    if (zoneData == null) continue;

                    String name = zoneData.getString("name", "Без названия");
                    String markerID = zoneData.getString("marker_ID", "");

                    List<Location> corners = new ArrayList<>();
                    List<Map<?, ?>> rawCorners = zoneData.getMapList("corners");
                    for (Map<?, ?> corner : rawCorners) {
                        String worldName = (String) corner.get("world");
                        double x = (double) corner.get("x");
                        double y = (double) corner.get("y");
                        double z = (double) corner.get("z");
                        float pitch = ((Number) corner.get("pitch")).floatValue();
                        float yaw = ((Number) corner.get("yaw")).floatValue();
                        Location loc = new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
                        corners.add(loc);
                    }

                    String key = typeKey + "_" + owner + "_" + zoneId;
                    ZoneInfo zoneInfo = new ZoneInfo(zoneType, zoneId, name, markerID, corners, owner);
                    System.out.println(zoneInfo.zoneName + " есть!!!");

                    zoneList.put(key, zoneInfo);
                }
            }
        }
        System.out.println(zoneList.size() + " зон загнружено!!");
    }

    public void saveZonesToConfig() {
        zonesConfig = new YamlConfiguration(); // Очистить перед сохранением

        for (ZoneInfo zone : zoneList.values()) {
            System.out.println(zone.zoneName + " имеется!!");
            String typeKey = zone.zoneType.name().toLowerCase();
            String path = typeKey + "." + zone.zoneOwner + "." + zone.zoneID;

            zonesConfig.set(path + ".name", zone.zoneName);
            zonesConfig.set(path + ".marker_ID", zone.markerID);

            List<Map<String, Object>> serializedCorners = new ArrayList<>();
            for (Location loc : zone.zoneCorners) {
                Map<String, Object> map = new HashMap<>();
                map.put("world", loc.getWorld().getName());
                map.put("x", loc.getX());
                map.put("y", loc.getY());
                map.put("z", loc.getZ());
                map.put("pitch", loc.getPitch());
                map.put("yaw", loc.getYaw());
                serializedCorners.add(map);
            }
            zonesConfig.set(path + ".corners", serializedCorners);
        }
        saveZonesConfig(); // сохраняем в файл
    }

    public void loadZoneData() {
        File zoneFile = new File(unityLauncher.getDataFolder(), "zones.yml");
        if (zoneFile.exists()) {
            YamlConfiguration zoneConfig = YamlConfiguration.loadConfiguration(zoneFile);

            for (String typeKey : zoneConfig.getKeys(false)) { // "hospital", "shop" и т.д.
                ConfigurationSection typeSection = zoneConfig.getConfigurationSection(typeKey);
                if (typeSection == null) continue;

                for (String playerName : typeSection.getKeys(false)) {
                    ConfigurationSection playerSection = typeSection.getConfigurationSection(playerName);
                    if (playerSection == null) continue;

                    for (String zoneID : playerSection.getKeys(false)) {
                        ConfigurationSection zoneSection = playerSection.getConfigurationSection(zoneID);
                        if (zoneSection == null) continue;

                        String zoneName = zoneSection.getString("name");
                        String markerID = zoneSection.getString("marker_ID");

                        List<Location> corners = new ArrayList<>();
                        List<Map<?, ?>> rawCorners = zoneSection.getMapList("corners");
                        for (Map<?, ?> cornerMap : rawCorners) {
                            try {
                                String worldName = (String) cornerMap.get("world");
                                double x = (double) cornerMap.get("x");
                                double y = (double) cornerMap.get("y");
                                double z = (double) cornerMap.get("z");

                                World world = Bukkit.getWorld(worldName);
                                if (world == null) continue;

                                Location cornerLoc = new Location(world, x, y, z);
                                corners.add(cornerLoc);
                            } catch (Exception e) {
                                Bukkit.getLogger().warning("Ошибка при загрузке угла зоны: " + e.getMessage());
                            }
                        }
                        List<Vector2d> corners2D = corners.stream()
                                .map(cornerLoc -> new Vector2d(cornerLoc.getX(), cornerLoc.getZ()))
                                .collect(Collectors.toList());
                        for (Location loc : signManager.genericSignList.keySet()) {
                            Vector2d point = new Vector2d(loc.getX(), loc.getZ());
                            if (isPointInsidePolygon(point, corners2D)) {
                                signManager.genericSignList.get(loc).setOwnerName(playerName);
                            }
                        }

                        // Пример: логгирование загрузки
                        Bukkit.getLogger().info("Загружена зона: " + typeKey + " / " + playerName + " → " + zoneID + " (" + zoneName + ")");
                    }
                }
            }
        }
    }

    public boolean isPointInsidePolygon(Vector2d point, List<Vector2d> polygon) {
        boolean inside = false;
        int j = polygon.size() - 1;
        for (int i = 0; i < polygon.size(); i++) {
            Vector2d vi = polygon.get(i);
            Vector2d vj = polygon.get(j);

            if ((vi.getY() > point.getY()) != (vj.getY() > point.getY()) &&
                    (point.getX() < (vj.getX() - vi.getX()) * (point.getY() - vi.getY()) / (vj.getY() - vi.getY()) + vi.getX())) {
                inside = !inside;
            }
            j = i;
        }
        return inside;
    }
    public Map<Location, List<ItemData>> getItemSummaryFromContainers(List<Block> containers, List<Location> signLocations) {
        Map<Location, List<ItemData>> result = new HashMap<>();

        for (int i = 0; i < containers.size(); i++) {
            Block containerBlock = containers.get(i);
            if (!(containerBlock.getState() instanceof Container container)) continue;

            Location signLocation = signLocations.get(i);
            SignVariables signVars = signManager.genericSignList.get(signLocation);
            if (signVars == null || signVars.getSignText().size() < 4) continue;

            String quantityString = ChatColor.stripColor(signVars.getSignText().get(2).replace("Кол-во: ", ""));
            String priceString = ChatColor.stripColor(signVars.getSignText().get(3).replace("Цена: ", ""));

            int quantity = Integer.parseInt(quantityString);
            double price = Double.parseDouble(priceString);

            // Временная мапа для одного контейнера: тип предмета → агрегированные данные
            Map<Material, ItemData> combinedItems = new HashMap<>();

            for (ItemStack item : container.getInventory().getContents()) {
                if (item == null || item.getType() == Material.AIR) continue;

                Material type = item.getType();

                if (combinedItems.containsKey(type)) {
                    // Увеличиваем общее количество
                    ItemData existing = combinedItems.get(type);
                    existing.overallQuantity += item.getAmount();
                } else {
                    // Новый предмет — создаём и добавляем
                    ItemData newItem = new ItemData(
                            container.getLocation(),
                            type.toString(),
                            quantity,
                            item.getAmount(),
                            price
                    );
                    combinedItems.put(type, newItem);
                }
            }

            // Помещаем агрегированный список в результат
            result.put(signLocation, new ArrayList<>(combinedItems.values()));
        }

        return result;
    }
}