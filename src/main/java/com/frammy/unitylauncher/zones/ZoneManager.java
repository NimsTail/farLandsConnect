package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.signs.ItemData;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.signs.SignVariables;
import com.flowpowered.math.vector.Vector2d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

/**
 * ZoneManager: компактный и читаемый.
 * Главное отличие: для страны игрока используется только кэш CountryRegistryJdbc,
 * фоново обновляемый из UnityLauncher. Никаких прямых SQL-запросов здесь нет.
 */
public class ZoneManager {

    // ==== DI / State ====
    public final UnityLauncher ul;
    public SignManager signManager;
    public BlueMapIntegration blueMapIntegration;
    public ActivityTracker activityTracker;

    private final File zonesFile;
    private YamlConfiguration zonesConfig;

    /** временные точки создаваемого полигона по игроку */
    public final Map<UUID, List<Location>> zonePoints = new HashMap<>();
    /** все зоны по markerID */
    public final Map<String, ZoneInfo> zoneList = new HashMap<>();
    /** последняя зона игрока (для actionbar/удаления/price) */
    private final Map<UUID, ZoneInfo> playerLastZone = new HashMap<>();

    public final ZoneId zoneId = ZoneId.systemDefault();

    private static final long PRICE_COOLDOWN_MS = 5 * 60_000L;
    private final Map<UUID, Long> lastPriceUse = new ConcurrentHashMap<>();

    private static final double Y_MIN = -64, Y_MAX = 255;

    /** настройки типов (взято из твоей версии) */
    public final Map<ZoneType, ZoneTypeData> zoneLimits = new HashMap<>() {{
        put(ZoneType.SHOP,       new ZoneTypeData("Торговая точка",   500.0, 2,  3.0,  true, 1,    10,  "unityLauncher.createZone.shop"));
        put(ZoneType.BANK,       new ZoneTypeData("Банк",             300.0, 2, 20.0,  true, 1,   150,  "unityLauncher.createZone.bank"));
        put(ZoneType.HOSPITAL,   new ZoneTypeData("Госпиталь",        700.0, 2, 15.0,  true, 1,   200,  "unityLauncher.createZone.hospital"));
        put(ZoneType.INDUSTRIAL, new ZoneTypeData("Промышленная зона",1000.0, 2, 30.0,  true, 1.15, 50,  "unityLauncher.createZone.industrial"));
        put(ZoneType.REGION,     new ZoneTypeData("Регион",         10000.0, 1,300.0,  true, 0.85,  0,  "unityLauncher.createZone.region"));
        put(ZoneType.COUNTRY,    new ZoneTypeData("Государство",    30000.0, 0,100.0,  true, 0.7,   0,  "unityLauncher.createZone.country"));
    }};

    // ==== Lifecycle / IO ====
    public ZoneManager(UnityLauncher plugin, SignManager signManager, BlueMapIntegration blueMapIntegration, ActivityTracker activityTracker) {
        this.ul = plugin;
        this.signManager = signManager;
        this.blueMapIntegration = blueMapIntegration;
        this.activityTracker = activityTracker;

        this.zonesFile = new File(plugin.getDataFolder(), "zones.yml");
        this.zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
    }

    public void setSignManager(SignManager signManager) { this.signManager = signManager; }
    public Collection<ZoneInfo> getZones() { return zoneList.values(); }

    private void saveZonesConfig() {
        try { zonesConfig.save(zonesFile); } catch (IOException e) { e.printStackTrace(); }
    }

    /** Единый загрузчик из YAML + проставление владельцев табличек. */
    public void loadZonesFromConfig() {
        zoneList.clear();

        for (String typeKey : zonesConfig.getKeys(false)) {
            ZoneType zoneType;
            try { zoneType = ZoneType.valueOf(typeKey.toUpperCase()); } catch (IllegalArgumentException ex) { continue; }
            ConfigurationSection owners = zonesConfig.getConfigurationSection(typeKey);
            if (owners == null) continue;

            for (String owner : owners.getKeys(false)) {
                ConfigurationSection zones = owners.getConfigurationSection(owner);
                if (zones == null) continue;

                for (String zoneId : zones.getKeys(false)) {
                    ConfigurationSection z = zones.getConfigurationSection(zoneId);
                    if (z == null) continue;

                    String name = z.getString("name", "Без названия");
                    String colorHex = z.getString("color", "#FFFFFF");
                    org.bukkit.Color color = hexToBukkit(colorHex);
                    String markerID = z.getString("marker_ID", "marker_" + UUID.randomUUID());
                    String worldOverride = z.getString("world", null);

                    List<Location> corners = new ArrayList<>();
                    for (Map<?, ?> m : z.getMapList("corners")) {
                        try {
                            String wName = worldOverride != null ? worldOverride : (String) m.get("world");
                            World w = Bukkit.getWorld(wName);
                            if (w == null) continue;
                            double x  = num(m, "x");
                            double y  = num(m, "y");
                            double zz = num(m, "z");
                            float pitch = fnum(m, "pitch");
                            float yaw   = fnum(m, "yaw");
                            corners.add(new Location(w, x, y, zz, yaw, pitch));
                        } catch (Throwable ignore) {}
                    }

                    ZoneInfo zi = new ZoneInfo(zoneType, zoneId, name, markerID, corners, owner, color);
                    zoneList.put(markerID, zi);

                    // Проставляем владельцев табличек (по миру зоны)
                    String zoneWorld = corners.isEmpty() ? null : corners.getFirst().getWorld().getName();
                    if (zoneWorld != null && signManager != null && signManager.genericSignList != null) {
                        List<Vector2d> poly2D = poly2D(corners);
                        for (Location signLoc : signManager.genericSignList.keySet()) {
                            if (!zoneWorld.equals(signLoc.getWorld().getName())) continue;
                            if (pointInPolygon(new Vector2d(signLoc.getX(), signLoc.getZ()), poly2D)) {
                                signManager.genericSignList.get(signLoc).setOwnerName(owner);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Заглушка под старый вызов из UnityLauncher; теперь вся загрузка в loadZonesFromConfig(). */
    public void loadZoneData() {
        // Ничего не делаем умышленно (чтобы не дублировать логику).
        // Метод оставлен для совместимости с существующим вызовом.
        Bukkit.getLogger().info("[ZoneManager] loadZoneData(): noop (используется loadZonesFromConfig())");
    }

    public void saveZonesToConfig() {
        zonesConfig = new YamlConfiguration();
        for (ZoneInfo z : zoneList.values()) {
            String path = z.getType().name().toLowerCase() + "." + z.getOwner() + "." + z.getID();
            zonesConfig.set(path + ".name", z.getName());
            zonesConfig.set(path + ".color", bukkitToHex(z.getFillColor() != null ? z.getFillColor() : org.bukkit.Color.WHITE));
            zonesConfig.set(path + ".marker_ID", z.getMarkerID());
            zonesConfig.set(path + ".world", z.getCorners().isEmpty() ? "world" : z.getCorners().getFirst().getWorld().getName());

            List<Map<String, Object>> corners = new ArrayList<>();
            for (Location l : z.getCorners()) {
                Map<String, Object> m = new HashMap<>();
                m.put("world", l.getWorld().getName());
                m.put("x", l.getX()); m.put("y", l.getY()); m.put("z", l.getZ());
                m.put("pitch", l.getPitch()); m.put("yaw", l.getYaw());
                corners.add(m);
            }
            zonesConfig.set(path + ".corners", corners);
        }
        saveZonesConfig();
    }

    // ==== Commands ====
    public void handleCommand(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Использование: /ul zone <addcorner|removecorner|build|update|price|remove|confirmremove|cancelremove>");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "addcorner" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone addcorner <zoneType>"); return; }
                addCorner(p, ZoneType.valueOf(args[1].toUpperCase()));
            }
            case "removecorner" -> removeCorner(p);
            case "build" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone build <zoneType> [zoneName]"); return; }
                ZoneType t = ZoneType.valueOf(args[1].toUpperCase());
                if (t == ZoneType.COUNTRY) {
                    buildZoneCountry(p); // имя страны берём из КЭША
                } else {
                    if (args.length < 3) { p.sendMessage(ChatColor.RED + "Использование: /ul zone build <zoneType> <zoneName>"); return; }
                    buildZone(p, t, args[2]);
                }
            }
            case "update" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone update <corners|name|color> <значение>"); return; }
                updateZone(p, args[1].toLowerCase(), args.length > 2 ? args[2] : "");
            }
            case "price" -> showPrice(p);
            case "remove" -> removeZone(p);
            case "confirmremove" -> confirmRemoveZone(p);
            case "cancelremove" -> cancelRemoveZone(p);
            default -> p.sendMessage(ChatColor.RED + "Неизвестная команда!");
        }
    }

    // ==== Build / Update ====
    private void addCorner(Player p, ZoneType type) {
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) { p.sendMessage(ChatColor.RED + "Неверный тип зоны!"); return; }

        UUID id = p.getUniqueId();
        List<Location> pts = zonePoints.computeIfAbsent(id, k -> new ArrayList<>());

        // Мир должен совпадать
        if (!pts.isEmpty() && !pts.getFirst().getWorld().equals(p.getWorld())) {
            p.sendMessage(ChatColor.RED + "Нельзя добавлять точки из разных миров.");
            return;
        }

        // COUNTRY — только в Overworld
        if (type == ZoneType.COUNTRY && p.getWorld().getEnvironment() != World.Environment.NORMAL) {
            p.sendMessage(ChatColor.RED + "Государство можно создавать только в Overworld.");
            return;
        }

        // Пересечения: COUNTRY не может пересекаться ни с чем
        ZoneInfo overlap = findOverlapAt(p.getLocation(), p.getName(), type, null);
        if (overlap != null) {
            if (type == ZoneType.COUNTRY) {
                p.sendMessage(ChatColor.RED + "Нельзя создать Государство: пересечение с " + overlap.getType() + " (ID " + overlap.getID() + ").");
                return;
            }
            ZoneTypeData other = zoneLimits.get(overlap.getType());
            if (!(ztd.allowOverlap() || (other != null && other.allowOverlap()))) {
                p.sendMessage(ChatColor.RED + "Пересечение с " + overlap.getType() + " (ID " + overlap.getID() + ") запрещено.");
                return;
            }
        }

        // Временный полигон: площадь и самопересечения
        List<Location> tmp = new ArrayList<>(pts); tmp.add(p.getLocation().clone());
        if (!areaOk(tmp, ztd)) { p.sendMessage(ChatColor.GRAY + "Площадь вне лимитов."); return; }
        if (hasSelfIntersections(poly2D(tmp))) { p.sendMessage(ChatColor.RED + "Фигура самопересекается."); return; }

        pts.add(p.getLocation().clone());
        p.sendMessage(ChatColor.GOLD + "[" + pts.size() + "] " + ChatColor.YELLOW + "Точка добавлена. Площадь: " + ChatColor.GOLD + String.format(Locale.US,"%.2f", calculateSurfaceArea(tmp)));
    }

    private void removeCorner(Player p) {
        List<Location> pts = zonePoints.get(p.getUniqueId());
        if (pts == null || pts.isEmpty()) { p.sendMessage(ChatColor.RED + "Нет точек для удаления!"); return; }
        pts.removeLast();
        p.sendMessage(ChatColor.GRAY + "Удалена последняя точка. Осталось: " + pts.size());
    }

    /** Создание территории страны: имя — из КЭША CountryRegistryJdbc; если пусто — запрет. */
    private void buildZoneCountry(Player p) {
        // берём страну из кэша; если кэша нет — попросим фоновую подкачку и запретим
        String country = ul.countryRegistryJdbc.getCountryCached(p.getName());
        if (country == null || country.isBlank()) {
            ul.countryRegistryJdbc.ensureScheduledRefresh(p.getName());
            p.sendMessage(ChatColor.RED + "Нельзя создать территорию страны: у вас нет страны в базе.");
            return;
        }

        List<Location> pts = zonePoints.get(p.getUniqueId());
        if (pts == null || pts.size() < 3) { p.sendMessage(ChatColor.RED + "Нужно минимум 3 точки!"); return; }

        World w0 = pts.getFirst().getWorld();
        if (!pts.stream().allMatch(l -> l.getWorld().equals(w0))) { p.sendMessage(ChatColor.RED + "Все точки должны быть в одном мире."); return; }
        if (w0.getEnvironment() != World.Environment.NORMAL) { p.sendMessage(ChatColor.RED + "Государство только в Overworld."); return; }

        for (Location loc : pts) {
            if (isInOtherZone(loc, p.getName(), ZoneType.COUNTRY, null)) {
                p.sendMessage(ChatColor.RED + "Нельзя создать страну: точка " + loc.toVector() + " пересекается с другой зоной.");
                return;
            }
        }

        // persist
        String rnd = UUID.randomUUID().toString();
        String zoneID = "zone_" + rnd, markerID = "marker_" + rnd;

        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".name", country);
        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".marker_ID", markerID);
        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".corners", pts.stream().map(Location::serialize).toList());

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
        ZoneInfo created = new ZoneInfo(ZoneType.COUNTRY, zoneID, country, markerID, pts, p.getName(), defaultColor);
        created.setOwnerCountry(country); // фиксируем страну-владельца

        zoneList.put(markerID, created);
        upsertBlueMapMarker(created, defaultColor);
        p.sendMessage(ChatColor.GREEN + "Территория страны \"" + country + "\" создана!");
        zonePoints.remove(p.getUniqueId());
    }

    /** Создание обычных зон: принадлежат игроку, страна НЕ проставляется при создании. */
    private void buildZone(Player p, ZoneType type, String zoneName) {
        List<Location> pts = zonePoints.get(p.getUniqueId());
        if (pts == null || pts.size() < 3) { p.sendMessage(ChatColor.RED + "Нужно минимум 3 точки!"); return; }

        World w0 = pts.getFirst().getWorld();
        if (!pts.stream().allMatch(l -> l.getWorld().equals(w0))) { p.sendMessage(ChatColor.RED + "Все точки должны быть в одном мире."); return; }
        if (type == ZoneType.COUNTRY && w0.getEnvironment() != World.Environment.NORMAL) { p.sendMessage(ChatColor.RED + "Государство только в Overworld."); return; }

        // запрет пересечений точек с чужими зонами
        for (Location loc : pts) {
            if (isInOtherZone(loc, p.getName(), type, null)) {
                p.sendMessage(ChatColor.RED + "Нельзя создать зону: точка " + loc.toVector() + " пересекается с другой зоной.");
                return;
            }
        }

        // REGION/INDUSTRIAL не должны попадать внутрь государств
        if (type == ZoneType.REGION || type == ZoneType.INDUSTRIAL) {
            for (ZoneInfo z : zoneList.values()) {
                if (z.getType() != ZoneType.COUNTRY) continue;
                if (!w0.equals(z.getCorners().isEmpty() ? null : z.getCorners().getFirst().getWorld())) continue;
                List<Location> country = z.getCorners();
                if (country.size() < 3) continue;
                List<Vector2d> country2D = poly2D(country);
                for (Location corner : pts) {
                    if (pointInPolygon(new Vector2d(corner.getX(), corner.getZ()), country2D)) {
                        p.sendMessage(ChatColor.RED + "Нельзя создать " + type + ": вершина внутри Государства \"" + z.getName() + "\" (ID " + z.getID() + ").");
                        return;
                    }
                }
            }
        }

        // persist
        String rnd = UUID.randomUUID().toString();
        String zoneID = "zone_" + rnd, markerID = "marker_" + rnd;

        zonesConfig.set(type + "." + p.getName() + "." + zoneID + ".name", zoneName);
        zonesConfig.set(type + "." + p.getName() + "." + zoneID + ".marker_ID", markerID);
        zonesConfig.set(type + "." + p.getName() + "." + zoneID + ".corners", pts.stream().map(Location::serialize).collect(Collectors.toList()));

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
        ZoneInfo created = new ZoneInfo(type, zoneID, zoneName, markerID, pts, p.getName(), defaultColor);
        // ВНИМАНИЕ: ownerCountry НЕ проставляем — апгрейды берутся динамически по стране создателя из кэша
        zoneList.put(markerID, created);

        upsertBlueMapMarker(created, defaultColor);
        p.sendMessage(ChatColor.GREEN + "Зона \"" + zoneName + "\" создана!");
        zonePoints.remove(p.getUniqueId());
    }

    public void updateZone(Player p, String updateType, String value) {
        ZoneInfo zi = resolvePlayerOwnZoneHere(p);
        if (zi == null) { p.sendMessage(ChatColor.RED + "Вы не в своей зоне!"); return; }
        playerLastZone.put(p.getUniqueId(), zi);

        switch (updateType) {
            case "corners" -> {
                LocalDate today = LocalDate.now(zoneId);
                double due = zi.getDueSinceLastBill(today);
                int days = zi.getDueDaysCount(today);
                if (due > 0) {
                    p.sendMessage(ChatColor.GRAY + "Перед изменением оплатите " + ChatColor.YELLOW + days + ChatColor.GRAY + " дн.: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", due));
                    try { zi.markBilled(today); p.sendMessage(ChatColor.GREEN + "Оплачено. Можно менять границы."); }
                    catch (Exception ex) { p.sendMessage(ChatColor.RED + "Недостаточно средств: " + ex.getMessage()); return; }
                }

                if ("+".equals(value)) {
                    if (isInOtherZone(p.getLocation(), p.getName(), zi.getType(), zi.getID())) { p.sendMessage(ChatColor.RED + "Точка пересекается с другой зоной!"); return; }
                    List<Location> tmp = new ArrayList<>(zi.getCorners()); tmp.add(p.getLocation().clone());
                    if (hasSelfIntersections(poly2D(tmp))) { p.sendMessage(ChatColor.GRAY + "Фигура самопересекается."); return; }
                    if (!areaOk(tmp, zoneLimits.get(zi.getType()))) { p.sendMessage(ChatColor.GRAY + "Площадь превышает лимит."); return; }
                    zi.getCorners().add(p.getLocation().clone());
                    p.sendMessage(ChatColor.GOLD + "[" + zi.getCorners().size() + "] " + ChatColor.YELLOW + "Точка добавлена. Площадь: " + ChatColor.GOLD + String.format(Locale.US,"%.2f", calculateSurfaceArea(tmp)));
                } else if ("-".equals(value)) {
                    if (zi.getCorners().size() <= 3) { p.sendMessage(ChatColor.RED + "Минимум 3 точки!"); return; }
                    zi.getCorners().removeLast();
                    p.sendMessage(ChatColor.GRAY + "Удалена последняя точка.");
                } else {
                    p.sendMessage(ChatColor.GRAY + "Используйте: /ul zone update corners +  или  -"); return;
                }
                upsertBlueMapMarker(zi, zi.getFillColor());
            }
            case "name" -> {
                zi.setName(value);
                upsertBlueMapMarker(zi, zi.getFillColor());
                p.sendMessage(ChatColor.GREEN + "Название обновлено!");

                // обновление табличек внутри зоны
                if (signManager != null && signManager.genericSignList != null && !zi.getCorners().isEmpty()) {
                    List<Vector2d> poly2D = poly2D(zi.getCorners());
                    World w = zi.getCorners().getFirst().getWorld();
                    for (Location loc : signManager.genericSignList.keySet()) {
                        if (!Objects.equals(loc.getWorld(), w)) continue;
                        if (pointInPolygon(new Vector2d(loc.getX(), loc.getZ()), poly2D)) {
                            String newLine0 = "Торговая точка [ " + value + " ]";
                            List<String> initial = signManager.genericSignList.get(loc).getSignText();
                            signManager.genericSignList.get(loc).setSignText(Arrays.asList(newLine0, initial.get(1), initial.get(2), initial.get(3)));

                            Sign sign = (Sign) loc.getBlock().getState();
                            signManager.stopScrollingTask(loc);
                            HashMap<Integer, String> scroll = new HashMap<>();
                            scroll.put(0, newLine0);
                            signManager.makeSignScrollingLines(loc, scroll, 6, 13);
                            sign.setLine(0, newLine0);
                            sign.update();
                        }
                    }
                }
            }
            case "color" -> {
                String[] rgb = value.split(",");
                if (rgb.length != 3) { p.sendMessage(ChatColor.RED + "Формат: R,G,B"); return; }
                try {
                    org.bukkit.Color c = org.bukkit.Color.fromRGB(
                            Integer.parseInt(rgb[0]), Integer.parseInt(rgb[1]), Integer.parseInt(rgb[2]));
                    zi.setFillColor(c);
                    upsertBlueMapMarker(zi, c);
                } catch (NumberFormatException nfe) { p.sendMessage(ChatColor.RED + "Только целые числа."); }
            }
            default -> { /* ignore */ }
        }
    }

    // ==== Remove ====
    private void removeZone(Player p) {
        ZoneInfo zi = resolvePlayerOwnZoneHere(p);
        if (zi == null) { p.sendMessage(ChatColor.RED + "Вы не находитесь в своей зоне!"); return; }

        TextComponent confirm = new TextComponent(ChatColor.GREEN + "[Подтвердить удаление]");
        confirm.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul zone confirmremove " + zi.getID()));
        TextComponent cancel = new TextComponent(ChatColor.RED + "[Отмена]");
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul zone cancelremove"));

        p.spigot().sendMessage(new TextComponent(ChatColor.YELLOW + "Удалить зону \"" + ChatColor.GOLD + zi.getName() + ChatColor.YELLOW + "\"? "), confirm, new TextComponent(" "), cancel);
        playerLastZone.put(p.getUniqueId(), zi);
    }

    public void confirmRemoveZone(Player p) {
        ZoneInfo zi = playerLastZone.get(p.getUniqueId());
        if (zi == null) { p.sendMessage(ChatColor.RED + "Нет зоны для удаления!"); return; }

        zoneList.remove(zi.getMarkerID());
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().flatMap(api -> api.getMap(zi.getCorners().getFirst().getWorld().getName())).ifPresent(map -> {
                String setId = "zones_" + zi.getType().name().toLowerCase();
                MarkerSet set = map.getMarkerSets().get(setId);
                if (set != null) set.getMarkers().remove(zi.getMarkerID());
                blueMapIntegration.saveBlueMapMarkers(setId);
            });
        }
        p.sendMessage(ChatColor.GREEN + "Зона \"" + zi.getName() + "\" удалена!");
        playerLastZone.remove(p.getUniqueId());
    }

    public void cancelRemoveZone(Player p) {
        p.sendMessage(ChatColor.YELLOW + "Удаление отменено.");
        playerLastZone.remove(p.getUniqueId());
    }

    // ==== Price ====
    private void showPrice(Player p) {
        if (!p.hasPermission("zones.price.bypass")) {
            long now = System.currentTimeMillis(), last = lastPriceUse.getOrDefault(p.getUniqueId(), 0L);
            long left = PRICE_COOLDOWN_MS - (now - last);
            if (left > 0) { p.sendMessage(ChatColor.GRAY + "Команда будет доступна через " + ChatColor.YELLOW + ((left + 999) / 1000) + ChatColor.GRAY + " сек."); return; }
            lastPriceUse.put(p.getUniqueId(), now);
        }

        ZoneInfo zi = playerLastZone.get(p.getUniqueId());
        if (zi == null) { p.sendMessage(ChatColor.GRAY + "Зона не найдена."); return; }
        if (!zi.getOwner().equals(p.getName())) { p.sendMessage(ChatColor.RED + "Ты не владеешь этой зоной."); return; }

        double cost = ul.zoneActivityCalculations.calculateZoneDailyCostCached(zi, activityTracker.getChunkStatsMap(), activityTracker.getWeights());
        List<Double> hours = ul.zoneActivityCalculations.getZoneHourlySeries(zi, activityTracker.getChunkStatsMap(), 12);

        StringBuilder hover = new StringBuilder(ChatColor.GOLD + "Активность по часам (последние " + hours.size() + "):\n");
        for (int i = 0; i < hours.size(); i++) {
            int hAgo = (hours.size() - 1) - i;
            hover.append(ChatColor.YELLOW).append("H-").append(hAgo < 10 ? "0" + hAgo : hAgo)
                    .append(ChatColor.GRAY).append(": ").append(ChatColor.WHITE)
                    .append(String.format(Locale.US, "%.3f", hours.get(i))).append("\n");
        }

        net.md_5.bungee.api.chat.TextComponent msg =
                new net.md_5.bungee.api.chat.TextComponent(ChatColor.GREEN + "Текущая дневная стоимость: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", cost) + "Ⓕ");
        msg.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                new net.md_5.bungee.api.chat.ComponentBuilder(hover.toString()).create()));
        p.spigot().sendMessage(msg);
    }

    // ==== Player location → zone ====
    public void checkPlayerZone(Player p) {
        ZoneInfo prev = playerLastZone.get(p.getUniqueId());
        ZoneInfo next = getZoneAt(p.getLocation());

        if (Objects.equals(prev, next)) return;

        if (prev == null) {
            ZoneTypeData ztd = zoneLimits.get(next.getType());
            if (ztd != null) p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.GREEN + "Зона: " + ChatColor.GOLD + ztd.displayName() + " \"" + next.getName() + "\""));
        } else if (next == null) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.RED + "Вы покинули зону"));
        } else {
            ZoneTypeData ztd = zoneLimits.get(next.getType());
            if (ztd != null) p.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    new TextComponent(ChatColor.YELLOW + "\"" + prev.getName() + "\"" + ChatColor.GRAY + " → " + ChatColor.GOLD + ztd.displayName() + " \"" + next.getName() + "\""));
        }
        playerLastZone.put(p.getUniqueId(), next);
    }

    public ZoneInfo getZoneAt(Location loc) {
        World w = loc.getWorld();
        if (w == null) return null;

        return zoneList.values().stream()
                .filter(z -> worldOk(z.getCorners(), w) && pointInZone(loc, z.getCorners())).max(Comparator.comparingInt((ZoneInfo z) -> zoneLimits.get(z.getType()).index())).orElse(null);
    }

    // ==== BlueMap upsert ====
    private void upsertBlueMapMarker(ZoneInfo z, org.bukkit.Color bukkitColor) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) return;
        if (z.getCorners().isEmpty()) return;

        BlueMapAPI.getInstance().flatMap(api -> api.getMap(z.getCorners().getFirst().getWorld().getName())).ifPresent(map -> {
            String setId = "zones_" + z.getType().name().toLowerCase();
            MarkerSet set = map.getMarkerSets().computeIfAbsent(setId, MarkerSet::new);
            set.setLabel(zoneLimits.get(z.getType()).displayName());

            List<Vector2d> base = poly2D(z.getCorners());
            Marker m = set.getMarkers().get(z.getMarkerID());

            Color fill = toBlueMapColor(bukkitColor != null ? bukkitColor : org.bukkit.Color.fromRGB(255, 0, 0), 0.35f);
            Color line = toBlueMapColor(bukkitColor != null ? bukkitColor : org.bukkit.Color.fromRGB(255, 0, 0), 1f);

            if (m instanceof ExtrudeMarker em) {
                em.setLabel(z.getName());
                em.setShape(new Shape(base), 42, 255);
                em.setFillColor(fill);
                em.setLineColor(line);
                em.setDetail(detailHtml(z));
            } else {
                ExtrudeMarker.Builder b = ExtrudeMarker.builder()
                        .label(z.getName())
                        .shape(new Shape(base), -64, 255)
                        .detail(detailHtml(z));
                ExtrudeMarker built = b.build();
                built.setFillColor(fill);
                built.setLineColor(line);
                set.getMarkers().put(z.getMarkerID(), built);
            }
            blueMapIntegration.saveBlueMapMarkers(setId);
        });
    }

    private String detailHtml(ZoneInfo z) {
        String owner = z.getOwner() != null ? z.getOwner() : "—";
        return "<b>" + zoneLimits.get(z.getType()).displayName() + " \"" + z.getName() + "\"</b>"
                + "<br><br><i>Владелец:</i> " + owner
                + "<br><i>Площадь:</i> " + String.format(Locale.US,"%.2f", calculateSurfaceArea(z.getCorners()));
    }

    // ==== Overlap helpers ====
    private ZoneInfo findOverlapAt(Location loc, String owner, ZoneType currentType, String currentId) {
        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() == currentType && Objects.equals(z.getID(), currentId)) continue;  // self
            if (Objects.equals(z.getOwner(), owner) && z.getType() == currentType) continue;   // same owner same type
            if (!worldOk(z.getCorners(), loc.getWorld())) continue;
            if (pointInZone(loc, z.getCorners())) return z;
        }
        return null;
    }
    private boolean isInOtherZone(Location loc, String owner, ZoneType t, String id) { return findOverlapAt(loc, owner, t, id) != null; }

    // ==== Geometry ====
    private static boolean worldOk(List<Location> corners, World w) {
        return corners != null && corners.size() >= 3 && Objects.equals(corners.getFirst().getWorld(), w);
    }
    private boolean pointInZone(Location loc, List<Location> corners) {
        if (!worldOk(corners, loc.getWorld())) return false;
        if (loc.getY() < Y_MIN || loc.getY() > Y_MAX) return false;
        return pointInPolygon(new Vector2d(loc.getX(), loc.getZ()), poly2D(corners));
    }
    private static List<Vector2d> poly2D(List<Location> corners) {
        return corners.stream().map(l -> new Vector2d(l.getX(), l.getZ())).collect(Collectors.toList());
    }
    private static boolean pointInPolygon(Vector2d p, List<Vector2d> poly) {
        boolean inside = false; int j = poly.size() - 1;
        for (int i = 0; i < poly.size(); i++) {
            Vector2d a = poly.get(i), b = poly.get(j);
            boolean inter = ((a.getY() > p.getY()) != (b.getY() > p.getY()))
                    && (p.getX() < (b.getX() - a.getX()) * (p.getY() - a.getY()) / (b.getY() - a.getY()) + a.getX());
            if (inter) inside = !inside; j = i;
        }
        return inside;
    }
    private static boolean ccw(Vector2d a, Vector2d b, Vector2d c) { return (b.getX()-a.getX())*(c.getY()-a.getY()) - (b.getY()-a.getY())*(c.getX()-a.getX()) > 0; }
    private static boolean segInter(Vector2d a, Vector2d b, Vector2d c, Vector2d d) {
        return ccw(a,c,d) != ccw(b,c,d) && ccw(a,b,c) != ccw(a,b,d);
    }
    private static boolean hasSelfIntersections(List<Vector2d> pts) {
        int n = pts.size();
        for (int i=0;i<n;i++) {
            Vector2d a1 = pts.get(i), a2 = pts.get((i+1)%n);
            for (int j=i+2;j<n;j++) {
                if (Math.abs(i-j)==1 || (i==0 && j==n-1)) continue;
                Vector2d b1 = pts.get(j), b2 = pts.get((j+1)%n);
                if (segInter(a1,a2,b1,b2)) return true;
            }
        }
        return false;
    }
    private static boolean areaOk(List<Location> pts, ZoneTypeData ztd) {
        double area = calculateSurfaceArea(pts);
        if (pts.size() >= 3 && area < ztd.minSize()) return false;
        return area <= ztd.areaLimit();
    }

    // ==== Public adapters for SignManager ====

    /** Проверка точки внутри полигона, где полигон задан массивом точек. */
    public boolean isPointInsidePolygon(Vector2d point, Vector2d[] polygon) {
        if (point == null || polygon == null || polygon.length < 3) return false;

        boolean inside = false;
        int n = polygon.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            Vector2d a = polygon[i];
            Vector2d b = polygon[j];

            boolean intersect = ((a.getY() > point.getY()) != (b.getY() > point.getY())) &&
                    (point.getX() < (b.getX() - a.getX()) * (point.getY() - a.getY()) / (b.getY() - a.getY()) + a.getX());
            if (intersect) inside = !inside;
        }
        return inside;
    }

    /**
     * Поддержка сигнатуры, которую вызывает SignManager:
     * передаётся список массивов точек (обычно один Shape → один массив).
     * Берём первый массив и проверяем по нему.
     */
    public boolean isPointInsidePolygon(Vector2d point, List<Vector2d[]> polygons) {
        if (polygons == null || polygons.isEmpty() || polygons.getFirst() == null) return false;
        return isPointInsidePolygon(point, polygons.getFirst());
    }


    // ==== Color / parse utils ====
    private static org.bukkit.Color hexToBukkit(String hex) {
        if (hex == null || hex.isEmpty()) return org.bukkit.Color.WHITE;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        int rgb = (int) Long.parseLong(s, 16);
        return org.bukkit.Color.fromRGB((rgb>>16)&0xFF, (rgb>>8)&0xFF, rgb&0xFF);
    }
    private static String bukkitToHex(org.bukkit.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
    private static Color toBlueMapColor(org.bukkit.Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }
    private static double num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).doubleValue() : 0.0;
    }
    private static float fnum(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number) v).floatValue() : (float) 0.0;
    }

    // ==== Resolve player's current own zone ====
    private ZoneInfo resolvePlayerOwnZoneHere(Player p) {
        ZoneInfo zi = playerLastZone.get(p.getUniqueId());
        if (zi != null) return zi;
        for (ZoneInfo z : zoneList.values()) {
            if (!Objects.equals(z.getOwner(), p.getName())) continue;
            if (pointInZone(p.getLocation(), z.getCorners())) return z;
        }
        return null;
    }

    // ==== Signs / Containers summary ====
    public Map<Location, List<ItemData>> getItemSummaryFromContainers(List<Block> containers, List<Location> signLocations) {
        Map<Location, List<ItemData>> out = new HashMap<>();
        for (int i = 0; i < containers.size(); i++) {
            Block b = containers.get(i);
            if (!(b.getState() instanceof Container c)) continue;

            Location signLoc = signLocations.get(i);
            SignVariables vars = signManager != null ? signManager.genericSignList.get(signLoc) : null;
            if (vars == null || vars.getSignText().size() < 4) continue;

            String qtyStr = ChatColor.stripColor(vars.getSignText().get(2).replace("Кол-во: ", ""));
            String priceStr = ChatColor.stripColor(vars.getSignText().get(3).replace("Цена: ", ""));
            int qty = Integer.parseInt(qtyStr);
            double price = Double.parseDouble(priceStr);

            Map<Material, ItemData> combined = new HashMap<>();
            for (ItemStack it : c.getInventory().getContents()) {
                if (it == null || it.getType() == Material.AIR) continue;
                combined.compute(it.getType(), (m, old) -> {
                    if (old == null) return new ItemData(c.getLocation(), m.toString(), qty, it.getAmount(), price);
                    old.overallQuantity += it.getAmount(); return old;
                });
            }
            out.put(signLoc, new ArrayList<>(combined.values()));
        }
        return out;
    }
}
