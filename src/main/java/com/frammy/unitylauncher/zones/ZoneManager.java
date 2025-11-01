package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.signs.ItemData;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.zones.countryrelations.CountryRelationshipDao;
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
import org.bukkit.Location;
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

import org.bukkit.World;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public class ZoneManager {

    // ==== DI / State ====
    public final UnityLauncher ul;
    public SignManager signManager;
    public BlueMapIntegration blueMapIntegration;
    public ActivityTracker activityTracker;

    private CountryRelationshipDao countryRelationshipDao;
    public CountryRelationshipDao getCountryRelationshipDao() { return countryRelationshipDao; }

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

    /** Иммутабельная копия всех зон для безопасного чтения. */
    public List<ZoneInfo> getAllZonesSnapshot() {
        // zoneList — это Map<String, ZoneInfo> со всеми зонами по markerID
        return new ArrayList<>(zoneList.values());
    }

    // --- КУЛДАУН РЕДАКТИРОВАНИЯ УГЛОВ ДЛЯ COUNTRY и COLONY (персистентно через YAML) ---
    private final Map<String, Long> lastCornersEditByMarker = new ConcurrentHashMap<>();
    private static final long CORNERS_EDIT_COOLDOWN_MS = 2L * 24L * 60L * 60L * 1000L; // 2 суток

    // ==== Commands ====
    public void handleCommand(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Использование: /ul zone <addcorner|removecorner|build|update|price|remove|confirmremove|cancelremove>");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "addcorner" -> {
                if (args.length != 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone addcorner <zoneType>"); return; }
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

    public final Map<ZoneType, ZoneTypeData> zoneLimits = new HashMap<>() {{
        // displayName, areaLimit, index(приоритет), baseCost, allowOverlap, areaMultiplier, minSize, perm
        put(ZoneType.SHOP,       new ZoneTypeData("Торговая точка",     500.0,  2,   3.0,  true,  1.0,  10, "unityLauncher.createZone.shop"));
        put(ZoneType.BANK,       new ZoneTypeData("Банк",               300.0,  3,  20.0,  false, 1.0, 150, "unityLauncher.createZone.bank"));
        put(ZoneType.HOSPITAL,   new ZoneTypeData("Госпиталь",          700.0,  3,  15.0,  false, 1.0, 200, "unityLauncher.createZone.hospital"));
        put(ZoneType.INDUSTRIAL, new ZoneTypeData("Промышленная зона", 1000.0,  3,  20.0,  false, 1.15, 50, "unityLauncher.createZone.industrial"));
        put(ZoneType.REGION,     new ZoneTypeData("Регион",           10000.0,  3, 300.0,  false, 0.85,  0, "unityLauncher.createZone.region"));
        put(ZoneType.COUNTRY,    new ZoneTypeData("Государство",      30000.0, 10, 100.0,  false, 0.70,  0, "unityLauncher.createZone.country"));

        // Новые типы, «только в стране или колонии»
        put(ZoneType.CHURCH,     new ZoneTypeData("Церковь",            500.0,  3,  10.0,  false, 1.0,  20, "unityLauncher.createZone.church"));
        put(ZoneType.LIBRARY,    new ZoneTypeData("Библиотека",         500.0,  3,  10.0,  false, 1.0,  20, "unityLauncher.createZone.library"));
        put(ZoneType.PARK,       new ZoneTypeData("Парк",               900.0,  3,   5.0,  false, 1.0,  20, "unityLauncher.createZone.park"));

        // COLONY — «мини-государство»: создаёт правитель, любое измерение, внутри при создании ничего
        put(ZoneType.COLONY,     new ZoneTypeData("Колония",          10000.0,  5,  80.0,  false, 0.85,  0, "unityLauncher.createZone.colony"));
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
        boolean needsSave = false;

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

                    // marker_ID с автогенерацией + записью в YAML при отсутствии
                    String markerID = z.getString("marker_ID", null);
                    if (markerID == null || markerID.isBlank()) {
                        markerID = "marker_" + UUID.randomUUID();
                        z.set("marker_ID", markerID);
                        needsSave = true;
                    }

                    String worldOverride = z.getString("world", null);

                    List<Location> corners = new ArrayList<>();
                    for (Map<?, ?> m : z.getMapList("corners")) {
                        try {
                            String wName = (worldOverride != null ? worldOverride : (String) m.get("world"));
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

                    // если world отсутствует наверху, но углы есть — проставим world из первого угла (удобно для будущих записей)
                    if ((worldOverride == null || worldOverride.isBlank()) && !corners.isEmpty() && corners.getFirst().getWorld() != null) {
                        z.set("world", corners.getFirst().getWorld().getName());
                        needsSave = true;
                    }

                    long lastEdit = z.getLong("lastCornersEdit", -1L);
                    if (lastEdit > 0L) {
                        lastCornersEditByMarker.put(markerID, lastEdit);
                    }

                    ZoneInfo zi = new ZoneInfo(zoneType, zoneId, name, markerID, corners, owner, color);

                    String ownerCountryYaml = z.getString("ownerCountry", null);
                    if (ownerCountryYaml != null && !ownerCountryYaml.isBlank()) {
                        zi.setOwnerCountry(ownerCountryYaml);
                    }

                    zoneList.put(markerID, zi);
                }
            }
        }

        if (needsSave) saveZonesToConfig();
    }

// ========= ПЛАВНЫЙ ПЕРЕСЧЁТ ВЛАДЕЛЬЦЕВ ТАБЛИЧЕК ПОСЛЕ СТАРТА =========

    /** Плавно пересчитать ownerName у всех табличек по полигонам зон, не душа главный поток. */
    public void scheduleSignOwnershipRecalc(SignManager signManager, int signsPerTick) {
        if (signManager == null || signManager.genericSignList == null || signManager.genericSignList.isEmpty()) return;

        // Группируем таблички по миру
        Map<String, List<Location>> signsByWorld = new HashMap<>();
        for (Location loc : signManager.genericSignList.keySet()) {
            if (loc.getWorld() == null) continue;
            signsByWorld.computeIfAbsent(loc.getWorld().getName(), k -> new ArrayList<>()).add(loc);
        }

        // Ключ: owner + '|' + type  → Отображаемое имя (страна если есть, иначе ник)
        Map<String, String> ownerTypeDisplay = new HashMap<>();
        for (ZoneInfo z : getAllZonesSnapshot()) {
            String owner = z.getOwner();
            if (owner == null) continue;
            String display = (z.getCountryName() != null && !z.getCountryName().isBlank())
                    ? z.getCountryName()
                    : owner;
            ownerTypeDisplay.put(owner + "|" + z.getType().name(), display);
        }

        // Кэш полигонов зон по миру: мир -> список (владелец, тип, полигон2D)
        record ZonePoly(String owner, ZoneType type, List<Vector2d> poly) {}
        Map<String, List<ZonePoly>> worldZones = new HashMap<>();
        for (ZoneInfo z : getAllZonesSnapshot()) {
            List<Location> cs = z.getCorners();
            if (cs == null || cs.isEmpty() || cs.getFirst().getWorld() == null) continue;
            String w = cs.getFirst().getWorld().getName();
            worldZones.computeIfAbsent(w, k -> new ArrayList<>())
                    .add(new ZonePoly(z.getOwner(), z.getType(), poly2D(cs)));
        }

        // Плоский список табличек для итерации
        List<Location> allSigns = new ArrayList<>(signManager.genericSignList.keySet());
        final int total = allSigns.size();
        final int batch = Math.max(10, signsPerTick);

        Bukkit.getLogger().info("[Zones] Пересчёт владельцев табличек: миров " + worldZones.size() + ", табличек " + total + ", батч " + batch + "/тик");

        new org.bukkit.scheduler.BukkitRunnable() {
            int idx = 0;
            @Override public void run() {
                int end = Math.min(idx + batch, total);
                for (int i = idx; i < end; i++) {
                    Location signLoc = allSigns.get(i);
                    String world = (signLoc.getWorld() != null) ? signLoc.getWorld().getName() : null;
                    if (world == null) continue;

                    List<ZonePoly> zp = worldZones.get(world);
                    if (zp == null || zp.isEmpty()) continue;

                    // Ищем первую зону, в которую попадает табличка; при желании можно добавить приоритет по типу
                    Vector2d p = new Vector2d(signLoc.getX(), signLoc.getZ());
                    for (ZonePoly zpp : zp) {
                        if (pointInPolygon(p, zpp.poly())) {
                            SignVariables sv = signManager.genericSignList.get(signLoc);
                            if (sv != null) {
                                String key = (zpp.owner()) + "|" + zpp.type().name();
                                String ownerDisplay = ownerTypeDisplay.getOrDefault(
                                        key,
                                        zpp.owner() != null ? zpp.owner() : "—"
                                );
                                sv.setOwnerName(ownerDisplay);
                            }
                            break;
                        }
                    }

                }
                idx = end;

                if (idx >= total) {
                    cancel();
                    Bukkit.getLogger().info("[Zones] Пересчёт владельцев табличек завершён. Всего: " + total);
                }
            }
        }.runTaskTimer(UnityLauncher.getInstance(), 1L, 1L);
    }

    public void saveZonesToConfig() {
        zonesConfig = new YamlConfiguration();
        for (ZoneInfo z : zoneList.values()) {
            String path = z.getType().name().toLowerCase() + "." + z.getOwner() + "." + z.getID();
            zonesConfig.set(path + ".name", z.getName());
            if (z.getCountryName() != null && !z.getCountryName().isBlank())
                zonesConfig.set(path + ".ownerCountry", z.getCountryName());
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
            Long lastEdit = lastCornersEditByMarker.get(z.getMarkerID());
            if (lastEdit != null && lastEdit > 0L) {
                zonesConfig.set(path + ".lastCornersEdit", lastEdit);
            }

        }
        saveZonesConfig();
    }

    /** true, если все углы полигона pts целиком лежат внутри ОДНОЙ зоны из набора типов allowedTypes */
    private boolean polygonInsideSingleZoneOfTypes(List<Location> pts, Set<ZoneType> allowedTypes) {
        if (pts == null || pts.size() < 3) return false;
        World w0 = pts.getFirst().getWorld();

        for (ZoneInfo parent : zoneList.values()) {
            if (!allowedTypes.contains(parent.getType())) continue;
            if (!worldOk(parent.getCorners(), w0)) continue;
            List<Vector2d> polyParent = poly2D(parent.getCorners());

            boolean allInside = true;
            for (Location corner : pts) {
                if (!pointInPolygon(new Vector2d(corner.getX(), corner.getZ()), polyParent)) {
                    allInside = false; break;
                }
            }
            if (allInside) return true;
        }
        return false;
    }

    /** true, если создаваемая область pts содержит внутри себя хоть одну существующую зону (любой тип) ИЛИ пересекается с ней */
    private boolean areaHasAnyZonesInsideOrIntersecting(List<Location> pts, String ignoreMarkerId) {
        // построим JTS-полигон создаваемой зоны
        ZoneInfo tmp = new ZoneInfo(ZoneType.REGION, "tmp", "tmp", "tmp_marker", pts, "tmp_owner", org.bukkit.Color.WHITE);
        var newPoly = toJtsPolygon(tmp);
        if (newPoly == null) return true;

        World w0 = pts.getFirst().getWorld();
        for (ZoneInfo existing : zoneList.values()) {
            if (ignoreMarkerId != null && ignoreMarkerId.equals(existing.getMarkerID())) continue;
            if (!worldOk(existing.getCorners(), w0)) continue;

            var exPoly = toJtsPolygon(existing);
            if (exPoly == null) continue;

            try {
                if (newPoly.intersects(exPoly) || newPoly.contains(exPoly) || exPoly.contains(newPoly)) {
                    return true;
                }
            } catch (Throwable t) {
                // если геометрия испорчена — перестрахуемся и запретим
                return true;
            }
        }
        return false;
    }

    // ==== NEW: проверка наличия у игрока своей страны ====
    /** true, если у игрока уже есть хотя бы одна зона типа COUNTRY, где он — владелец. */
    public boolean playerHasCountryZone(String owner) {
        if (owner == null || owner.isBlank()) return false;
        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() == ZoneType.COUNTRY && owner.equals(z.getOwner())) return true;
        }
        return false;
    }

    // ==== Build / Update ====
    private void addCorner(Player p, ZoneType type) {
        ZoneTypeData ztd;
        try {
            ztd = zoneLimits.get(type);
            if (ztd == null) { p.sendMessage(ChatColor.RED + "Неверный тип зоны!"); return; }
        } catch (Exception e) {
            p.sendMessage(ChatColor.RED + "Неверный тип зоны!"); return;
        }


        // Блокировка добавления точек для новой страны, если страна уже существует
        if (type == ZoneType.COUNTRY && playerHasCountryZone(p.getName())) {
            p.sendMessage(ChatColor.RED + "У вас уже есть территория Государства. Нельзя создавать вторую.");
            return;
        }

        if (type == ZoneType.COUNTRY) {
            var reg = UnityLauncher.getInstance().countryRegistryJdbc;
            String country = reg.getCountryOfPlayer(p.getName());
            if (country == null || country.isBlank()) {
                p.sendMessage(ChatColor.RED + "Для зон типа COUNTRY требуется состоять в стране.");
                return;
            }
        }

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

        // Собираем временный полигон-кандидат
        List<Location> tmp = new ArrayList<>(pts);
        tmp.add(p.getLocation().clone());

        // Площадь/самопересечение ещё до пересечений — чтобы не гонять лишние полигоны
        if (!areaOkDraft(tmp, ztd)) {
            p.sendMessage(ChatColor.GRAY + "Площадь превышает максимум " + (int) ztd.areaLimit() + " блоков².");
            return;
        }
        if (hasSelfIntersections(poly2D(tmp))) { p.sendMessage(ChatColor.RED + "Фигура самопересекается."); return; }

        // Полигональная проверка пересечений с существующими зонами того же мира
        ZoneInfo candidate = new ZoneInfo(type, "tmp", "tmp", "tmp", tmp, p.getName(), org.bukkit.Color.WHITE);
        for (ZoneInfo other : zoneList.values()) {
            if (other == null || other == candidate) continue;
            if (other.getWorld() == null || candidate.getWorld() == null) continue;
            if (!other.getWorld().equals(candidate.getWorld())) continue;

            // Либо не пересекаются, либо пересечение разрешено правилами
            if (!canZonesCoexist(candidate, other)) {
                p.sendMessage(ChatColor.RED + "Пересечение с " + other.getType() + " \"" + other.getName() + "\" запрещено.");
                return;
            }
        }

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
        // 1) только лидер своей страны
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (playerCountry == null || playerCountry.isBlank()) {
            p.sendMessage(ChatColor.RED + "Нельзя создать Государство: вы не состоите ни в одной стране.");
            return;
        }
        if (!ul.countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
            String leader = ul.countryRegistryJdbc.getLeaderOfCountry(playerCountry);
            p.sendMessage(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Государство."
                    + (leader != null ? ChatColor.GRAY + " Лидер: " + leader : ""));
            return;
        }

        // 2) запрет второй страны
        if (playerHasCountryZone(p.getName())) {
            p.sendMessage(ChatColor.RED + "У вас уже есть территория Государства. Сначала удалите существующую.");
            return;
        }

        // 3) точки
        List<Location> pts = zonePoints.get(p.getUniqueId());
        if (pts == null || pts.size() < 3) {
            p.sendMessage(ChatColor.RED + "Нужно минимум 3 точки!"); return;
        }
        World w0 = pts.getFirst().getWorld();
        if (!pts.stream().allMatch(l -> l.getWorld().equals(w0))) {
            p.sendMessage(ChatColor.RED + "Все точки должны быть в одном мире."); return;
        }
        if (w0.getEnvironment() != World.Environment.NORMAL) {
            p.sendMessage(ChatColor.RED + "Государство можно создавать только в Overworld."); return;
        }

        // 4) в области ничего не должно быть
        if (areaHasAnyZonesInsideOrIntersecting(pts, null)) {
            p.sendMessage(ChatColor.RED + "Нельзя создать Государство: внутри/по границе уже есть другие зоны.");
            return;
        }

        // 5) создаём
        String rnd = UUID.randomUUID().toString();
        String zoneID = "zone_" + rnd;
        String markerID = "marker_" + rnd;

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
        ZoneInfo created = new ZoneInfo(
                ZoneType.COUNTRY,
                zoneID,
                playerCountry, // имя страны как имя зоны по умолчанию
                markerID,
                pts,
                p.getName(),   // технический владелец
                defaultColor
        );
        created.setOwnerCountry(playerCountry);

        // сохраним в YAML
        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".name", playerCountry);
        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".ownerCountry", playerCountry);
        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".marker_ID", markerID);
        zonesConfig.set("COUNTRY." + p.getName() + "." + zoneID + ".corners",
                pts.stream().map(Location::serialize).toList());

        zoneList.put(markerID, created);
        lastCornersEditByMarker.put(markerID, System.currentTimeMillis());

        upsertBlueMapMarker(created, defaultColor);
        p.sendMessage(ChatColor.GREEN + "Территория страны \"" + playerCountry + "\" создана! ");

        // стартовый лимит банкоматов — теперь идёт через новую ensureInitialAtmAllowance
        ul.countryRegistryJdbc.ensureInitialAtmAllowance(playerCountry, 5);

        zonePoints.remove(p.getUniqueId());
        saveZonesToConfig();
    }

    /** Создание обычных зон: принадлежат игроку, страна НЕ проставляется при создании. */
    private void buildZone(Player p, ZoneType type, String zoneName) {
        List<Location> pts = zonePoints.get(p.getUniqueId());
        if (pts == null || pts.size() < 3) {
            p.sendMessage(ChatColor.RED + "Нужно минимум 3 точки!");
            return;
        }

        // все точки в одном мире
        World w0 = pts.getFirst().getWorld();
        if (!pts.stream().allMatch(l -> l.getWorld().equals(w0))) {
            p.sendMessage(ChatColor.RED + "Все точки должны быть в одном мире.");
            return;
        }

        // общие проверки формы
        if (hasSelfIntersections(poly2D(pts))) {
            p.sendMessage(ChatColor.RED + "Фигура самопересекается.");
            return;
        }
        ZoneTypeData ztdBuild = zoneLimits.get(type);
        double area = calculateSurfaceArea(pts);
        if (pts.size() >= 3 && area < ztdBuild.minSize()) {
            p.sendMessage(ChatColor.RED + "Площадь меньше минимума для " + type + ": "
                    + (int) area + " < " + (int) ztdBuild.minSize() + " блоков².");
            return;
        }
        if (area > ztdBuild.areaLimit()) {
            p.sendMessage(ChatColor.RED + "Площадь превышает максимум для " + type + ": "
                    + (int) area + " > " + (int) ztdBuild.areaLimit() + " блоков².");
            return;
        }

        // страна игрока (может быть null, если игрок ни в одной стране)
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());

        // особый случай: COLONY
        if (type == ZoneType.COLONY) {
            // только лидер страны может сделать колонию
            if (playerCountry == null || playerCountry.isBlank()) {
                p.sendMessage(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
                return;
            }
            if (!ul.countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
                p.sendMessage(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Колонию.");
                return;
            }
            // внутри при создании не должно быть других зон
            if (areaHasAnyZonesInsideOrIntersecting(pts, null)) {
                p.sendMessage(ChatColor.RED + "Нельзя создать Колонию: внутри/по границе уже есть другие зоны.");
                return;
            }
            // Колония разрешена в любом мире (Overworld / Nether / End) по твоему текущему правилу.
        }

        // типы, которые обязаны быть внутри страны или колонии
        boolean mustBeInsideCountryOrColony =
                (type == ZoneType.BANK ||
                        type == ZoneType.HOSPITAL ||
                        type == ZoneType.REGION ||
                        type == ZoneType.CHURCH ||
                        type == ZoneType.LIBRARY ||
                        type == ZoneType.PARK ||
                        type == ZoneType.INDUSTRIAL);

        ZoneInfo parentZoneForChildren;
        if (mustBeInsideCountryOrColony) {
            Set<ZoneType> parents = Set.of(ZoneType.COUNTRY, ZoneType.COLONY);
            parentZoneForChildren = findSingleContainingZoneOfTypes(pts, parents);

            if (parentZoneForChildren == null) {
                p.sendMessage(ChatColor.RED + "Зона " + type + " должна полностью находиться внутри Государства или Колонии.");
                return;
            }

            // определяем страну родителя
            String parentCountry;
            // колония тоже получает ownerCountry страны
            parentCountry = parentZoneForChildren.getCountryName(); // должен быть setOwnerCountry на стране

            if (playerCountry == null || playerCountry.isBlank()) {
                p.sendMessage(ChatColor.RED + "Нельзя создать зону: вы не состоите ни в одной стране.");
                return;
            }

            if (!Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                p.sendMessage(ChatColor.RED + "Другие зоны можно создавать только на территории своей страны или своей колонии.");
                return;
            }

            // теперь убедимся, что ни одна точка не пересекается с чужими зонами, кроме нашего родителя
            for (Location loc : pts) {
                ZoneInfo overlap = findOverlapAt(loc, p.getName(), type,
                        parentZoneForChildren.getMarkerID());

                if (overlap == null) continue;

                // если мы наткнулись на самого родителя (COUNTRY или COLONY), ок
                if (isCountryOrColony(overlap) &&
                        Objects.equals(overlap.getMarkerID(), parentZoneForChildren.getMarkerID())) {
                    continue;
                }

                p.sendMessage(ChatColor.RED + "Нельзя создать зону: точка " + loc.toVector()
                        + " пересекается с другой зоной \"" + overlap.getName() + "\".");
                return;
            }
        }

        // SHOP — свободный случай, ничего особого не требуем. Может жить где угодно.

        // Всё ок — создаём сам объект ZoneInfo
        String rnd = UUID.randomUUID().toString();
        String zoneID = "zone_" + rnd;
        String markerID = "marker_" + rnd;

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
        ZoneInfo created = new ZoneInfo(type, zoneID, zoneName, markerID, pts, p.getName(), defaultColor);

        // проставляем ownerCountry:
        // 1) если это COLONY — страна лидера
        // 2) если это mustBeInsideCountryOrColony — страна игрока (мы уже проверили, что она совпадает с родителем)
        // 3) если это SHOP — если игрок имеет страну, дадим её тоже (чтоб апгрейды и налоги понимали чьё это)
        if (type == ZoneType.COLONY) {
            if (!playerCountry.isBlank()) {
                created.setOwnerCountry(playerCountry);
            }
        } else if (mustBeInsideCountryOrColony) {
            if (!playerCountry.isBlank()) {
                created.setOwnerCountry(playerCountry);
            }
        } else if (type == ZoneType.SHOP) {
            if (playerCountry != null && !playerCountry.isBlank()) {
                created.setOwnerCountry(playerCountry);
            }
        } else {
            // fallback для чего-то редкого
            if (playerCountry != null && !playerCountry.isBlank()) {
                created.setOwnerCountry(playerCountry);
            }
        }

        // Сохраняем в YAML
        String path = type + "." + p.getName() + "." + zoneID;
        zonesConfig.set(path + ".name", zoneName);
        zonesConfig.set(path + ".ownerCountry", created.getCountryName());
        zonesConfig.set(path + ".color", bukkitToHex(defaultColor));
        zonesConfig.set(path + ".marker_ID", markerID);
        zonesConfig.set(path + ".world", w0.getName());
        zonesConfig.set(path + ".corners",
                pts.stream().map(Location::serialize).collect(Collectors.toList()));

        zoneList.put(markerID, created);
        lastCornersEditByMarker.put(markerID, System.currentTimeMillis());

        upsertBlueMapMarker(created, defaultColor);
        p.sendMessage(ChatColor.GREEN + "Зона \"" + zoneName + "\" создана!");

        zonePoints.remove(p.getUniqueId());
        saveZonesToConfig();
    }

    // ==== Overlap helpers ====

    /** Находит чужую зону, в которую попадает точка loc.
     *  currentType/currentId — чтобы не ловить пересечение с самой собой при апдейте. */
    private ZoneInfo findOverlapAt(Location loc, String owner, ZoneType currentType, String ignoreMarkerId) {
        for (ZoneInfo z : zoneList.values()) {
            // 1) игнорируем саму редактируемую зону (или родителя при создании)
            if (ignoreMarkerId != null && ignoreMarkerId.equals(z.getMarkerID())) continue;

            // 2) по желанию: игнорируем зоны того же владельца того же типа
            if (Objects.equals(z.getOwner(), owner) && z.getType() == currentType) continue;

            if (!worldOk(z.getCorners(), loc.getWorld())) continue;
            if (pointInZone(loc, z.getCorners())) return z;
        }
        return null;
    }

    /** true, если точка попадает в ЧУЖУЮ зону. */
    private boolean isInOtherZone(Location loc, String owner, ZoneType currentType) {
        return findOverlapAt(loc, owner, currentType, null) != null;
    }

    public boolean isInsideZoneType(Location loc, ZoneType type) {
        if (loc == null || type == null) return false;
        World w = loc.getWorld();
        if (w == null) return false;

        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() != type) continue;
            if (!worldOk(z.getCorners(), w)) continue;
            if (pointInZone(loc, z.getCorners())) return true;
        }
        return false;
    }

    public void updateZone(Player p, String updateType, String value) {
        ZoneInfo zi = resolvePlayerOwnZoneHere(p);
        if (zi == null) { p.sendMessage(ChatColor.RED + "Вы не в своей зоне!"); return; }
        playerLastZone.put(p.getUniqueId(), zi);

        switch (updateType) {
            case "corners" -> {
                // Кулдаун 2 суток для COUNTRY и COLONY
                if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
                    long now = System.currentTimeMillis();
                    long last = lastCornersEditByMarker.getOrDefault(zi.getMarkerID(), 0L);
                    long left = CORNERS_EDIT_COOLDOWN_MS - (now - last);
                    if (left > 0) {
                        long hrs = (left + 3_600_000L - 1) / 3_600_000L;
                        p.sendMessage(ChatColor.RED + "Изменение углов доступно через ~" + ChatColor.YELLOW + hrs + ChatColor.RED + " ч.");
                        return;
                    }
                }

                // Биллинг как у вас было
                LocalDate today = LocalDate.now(zoneId);
                double due = zi.getDueSinceLastBill(today);
                int days = zi.getDueDaysCount(today);
                if (due > 0) {
                    p.sendMessage(ChatColor.GRAY + "Перед изменением оплатите " + ChatColor.YELLOW + days + ChatColor.GRAY + " дн.: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", due));
                    try { zi.markBilled(today); p.sendMessage(ChatColor.GREEN + "Оплачено. Можно менять границы."); }
                    catch (Exception ex) { p.sendMessage(ChatColor.RED + "Недостаточно средств: " + ex.getMessage()); return; }
                }

                if ("+".equals(value)) {
                    List<Location> tmp = new ArrayList<>(zi.getCorners()); tmp.add(p.getLocation().clone());

                    // Самопересечения/площадь
                    if (hasSelfIntersections(poly2D(tmp))) { p.sendMessage(ChatColor.GRAY + "Фигура самопересекается."); return; }
                    if (!areaOk(tmp, zoneLimits.get(zi.getType()))) { p.sendMessage(ChatColor.GRAY + "Площадь превышает лимит."); return; }

                    // Специфика для типов «только внутри страны/колонии»
                    if (zi.getType() == ZoneType.BANK || zi.getType() == ZoneType.HOSPITAL || zi.getType() == ZoneType.REGION
                            || zi.getType() == ZoneType.CHURCH || zi.getType() == ZoneType.LIBRARY || zi.getType() == ZoneType.PARK
                            || zi.getType() == ZoneType.INDUSTRIAL) {
                        if (!polygonInsideSingleZoneOfTypes(tmp, Set.of(ZoneType.COUNTRY, ZoneType.COLONY))) {
                            p.sendMessage(ChatColor.RED + "Эта зона должна целиком оставаться внутри Государства или Колонии.");
                            return;
                        }
                    }

                    // Для COUNTRY/COLONY — не содержать других зон и не пересекаться
                    if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
                        if (areaHasAnyZonesInsideOrIntersecting(tmp, zi.getMarkerID())) {
                            p.sendMessage(ChatColor.RED + "Нельзя менять границы: внутри/по границе окажутся другие зоны.");
                            return;
                        }
                    }

                    zi.getCorners().add(p.getLocation().clone());
                    p.sendMessage(ChatColor.GOLD + "[" + zi.getCorners().size() + "] " + ChatColor.YELLOW + "Точка добавлена. Площадь: " + ChatColor.GOLD + String.format(Locale.US,"%.2f", calculateSurfaceArea(tmp)));
                } else if ("-".equals(value)) {
                    if (zi.getCorners().size() <= 3) { p.sendMessage(ChatColor.RED + "Минимум 3 точки!"); return; }
                    List<Location> tmp = new ArrayList<>(zi.getCorners());
                    tmp.removeLast();

                    // Для COUNTRY/COLONY — по-прежнему нельзя, чтобы новые границы включали чужие зоны/пересечения
                    if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
                        if (areaHasAnyZonesInsideOrIntersecting(tmp, zi.getMarkerID())) {
                            p.sendMessage(ChatColor.RED + "Нельзя менять границы: внутри/по границе окажутся другие зоны.");
                            return;
                        }
                    }

                    zi.getCorners().removeLast();
                    p.sendMessage(ChatColor.GRAY + "Удалена последняя точка.");
                } else {
                    p.sendMessage(ChatColor.GRAY + "Используйте: /ul zone update corners +  или  -"); return;
                }

                // апдейт цвета/маркер, запись таймстемпа кулдауна
                upsertBlueMapMarker(zi, zi.getFillColor());
                lastCornersEditByMarker.put(zi.getMarkerID(), System.currentTimeMillis());
                saveZonesToConfig();
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
        if (zi == null) {
            p.sendMessage(ChatColor.RED + "Нет зоны для удаления!");
            return;
        }

        // 1) Если страна — широкаовещалка сразу (без ожидания БД)
        if (zi.getType() == ZoneType.COUNTRY) {
            Bukkit.broadcastMessage(
                    ChatColor.RED + "[Уведомление] Государство \"" +
                            ChatColor.GOLD + zi.getName() +
                            ChatColor.RED + "\" было удалено владельцем " +
                            ChatColor.YELLOW + p.getName() + ChatColor.RED + "."
            );
        }

        // 2) Локальная очистка (in-memory)
        zoneList.remove(zi.getMarkerID());
        // если ведёте таймстемпы редактирования углов — чистим тоже:
        lastCornersEditByMarker.remove(zi.getMarkerID());

        // 3) BlueMap (удаление маркера набора зоны)
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap") && !zi.getCorners().isEmpty()) {
            BlueMapAPI.getInstance()
                    .flatMap(api -> api.getMap(zi.getCorners().getFirst().getWorld().getName()))
                    .ifPresent(map -> {
                        String setId = "zones_" + zi.getType().name().toLowerCase();
                        MarkerSet set = map.getMarkerSets().get(setId);
                        if (set != null) set.getMarkers().remove(zi.getMarkerID());
                        if (blueMapIntegration != null) {
                            blueMapIntegration.saveBlueMapMarkers(setId);
                        }
                    });
        }

        p.sendMessage(ChatColor.GREEN + "Зона \"" + zi.getName() + "\" удалена!");
        playerLastZone.remove(p.getUniqueId());

        // 4) Фиксируем YAML на диск (без БД)
        saveZonesToConfig();

        // 5) Если удалили страну — уходим в фон и делаем РОВНО ОДНУ БД-транзакцию
        if (zi.getType() == ZoneType.COUNTRY) {
            Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
                try (java.sql.Connection conn = UnityLauncher.DBConnect()) {
                    if (conn == null) {
                        throw new RuntimeException("DBConnect() вернул null");
                    }

                    try {
                        conn.setAutoCommit(false);

                        // 1. Чистим отношения дипломатии для этой страны, если DAO есть
                        if (UnityLauncher.getInstance().countryRelationshipDao != null) {
                            UnityLauncher.getInstance().countryRelationshipDao.deleteByCountryTx(conn, zi.getName());
                        }

                        // 2. Удаляем страну из таблицы Countries + чистим локальный кэш CountryRegistryJdbc
                        UnityLauncher.getInstance().countryRegistryJdbc.deleteCountryTx(conn, zi.getName());

                        // 3. Пример: сюда можно добавить удаление записей из atm_quota и т.д.
                        // try (PreparedStatement ps = conn.prepareStatement(
                        //        "DELETE FROM atm_quota WHERE country = ?")) {
                        //     ps.setString(1, zi.getName());
                        //     ps.executeUpdate();
                        // }

                        conn.commit();
                    } catch (Exception inner) {
                        try { conn.rollback(); } catch (Exception ignore) {}
                        throw inner;
                    }

                    // сообщим в лог на главном потоке
                    Bukkit.getScheduler().runTask(ul, () ->
                            Bukkit.getLogger().info("[Zones] Страна \"" + zi.getName() + "\" удалена из БД (TX ok).")
                    );

                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(ul, () -> {
                        Bukkit.getLogger().warning("[Zones] Ошибка удаления страны \"" + zi.getName() + "\" из БД: " + ex.getMessage());
                        p.sendMessage(ChatColor.RED + "Удаление страны в БД завершилось ошибкой, см. консоль.");
                    });
                }
            });
        }

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
                    new TextComponent(ChatColor.GOLD + ztd.displayName() + " \"" + next.getName() + "\""));
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
                .filter(z -> worldOk(z.getCorners(), w) && pointInZone(loc, z.getCorners()))
                .max(Comparator.comparingInt((ZoneInfo z) -> zoneLimits.get(z.getType()).index()))
                .orElse(null);
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
                em.setShape(new Shape(base), -64, 255);
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
        String country = z.getCountryName() != null ? z.getCountryName() : "—";
        return "<b>" + zoneLimits.get(z.getType()).displayName() + " \"" + z.getName() + "\"</b>"
                + "<br><br><i>Владелец:</i> " + owner
                + "<br><i>Страна:</i> " + country
                + "<br><i>Площадь:</i> " + String.format(Locale.US,"%.2f", calculateSurfaceArea(z.getCorners()));
    }


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

    // Во время добавления точек (черновик) — проверяем только верхний предел площади.
    private static boolean areaOkDraft(List<Location> pts, ZoneTypeData ztd) {
        double area = calculateSurfaceArea(pts);
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

    // ---- ЕДИНЫЙ ПРАВИЛЬНЫЙ ХЕЛПЕР ДЛЯ ПРАВИЛ ПЕРЕСЕЧЕНИЙ ----

    // Разрешение «пересечений»: допускаем только полное ВХОЖДЕНИЕ дочерних зон внутрь COUNTRY/COLONY.
    boolean isOverlapAllowed(ZoneInfo a, ZoneInfo b) {
        if (a == null || b == null) return true;
        if (a.getWorld() == null || b.getWorld() == null) return true;
        if (!a.getWorld().getUID().equals(b.getWorld().getUID())) return true;

        // Родитель ⟷ Дочерняя: разрешаем ТОЛЬКО полное вхождение дочерней в родителя
        if (isCountryOrColony(a) && isChildType(b)) {
            return childInsideParent(b, a);
        }
        if (isCountryOrColony(b) && isChildType(a)) {
            return childInsideParent(a, b);
        }

        // Прочее — по флагам типов
        ZoneTypeData ad = zoneLimits.get(a.getType());
        ZoneTypeData bd = zoneLimits.get(b.getType());
        boolean aAllow = (ad == null) || ad.allowOverlap();
        boolean bAllow = (bd == null) || bd.allowOverlap();
        return aAllow && bAllow;
    }

    private static boolean isCountryOrColony(ZoneInfo z) {
        ZoneType t = z.getType();
        return t == ZoneType.COUNTRY || t == ZoneType.COLONY;
    }

    private static boolean isChildType(ZoneInfo z) {
        ZoneType t = z.getType();
        return t == ZoneType.INDUSTRIAL
                || t == ZoneType.REGION
                || t == ZoneType.BANK
                || t == ZoneType.HOSPITAL
                || t == ZoneType.CHURCH
                || t == ZoneType.LIBRARY
                || t == ZoneType.PARK
                || t == ZoneType.SHOP;
    }

    private boolean childInsideParent(ZoneInfo child, ZoneInfo parent) {
        try {
            var childPoly  = toJtsPolygon(child);
            var parentPoly = toJtsPolygon(parent);
            if (childPoly == null || parentPoly == null) return false;
            return parentPoly.contains(childPoly); // именно полное вхождение
        } catch (Throwable t) {
            return false;
        }
    }

    /** Вернёт зону-«родителя» из allowedTypes, если ВСЕ углы pts целиком внутри неё. */
    private ZoneInfo findSingleContainingZoneOfTypes(List<Location> pts, Set<ZoneType> allowedTypes) {
        if (pts == null || pts.size() < 3) return null;
        World w0 = pts.getFirst().getWorld();

        for (ZoneInfo parent : zoneList.values()) {
            if (!allowedTypes.contains(parent.getType())) continue;
            if (!worldOk(parent.getCorners(), w0)) continue;

            List<Vector2d> polyParent = poly2D(parent.getCorners());
            boolean allInside = true;
            for (Location corner : pts) {
                if (!pointInPolygon(new Vector2d(corner.getX(), corner.getZ()), polyParent)) {
                    allInside = false; break;
                }
            }
            if (allInside) return parent;
        }
        return null;
    }

    private static String normCountry(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        t = t.replace(' ', '_');
        return t.replaceAll("[^a-z0-9_\\-.]", "");
    }

    /**
     * Геометрически пересекаются ли полигоны зон по XZ в рамках одного мира.
     * Использует JTS-Polygon. Высота игнорируется.
     */
    public boolean zonesIntersect2D(ZoneInfo a, ZoneInfo b) {
        World wa = a.getWorld(), wb = b.getWorld();
        if (wa == null || wb == null) return false;
        if (!wa.getUID().equals(wb.getUID())) return false;

        Polygon pa = toJtsPolygon(a);
        Polygon pb = toJtsPolygon(b);
        if (pa == null || pb == null) return false;

        try {
            // touches/overlaps/intersects — всё считаем пересечением границ
            return pa.intersects(pb);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Совместимы ли зоны: либо не пересекаются, либо оверлап разрешён правилами. */
    public boolean canZonesCoexist(ZoneInfo a, ZoneInfo b) {
        if (a == null || b == null) return true;
        if (!zonesIntersect2D(a, b)) return true;
        return isOverlapAllowed(a, b);
    }

// ---- ВСПОМОГАТЕЛЬНОЕ: конвертация ZoneInfo -> JTS Polygon ----

    private static final GeometryFactory GF = new GeometryFactory();

    private Polygon toJtsPolygon(ZoneInfo z) {
        var pts = z.getCorners();
        if (pts == null || pts.size() < 3) return null;

        // Собираем координаты по XZ и замыкаем кольцо
        Coordinate[] ring = new Coordinate[pts.size() + 1];
        for (int i = 0; i < pts.size(); i++) {
            var L = pts.get(i);
            ring[i] = new Coordinate(L.getX(), L.getZ());
        }
        ring[ring.length - 1] = new Coordinate(pts.getFirst().getX(), pts.getFirst().getZ());

        // JTS требует LinearRing
        LinearRing shell = new LinearRing(new CoordinateArraySequence(ring), GF);
        if (!shell.isValid()) return null;

        return new Polygon(shell, null, GF);
    }

}
