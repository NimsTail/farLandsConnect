package com.frammy.unitylauncher.zones;
import com.frammy.unitylauncher.MoneyManager;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkStats;
import com.frammy.unitylauncher.signs.ItemData;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.signs.SignVariables;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.*;
import de.bluecolored.bluemap.api.markers.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public class ZoneManager {
    public final UnityLauncher ul;
    public SignManager signManager;
    public BlueMapIntegration blueMapIntegration;
    public ActivityTracker activityTracker;
    //private MoneyManager moneyManager;
    private final File zonesFile;
    private YamlConfiguration zonesConfig;
    public final Map<UUID, List<Location>> zonePoints = new HashMap<>();
    public final ZoneId zoneId = ZoneId.systemDefault();
    private static final long PRICE_COOLDOWN_MS = 5 * 60_000L;
    private final Map<UUID, Long> lastPriceUse = new ConcurrentHashMap<>();

    public HashMap<String, ZoneInfo> zoneList = new HashMap<>();


    public ZoneManager(UnityLauncher plugin, SignManager signManager, BlueMapIntegration blueMapIntegration, ActivityTracker activityTracker) {
        this.ul = plugin;
        this.signManager = signManager;
        this.blueMapIntegration = blueMapIntegration;
        this.activityTracker = activityTracker;

        this.zonesFile = new File(plugin.getDataFolder(), "zones.yml"); // <-- создаём файл в папке плагина
        this.zonesConfig = YamlConfiguration.loadConfiguration(zonesFile); // загружаем конфиг

    }
    public void setSignManager(SignManager signManager) {
        this.signManager = signManager;
    }

    public final Map<ZoneType, ZoneTypeData> zoneLimits = new HashMap<>() {{
        put(ZoneType.SHOP, new ZoneTypeData("Торговая точка", 500.0, 2, 3.0, false, 1, 10, "unityLauncher.createZone.shop"));
        put(ZoneType.BANK, new ZoneTypeData("Банк", 300.0,2, 20.0, false, 1, 150, "unityLauncher.createZone.bank"));
        put(ZoneType.HOSPITAL, new ZoneTypeData("Госпиталь", 700.0, 2, 15.0, false, 1, 200, "unityLauncher.createZone.hospital"));
        put(ZoneType.INDUSTRIAL, new ZoneTypeData("Промышленная зона", 1000.0, 2, 30.0, false, 1.15, 50, "unityLauncher.createZone.industrial"));
        put(ZoneType.REGION, new ZoneTypeData("Регион", 10000.0, 1, 300.0, true, 0.85, 0, "unityLauncher.createZone.region"));
        put(ZoneType.COUNTRY, new ZoneTypeData("Государство", 30000.0, 0, 100.0, true, 0.7, 0, "unityLauncher.createZone.country"));
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
            case "price": {
                // кулдаун (разреши обход правом, если нужно)
                if (!player.hasPermission("zones.price.bypass")) {
                    long now = System.currentTimeMillis();
                    long last = lastPriceUse.getOrDefault(player.getUniqueId(), 0L);
                    long left = PRICE_COOLDOWN_MS - (now - last);
                    if (left > 0) {
                        long sec = (left + 999) / 1000;
                        player.sendMessage(ChatColor.GRAY + "Команда будет доступна через "
                                + ChatColor.YELLOW + sec + ChatColor.GRAY + " сек.");
                        break;
                    }
                    lastPriceUse.put(player.getUniqueId(), now);
                }

                ZoneInfo zoneInfo = playerLastZone.get(player.getUniqueId());
                if (zoneInfo == null) {
                    player.sendMessage(ChatColor.GRAY + "Зона не найдена.");
                    break;
                }
                if (!zoneInfo.getOwner().equals(player.getName())) {
                    player.sendMessage(ChatColor.RED + "Ты не владеешь этой зоной.");
                    break;
                }

                // дневная цена — ИСПОЛЬЗУЕМ кэш (твоя реализация calculateZoneDailyCostCached)
                double cost = ul.zoneActivityCalculations.calculateZoneDailyCostCached(
                        zoneInfo,
                        activityTracker.getChunkStatsMap(),
                        activityTracker.getWeights()
                );

                // серия по часам (последние 24ч), если нужно — оставляем как есть
                List<Double> hours = ul.zoneActivityCalculations.getZoneHourlySeries(
                        zoneInfo,
                        activityTracker.getChunkStatsMap(),
                        activityTracker.getWeights(),
                        12
                );

                // hover-текст
                StringBuilder hover = new StringBuilder();
                hover.append(ChatColor.GOLD).append("Активность по часам (последние ")
                        .append(hours.size()).append("):").append("\n");

                for (int i = 0; i < hours.size(); i++) {
                    int hAgo = (hours.size() - 1) - i; // H-23 ... H-0
                    hover.append(ChatColor.YELLOW)
                            .append("H-").append(hAgo < 10 ? "0" + hAgo : hAgo)
                            .append(ChatColor.GRAY).append(": ")
                            .append(ChatColor.WHITE)
                            .append(String.format(Locale.US, "%.3f", hours.get(i)))
                            .append("\n");
                }

                TextComponent msg =
                        new TextComponent(
                                ChatColor.GREEN + "Текущая дневная стоимость: "
                                        + ChatColor.GOLD + String.format(Locale.US, "%.2f", cost) + "Ⓕ"
                        );

                msg.setHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder(hover.toString()).create()
                ));

                player.spigot().sendMessage(msg);
                break;
            }
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
        World.Environment env = player.getWorld().getEnvironment();
        if (zoneType == ZoneType.COUNTRY && env != World.Environment.NORMAL) {
            player.sendMessage(ChatColor.RED + "Государство можно создавать только в верхнем мире (Overworld).");
            return;
        }

        // Запрет смешивать миры в одной зоне
        if (points != null && !points.isEmpty() && !points.get(0).getWorld().equals(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "Нельзя добавлять точки зоны из разных миров.");
            return;
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
            player.sendMessage(ChatColor.RED + "Точки пересекаются - фигура имеет неправильную форму.");
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
        player.sendMessage(ChatColor.GRAY + "Удалена последняя точка. Текущее количество точек: " + (points.size()));
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

        World world0 = points.get(0).getWorld();
        boolean sameWorld = points.stream().allMatch(l -> l.getWorld().equals(world0));
        if (!sameWorld) {
            player.sendMessage(ChatColor.RED + "Все точки зоны должны быть в одном мире.");
            return;
        }

        if (zoneType == ZoneType.COUNTRY && world0.getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage(ChatColor.RED + "Государство можно создавать только в верхнем мире (Overworld).");
            return;
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

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
       // saveZonesConfig();
        zoneList.put(markerID, new ZoneInfo(zoneType, zoneID, zoneName, markerID, points, playerName, defaultColor));
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
                LocalDate today = LocalDate.now(zoneId);

                // 3.1 Рассчитать долг зоны
                double due = zoneInfo.getDueSinceLastBill(today);
                int days = zoneInfo.getDueDaysCount(today);
                if (due > 0) {
                    player.sendMessage(ChatColor.GRAY + "Перед изменением границ необходимо оплатить " +
                            ChatColor.YELLOW + days + ChatColor.GRAY + " дн. задолженности: " +
                            ChatColor.GOLD + String.format(Locale.US,"%.2f", due));

                    // Можно сделать подтверждение: требовать вторую команду /ul zone update corners pay
                    // Или списывать сразу:
                    try {
                        //moneyManager.withdraw(zoneInfo.getOwner(), due);
                        zoneInfo.markBilled(today); // фиксируем закрытие недели/периода на сегодня
                        player.sendMessage(ChatColor.GREEN + "Оплачено " + String.format(Locale.US,"%.2f", due) + ". Можно изменять границы.");
                    } catch (Exception ex) {
                        player.sendMessage(ChatColor.RED + "Недостаточно средств: " + ex.getMessage());
                        return; // прерываем апдейт, пока не оплатит
                    }
                }
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

                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, zoneInfo.zoneName, null);
                break;

            case "name":
               // zonesConfig.set(zoneInfo.zoneType + "." + playerName + "." + zoneInfo.zoneID +
                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, newValue, null);
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
            case "color":
                List<String> rgb = List.of(newValue.split(","));
                if (rgb.size() != 3) return;
                Integer r,g,b;
                org.bukkit.Color newFillColor = null;
                try {
                    r = Integer.parseInt(rgb.get(0));
                    g = Integer.parseInt(rgb.get(1));
                    b = Integer.parseInt(rgb.get(2));
                    newFillColor = org.bukkit.Color.fromRGB(
                            r,
                            g,
                            b
                    );
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Код цвета может содержать только целые числа.");
                    return;
                }

                updateBlueMapMarker(zoneInfo.zoneType, zoneInfo.markerID, zoneInfo.zoneCorners, zoneInfo.zoneName, newFillColor);
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
            if (zone.zoneType == currentZoneType && zone.zoneID.equals(currentZoneID)) continue;

            // Пропускаем зону, если она принадлежит текущему игроку и уже была проверена ранее
            if (zone.zoneOwner.equals(ownerName) && zone.zoneType == currentZoneType) continue;

            if (zone.zoneCorners == null || zone.zoneCorners.size() < 3) continue;

            if (!zone.zoneCorners.get(0).getWorld().equals(loc.getWorld())) continue;

            // проверка попадания в зону
            if (isPlayerInZone(loc, zone.zoneCorners)) return true;
        }
        return false;
    }

    private boolean isPlayerInZone(Location loc, List<Location> zoneCorners) {
        if (zoneCorners == null || zoneCorners.size() < 3) return false;
        if (!zoneCorners.get(0).getWorld().equals(loc.getWorld())) return false;

        double minY = -64;
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
        World playerWorld = player.getWorld();

        for (ZoneInfo zone : zoneList.values()) {
            if (zone.getCorners() == null || zone.getCorners().size() < 3) continue;
            if (!zone.getCorners().get(0).getWorld().equals(playerWorld)) continue;

            if (isPlayerInZone(playerLoc, zone.getCorners())) {
                ZoneTypeData ztd = zoneLimits.get(zone.getType());
                if (ztd == null) continue;
                zonesByIndex.put(ztd.getIndex(), zone);
            }
        }

        ZoneInfo prevZone = playerLastZone.get(playerId);              // что было
        ZoneInfo newZone  = zonesByIndex.isEmpty() ? null
                : zonesByIndex.values().iterator().next();    // что стало

        // Если ничего не поменялось — выходим без сообщений
        if (Objects.equals(prevZone, newZone)) {
            return;
        }

        // Переходы
        if (prevZone == null && newZone != null) {
            // Вход в зону
            ZoneTypeData ztd = zoneLimits.get(newZone.zoneType);
            if (ztd != null) {
                player.spigot().sendMessage(
                        ChatMessageType.ACTION_BAR,
                        new TextComponent(ChatColor.GREEN + "Зона: " + ChatColor.GOLD +
                                ztd.getDisplayName() + " \"" + newZone.zoneName + "\"")
                );
            }
        } else if (prevZone != null && newZone == null) {
            // Выход из зоны
            player.spigot().sendMessage(
                    ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.RED + "Вы покинули зону")
            );
        } else {
            // Смена зоны A -> B
            ZoneTypeData ztd = zoneLimits.get(newZone.zoneType);
            if (ztd != null) {
                player.spigot().sendMessage(
                        ChatMessageType.ACTION_BAR,
                        new TextComponent(ChatColor.YELLOW + "\"" + prevZone.zoneName + "\"" +
                                ChatColor.GRAY + " → " + ChatColor.GOLD + ztd.getDisplayName() +
                                " \"" + newZone.zoneName + "\"")
                );
            }
        }

        playerLastZone.put(playerId, newZone);
        playerZoneStatus.put(playerId, newZone != null);
    }

    // Утилита для читаемого отображения локации
    private String locToStr(Location loc) {
        return String.format("(%s: %.1f, %.1f, %.1f)", loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
    }

    private void addBlueMapMarker(ZoneType zoneType, String markerID, List<Location> locations, String zoneName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            Location location = locations.get(0);
            blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                String markerSetID = "zones_" + zoneType.name().toLowerCase();
                MarkerSet markerSet = map.getMarkerSets().computeIfAbsent(markerSetID, k -> new MarkerSet(markerSetID));
                markerSet.setLabel(zoneLimits.get(zoneType).getDisplayName());
                List<Vector2d> basePoints = locations.stream()
                        .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                        .collect(Collectors.toList());

                ExtrudeMarker.Builder markerBuilder = ExtrudeMarker.builder()
                        .label(zoneName) // Заголовок маркера
                        .shape(new Shape(basePoints), -64, 255) // Контур зоны
                        .detail("<b>" + zoneLimits.get(zoneType).getDisplayName() + " \"" + zoneName + "\"</b><br><br><i> Владелец:</i> " + zoneList.get(markerID).getOwner() + "<br><i>Площадь:</i> " + calculateSurfaceArea(locations)); // 📌 Добавляем описание
                markerSet.getMarkers().put(markerID, markerBuilder.build());
                blueMapIntegration.saveBlueMapMarkers(markerSetID);
            });
        });
    }

    private void updateBlueMapMarker(ZoneType zoneType, String markerID, List<Location> locations, String zoneName, org.bukkit.Color bukkitColor) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;

        BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
            if (locations == null || locations.isEmpty()) return;
            Location location = locations.get(0);

            blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                String markerSetID = "zones_" + zoneType.name().toLowerCase();
                MarkerSet markerSet = map.getMarkerSets().get(markerSetID);
                if (markerSet == null) return;

                Marker existing = markerSet.getMarkers().get(markerID);
                if (!(existing instanceof ExtrudeMarker)) return;

                ExtrudeMarker marker = (ExtrudeMarker) existing;

                List<Vector2d> basePoints = locations.stream()
                        .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                        .collect(Collectors.toList());

                // цвет: если прилетел null — возьмем  с альфой
                org.bukkit.Color safe = (bukkitColor != null) ? bukkitColor : org.bukkit.Color.fromRGB(255, 0, 0);
                // подбери альфу как хочешь (0..1)
                Color bmColor = toBlueMapColor(safe, 0.35f);
                Color bmLineColor = toBlueMapColor(safe, 1f);
                marker.setFillColor(bmColor);
                marker.setLineColor(bmLineColor);

                marker.setShape(new Shape(basePoints), 42, 255);
                marker.setLabel(zoneName);

                // detail-текст — оставил твой, только подстраховка от NPE
                ZoneInfo zi = zoneList.get(markerID);
                String ownerName = (zi != null && zi.getOwner() != null) ? zi.getOwner() : "—";
                marker.setDetail("<b>" + zoneLimits.get(zoneType).getDisplayName() + " \"" + zoneName + "\"</b>"
                        + "<br><br><i>Владелец:</i> " + ownerName
                        + "<br><i>Площадь:</i> " + calculateSurfaceArea(locations));

                blueMapIntegration.saveBlueMapMarkers(markerSetID);
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
    private static org.bukkit.Color hexToBukkit(String hex) {
        if (hex == null || hex.isEmpty()) return org.bukkit.Color.fromRGB(255, 255, 255);
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        int rgb = (int) Long.parseLong(s, 16);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return org.bukkit.Color.fromRGB(r, g, b);
    }

    // Bukkit Color -> HEX "#RRGGBB"
    private static String bukkitToHex(org.bukkit.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // Bukkit Color -> BlueMap Color (0..1) с альфой
    private static Color toBlueMapColor(org.bukkit.Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

     private void saveZonesConfig() {
        try {
            zonesConfig.save(zonesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void loadZonesFromConfig() {
        zoneList.clear();

        for (String typeKey : zonesConfig.getKeys(false)) {
            ZoneType zoneType;
            try { zoneType = ZoneType.valueOf(typeKey.toUpperCase()); }
            catch (IllegalArgumentException e) { continue; }

            ConfigurationSection ownersSection = zonesConfig.getConfigurationSection(typeKey);
            if (ownersSection == null) continue;

            for (String owner : ownersSection.getKeys(false)) {
                ConfigurationSection zonesSection = ownersSection.getConfigurationSection(owner);
                if (zonesSection == null) continue;

                for (String zoneId : zonesSection.getKeys(false)) {
                    ConfigurationSection zoneData = zonesSection.getConfigurationSection(zoneId);
                    if (zoneData == null) continue;

                    String name = zoneData.getString("name", "Без названия");

                    // ---- ВАЖНО: цвет как HEX-строка
                    String colorHex = zoneData.getString("color", "#FFFFFF");
                    org.bukkit.Color bukkitColor = hexToBukkit(colorHex);

                    String markerID = zoneData.getString("marker_ID", "");
                    String worldNameSaved = zoneData.getString("world", null);

                    List<Location> corners = new ArrayList<>();
                    List<Map<?, ?>> rawCorners = zoneData.getMapList("corners");
                    for (Map<?, ?> corner : rawCorners) {
                        String worldName = (String) corner.get("world");
                        if (worldNameSaved != null) worldName = worldNameSaved;

                        World w = Bukkit.getWorld(worldName);
                        if (w == null) continue;

                        double x = ((Number) corner.get("x")).doubleValue();
                        double y = ((Number) corner.get("y")).doubleValue();
                        double z = ((Number) corner.get("z")).doubleValue();
                        float pitch = ((corner.get("pitch") instanceof Number) ? ((Number) corner.get("pitch")).floatValue() : 0f);
                        float yaw   = ((corner.get("yaw")   instanceof Number) ? ((Number) corner.get("yaw")).floatValue()   : 0f);

                        corners.add(new Location(w, x, y, z, yaw, pitch));
                    }

                    String key = typeKey + "_" + owner + "_" + zoneId;

                    // ==== тут меняем конструктор ZoneInfo, см. ниже
                    ZoneInfo zoneInfo = new ZoneInfo(
                            zoneType,
                            zoneId,
                            name,
                            markerID,
                            corners,
                            owner,
                            bukkitColor // <--- храним именно Bukkit Color
                    );

                    zoneList.put(key, zoneInfo);
                }
            }
        }
    }

    public void saveZonesToConfig() {
        zonesConfig = new YamlConfiguration();

        for (ZoneInfo zone : zoneList.values()) {
            String typeKey = zone.getType().name().toLowerCase();
            String path = typeKey + "." + zone.getOwner() + "." + zone.getID();

            zonesConfig.set(path + ".name", zone.getName());

            // ---- ВАЖНО: сохраняем как строку "#RRGGBB"
            org.bukkit.Color c = zone.getFillColor();
            zonesConfig.set(path + ".color", (c != null ? bukkitToHex(c) : "#FFFFFF"));

            zonesConfig.set(path + ".marker_ID", zone.getMarkerID());
            zonesConfig.set(path + ".world", zone.getCorners().isEmpty() ? "world" : zone.getCorners().get(0).getWorld().getName());

            List<Map<String, Object>> serializedCorners = new ArrayList<>();
            for (Location loc : zone.getCorners()) {
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

        saveZonesConfig(); // как у тебя
    }


    public void loadZoneData() {
        File zoneFile = new File(ul.getDataFolder(), "zones.yml");
        if (zoneFile.exists()) {
            YamlConfiguration zoneConfig = YamlConfiguration.loadConfiguration(zoneFile);

            for (String typeKey : zoneConfig.getKeys(false)) {
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
                        String worldNameSaved = zoneSection.getString("world", null);

                        List<Location> corners = new ArrayList<>();
                        List<Map<?, ?>> rawCorners = zoneSection.getMapList("corners");
                        for (Map<?, ?> cornerMap : rawCorners) {
                            try {
                                String worldName = (String) cornerMap.get("world");
                                if (worldNameSaved != null) worldName = worldNameSaved; // ✅ форсим мир зоны
                                World world = Bukkit.getWorld(worldName);
                                if (world == null) continue;

                                double x = ((Number) cornerMap.get("x")).doubleValue();
                                double y = ((Number) cornerMap.get("y")).doubleValue();
                                double z = ((Number) cornerMap.get("z")).doubleValue();

                                Location cornerLoc = new Location(world, x, y, z);
                                corners.add(cornerLoc);
                            } catch (Exception e) {
                                Bukkit.getLogger().warning("Ошибка при загрузке угла зоны: " + e.getMessage());
                            }
                        }
                        String zoneWorld = (corners.isEmpty() || corners.get(0).getWorld() == null)
                                ? null : corners.get(0).getWorld().getName();

                        List<Vector2d> corners2D = corners.stream()
                                .map(cornerLoc -> new Vector2d(cornerLoc.getX(), cornerLoc.getZ()))
                                .collect(Collectors.toList());

                        for (Location loc : signManager.genericSignList.keySet()) {
                            if (zoneWorld != null && !loc.getWorld().getName().equals(zoneWorld)) continue; // ✅ игнор других миров
                            Vector2d point = new Vector2d(loc.getX(), loc.getZ());
                            if (isPointInsidePolygon(point, corners2D)) {
                                signManager.genericSignList.get(loc).setOwnerName(playerName);
                            }
                        }

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