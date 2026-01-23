package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.signs.SignManager;
import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.zones.bluemap.ZoneBlueMapService;
import com.frammy.unitylauncher.zones.geom.ZoneGeometry;
import com.frammy.unitylauncher.zones.io.ZoneYamlRepository;
import com.frammy.unitylauncher.zones.quota.ZoneQuotaService;
import com.frammy.unitylauncher.zones.signs.ZoneSignOwnershipService;
import com.frammy.unitylauncher.zones.geom.ZoneOverlapRules;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.World;

import java.io.File;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public class ZoneManager {

    // ==== DI / State ====
    public final UnityLauncher ul;
    public SignManager signManager;
    public BlueMapIntegration blueMapIntegration;
    public ActivityTracker activityTracker;

    /** временные точки создаваемого полигона по игроку */
    public final Map<UUID, List<Location>> zonePoints = new HashMap<>();
    /** тип зоны, которую сейчас рисует игрок (для контроля addcorner/build) */
    private final Map<UUID, ZoneType> pendingZoneType = new HashMap<>();
    /** все зоны по markerID */
    public final Map<String, ZoneInfo> zoneList = new ConcurrentHashMap<>();
    /** последняя зона игрока (для actionbar/удаления/price) */
    private final Map<UUID, ZoneInfo> playerLastZone = new HashMap<>();

    public final ZoneId zoneId = ZoneId.systemDefault();

    private static final long PRICE_COOLDOWN_MS = 5 * 60_000L;
    private final Map<UUID, Long> lastPriceUse = new ConcurrentHashMap<>();
    private final ZoneBlueMapService blueMapService;

    private static final double Y_MIN = -64, Y_MAX = 255;
    private final ZoneYamlRepository zoneRepo;
    private final ZoneSignOwnershipService signOwnershipService;
    private final ZoneCommands commands;
    private final ZoneValidationService validator;

    /** Иммутабельная копия всех зон для безопасного чтения. */
    public List<ZoneInfo> getAllZonesSnapshot() {
        // zoneList — это Map<String, ZoneInfo> со всеми зонами по markerID
        return new ArrayList<>(zoneList.values());
    }

    // --- КУЛДАУН РЕДАКТИРОВАНИЯ УГЛОВ ДЛЯ COUNTRY и COLONY (персистентно через YAML) ---
    private final Map<String, Long> lastCornersEditByMarker = new ConcurrentHashMap<>();
    private static final long CORNERS_EDIT_COOLDOWN_MS = 2L * 24L * 60L * 60L * 1000L; // 2 суток

    // ==== Command bridges (чтобы команды жили отдельно, а логика тут) ====

    ZoneType getPendingZoneType(UUID playerId) {
        return pendingZoneType.get(playerId);
    }

    void clearPendingBuildState(UUID playerId) {
        zonePoints.remove(playerId);
        pendingZoneType.remove(playerId);
    }

    void addCornerCmd(Player p, ZoneType type) {
        addCorner(p, type);
    }

    void removeCornerCmd(Player p) {
        removeCorner(p);
    }

    boolean buildZoneCountryCmd(Player p) {
        return buildZoneCountry(p);
    }

    boolean buildZoneCmd(Player p, ZoneType type, String name) {
        return buildZone(p, type, name);
    }

    void showPriceCmd(Player p) {
        showPrice(p);
    }

    void removeZoneCmd(Player p) {
        removeZone(p);
    }

    public void handleCommand(Player p, String[] args) {
        commands.handle(p, args);
    }

    public final Map<ZoneType, ZoneTypeData> zoneLimits = new EnumMap<>(ZoneType.class);

    // ==== Lifecycle / IO ====
    public ZoneManager(UnityLauncher plugin, SignManager signManager, BlueMapIntegration blueMapIntegration, ActivityTracker activityTracker) {
        this.ul = plugin;
        this.signManager = signManager;
        this.blueMapIntegration = blueMapIntegration;
        this.activityTracker = activityTracker;

        zoneLimits.put(ZoneType.COUNTRY,    new ZoneTypeData("Государство", 30000.0, 1, 100.0, false, 0.70,  0, "unityLauncher.createZone.country"));
        zoneLimits.put(ZoneType.COLONY,     new ZoneTypeData("Колония",     10000.0, 2,  80.0, false, 0.85,  0, "unityLauncher.createZone.colony"));

        zoneLimits.put(ZoneType.BANK,       new ZoneTypeData("Банк",          300.0, 4,  20.0, false, 1.0, 150, "unityLauncher.createZone.bank"));
        zoneLimits.put(ZoneType.HOSPITAL,   new ZoneTypeData("Госпиталь",     700.0, 4,  15.0, false, 1.0, 200, "unityLauncher.createZone.hospital"));
        zoneLimits.put(ZoneType.INDUSTRIAL, new ZoneTypeData("Промышленная зона", 1000.0, 4, 20.0, false, 1.15, 50, "unityLauncher.createZone.industrial"));
        zoneLimits.put(ZoneType.PARK,       new ZoneTypeData("Парк",         1000.0, 4,  30.0, false, 0.85,  0, "unityLauncher.createZone.park"));
        zoneLimits.put(ZoneType.CHURCH,     new ZoneTypeData("Церковь",       500.0, 4,  10.0, false, 1.0,  20, "unityLauncher.createZone.church"));
        zoneLimits.put(ZoneType.LIBRARY,    new ZoneTypeData("Библиотека",    500.0, 4,  10.0, false, 1.0,  20, "unityLauncher.createZone.library"));
        zoneLimits.put(ZoneType.GREENHOUSE, new ZoneTypeData("Теплица",       900.0, 4,   5.0, false, 1.0,  20, "unityLauncher.createZone.greenhouse"));

        zoneLimits.put(ZoneType.SHOP,       new ZoneTypeData("Торговая точка", 500.0, 10, 3.0, true, 1.0, 10, "unityLauncher.createZone.shop"));

        File zonesFile = new File(plugin.getDataFolder(), "zones.yml");
        this.zoneRepo = new ZoneYamlRepository(zonesFile);

        this.blueMapService = new ZoneBlueMapService(this.blueMapIntegration, this.zoneLimits);
        ZoneQuotaService quotaService = new ZoneQuotaService(this::getAllZonesSnapshot);
        this.signOwnershipService = new ZoneSignOwnershipService(ul, this::getAllZonesSnapshot);
        this.commands = new ZoneCommands(this);
        this.validator = new ZoneValidationService(ul, quotaService, zoneLimits, zoneList);

    }

    public void setSignManager(SignManager signManager) {
        this.signManager = signManager;
    }

    public Collection<ZoneInfo> getZones() { return List.copyOf(zoneList.values()); }

    /** Единый загрузчик из YAML + проставление владельцев табличек. */
    public void loadZonesFromConfig() {
        lastCornersEditByMarker.clear();

        boolean needsSave = zoneRepo.loadInto(zoneList, lastCornersEditByMarker);
        if (needsSave) {
            zoneRepo.saveFrom(zoneList.values(), lastCornersEditByMarker);
        }
    }

    public void scheduleSignOwnershipRecalc(SignManager signManager, int signsPerTick) {
        signOwnershipService.scheduleSignOwnershipRecalc(signManager, signsPerTick);
    }

    public void saveZonesToConfig() {
        zoneRepo.saveFrom(zoneList.values(), lastCornersEditByMarker);
    }

    // ==== NEW: проверка наличия у игрока своей страны ====
    /** true, если у игрока уже есть хотя бы одна зона типа COUNTRY, где он — владелец. */
    public boolean playerHasCountryZone(String owner) {
        if (owner == null || owner.isBlank()) return false;
        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() == ZoneType.COUNTRY && NameUtil.eqCi(owner, z.getOwner())) return true;
        }
        return false;
    }

    // ==== Build / Update ====
    private void addCorner(Player p, ZoneType type) {
        if (type == null || !zoneLimits.containsKey(type)) {
            p.sendMessage(ChatColor.RED + "Неверный тип зоны!");
            return;
        }

        UUID id = p.getUniqueId();

        // Проверяем/фиксируем тип строящейся зоны
        ZoneType existing = pendingZoneType.get(id);
        List<Location> pts = zonePoints.computeIfAbsent(id, k -> new ArrayList<>());

        if (existing != null && existing != type && !pts.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Вы уже строите зону типа " + ChatColor.GOLD + existing +
                    ChatColor.RED + ". Завершите её или очистите точки перед сменой типа.");
            return;
        }
        pendingZoneType.put(id, type);

        // Ранний отказ, чтобы не ставили точки впустую (единая проверка для всех типов)
        if (pts.isEmpty()) {
            var r = validator.validateStartAddCorner(p, type, playerHasCountryZone(p.getName()));
            if (!r.success()) {
                p.sendMessage(r.message());
                pendingZoneType.remove(id);
                return;
            }
        }

        // Мир должен совпадать
        if (!pts.isEmpty() && !pts.getFirst().getWorld().equals(p.getWorld())) {
            p.sendMessage(ChatColor.RED + "Нельзя добавлять точки из разных миров.");
            return;
        }

        // Собираем временный полигон-кандидат
        List<Location> tmp = new ArrayList<>(pts);
        tmp.add(p.getLocation().clone());

        var r2 = validator.validateDraftAfterAddingCorner(p, type, tmp);
        if (!r2.success()) {
            p.sendMessage(r2.message());
            return;
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
    private boolean buildZoneCountry(Player p) {
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (playerCountry == null || playerCountry.isBlank()) {
            p.sendMessage(ChatColor.RED + "Нельзя создать Государство: вы не состоите ни в одной стране.");
            return false;
        }

        if (!ul.countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
            String leader = ul.countryRegistryJdbc.getLeaderOfCountry(playerCountry);
            p.sendMessage(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Государство."
                    + (leader != null ? ChatColor.GRAY + " Лидер: " + leader : ""));
            return false;
        }

        if (playerHasCountryZone(p.getName())) {
            p.sendMessage(ChatColor.RED + "У вас уже есть территория Государства. Сначала удалите существующую.");
            return false;
        }

        List<Location> pts = zonePoints.get(p.getUniqueId());
        if (pts == null || pts.size() < 3) {
            p.sendMessage(ChatColor.RED + "Нужно минимум 3 точки!");
            return false;
        }

        World w0 = pts.getFirst().getWorld();
        if (!pts.stream().allMatch(l -> l.getWorld().equals(w0))) {
            p.sendMessage(ChatColor.RED + "Все точки должны быть в одном мире.");
            return false;
        }
        if (w0.getEnvironment() != World.Environment.NORMAL) {
            p.sendMessage(ChatColor.RED + "Государство можно создавать только в Overworld.");
            return false;
        }

        ZoneInfo candidate = new ZoneInfo(
                ZoneType.COUNTRY,
                "tmp_id",
                playerCountry,
                "tmp_marker",
                pts,
                p.getName(),
                org.bukkit.Color.WHITE
        );

        for (ZoneInfo existing : zoneList.values()) {
            if (!ZoneOverlapRules.canZonesCoexist(candidate, existing, zoneLimits)) {
                p.sendMessage(ChatColor.RED + "Нельзя создать Государство: конфликт с зоной "
                        + ChatColor.GOLD + existing.getName()
                        + ChatColor.RED + " (" + existing.getType() + ").");
                return false;
            }
        }

        String markerID = java.util.UUID.randomUUID().toString();
        String zoneID = "zone_" + markerID;

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
        ZoneInfo created = new ZoneInfo(
                ZoneType.COUNTRY,
                zoneID,
                playerCountry,
                markerID,
                pts,
                p.getName(),
                defaultColor
        );
        created.setOwnerCountry(playerCountry);

        double areaCountry = calculateSurfaceArea(pts);
        ul.countryRegistryJdbc.setCountryAreaPreserveMoney(playerCountry, areaCountry);

        zoneList.put(markerID, created);
        lastCornersEditByMarker.put(markerID, System.currentTimeMillis());

        blueMapService.upsert(created, defaultColor);

        p.sendMessage(ChatColor.GREEN + "Территория страны \"" + playerCountry + "\" создана! ");

        ul.countryRegistryJdbc.ensureInitialAtmAllowance(playerCountry, 5);

        clearPendingBuildState(p.getUniqueId());
        saveZonesToConfig();
        return true;
    }

    /** Создание обычных зон: принадлежат игроку, страна НЕ проставляется при создании. */
    private boolean buildZone(Player p, ZoneType type, String zoneName) {
        List<Location> pts = zonePoints.get(p.getUniqueId());
        var vr = validator.validateBuildZone(p, type, zoneName, pts);
        if (!vr.success()) {
            p.sendMessage(vr.message());
            return false;
        }

        // страна игрока (может быть null)
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());

        // Всё ок — создаём сам объект ZoneInfo
        String markerID = java.util.UUID.randomUUID().toString();
        String zoneID = "zone_" + markerID;

        org.bukkit.Color defaultColor = org.bukkit.Color.fromRGB(255, 0, 0);
        ZoneInfo created = new ZoneInfo(type, zoneID, zoneName, markerID, pts, p.getName(), defaultColor);

        if (playerCountry != null && !playerCountry.isBlank()) {
            created.setOwnerCountry(playerCountry);
        }

        zoneList.put(markerID, created);
        lastCornersEditByMarker.put(markerID, System.currentTimeMillis());

        blueMapService.upsert(created, defaultColor);

        p.sendMessage(ChatColor.GREEN + "Зона \"" + zoneName + "\" создана!");

        // === Если это колония — добавляем её площадь ===
        if (type == ZoneType.COLONY) {
            double colonyArea = calculateSurfaceArea(pts);
            ul.countryRegistryJdbc.addCountryArea(playerCountry, colonyArea);
        }

        clearPendingBuildState(p.getUniqueId());
        saveZonesToConfig();
        return true;
    }

    public void updateZone(Player p, String updateType, String value) {
        ZoneInfo zi = resolvePlayerZoneForUpdate(p);
        if (zi == null) {
            p.sendMessage(ChatColor.RED + "Не удалось определить, какую из ваших зон редактировать. Зайдите в нужную зону ещё раз.");
            return;
        }
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
                    var vr = validator.validateUpdateCornersDraft(p, zi, tmp, true);
                    if (!vr.success()) {
                        p.sendMessage(vr.message());
                        return;
                    }

                    zi.getCorners().add(p.getLocation().clone());
                    p.sendMessage(ChatColor.GOLD + "[" + zi.getCorners().size() + "] " + ChatColor.YELLOW + "Точка добавлена. Площадь: " + ChatColor.GOLD + String.format(Locale.US,"%.2f", calculateSurfaceArea(tmp)));
                } else if ("-".equals(value)) {
                    if (zi.getCorners().size() <= 3) { p.sendMessage(ChatColor.RED + "Минимум 3 точки!"); return; }

                    List<Location> tmp = new ArrayList<>(zi.getCorners());
                    tmp.removeLast();
                    var vr = validator.validateUpdateCornersDraft(p, zi, tmp, false);
                    if (!vr.success()) {
                        p.sendMessage(vr.message());
                        return;
                    }

                    zi.getCorners().removeLast();
                    p.sendMessage(ChatColor.GRAY + "Удалена последняя точка.");

                } else {
                    p.sendMessage(ChatColor.GRAY + "Используйте: /ul zone update corners +  или  -"); return;
                }

                // апдейт цвета/маркер, запись таймстемпа кулдауна
                blueMapService.upsert(zi, zi.getFillColor());

                lastCornersEditByMarker.put(zi.getMarkerID(), System.currentTimeMillis());
                saveZonesToConfig();
            }
            case "name" -> {
                if (zi.getType() == ZoneType.COUNTRY) {
                    p.sendMessage(ChatColor.RED + "Имя государства менять нельзя.");
                    return;
                }
                if (value == null || value.isBlank()) {
                    p.sendMessage(ChatColor.RED + "Название не может быть пустым.");
                    return;
                }

                zi.setName(value);
                blueMapService.upsert(zi, zi.getFillColor());

                p.sendMessage(ChatColor.GREEN + "Название обновлено!");

                // если это SHOP — уведомим SignManager, он обновит листы/таблички сам
                if (zi.getType() == ZoneType.SHOP && signManager != null) {
                    signManager.onShopZoneRenamed();
                }

            }
            case "color" -> {
                String[] rgb = value.split(",");
                if (rgb.length != 3) { p.sendMessage(ChatColor.RED + "Формат: R,G,B"); return; }
                try {
                    org.bukkit.Color c = org.bukkit.Color.fromRGB(
                            Integer.parseInt(rgb[0]), Integer.parseInt(rgb[1]), Integer.parseInt(rgb[2]));
                    zi.setFillColor(c);
                    blueMapService.upsert(zi, c);

                } catch (NumberFormatException nfe) { p.sendMessage(ChatColor.RED + "Только целые числа."); }
            }
            default -> { /* ignore */ }
        }
    }

    // ==== Remove ====
    private void removeZone(Player p) {
        ZoneInfo zi = getZoneAt(p.getLocation());
        if (zi == null || !NameUtil.eqCi(zi.getOwner(), p.getName())) {
            p.sendMessage(ChatColor.RED + "Вы не находитесь в своей зоне!");
            return;
        }

        TextComponent confirm = new TextComponent(ChatColor.GREEN + "[Подтвердить удаление]");
        confirm.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul zone confirmremove " + zi.getID()));
        TextComponent cancel = new TextComponent(ChatColor.RED + "[Отмена]");
        cancel.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul zone cancelremove"));

        p.spigot().sendMessage(new TextComponent(ChatColor.YELLOW + "Удалить зону \"" + ChatColor.GOLD + zi.getName() + ChatColor.YELLOW + "\"? "), confirm, new TextComponent(" "), cancel);
        playerLastZone.put(p.getUniqueId(), zi);
    }

    /** Внутреннее удаление зоны: из памяти + BlueMap + кулдаун-метки. */
    private void removeZoneInternal(ZoneInfo zi) {
        if (zi == null) return;

        zoneList.remove(zi.getMarkerID());
        lastCornersEditByMarker.remove(zi.getMarkerID());

        // BlueMap cleanup
        if (blueMapService != null) {
            blueMapService.remove(zi);
        }
    }

    public void confirmRemoveZone(Player p) {
        ZoneInfo zi = playerLastZone.get(p.getUniqueId());
        if (zi == null) {
            p.sendMessage(ChatColor.RED + "Нет зоны для удаления!");
            return;
        }

        // ЖЁСТКАЯ ПРОВЕРКА ВЛАДЕЛЬЦА
        if (!NameUtil.eqCi(zi.getOwner(), p.getName())) {
            p.sendMessage(ChatColor.RED + "Вы не владелец этой зоны.");
            playerLastZone.remove(p.getUniqueId());
            return;
        }

        // ===== УДАЛЕНИЕ СТРАНЫ С КАСКАДОМ =====
        if (zi.getType() == ZoneType.COUNTRY) {
            // Имя страны (используем helper, чтобы не промахнуться)
            String countryName = zoneCountry(zi);
            if (countryName == null || countryName.isBlank()) {
                // fallback — всё равно что-то выведем
                countryName = zi.getName();
            }
            String normTarget = normCountry(countryName);

            // Сохраняем онлайн-игроков этой страны, чтобы после удаления очистить им LP-префиксы
            Set<UUID> affectedPlayers = Bukkit.getOnlinePlayers().stream()
                    .filter(pl -> {
                        String c = ul.countryRegistryJdbc.getCountryOfPlayer(pl.getName());
                        return c != null && Objects.equals(normCountry(c), normTarget);
                    })
                    .map(Player::getUniqueId)
                    .collect(Collectors.toSet());

            // 0) Широковещалка
            Bukkit.broadcastMessage(
                    ChatColor.RED + "[Уведомление] Государство \"" +
                            ChatColor.GOLD + countryName +
                            ChatColor.RED + "\" было удалено владельцем " +
                            ChatColor.YELLOW + p.getName() + ChatColor.RED + "."
            );

            List<ZoneInfo> toDelete = new ArrayList<>();
            for (ZoneInfo z : new ArrayList<>(zoneList.values())) {
                String zCountry = zoneCountry(z);
                if (zCountry == null || zCountry.isBlank()) continue;
                if (Objects.equals(normCountry(zCountry), normTarget)) {
                    toDelete.add(z);
                }
            }

            // 2) Удаляем все найденные зоны локально
            for (ZoneInfo z : toDelete) {
                removeZoneInternal(z);
            }

            p.sendMessage(ChatColor.GREEN + "Государство \"" + countryName
                    + "\" и все связанные с ним зоны (" + toDelete.size() + " шт.) удалены.");

            // Сбрасываем lastZone игрока и сохраняем YAML
            playerLastZone.remove(p.getUniqueId());
            saveZonesToConfig();

            // 3) Асинхронная очистка по БД — как у тебя было, только используем countryName
            String finalCountryName = countryName;
            Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
                try (Connection conn = UnityLauncher.DBConnect()) {
                    if (conn == null) {
                        throw new RuntimeException("DBConnect() вернул null");
                    }
                    try {
                        conn.setAutoCommit(false);

                        // 1. Чистим отношения дипломатии для этой страны, если DAO есть
                        if (UnityLauncher.getInstance().countryRelationshipDao != null) {
                            UnityLauncher.getInstance().countryRelationshipDao.deleteByCountryTx(conn, finalCountryName);
                        }

                        // 2. Удаляем страну из таблицы Countries + чистим локальный кэш CountryRegistryJdbc
                        UnityLauncher.getInstance().countryRegistryJdbc.deleteCountryTx(conn, finalCountryName);

                        conn.commit();
                    } catch (Exception inner) {
                        try { conn.rollback(); } catch (Exception ignore) {}
                        throw inner;
                    }

                    // После успешного коммита — на главном потоке чистим LP-префиксы и пишем лог
                    Bukkit.getScheduler().runTask(ul, () -> {
                        var prefixService = UnityLauncher.getInstance().luckPermsPrefixService;
                        if (prefixService != null && !affectedPlayers.isEmpty()) {
                            for (UUID uuid : affectedPlayers) {
                                prefixService.clear(uuid);
                            }
                        } else if (prefixService == null && !affectedPlayers.isEmpty()) {
                            Bukkit.getLogger().warning("[UnityLauncher] luckPermsPrefixService == null при удалении страны " + finalCountryName);
                        }

                        Bukkit.getLogger().info("[Zones] Страна \"" + finalCountryName + "\" и её зоны удалены из БД (TX ok).");
                    });

                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(ul, () -> {
                        Bukkit.getLogger().warning("[Zones] Ошибка при удалении страны \"" + finalCountryName + "\" из БД: " + ex.getMessage());
                        p.sendMessage(ChatColor.RED + "Удаление страны в БД завершилось ошибкой, см. консоль.");
                    });
                }
            });

            return;
        }

        // ===== Обычная зона (не страна) =====
        removeZoneInternal(zi);

        p.sendMessage(ChatColor.GREEN + "Зона \"" + zi.getName() + "\" удалена!");
        playerLastZone.remove(p.getUniqueId());

        // Фиксируем YAML
        saveZonesToConfig();
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

        TextComponent msg =
                new TextComponent(ChatColor.GREEN + "Текущая дневная стоимость: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", cost) + "Ⓕ");
        msg.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(hover.toString()).create()));
        p.spigot().sendMessage(msg);
    }

    private String resolveDisplayCountry(ZoneInfo z) {
        if (z == null) return "—";

        // 1) Если зона сама страна — её имя и есть страна
        if (z.getType() == ZoneType.COUNTRY) {
            return (z.getName() != null && !z.getName().isBlank()) ? z.getName() : "—";
        }

        // 2) Если это колония — страна владельца (ownerCountry) или имя зоны-страны (fallback)
        if (z.getType() == ZoneType.COLONY) {
            String c = z.getCountryName();
            if (c != null && !c.isBlank()) return c;
            // На всякий случай — fallback к имени
            return (z.getName() != null && !z.getName().isBlank()) ? z.getName() : "—";
        }

        // 3) Внутренняя зона: ищем родителя (COUNTRY/COLONY), который полностью содержит полигоны z
        try {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(z.getCorners(), Set.of(ZoneType.COUNTRY, ZoneType.COLONY), zoneList.values());
            if (parent != null) {
                String pc = zoneCountry(parent); // уже есть статический helper ниже в файле
                if (pc != null && !pc.isBlank()) return pc;
                // для COUNTRY zoneCountry(parent) возвращает имя зоны, так что сюда редко попадём
                return (parent.getName() != null && !parent.getName().isBlank()) ? parent.getName() : "—";
            }
        } catch (Throwable ignored) { /* защитный контур — ничего страшного, покажем "—" */ }

        // 4) Магазины и прочее вне стран: если у самой зоны задан ownerCountry — используем его
        String own = z.getCountryName();
        return (own != null && !own.isBlank()) ? own : "—";
    }

    // ==== Player location → zone ====
    public void checkPlayerZone(Player p) {
        ZoneInfo prev = playerLastZone.get(p.getUniqueId());
        ZoneInfo next = getZoneAt(p.getLocation());

        if (Objects.equals(prev, next)) return;

        String barText;

        if (next == null) {
            // Вышли из любых зон
            barText = ChatColor.DARK_GRAY + "Зона: " + ChatColor.GRAY + "—";
        } else {
            ZoneType type = next.getType();
            String country = resolveDisplayCountry(next); // страна/колония, если есть

            switch (type) {
                case COUNTRY -> {
                    // Вход в страну: показываем только название страны
                    String name = (country != null && !country.isBlank())
                            ? country
                            : next.getName();
                    barText = ChatColor.DARK_GREEN + "Страна: " + ChatColor.GREEN + name;
                }
                case SHOP -> {
                    // Вход в магазин: только название магазина (опционально можно подсунуть страну в скобках)
                    barText = ChatColor.GOLD + "Магазин: " + ChatColor.YELLOW + next.getName();
                    if (country != null && !country.isBlank()) {
                        barText += ChatColor.GRAY + " (" +
                                ChatColor.DARK_GREEN + "Страна: " +
                                ChatColor.GREEN + country +
                                ChatColor.GRAY + ")";
                    }
                }
                default -> {
                    // Вход в обычную зону внутри страны/колонии:
                    // показываем название зоны + страну, в которой она находится
                    ZoneTypeData ztd = zoneLimits.get(type);
                    String typeName = (ztd != null ? ztd.displayName() : type.name());

                    barText = ChatColor.GOLD + typeName + ": " +
                            ChatColor.YELLOW + next.getName();

                    if (country != null && !country.isBlank()) {
                        barText += ChatColor.GRAY + " | " +
                                ChatColor.DARK_GREEN + "Страна: " +
                                ChatColor.GREEN + country;
                    }
                }
            }
        }

        // Только маленькая надпись снизу, без Title
        p.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent(barText)
        );

        playerLastZone.put(p.getUniqueId(), next);
    }

    public ZoneInfo getZoneAt(Location loc) {
        if (loc == null) return null;
        World w = loc.getWorld();
        if (w == null) return null;

        return zoneList.values().stream()
                .filter(z -> ZoneGeometry.worldOk(z.getCorners(), w) && ZoneGeometry.pointInZone(loc, z.getCorners(), Y_MIN, Y_MAX))
                .max(Comparator.comparingInt((ZoneInfo z) -> {
                    ZoneTypeData d = zoneLimits.get(z.getType());
                    return d != null ? d.index() : Integer.MIN_VALUE;
                }))
                .orElse(null);
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

    /**
     * Специальный резолвер зоны именно для /ul zone update:
     *
     * 1) Если есть lastZone → берём её (можно редактировать хоть снаружи).
     * 2) Иначе пробуем зону под игроком (getZoneAt), но только если он её владелец.
     * 3) Если всё ещё ничего нет — если у игрока ровно ОДНА зона-владелец, берём её.
     *    Если их несколько, возвращаем null (чтобы не гадать, какую именно он хотел).
     */
    private ZoneInfo resolvePlayerZoneForUpdate(Player p) {
        // 1) Последняя запомненная зона игрока
        ZoneInfo zi = playerLastZone.get(p.getUniqueId());
        if (zi != null && zi.getMarkerID() != null && zoneList.containsKey(zi.getMarkerID())) {
            return zi;
        }

        // 2) Зона "под ногами", если он её владелец
        ZoneInfo here = getZoneAt(p.getLocation());
        if (here != null && NameUtil.eqCi(here.getOwner(), p.getName())) {
            return here;
        }

        // 3) Фоллбек: если у игрока ровно одна своя зона — используем её
        ZoneInfo owned = null;
        for (ZoneInfo z : zoneList.values()) {
            if (!NameUtil.eqCi(z.getOwner(), p.getName())) continue;
            if (owned != null) {
                // Больше одной зоны — непонятно, какую редактировать
                return null;
            }
            owned = z;
        }
        return owned;
    }

    // ---- ЕДИНЫЙ ПРАВИЛЬНЫЙ ХЕЛПЕР ДЛЯ ПРАВИЛ ПЕРЕСЕЧЕНИЙ ----

    private static String normCountry(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        t = t.replace(' ', '_');
        return t.replaceAll("[^a-z0-9_\\-.]", "");
    }

    /** Страна зоны: сначала ownerCountry, для COUNTRY — fallback к имени зоны */
    private static String zoneCountry(ZoneInfo z) {
        if (z == null) return null;
        String c = z.getCountryName();
        if (c != null && !c.isBlank()) return c;
        return (z.getType() == ZoneType.COUNTRY) ? z.getName() : null;
    }

    public static final class NameUtil {
        public static boolean eqCi(String a, String b) {
            return a != null && a.equalsIgnoreCase(b);
        }
    }

    /** Возвращает SHOP-зону, которая целиком содержит loc, иначе null. */
    public ZoneInfo getShopZoneAt(Location loc) {
        if (loc == null) return null;
        World w = loc.getWorld();
        if (w == null) return null;
        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() != ZoneType.SHOP) continue;
            if (!ZoneGeometry.worldOk(z.getCorners(), w)) continue;
            if (ZoneGeometry.pointInZone(loc, z.getCorners(), Y_MIN, Y_MAX)) return z;
        }
        return null;
    }

    /** true, если loc находится в SHOP-зоне, владельцем которой является playerName (case-insensitive). */
    public boolean isPlayerOwnerOfShopZoneAt(String playerName, Location loc) {
        ZoneInfo shop = getShopZoneAt(loc);
        return shop != null && NameUtil.eqCi(shop.getOwner(), playerName);
    }

    // ==== Country helpers for upgrades ====

    /**
     * Чья страна/колония в этой точке.
     * Возвращает "живое" имя страны (как в БД/зоне), БЕЗ нормализации.
     * Может вернуть null, если точка вне стран/колоний.
     */
    public String getCountryAt(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;

        ZoneInfo here = getZoneAt(loc);
        if (here == null) return null;

        // 1) Если зона сама страна или колония — берём её страну
        String c = zoneCountry(here);
        if (c != null && !c.isBlank()) {
            return c;
        }

        // 2) Если это внутренняя зона без ownerCountry —
        //    ищем родителя COUNTRY / COLONY, который её целиком содержит
        try {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(
                    here.getCorners(),
                    java.util.Set.of(ZoneType.COUNTRY, ZoneType.COLONY),
                    zoneList.values()
            );
            if (parent != null) {
                String pc = zoneCountry(parent);
                if (pc != null && !pc.isBlank()) return pc;
            }
        } catch (Throwable ignored) {
            // в худшем случае вернём null
        }

        // 3) fallback: если у самой зоны был ownerCountry, но zoneCountry не вернул — на всякий
        String own = here.getCountryName();
        return (own != null && !own.isBlank()) ? own : null;
    }

    /**
     * Та же страна, но уже нормализованная (как normCountry в этом классе).
     * Удобно для сравнения и вызовов countryMaxLevel(...).
     */
    public String getCountryCanonicalAt(Location loc) {
        String c = getCountryAt(loc);
        return c != null ? normCountry(c) : null;
    }

}
