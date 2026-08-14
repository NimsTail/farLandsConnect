package com.frammy.unitylauncher.zones;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;
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

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import com.frammy.unitylauncher.zones.web.ZoneWebRequestService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import com.google.gson.JsonArray;
import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public class ZoneManager implements com.frammy.unitylauncher.zones.web.ZoneWebRequestHandler {

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
    private final ZoneQuotaService quotaService;

    /** Типы, до которых можно бесплатно "повысить" PLOT (Участок) — при наличии квоты и подходящей площади. */
    private static final EnumSet<ZoneType> UPGRADEABLE_FROM_PLOT = EnumSet.of(
            ZoneType.SHOP, ZoneType.BANK, ZoneType.HOSPITAL, ZoneType.INDUSTRIAL,
            ZoneType.PARK, ZoneType.CHURCH, ZoneType.LIBRARY, ZoneType.GREENHOUSE,
            ZoneType.MILITARY
    );

    /** Иммутабельная копия всех зон для безопасного чтения. */
    public List<ZoneInfo> getAllZonesSnapshot() {
        // zoneList — это Map<String, ZoneInfo> со всеми зонами по markerID
        return new ArrayList<>(zoneList.values());
    }
    public record ZoneOpResult(boolean success, String message, String markerId) {
        public static ZoneOpResult ok(String msg, String markerId) { return new ZoneOpResult(true, msg, markerId); }
        public static ZoneOpResult fail(String msg) { return new ZoneOpResult(false, msg, null); }
    }

    // --- КУЛДАУН РЕДАКТИРОВАНИЯ УГЛОВ ДЛЯ COUNTRY и COLONY (персистентно через YAML) ---
    private final Map<String, Long> lastCornersEditByMarker = new ConcurrentHashMap<>();
    private static final long CORNERS_EDIT_COOLDOWN_MS = 2L * 24L * 60L * 60L * 1000L; // 2 суток

    // --- КУЛДАУН СМЕНЫ ИМЕНИ/ЦВЕТА ЗОНЫ (только в памяти — сбрасывается при
    // рестарте сервера; ниже риск, чем у границ, поэтому не стали городить
    // отдельную персистентность через ZoneYamlRepository) ---
    private final Map<String, Long> lastNameColorEditByMarker = new ConcurrentHashMap<>();
    private static final long NAME_COLOR_EDIT_COOLDOWN_MS = 24L * 60L * 60L * 1000L; // 1 сутки

    /**
     * Оставшееся время кулдауна на изменение территории (границы/добавление
     * фигуры) в мс, 0 если можно редактировать прямо сейчас. Только для
     * COUNTRY/COLONY — у остальных типов зон такого кулдауна нет. Публично,
     * чтобы ZoneRequestPoller мог отдать это в zones_sync-снапшоте для сайта
     * (Менеджер зон заранее блокирует кнопки вместо того, чтобы игрок узнавал
     * про кулдаун только после отклонённой заявки).
     */
    public long cornersCooldownRemainingMs(ZoneInfo z) {
        if (z == null || (z.getType() != ZoneType.COUNTRY && z.getType() != ZoneType.COLONY)) return 0;
        Long last = lastCornersEditByMarker.get(z.getMarkerID());
        if (last == null) return 0;
        return Math.max(0, CORNERS_EDIT_COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

    /** Оставшееся время кулдауна на смену имени/цвета в мс, 0 если можно прямо сейчас — см. cornersCooldownRemainingMs. */
    public long nameColorCooldownRemainingMs(ZoneInfo z) {
        if (z == null) return 0;
        Long last = lastNameColorEditByMarker.get(z.getMarkerID());
        if (last == null) return 0;
        return Math.max(0, NAME_COLOR_EDIT_COOLDOWN_MS - (System.currentTimeMillis() - last));
    }

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

    void addMemberCmd(Player p, String targetPlayer) {
        ZoneInfo zi = resolvePlayerZoneForUpdate(p);
        if (zi == null) { p.sendMessage(ChatColor.RED + "Не удалось определить, какую из ваших зон редактировать. Зайдите в нужную зону ещё раз."); return; }
        ZoneOpResult r = addMemberCore(p.getName(), zi, targetPlayer);
        if (r.message() != null) p.sendMessage(r.message());
    }

    void removeMemberCmd(Player p, String targetPlayer) {
        ZoneInfo zi = resolvePlayerZoneForUpdate(p);
        if (zi == null) { p.sendMessage(ChatColor.RED + "Не удалось определить, какую из ваших зон редактировать. Зайдите в нужную зону ещё раз."); return; }
        ZoneOpResult r = removeMemberCore(p.getName(), zi, targetPlayer);
        if (r.message() != null) p.sendMessage(r.message());
    }

    void transferOwnerCmd(Player p, String targetPlayer) {
        ZoneInfo zi = resolvePlayerZoneForUpdate(p);
        if (zi == null) { p.sendMessage(ChatColor.RED + "Не удалось определить, какую из ваших зон редактировать. Зайдите в нужную зону ещё раз."); return; }
        ZoneOpResult r = transferOwnershipCore(p.getName(), zi, targetPlayer);
        if (r.message() != null) p.sendMessage(r.message());
    }

    // military-diplomacy-design.md §4.1/§14.2, GH#24 п.2-3 — переключить
    // "главный тип" (специализацию) военного объекта. Временная in-game
    // команда — когда военная карта на сайте (GH#24 п.4) научится слать
    // это же действие через zone-request очередь, дропдаун там должен
    // вызывать ровно этот же MilitarySpecializationService.requestSwitch,
    // просто с другой точки входа.
    void militarySpecializeCmd(Player p, com.frammy.unitylauncher.military.MilitarySpecialization target) {
        ZoneInfo zi = resolvePlayerZoneForUpdate(p);
        if (zi == null) {
            p.sendMessage(ChatColor.RED + "Не удалось определить, какую из ваших зон редактировать. Зайдите в нужную зону ещё раз.");
            return;
        }
        if (zi.getType() != ZoneType.MILITARY) {
            p.sendMessage(ChatColor.RED + "Специализация есть только у военных объектов.");
            return;
        }
        if (!NameUtil.eqCi(zi.getOwner(), p.getName())) {
            p.sendMessage(ChatColor.RED + "Вы не владелец этого объекта.");
            return;
        }

        var outcome = com.frammy.unitylauncher.UnityLauncher.getInstance().militarySpecializationService.requestSwitch(zi, target);
        p.sendMessage((outcome.success() ? ChatColor.GREEN : ChatColor.RED) + outcome.message());
    }

    // Веб-заявка (сайт → эта же логика, что и militarySpecializeCmd, только
    // вход по UUID+markerId вместо Player+"зона под ногами" — сайт уже знает
    // ТОЧНО какую зону редактирует, резолвить её не нужно.
    private ZoneOpResult setMilitarySpecializationWebCore(UUID playerUuid, String markerId, String specializationRaw) {
        String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
        if (playerName == null) return ZoneOpResult.fail("Не удалось определить игрока по UUID.");
        if (markerId == null) return ZoneOpResult.fail("Не указана зона.");
        ZoneInfo zi = zoneList.get(markerId);
        if (zi == null) return ZoneOpResult.fail("Зона не найдена.");
        if (zi.getType() != ZoneType.MILITARY) return ZoneOpResult.fail("Специализация есть только у военных объектов.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) return ZoneOpResult.fail("Вы не владелец этого объекта.");

        com.frammy.unitylauncher.military.MilitarySpecialization target;
        try {
            target = com.frammy.unitylauncher.military.MilitarySpecialization.valueOf(String.valueOf(specializationRaw));
        } catch (Exception ex) {
            return ZoneOpResult.fail("Неизвестная специализация: " + specializationRaw);
        }

        var outcome = com.frammy.unitylauncher.UnityLauncher.getInstance().militarySpecializationService.requestSwitch(zi, target);
        return outcome.success() ? ZoneOpResult.ok(outcome.message(), markerId) : ZoneOpResult.fail(outcome.message());
    }

    // GH#24 (фидбек 2026-08-14 п.1/4) — тот же паттерн, что setMilitarySpecializationWebCore выше, одним уровнем ниже.
    private ZoneOpResult setMilitaryDefenseSubtypeWebCore(UUID playerUuid, String markerId, String subtypeRaw) {
        String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
        if (playerName == null) return ZoneOpResult.fail("Не удалось определить игрока по UUID.");
        if (markerId == null) return ZoneOpResult.fail("Не указана зона.");
        ZoneInfo zi = zoneList.get(markerId);
        if (zi == null) return ZoneOpResult.fail("Зона не найдена.");
        if (zi.getType() != ZoneType.MILITARY) return ZoneOpResult.fail("Тип обороны есть только у военных объектов.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) return ZoneOpResult.fail("Вы не владелец этого объекта.");

        com.frammy.unitylauncher.military.MilitaryDefenseSubtype target;
        try {
            target = com.frammy.unitylauncher.military.MilitaryDefenseSubtype.valueOf(String.valueOf(subtypeRaw));
        } catch (Exception ex) {
            return ZoneOpResult.fail("Неизвестный тип обороны: " + subtypeRaw);
        }

        var outcome = com.frammy.unitylauncher.UnityLauncher.getInstance().militaryDefenseSubtypeService.requestSwitch(zi, target);
        return outcome.success() ? ZoneOpResult.ok(outcome.message(), markerId) : ZoneOpResult.fail(outcome.message());
    }

    public void handleCommand(Player p, String[] args) {
        commands.handle(p, args);
    }

    public final Map<ZoneType, ZoneTypeData> zoneLimits = new EnumMap<>(ZoneType.class);

    // ==== Докупаемое расширение территории Государства (см. upgrades_country.json: 9_territoryExpansion) ====
    private static final String COUNTRY_TERRITORY_BONUS_PERM = "unity.zone.territory_bonus";
    private static final int COUNTRY_TERRITORY_BONUS_MAX_LEVEL = 5;
    private static final double COUNTRY_TERRITORY_BONUS_PER_LEVEL = 5000.0; // блоков² за уровень

    /** Эффективный лимит площади для типа с учётом докупленного бонуса страны (актуально только для COUNTRY). */
    public double countryAreaLimitFor(ZoneType type, String countryName) {
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return 0;
        if (type != ZoneType.COUNTRY || countryName == null || countryName.isBlank()) return ztd.areaLimit();

        int lvl = com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel(
                com.frammy.unitylauncher.upgrades.UpgradeCondition.resolveCountryGroupId(countryName),
                COUNTRY_TERRITORY_BONUS_PERM, COUNTRY_TERRITORY_BONUS_MAX_LEVEL);
        return ztd.areaLimit() + lvl * COUNTRY_TERRITORY_BONUS_PER_LEVEL;
    }

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
        // infra/military-diplomacy-design.md §3.1: находится только в стране/колонии,
        // как HOSPITAL/BANK — квота/лимиты по той же логике, чисел пока не утверждено,
        // взяты на паритет с HOSPITAL.
        zoneLimits.put(ZoneType.MILITARY,   new ZoneTypeData("Военный объект", 700.0, 4,  15.0, false, 1.0, 200, "unityLauncher.createZone.military"));

        zoneLimits.put(ZoneType.SHOP,       new ZoneTypeData("Торговая точка", 500.0, 10, 3.0, true, 1.0, 10, "unityLauncher.createZone.shop"));
        zoneLimits.put(ZoneType.PLOT,       new ZoneTypeData("Участок",        500.0, 10, 3.0, true, 1.0, 10, "unityLauncher.createZone.plot"));

        File zonesFile = new File(plugin.getDataFolder(), "zones.yml");
        this.zoneRepo = new ZoneYamlRepository(zonesFile);

        this.blueMapService = new ZoneBlueMapService(this.blueMapIntegration, this.zoneLimits);
        this.quotaService = new ZoneQuotaService(this::getAllZonesSnapshot);
        this.signOwnershipService = new ZoneSignOwnershipService(ul, this::getAllZonesSnapshot);
        this.commands = new ZoneCommands(this);
        this.validator = new ZoneValidationService(ul, quotaService, zoneLimits, zoneList);

    }

    public ZoneQuotaService getQuotaService() {
        return quotaService;
    }

    public void setSignManager(SignManager signManager) {
        this.signManager = signManager;
    }

    public Collection<ZoneInfo> getZones() { return List.copyOf(zoneList.values()); }

    /** Единый загрузчик из YAML + проставление владельцев табличек. */
    // Устанавливается один раз, после первой РЕАЛЬНОЙ загрузки зон (см.
    // LazyBlueMapLoader) — нужно, чтобы отличать "зоны ещё не подгрузились
    // при старте, подожди" от "зоны загружены, но тут их правда нет"
    // (например, табличка SHOP ставится вне какой-либо SHOP-зоны). До
    // появления этого флага обе ситуации давали игроку одно и то же
    // (неверное) сообщение "зоны не загружены".
    private volatile boolean zonesLoadedOnce = false;

    public void loadZonesFromConfig() {
        lastCornersEditByMarker.clear();

        boolean needsSave = zoneRepo.loadInto(zoneList, lastCornersEditByMarker);
        if (needsSave) {
            zoneRepo.saveFrom(zoneList.values(), lastCornersEditByMarker);
        }
        zonesLoadedOnce = true;
    }

    public boolean zonesReady() { return zonesLoadedOnce; }

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
            var r = validator.validateStartAddCorner(p.getUniqueId(), p.getName(), p.getWorld(), type, playerHasCountryZone(p.getName()));
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
    // команда — тонкая обёртка, поведение не меняется вообще
    private boolean buildZoneCountry(Player p) {
        List<Location> pts = zonePoints.get(p.getUniqueId());
        ZoneOpResult r = buildZoneCountryCore(p.getName(), pts == null ? List.of() : List.of(pts), null);
        if (r.message() != null) p.sendMessage(r.message());
        return r.success();
    }

    /**
     * Ядро создания территории Государства — переиспользуется командой и веб-обработчиком.
     * Поддерживает МУЛЬТИ-ПОЛИГОН (несколько отдельных эксклавов территории сразу),
     * в отличие от Колонии, которая принципиально однополигональна.
     */
    private ZoneOpResult buildZoneCountryCore(String playerName, List<List<Location>> shapes, String colorHex) {
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
        if (playerCountry == null || playerCountry.isBlank()) {
            // страна могла быть создана через сайт секунды назад — кэш стран (TTL 5с)
            // на главном потоке ещё не успел обновиться асинхронно. Форсируем
            // синхронный рефреш и пробуем ещё раз, прежде чем окончательно отказать.
            ul.countryRegistryJdbc.forceRefreshBlocking();
            playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
        }
        if (playerCountry == null || playerCountry.isBlank()) {
            return ZoneOpResult.fail(ChatColor.RED + "Нельзя создать Государство: вы не состоите ни в одной стране.");
        }
        if (!ul.countryRegistryJdbc.isCountryLeaderCached(playerName)) {
            String leader = ul.countryRegistryJdbc.getLeaderOfCountry(playerCountry);
            return ZoneOpResult.fail(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Государство."
                    + (leader != null ? ChatColor.GRAY + " Лидер: " + leader : ""));
        }
        if (playerHasCountryZone(playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "У вас уже есть территория Государства. Сначала удалите существующую.");
        }
        if (shapes == null || shapes.isEmpty() || shapes.get(0) == null || shapes.get(0).size() < 3) {
            return ZoneOpResult.fail(ChatColor.RED + "Нужно минимум 3 точки!");
        }
        for (List<Location> shape : shapes) {
            if (shape == null || shape.size() < 3) {
                return ZoneOpResult.fail(ChatColor.RED + "Каждая часть территории должна иметь минимум 3 точки!");
            }
        }

        List<Location> firstShape = shapes.get(0);
        World w0 = firstShape.getFirst().getWorld();
        for (List<Location> shape : shapes) {
            if (!shape.stream().allMatch(l -> l.getWorld().equals(w0))) {
                return ZoneOpResult.fail(ChatColor.RED + "Все точки должны быть в одном мире.");
            }
        }
        if (w0.getEnvironment() != World.Environment.NORMAL) {
            return ZoneOpResult.fail(ChatColor.RED + "Государство можно создавать только в Overworld.");
        }

        // части территории (эксклавы) не должны пересекаться между собой и должны
        // соблюдать общий зазор/разброс (та же логика, что и ADD_SHAPE для обычных зон)
        if (shapes.size() > 1) {
            for (int i = 1; i < shapes.size(); i++) {
                List<Location> shape = shapes.get(i);
                List<List<Location>> prior = shapes.subList(0, i);

                var newPoly = com.frammy.unitylauncher.zones.geom.ZoneGeometry.toJtsPolygon(shape);
                for (List<Location> priorShape : prior) {
                    var priorPoly = com.frammy.unitylauncher.zones.geom.ZoneGeometry.toJtsPolygon(priorShape);
                    if (newPoly != null && priorPoly != null && newPoly.intersects(priorPoly)) {
                        return ZoneOpResult.fail(ChatColor.RED + "Части территории Государства не должны пересекаться между собой.");
                    }
                }

                String spacingErr = com.frammy.unitylauncher.zones.geom.ZoneGeometry.checkShapeSpacing(shape, prior);
                if (spacingErr != null) {
                    return ZoneOpResult.fail(ChatColor.RED + spacingErr);
                }
            }
        }

        ZoneInfo candidate = new ZoneInfo(ZoneType.COUNTRY, "tmp_id", playerCountry, "tmp_marker", firstShape, playerName, org.bukkit.Color.WHITE);
        for (int i = 1; i < shapes.size(); i++) candidate.addShape(shapes.get(i));
        for (ZoneInfo existing : zoneList.values()) {
            if (!ZoneOverlapRules.canZonesCoexist(candidate, existing, zoneLimits)) {
                return ZoneOpResult.fail(ChatColor.RED + "Нельзя создать Государство: конфликт с зоной "
                        + ChatColor.GOLD + existing.getName() + ChatColor.RED + " (" + existing.getType() + ").");
            }
        }

        String markerID = java.util.UUID.randomUUID().toString();
        String zoneID = "zone_" + markerID;
        org.bukkit.Color chosenColor = parseColorHexOrDefault(colorHex);
        ZoneInfo created = new ZoneInfo(ZoneType.COUNTRY, zoneID, playerCountry, markerID, firstShape, playerName, chosenColor);
        for (int i = 1; i < shapes.size(); i++) created.addShape(shapes.get(i));
        created.setOwnerCountry(playerCountry);

        double areaCountry = com.frammy.unitylauncher.zones.geom.ZoneGeometry.totalArea(shapes);
        ul.countryRegistryJdbc.setCountryAreaPreserveMoney(playerCountry, areaCountry);

        zoneList.put(markerID, created);
        lastCornersEditByMarker.put(markerID, System.currentTimeMillis());
        blueMapService.upsert(created, chosenColor);
        ul.countryRegistryJdbc.ensureInitialAtmAllowance(playerCountry, 5);
        syncZoneToWebView(created); // см. ниже

        saveZonesToConfig();
        return ZoneOpResult.ok(ChatColor.GREEN + "Территория страны \"" + playerCountry + "\" создана!", markerID);
    }

    /** Создание обычных зон: принадлежат игроку, страна НЕ проставляется при создании. */
    private boolean buildZone(Player p, ZoneType type, String zoneName) {
        List<Location> pts = zonePoints.get(p.getUniqueId());
        ZoneOpResult r = buildZoneCore(p.getUniqueId(), p.getName(), type, zoneName, pts, null);
        if (r.message() != null) p.sendMessage(r.message());
        if (r.success()) clearPendingBuildState(p.getUniqueId());
        return r.success();
    }

    private ZoneOpResult buildZoneCore(UUID playerUuid, String playerName, ZoneType type, String zoneName, List<Location> pts, String colorHex) {
        return buildZoneCore(playerUuid, playerName, type, zoneName, pts, colorHex, true);
    }

    /**
     * @param syncToWeb writing the async DB row (zones_view) here can race with a
     *                  second write done later for the same marker_id (see
     *                  buildZoneCoreMulti, которая добавляет доп. фигуры сразу после
     *                  создания) — тогда какая запись "выиграет" не гарантировано,
     *                  и extra_shapes может молча потеряться. Передавайте false,
     *                  если вызывающий код сам вызовет syncZoneToWebView() один раз,
     *                  уже после того как зона полностью собрана.
     */
    private ZoneOpResult buildZoneCore(UUID playerUuid, String playerName, ZoneType type, String zoneName, List<Location> pts, String colorHex, boolean syncToWeb) {
        var vr = validator.validateBuildZone(playerUuid, playerName, type, zoneName, pts);
        if (!vr.success()) {
            return ZoneOpResult.fail(vr.message());
        }

        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);

        String markerID = java.util.UUID.randomUUID().toString();
        String zoneID = "zone_" + markerID;

        org.bukkit.Color color = parseColorHexOrDefault(colorHex);
        ZoneInfo created = new ZoneInfo(type, zoneID, zoneName, markerID, pts, playerName, color);

        if (playerCountry != null && !playerCountry.isBlank()) {
            created.setOwnerCountry(playerCountry);
        }

        zoneList.put(markerID, created);
        lastCornersEditByMarker.put(markerID, System.currentTimeMillis());
        blueMapService.upsert(created, color);
        if (syncToWeb) syncZoneToWebView(created);

        if (type == ZoneType.COLONY) {
            double colonyArea = calculateSurfaceArea(pts);
            ul.countryRegistryJdbc.addCountryArea(playerCountry, colonyArea);
        }

        saveZonesToConfig();
        return ZoneOpResult.ok(ChatColor.GREEN + "Зона \"" + zoneName + "\" создана!", markerID);
    }

    private static org.bukkit.Color parseColorHexOrDefault(String hex) {
        if (hex == null || hex.isBlank()) return org.bukkit.Color.fromRGB(255, 0, 0);
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            if (h.length() != 6) return org.bukkit.Color.fromRGB(255, 0, 0);
            return org.bukkit.Color.fromRGB(Integer.parseInt(h, 16));
        } catch (Exception e) {
            return org.bukkit.Color.fromRGB(255, 0, 0);
        }
    }

    public void updateZone(Player p, String updateType, String value) {
        ZoneInfo zi = resolvePlayerZoneForUpdate(p);
        if (zi == null) {
            p.sendMessage(ChatColor.RED + "Не удалось определить, какую из ваших зон редактировать. Зайдите в нужную зону ещё раз.");
            return;
        }
        playerLastZone.put(p.getUniqueId(), zi);

        ZoneOpResult r = switch (updateType) {
            case "corners" -> {
                if ("+".equals(value)) {
                    List<Location> tmp = new ArrayList<>(zi.getCorners());
                    tmp.add(p.getLocation().clone());
                    yield updateCornersCore(p.getName(), zi, tmp, true);
                } else if ("-".equals(value)) {
                    if (zi.getCorners().size() <= 3) yield ZoneOpResult.fail(ChatColor.RED + "Минимум 3 точки!");
                    List<Location> tmp = new ArrayList<>(zi.getCorners());
                    tmp.removeLast();
                    yield updateCornersCore(p.getName(), zi, tmp, false);
                } else {
                    yield ZoneOpResult.fail(ChatColor.GRAY + "Используйте: /ul zone update corners +  или  -");
                }
            }
            case "name" -> updateNameCore(p.getName(), zi, value);
            case "color" -> updateColorCore(p.getName(), zi, value);
            default -> ZoneOpResult.fail(null);
        };

        if (r.message() != null) p.sendMessage(r.message());
    }
    /** isPlus сохранён для совместимости с командой (+ добавляет точку, - убирает),
     но геометрическая проверка одна и та же — полный пересчёт нового контура. */
    private ZoneOpResult updateCornersCore(String playerName, ZoneInfo zi, List<Location> newCorners, boolean isPlus) {
        return updateCornersCore(playerName, zi, newCorners, isPlus, 0);
    }

    /** shapeIndex — какую ИМЕННО фигуру многофигурной зоны редактируем (0 = основная/единственная у обычных зон). */
    private ZoneOpResult updateCornersCore(String playerName, ZoneInfo zi, List<Location> newCorners, boolean isPlus, int shapeIndex) {
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }

        if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
            long now = System.currentTimeMillis();
            long last = lastCornersEditByMarker.getOrDefault(zi.getMarkerID(), 0L);
            long left = CORNERS_EDIT_COOLDOWN_MS - (now - last);
            if (left > 0) {
                long hrs = (left + 3_600_000L - 1) / 3_600_000L;
                return ZoneOpResult.fail(ChatColor.RED + "Изменение углов доступно через ~" + ChatColor.YELLOW + hrs + ChatColor.RED + " ч.");
            }
        }

        LocalDate today = LocalDate.now(zoneId);
        double due = zi.getDueSinceLastBill(today);
        int days = zi.getDueDaysCount(today);
        String billedNote = null;
        if (due > 0) {
            try {
                zi.markBilled(today);
                billedNote = ChatColor.GRAY + "Списано за " + days + " дн.: " + ChatColor.GOLD + String.format(Locale.US, "%.2f", due) + ". ";
            } catch (Exception ex) {
                return ZoneOpResult.fail(ChatColor.RED + "Недостаточно средств: " + ex.getMessage());
            }
        }

        var vr = validator.validateUpdateCornersDraft(playerName, zi, newCorners, isPlus, shapeIndex);
        if (!vr.success()) {
            return ZoneOpResult.fail(vr.message());
        }

        zi.setShapeAt(shapeIndex, newCorners);
        blueMapService.upsert(zi, zi.getFillColor());
        lastCornersEditByMarker.put(zi.getMarkerID(), System.currentTimeMillis());
        syncZoneToWebView(zi);
        saveZonesToConfig();

        String note = (billedNote != null ? billedNote : "");
        return ZoneOpResult.ok(note + ChatColor.GREEN + "Границы обновлены. Точек: " + newCorners.size(), zi.getMarkerID());
    }

    /** Общая проверка кулдауна смены имени/цвета — 1/сутки, независимо от кулдауна на границы. */
    private ZoneOpResult checkNameColorCooldown(ZoneInfo zi) {
        long now = System.currentTimeMillis();
        long last = lastNameColorEditByMarker.getOrDefault(zi.getMarkerID(), 0L);
        long left = NAME_COLOR_EDIT_COOLDOWN_MS - (now - last);
        if (left > 0) {
            long hrs = (left + 3_600_000L - 1) / 3_600_000L;
            return ZoneOpResult.fail(ChatColor.RED + "Смена имени/цвета доступна через ~" + ChatColor.YELLOW + hrs + ChatColor.RED + " ч.");
        }
        return null;
    }

    private ZoneOpResult updateNameCore(String playerName, ZoneInfo zi, String newName) {
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }
        if (zi.getType() == ZoneType.COUNTRY) {
            return ZoneOpResult.fail(ChatColor.RED + "Имя государства менять нельзя.");
        }
        if (newName == null || newName.isBlank()) {
            return ZoneOpResult.fail(ChatColor.RED + "Название не может быть пустым.");
        }
        ZoneOpResult cooldownFail = checkNameColorCooldown(zi);
        if (cooldownFail != null) return cooldownFail;

        zi.setName(newName);
        blueMapService.upsert(zi, zi.getFillColor());
        syncZoneToWebView(zi);
        saveZonesToConfig(); // в оригинальной команде этого вызова не было — похоже на недочёт, добавил сюда
        lastNameColorEditByMarker.put(zi.getMarkerID(), System.currentTimeMillis());

        if (zi.getType() == ZoneType.SHOP && signManager != null) {
            signManager.onShopZoneRenamed();
        }
        return ZoneOpResult.ok(ChatColor.GREEN + "Название обновлено!", zi.getMarkerID());
    }

    private ZoneOpResult updateColorCore(String playerName, ZoneInfo zi, String colorValue) {
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }
        if (colorValue == null || colorValue.isBlank()) {
            return ZoneOpResult.fail(ChatColor.RED + "Цвет не указан.");
        }
        ZoneOpResult cooldownFail = checkNameColorCooldown(zi);
        if (cooldownFail != null) return cooldownFail;

        try {
            org.bukkit.Color c;
            if (colorValue.contains(",")) {
                // формат команды: R,G,B
                String[] rgb = colorValue.split(",");
                if (rgb.length != 3) return ZoneOpResult.fail(ChatColor.RED + "Формат: R,G,B");
                c = org.bukkit.Color.fromRGB(Integer.parseInt(rgb[0]), Integer.parseInt(rgb[1]), Integer.parseInt(rgb[2]));
            } else {
                // формат с сайта: #RRGGBB
                String hex = colorValue.startsWith("#") ? colorValue.substring(1) : colorValue;
                if (hex.length() != 6) return ZoneOpResult.fail(ChatColor.RED + "Формат цвета: #RRGGBB.");
                c = org.bukkit.Color.fromRGB(Integer.parseInt(hex, 16));
            }

            zi.setFillColor(c);
            blueMapService.upsert(zi, c);
            syncZoneToWebView(zi);
            saveZonesToConfig(); // тоже добавил — в команде тоже отсутствовал save
            lastNameColorEditByMarker.put(zi.getMarkerID(), System.currentTimeMillis());

            return ZoneOpResult.ok(ChatColor.GREEN + "Цвет обновлён.", zi.getMarkerID());
        } catch (IllegalArgumentException ex) {
            return ZoneOpResult.fail(ChatColor.RED + "Только целые числа / некорректный цвет.");
        }
    }

    /**
     * Бесплатное "повышение" Участка (PLOT) до целевого типа.
     * Никаких платежей здесь нет — стоимость уже оплачена через покупку
     * апгрейда квоты (страна) или личного апгрейда (магазин); тут только
     * проверяем, что квота ещё не выбрана и площадь укладывается в лимиты.
     */
    private ZoneOpResult upgradeTypeCore(UUID playerUuid, String playerName, ZoneInfo zi, ZoneType targetType) {
        if (zi == null) return ZoneOpResult.fail(ChatColor.RED + "Зона не найдена.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }
        if (zi.getType() != ZoneType.PLOT) {
            return ZoneOpResult.fail(ChatColor.RED + "Повысить можно только Участок.");
        }
        if (targetType == null || !UPGRADEABLE_FROM_PLOT.contains(targetType)) {
            return ZoneOpResult.fail(ChatColor.RED + "Участок нельзя повысить до этого типа.");
        }

        ZoneTypeData ztd = zoneLimits.get(targetType);
        if (ztd == null) return ZoneOpResult.fail(ChatColor.RED + "Не задан лимит для " + targetType + ".");

        // Участку разрешено пересекаться со своей же не-участковой зоной при создании
        // (см. findOverlappingZone на сайте) — но повышать такой участок нельзя: после
        // повышения на месте окажутся ДВЕ полноценные зоны разных типов, наложенные друг
        // на друга, что не имеет смысла (лимиты площади/квоты считались бы дважды и т.п.).
        ZoneInfo realOverlap = findRealOverlapExcludingParent(zi);
        if (realOverlap != null) {
            return ZoneOpResult.fail(ChatColor.RED + "Нельзя повысить: участок пересекается с зоной \""
                    + realOverlap.getName() + "\". Уберите пересечение (перестройте участок) и попробуйте снова.");
        }

        double area = ZoneGeometry.totalArea(zi.getShapes()); // мульти-полигон: суммарная площадь ВСЕХ фигур участка
        if (area > ztd.areaLimit()) {
            return ZoneOpResult.fail(ChatColor.RED + "Площадь участка превышает максимум для " + targetType + ": "
                    + (int) area + " > " + (int) ztd.areaLimit() + " блоков². Уменьшите площадь и попробуйте снова.");
        }
        if (area < ztd.minSize()) {
            return ZoneOpResult.fail(ChatColor.RED + "Площадь участка меньше минимума для " + targetType + ": "
                    + (int) area + " < " + (int) ztd.minSize() + " блоков². Расширьте участок и попробуйте снова.");
        }

        if (targetType == ZoneType.SHOP) {
            var q = quotaService.checkPersonalShopQuotaSafe(playerUuid, playerName);
            switch (q.check()) {
                case PENDING -> { return ZoneOpResult.fail(ChatColor.GRAY + "Проверяем ваши права, повторите через пару секунд."); }
                case DENIED -> { return ZoneOpResult.fail(ChatColor.RED + q.message()); }
                case ALLOWED -> { /* ok */ }
            }
        } else {
            String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
            if (playerCountry == null || playerCountry.isBlank()) {
                return ZoneOpResult.fail(ChatColor.RED + "Нужно состоять в стране, чтобы повысить участок до " + targetType + ".");
            }

            // мульти-полигон: ВСЕ фигуры участка должны лежать внутри ОДНОЙ и той же страны/колонии
            ZoneInfo parent = null;
            for (List<Location> shape : zi.getShapes()) {
                ZoneInfo p = ZoneOverlapRules.findSingleContainingZoneOfTypes(
                        shape, java.util.Set.of(ZoneType.COUNTRY, ZoneType.COLONY), zoneList.values());
                if (p == null) {
                    return ZoneOpResult.fail(ChatColor.RED + "Зона " + targetType + " должна полностью находиться внутри Государства или Колонии.");
                }
                if (parent == null) parent = p;
                else if (!Objects.equals(parent.getMarkerID(), p.getMarkerID())) {
                    return ZoneOpResult.fail(ChatColor.RED + "Все фигуры участка должны находиться в одном и том же Государстве/Колонии.");
                }
            }
            String parentCountry = zoneCountry(parent);
            if (parentCountry == null || !Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                return ZoneOpResult.fail(ChatColor.RED + "Участок должен находиться в пределах вашей страны/колонии.");
            }

            var q = quotaService.checkCountryZoneQuotaSafe(playerCountry, targetType);
            switch (q.check()) {
                case PENDING -> { return ZoneOpResult.fail(ChatColor.GRAY + "Проверяем квоту страны, повторите через пару секунд."); }
                case DENIED -> { return ZoneOpResult.fail(ChatColor.RED + q.message()); }
                case ALLOWED -> { /* ok */ }
            }

            zi.setOwnerCountry(playerCountry);
        }

        // маркер BlueMap лежит в MarkerSet, привязанном к (старому) типу — сначала убираем его оттуда
        blueMapService.remove(zi);
        zi.setType(targetType);
        blueMapService.upsert(zi, zi.getFillColor());
        syncZoneToWebView(zi);
        saveZonesToConfig();

        return ZoneOpResult.ok(ChatColor.GREEN + "Участок повышен до \"" + ztd.displayName() + "\"!", zi.getMarkerID());
    }

    /**
     * Есть ли у зоны {@code zi} НАСТОЯЩЕЕ пересечение площадью с какой-либо ДРУГОЙ зоной,
     * кроме её родителя (COUNTRY/COLONY, внутри которого зона и так обязана лежать).
     * Используется только для запрета "Повысить" — при создании участка такое пересечение
     * (со своей же не-участковой зоной) допускается, но повышать его после этого нельзя.
     */
    private ZoneInfo findRealOverlapExcludingParent(ZoneInfo zi) {
        for (List<Location> shape : zi.getShapes()) {
            var shapePoly = ZoneGeometry.toJtsPolygon(shape);
            if (shapePoly == null) continue;

            for (ZoneInfo other : zoneList.values()) {
                if (Objects.equals(other.getMarkerID(), zi.getMarkerID())) continue;
                if (other.getType() == ZoneType.COUNTRY || other.getType() == ZoneType.COLONY) continue;

                for (List<Location> otherShape : other.getShapes()) {
                    var otherPoly = ZoneGeometry.toJtsPolygon(otherShape);
                    if (otherPoly != null && ZoneGeometry.trueAreaOverlap(shapePoly, otherPoly)) return other;
                }
            }
        }
        return null;
    }

    /**
     * Добавляет ЕЩЁ ОДНУ отдельную фигуру к уже существующей зоне (мульти-полигон).
     * В отличие от "Расширить границы" (только приращение основной фигуры), это
     * отдельный, геометрически не связанный участок той же зоны — с суммарным
     * лимитом площади и ограничением по расстоянию (ZoneGeometry.checkShapeSpacing).
     */
    private ZoneOpResult addShapeCore(String playerName, ZoneInfo zi, List<Location> newShape) {
        if (zi == null) return ZoneOpResult.fail(ChatColor.RED + "Зона не найдена.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }

        // Та же территориальная блокировка, что и в updateCornersCore (одни часы на маркер —
        // добавление фигуры это тоже изменение территории Государства/Колонии). Раньше здесь
        // проверки не было вовсе — можно было обойти кулдаун на расширение границ, просто
        // добавляя отдельные фигуры вместо редактирования контура.
        if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
            long now = System.currentTimeMillis();
            long last = lastCornersEditByMarker.getOrDefault(zi.getMarkerID(), 0L);
            long left = CORNERS_EDIT_COOLDOWN_MS - (now - last);
            if (left > 0) {
                long hrs = (left + 3_600_000L - 1) / 3_600_000L;
                return ZoneOpResult.fail(ChatColor.RED + "Изменение территории доступно через ~" + ChatColor.YELLOW + hrs + ChatColor.RED + " ч.");
            }
        }

        var vr = validator.validateAddShapeDraft(playerName, zi, newShape);
        if (!vr.success()) return ZoneOpResult.fail(vr.message());

        zi.addShape(newShape);
        blueMapService.upsert(zi, zi.getFillColor());
        lastCornersEditByMarker.put(zi.getMarkerID(), System.currentTimeMillis());
        syncZoneToWebView(zi);
        saveZonesToConfig();

        return ZoneOpResult.ok(ChatColor.GREEN + "Добавлена ещё одна фигура зоны. Всего фигур: " + zi.getShapeCount() + ".", zi.getMarkerID());
    }

    // ==== Участники (реестр, без игровых прав) — только для обычных зон ====

    private ZoneOpResult addMemberCore(String playerName, ZoneInfo zi, String targetPlayer) {
        if (zi == null) return ZoneOpResult.fail(ChatColor.RED + "Зона не найдена.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }
        if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
            return ZoneOpResult.fail(ChatColor.RED + "Для Государства/Колонии участники зоны не применяются — используйте систему прав страны.");
        }
        if (targetPlayer == null || targetPlayer.isBlank()) {
            return ZoneOpResult.fail(ChatColor.RED + "Не указан игрок.");
        }
        if (NameUtil.eqCi(targetPlayer, playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы уже владелец этой зоны.");
        }
        var target = Bukkit.getOfflinePlayer(targetPlayer);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            return ZoneOpResult.fail(ChatColor.RED + "Игрок \"" + targetPlayer + "\" не найден.");
        }
        String canonicalName = target.getName() != null ? target.getName() : targetPlayer;

        if (!zi.addMember(canonicalName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Этот игрок уже участник зоны.");
        }
        syncZoneToWebView(zi);
        saveZonesToConfig();

        return ZoneOpResult.ok(ChatColor.GREEN + canonicalName + " добавлен(а) в участники зоны \"" + zi.getName() + "\".", zi.getMarkerID());
    }

    private ZoneOpResult removeMemberCore(String playerName, ZoneInfo zi, String targetPlayer) {
        if (zi == null) return ZoneOpResult.fail(ChatColor.RED + "Зона не найдена.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }
        if (targetPlayer == null || targetPlayer.isBlank()) {
            return ZoneOpResult.fail(ChatColor.RED + "Не указан игрок.");
        }
        if (!zi.removeMember(targetPlayer)) {
            return ZoneOpResult.fail(ChatColor.RED + "Этот игрок не участник зоны.");
        }
        syncZoneToWebView(zi);
        saveZonesToConfig();

        return ZoneOpResult.ok(ChatColor.GREEN + targetPlayer + " удалён(а) из участников зоны \"" + zi.getName() + "\".", zi.getMarkerID());
    }

    /** Передача владения зоной другому игроку. Старый владелец автоматически становится участником. */
    private ZoneOpResult transferOwnershipCore(String playerName, ZoneInfo zi, String targetPlayer) {
        if (zi == null) return ZoneOpResult.fail(ChatColor.RED + "Зона не найдена.");
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }
        if (zi.getType() == ZoneType.COUNTRY || zi.getType() == ZoneType.COLONY) {
            return ZoneOpResult.fail(ChatColor.RED + "Передача владения недоступна для Государства/Колонии.");
        }
        if (targetPlayer == null || targetPlayer.isBlank()) {
            return ZoneOpResult.fail(ChatColor.RED + "Не указан игрок.");
        }
        if (NameUtil.eqCi(targetPlayer, playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы уже владелец этой зоны.");
        }
        var target = Bukkit.getOfflinePlayer(targetPlayer);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            return ZoneOpResult.fail(ChatColor.RED + "Игрок \"" + targetPlayer + "\" не найден.");
        }
        String canonicalName = target.getName() != null ? target.getName() : targetPlayer;

        String previousOwner = zi.getOwner();
        zi.removeMember(canonicalName); // новый владелец не должен одновременно числиться участником
        zi.setOwner(canonicalName);
        zi.addMember(previousOwner); // прежний владелец остаётся с доступом как участник

        blueMapService.upsert(zi, zi.getFillColor());
        syncZoneToWebView(zi);
        saveZonesToConfig();

        // GH #8/#11: shop signs cache the zone owner's name on SignVariables
        // (see ZoneSignOwnershipService) and it was previously only ever
        // recalculated on server start or /ul reload — a transfer changed
        // zi.getOwner() right above, but every SHOP/SHOP_SOURCE sign sitting
        // in this zone kept pointing at the PREVIOUS owner's name until the
        // next reload/restart. AutoDebitService/ShopController.
        // resolveShopOwnerName() reads straight off that stale cache and
        // hands it to applyMoneyDelta, which looks the name up in the
        // plugin's own Users table — if the previous owner's row doesn't
        // resolve (renamed, never played, whatever), every sale at that shop
        // fails with "Оплата не прошла (владелец недоступен)" until someone
        // happens to run /ul reload. Recalculating right after a transfer
        // closes that window instead of relying on it.
        if (signManager != null) {
            signOwnershipService.scheduleSignOwnershipRecalc(signManager, 400);
        }

        return ZoneOpResult.ok(ChatColor.GREEN + "Владение зоной \"" + zi.getName() + "\" передано игроку " + canonicalName + ".", zi.getMarkerID());
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

        if (blueMapService != null) {
            blueMapService.remove(zi);
        }

        // GH #11: signs (shop or otherwise) sitting inside this zone used to
        // keep working after the zone itself was gone — a SHOP zone
        // disappearing left its signs fully "live" against a chest/owner
        // that no longer resolved to anything, which is what the report
        // called "всё посыпалось". Deactivate them the same way a physical
        // break would, right as the zone stops existing.
        if (signManager != null) {
            signManager.deactivateSignsInZone(zi.getShapes());
        }

        deleteZoneFromWebView(zi.getMarkerID());
    }

    private void deleteZoneFromWebView(String markerId) {
        Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
            try (Connection con = DBConnect()) {
                if (con == null) return;
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM zones_view WHERE marker_id = ?")) {
                    ps.setString(1, markerId);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[ZoneManager] deleteZoneFromWebView error: " + t);
            }
        });
    }

    public void confirmRemoveZone(Player p) {
        ZoneInfo zi = playerLastZone.get(p.getUniqueId());
        if (zi == null) {
            p.sendMessage(ChatColor.RED + "Нет зоны для удаления!");
            return;
        }
        ZoneOpResult r = removeZoneCore(p.getName(), zi, p);
        if (r.message() != null) p.sendMessage(r.message());
        playerLastZone.remove(p.getUniqueId());
    }
    /** notifyPlayer может быть null (веб-вызов, игрок мог выйти к моменту завершения асинхронной части). */
    private ZoneOpResult removeZoneCore(String playerName, ZoneInfo zi, Player notifyPlayer) {
        if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
            return ZoneOpResult.fail(ChatColor.RED + "Вы не владелец этой зоны.");
        }

        if (zi.getType() == ZoneType.COUNTRY) {
            return ZoneOpResult.fail(ChatColor.RED + "Территорию Государства нельзя удалить напрямую — расформируйте страну на странице страны.");
        }

        removeZoneInternal(zi);
        saveZonesToConfig();
        return ZoneOpResult.ok(ChatColor.GREEN + "Зона \"" + zi.getName() + "\" удалена!", zi.getMarkerID());
    }

    /**
     * Каскадное удаление территории Государства и ВСЕХ зон внутри неё — из памяти,
     * BlueMap, zones_view, плюс удаление самой страны и её отношений из БД.
     * Переиспользуется и ручным удалением ({@code /ul zone remove}), и автоматической
     * чисткой "осиротевших" территорий ({@link #cleanupOrphanCountryZones()}) — например,
     * когда лидер выходит из страны последним и сайт удаляет её из Countries, не зная
     * ничего про игровые зоны (см. countryOperations.php: CountryLeave, disband-ветка).
     */
    // Per ZoneType's own javadoc: INDUSTRIAL/BANK/HOSPITAL/PARK/CHURCH/LIBRARY/
    // GREENHOUSE explicitly "находится только в стране или колонии" — they
    // can't meaningfully exist without one, deleting them alongside COUNTRY/
    // COLONY is correct. PLOT ("не привязан к стране... просто застолблённая
    // территория") and SHOP ("может находится вне страны, самостоятельная
    // зона") are explicitly documented as independent — a player's personal
    // plot/shop being deleted just because their country disbanded was the
    // actual bug reported (GH-adjacent user report, 2026-08-11): the old
    // code swept up EVERY zone tagged with the country's name here,
    // regardless of type, destroying player-owned property they still
    // rightfully own. Those get detached (countryName cleared) instead of
    // deleted — same idea as an employee losing employer-provided access
    // when the employer closes, not losing their own house.
    private static final Set<ZoneType> ZONE_TYPES_BOUND_TO_COUNTRY = EnumSet.of(
            ZoneType.COUNTRY, ZoneType.COLONY, ZoneType.INDUSTRIAL, ZoneType.BANK,
            ZoneType.HOSPITAL, ZoneType.PARK, ZoneType.CHURCH, ZoneType.LIBRARY, ZoneType.GREENHOUSE
    );

    private ZoneOpResult disbandCountryZoneCascade(ZoneInfo zi, String broadcastSuffix, Player notifyPlayer) {
        String countryName = zoneCountry(zi);
        if (countryName == null || countryName.isBlank()) countryName = zi.getName();
        final String finalCountryName = countryName;
        final String normTarget = normCountry(countryName);

        Set<UUID> affectedPlayers = Bukkit.getOnlinePlayers().stream()
                .filter(pl -> {
                    String c = ul.countryRegistryJdbc.getCountryOfPlayer(pl.getName());
                    return c != null && Objects.equals(normCountry(c), normTarget);
                })
                .map(Player::getUniqueId)
                .collect(Collectors.toSet());

        Bukkit.broadcastMessage(
                ChatColor.RED + "[Уведомление] Государство \"" + ChatColor.GOLD + finalCountryName + broadcastSuffix
        );

        List<ZoneInfo> toDelete = new ArrayList<>();
        List<ZoneInfo> toDetach = new ArrayList<>();
        for (ZoneInfo z : new ArrayList<>(zoneList.values())) {
            String zCountry = zoneCountry(z);
            if (zCountry == null || zCountry.isBlank()) continue;
            if (!Objects.equals(normCountry(zCountry), normTarget)) continue;
            if (ZONE_TYPES_BOUND_TO_COUNTRY.contains(z.getType())) {
                toDelete.add(z);
            } else {
                toDetach.add(z);
            }
        }
        for (ZoneInfo z : toDelete) removeZoneInternal(z);
        for (ZoneInfo z : toDetach) {
            z.setOwnerCountry(null);
            if (blueMapService != null) blueMapService.upsert(z, z.getFillColor());
            syncZoneToWebView(z);
        }

        saveZonesToConfig();

        Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
            try (Connection conn = UnityLauncher.DBConnect()) {
                if (conn == null) throw new RuntimeException("DBConnect() вернул null");
                try {
                    conn.setAutoCommit(false);
                    if (UnityLauncher.getInstance().countryRelationshipDao != null) {
                        UnityLauncher.getInstance().countryRelationshipDao.deleteByCountryTx(conn, finalCountryName);
                    }
                    // идемпотентно: если страна уже удалена на сайте (напр. выход последнего
                    // лидера), тут просто DELETE на 0 строк — не ошибка
                    UnityLauncher.getInstance().countryRegistryJdbc.deleteCountryTx(conn, finalCountryName);
                    conn.commit();
                } catch (Exception inner) {
                    try { conn.rollback(); } catch (Exception ignore) {}
                    throw inner;
                }

                Bukkit.getScheduler().runTask(ul, () -> {
                    var prefixService = UnityLauncher.getInstance().luckPermsPrefixService;
                    if (prefixService != null && !affectedPlayers.isEmpty()) {
                        for (UUID uuid : affectedPlayers) prefixService.clear(uuid);
                    }
                    Bukkit.getLogger().info("[Zones] Страна \"" + finalCountryName + "\" и её зоны удалены из БД (TX ok).");
                });
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(ul, () -> {
                    Bukkit.getLogger().warning("[Zones] Ошибка при удалении страны \"" + finalCountryName + "\" из БД: " + ex.getMessage());
                    if (notifyPlayer != null && notifyPlayer.isOnline()) {
                        notifyPlayer.sendMessage(ChatColor.RED + "Удаление страны в БД завершилось ошибкой, см. консоль.");
                    }
                });
            }
        });

        return ZoneOpResult.ok(ChatColor.GREEN + "Государство \"" + finalCountryName
                + "\" и привязанные к нему зоны (" + toDelete.size() + " шт.) удалены."
                + (toDetach.isEmpty() ? "" : " Личные участки/магазины (" + toDetach.size() + " шт.) сохранены, просто отвязаны от страны."),
                zi.getMarkerID());
    }

    /**
     * Периодическая проверка "осиротевших" территорий: COUNTRY-зона существует у нас в
     * памяти, но её страны уже нет в Countries (например, лидер вышел последним и сайт
     * удалил Countries-строку в countryOperations.php, ничего не сообщив плагину — там
     * нет вызова веб-очереди зон, только прямой DELETE). Чистим территорию и всё, что
     * внутри неё, тем же путём, что и ручное удаление.
     */
    private void cleanupOrphanCountryZones() {
        List<ZoneInfo> countryZones = zoneList.values().stream()
                .filter(z -> z.getType() == ZoneType.COUNTRY)
                .toList();
        if (countryZones.isEmpty()) return;

        // countryExistsCached() reads an in-memory cache that refreshes itself
        // asynchronously and returns stale (possibly still-empty) data while that
        // refresh is in flight — see CountryRegistryJdbc.refreshCacheIfExpired().
        // Right after server restart the cache hasn't loaded even once yet, so
        // every real country would read back as "doesn't exist" and this loop
        // would disband them all. Force one synchronous load first so the check
        // below always sees real data.
        //
        // THE ACTUAL BUG (found analyzing "страны сами по себе удалялись через
        // несколько рестартов"): forceRefreshBlocking() can itself fail —
        // DBConnect() returns null, or the query throws — if MySQL/the pool
        // isn't fully up yet this soon after a restart. On failure it just
        // logs and returns, leaving the cache exactly as it was. The FIRST
        // time this runs after a fresh boot, "as it was" means genuinely
        // empty (cacheLoadedOnce still false) — so every real country reads
        // back as nonexistent and the loop below disbanded ALL of them in one
        // sweep. Not on every restart (depends on whether the DB happened to
        // be ready in time by the ~2min mark this first fires), matching the
        // reported "after a few restarts" pattern exactly. Bailing out
        // entirely when the cache still isn't loaded is the fix — better to
        // skip this pass and retry next cycle (2 min later) than to trust
        // country-doesn't-exist answers from an empty cache.
        ul.countryRegistryJdbc.forceRefreshBlocking();
        if (!ul.countryRegistryJdbc.isCacheLoaded()) {
            Bukkit.getLogger().warning("[Zones] cleanupOrphanCountryZones: countries cache still isn't loaded "
                    + "(DB not ready yet?) — skipping this pass rather than risk disbanding real countries.");
            return;
        }

        for (ZoneInfo zi : countryZones) {
            // маркер мог быть уже удалён предыдущей итерацией (одна COUNTRY-зона —
            // один маркер, но на всякий случай проверяем актуальность)
            if (!zoneList.containsKey(zi.getMarkerID())) continue;

            String countryName = zoneCountry(zi);
            if (countryName == null || countryName.isBlank()) countryName = zi.getName();
            if (ul.countryRegistryJdbc.countryExistsCached(countryName)) continue;

            Bukkit.getLogger().info("[Zones] Обнаружена осиротевшая территория Государства \""
                    + countryName + "\" (страны больше нет в Countries) — удаляю.");
            disbandCountryZoneCascade(zi,
                    ChatColor.RED + "\" было автоматически расформировано — страна больше не существует.", null);
        }
    }

    /**
     * Запускает периодическую чистку осиротевших территорий Государств (см.
     * cleanupOrphanCountryZones). Остаётся на главном потоке намеренно —
     * cleanupOrphanCountryZones/disbandCountryZoneCascade зовут
     * Bukkit.broadcastMessage/getOnlinePlayers и мутируют zoneList/BlueMap
     * напрямую, без самостоятельного ухода на главный поток (в отличие от
     * записи в БД в конце каскада, которая уже async сама по себе).
     * forceRefreshBlocking() внутри — блокирующий JDBC-вызов раз в 2 минуты;
     * известная цена (небольшой хитч), не трогал — раскидывать эти
     * Bukkit-вызовы по потокам отдельная, более рискованная задача, не то,
     * о чём просили в этом заходе.
     */
    public void startOrphanCountryZoneCleanup(long periodTicks) {
        Bukkit.getScheduler().runTaskTimer(ul, this::cleanupOrphanCountryZones, periodTicks, periodTicks);
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

    /** military-diplomacy-design.md §2.2/§13 Фаза 4 — "Нарушение границы": член другой страны в чужой COUNTRY-зоне без прав. One report per fresh entry (checkPlayerZone already only calls this on an actual zone change, not every tick). */
    private void reportBorderViolationIfApplicable(Player p, ZoneInfo next) {
        if (next == null || next.getType() != ZoneType.COUNTRY) return;
        String zoneCountry = zoneCountry(next);
        if (zoneCountry == null || zoneCountry.isBlank()) return;
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (playerCountry != null && playerCountry.equalsIgnoreCase(zoneCountry)) return; // own country, not a violation

        var api = UnityLauncher.getInstance().getFarLandsApi();
        if (api != null) api.reportBorderViolation(p.getName(), zoneCountry);
    }

    /** military-diplomacy-design.md §3.2 — гражданин своей страны с правом viewMilitary. Союзники с расшариванием — не реализовано, см. CountryRegistryJdbc.getPlayerViewMilitaryPermission. */
    private boolean canSeeMilitaryZone(Player p, ZoneInfo z) {
        String owner = zoneCountry(z);
        if (owner == null || owner.isBlank()) return false;
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (playerCountry == null || !playerCountry.equalsIgnoreCase(owner)) return false;
        return ul.countryRegistryJdbc.getPlayerViewMilitaryPermission(p.getName());
    }

    // ==== Player location → zone ====
    public void checkPlayerZone(Player p) {
        ZoneInfo prev = playerLastZone.get(p.getUniqueId());
        ZoneInfo next = getZoneAt(p.getLocation());

        if (Objects.equals(prev, next)) return;

        reportBorderViolationIfApplicable(p, next);

        String barText;

        if (next == null || (next.getType() == ZoneType.MILITARY && !canSeeMilitaryZone(p, next))) {
            // Вышли из любых зон — военная зона без права viewMilitary
            // неотличима от дикой местности (military-diplomacy-design.md
            // §3.2): ни тайтла, ни action bar, ничего.
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

                    // GH#24 (фидбек 2026-08-14 п.2) — военная зона с назначенной
                    // специализацией показывает её вместо общего "Военный объект"
                    // (например "Разведпункт" вместо "Военный объект"); без
                    // специализации — как и раньше, generic-название типа.
                    if (type == ZoneType.MILITARY && next.getMilitarySpecialization() != null) {
                        typeName = next.getMilitarySpecialization().displayName();
                    }

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
                .filter(z -> ZoneGeometry.worldOkAny(z.getShapes(), w) && ZoneGeometry.pointInAnyShape(loc, z.getShapes(), Y_MIN, Y_MAX))
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
        return CountryNameUtil.normalizeCountry(s);
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
            if (!ZoneGeometry.worldOkAny(z.getShapes(), w)) continue;
            if (ZoneGeometry.pointInAnyShape(loc, z.getShapes(), Y_MIN, Y_MAX)) return z;
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
        //    ищем родителя COUNTRY / COLONY, который целиком содержит ТУ ФИГУРУ
        //    зоны, в которой реально стоит игрок (мульти-полигон: разные фигуры
        //    одной зоны в принципе могут лежать в разных странах)
        try {
            List<Location> shapeAtLoc = here.getCorners();
            for (List<Location> shape : here.getShapes()) {
                if (ZoneGeometry.pointInZone(loc, shape, -64, 255)) { shapeAtLoc = shape; break; }
            }
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(
                    shapeAtLoc,
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
     * true, если в этой точке есть зона, владельцем которой является playerName,
     * ЛИБО зона принадлежит стране, гражданином которой является playerName.
     * Используется ActivityTracker'ом, чтобы не засчитывать полный вес "трафика"
     * за визиты хозяина/сограждан в свою же зону (иначе налог на землю растёт
     * от собственной игры, а не от реального чужого интереса к месту).
     * Возвращает false, если зоны в этой точке нет вообще.
     */
    public boolean isOwnerOrMemberAt(Location loc, String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        ZoneInfo z = getZoneAt(loc);
        if (z == null) return false;

        if (NameUtil.eqCi(z.getOwner(), playerName)) return true;

        String zoneCountry = zoneCountry(z);
        if (zoneCountry == null || zoneCountry.isBlank()) return false;

        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
        return playerCountry != null && !playerCountry.isBlank()
                && Objects.equals(normCountry(playerCountry), normCountry(zoneCountry));
    }

    /**
     * Та же страна, но уже нормализованная (как normCountry в этом классе).
     * Удобно для сравнения и вызовов countryMaxLevel(...).
     */
    public String getCountryCanonicalAt(Location loc) {
        String c = getCountryAt(loc);
        return c != null ? normCountry(c) : null;
    }
    @Override
    public Result handle(ZoneWebRequestService.ZoneWebRequest request) {
        ZoneType type = null;
        if (request.zoneType() != null) {
            try {
                type = ZoneType.valueOf(request.zoneType());
            } catch (IllegalArgumentException ex) {
                return Result.error("Неизвестный тип зоны: " + request.zoneType());
            }
        }

        String colorHex = (request.payload() != null && request.payload().has("color"))
                ? request.payload().get("color").getAsString()
                : null;

        String targetPlayer = (request.payload() != null && request.payload().has("targetPlayer"))
                ? request.payload().get("targetPlayer").getAsString()
                : null;

        // какую именно фигуру многофигурной зоны редактируем (UPDATE_CORNERS); 0 по умолчанию
        int shapeIndex = (request.payload() != null && request.payload().has("shapeIndex"))
                ? request.payload().get("shapeIndex").getAsInt()
                : 0;

        // CREATE поддерживает мульти-полигон (несколько отдельных фигур зоны сразу) —
        // обрабатываем его отдельной веткой с payload.shapes вместо payload.points.
        // GH#24 п.2-3/§4.1 — не мультиполигон, не связана с points/shapes,
        // короче обработать отдельной веткой до общего handleWebRequest,
        // как и CREATE ниже.
        if (request.action() == ZoneWebRequestService.Action.SET_MILITARY_SPECIALIZATION) {
            String specRaw = (request.payload() != null && request.payload().has("specialization"))
                    ? request.payload().get("specialization").getAsString() : null;
            ZoneOpResult r = setMilitarySpecializationWebCore(request.playerUuid(), request.markerId(), specRaw);
            return r.success() ? Result.ok(r.markerId()) : Result.error(stripColor(r.message()));
        }

        if (request.action() == ZoneWebRequestService.Action.SET_MILITARY_DEFENSE_SUBTYPE) {
            String subtypeRaw = (request.payload() != null && request.payload().has("defenseSubtype"))
                    ? request.payload().get("defenseSubtype").getAsString() : null;
            ZoneOpResult r = setMilitaryDefenseSubtypeWebCore(request.playerUuid(), request.markerId(), subtypeRaw);
            return r.success() ? Result.ok(r.markerId()) : Result.error(stripColor(r.message()));
        }

        if (request.action() == ZoneWebRequestService.Action.CREATE) {
            World world = (request.worldName() != null) ? Bukkit.getWorld(request.worldName()) : null;
            List<List<double[]>> shapesXZ = parseShapesFromPayload(request.payload());
            List<List<Location>> shapes = new ArrayList<>(shapesXZ.size());
            for (List<double[]> s : shapesXZ) shapes.add(toLocations(world, s));

            ZoneOpResult r = handleCreateWebRequest(request.playerUuid(), type, request.zoneName(), shapes, colorHex);
            return r.success() ? Result.ok(r.markerId()) : Result.error(stripColor(r.message()));
        }

        List<double[]> points = parsePointsFromPayload(request.payload());
        ZoneOpResult r = handleWebRequest(
                request.playerUuid(), request.action(), type, request.zoneName(),
                request.worldName(), points, request.markerId(), colorHex, targetPlayer, shapeIndex
        );

        return r.success()
                ? Result.ok(r.markerId())
                : Result.error(stripColor(r.message()));
    }

    private List<double[]> parsePointsFromPayload(JsonObject payload) {
        List<double[]> out = new ArrayList<>();
        if (payload == null || !payload.has("points")) return out;
        JsonArray arr = payload.getAsJsonArray("points");
        for (var el : arr) {
            JsonObject o = el.getAsJsonObject();
            out.add(new double[]{ o.get("x").getAsDouble(), o.get("z").getAsDouble() });
        }
        return out;
    }

    /**
     * Мульти-полигон: payload.shapes = [[{x,z},...], [{x,z},...], ...].
     * Обратная совместимость: если shapes нет, но есть старый плоский payload.points — считаем это одной фигурой.
     */
    private List<List<double[]>> parseShapesFromPayload(JsonObject payload) {
        List<List<double[]>> out = new ArrayList<>();
        if (payload == null) return out;

        if (payload.has("shapes")) {
            for (var shapeEl : payload.getAsJsonArray("shapes")) {
                List<double[]> shape = new ArrayList<>();
                for (var el : shapeEl.getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    shape.add(new double[]{ o.get("x").getAsDouble(), o.get("z").getAsDouble() });
                }
                if (!shape.isEmpty()) out.add(shape);
            }
            return out;
        }

        List<double[]> legacy = parsePointsFromPayload(payload);
        if (!legacy.isEmpty()) out.add(legacy);
        return out;
    }

    /** Точка входа для веб-заявок CREATE — поддерживает несколько фигур сразу (мульти-полигон). */
    private ZoneOpResult handleCreateWebRequest(UUID playerUuid, ZoneType type, String zoneName,
                                                List<List<Location>> shapes, String colorHex) {
        String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
        if (playerName == null) return ZoneOpResult.fail("Не удалось определить игрока по UUID.");
        if (shapes.isEmpty() || shapes.get(0).isEmpty()) return ZoneOpResult.fail("Нет точек.");

        if (type == ZoneType.COUNTRY) {
            return buildZoneCountryCore(playerName, shapes, colorHex);
        }
        return buildZoneCoreMulti(playerUuid, playerName, type, zoneName, shapes, colorHex);
    }

    /**
     * Создаёт зону из НЕСКОЛЬКИХ фигур: первая фигура проходит через обычный
     * buildZoneCore (без изменений — ноль риска регрессии для однополигональных
     * зон), остальные добавляются через тот же валидатор, что и ADD_SHAPE.
     * COLONY принципиально не поддерживает доп. фигуры. COUNTRY сюда вообще не
     * попадает — для неё есть отдельное мульти-полигонное ядро buildZoneCountryCore
     * (см. handleCreateWebRequest), оставляем проверку ниже как защитный дубль.
     */
    private ZoneOpResult buildZoneCoreMulti(UUID playerUuid, String playerName, ZoneType type, String zoneName,
                                            List<List<Location>> shapes, String colorHex) {
        // при 2+ фигурах НЕ синхронизируем в zones_view сразу — иначе эта запись
        // (ещё без доп. фигур) может асинхронно "обогнать" вторую, финальную запись
        // ниже (added > 0) и extra_shapes молча потеряются (гонка двух async-задач
        // на один marker_id). Синхронизируем один раз, уже после сборки всей зоны.
        boolean syncFirstImmediately = shapes.size() == 1;
        ZoneOpResult first = buildZoneCore(playerUuid, playerName, type, zoneName, shapes.get(0), colorHex, syncFirstImmediately);
        if (!first.success() || shapes.size() == 1) return first;

        if (type == ZoneType.COUNTRY || type == ZoneType.COLONY) {
            // доп. фигуры не поддерживаются для этого типа — первая (и единственная)
            // фигура так и не была синхронизирована выше (syncFirstImmediately=false
            // при shapes.size()>1), досинхронизируем её здесь
            ZoneInfo onlyShape = zoneList.get(first.markerId());
            if (onlyShape != null) syncZoneToWebView(onlyShape);
            return first;
        }

        ZoneInfo created = zoneList.get(first.markerId());
        if (created == null) return first;

        int added = 0;
        String rejectMsg = null;
        for (int i = 1; i < shapes.size(); i++) {
            List<Location> extra = shapes.get(i);
            var vr = validator.validateAddShapeDraft(playerName, created, extra);
            if (!vr.success()) {
                rejectMsg = ChatColor.stripColor(vr.message());
                break; // дальше проверять смысла нет — сообщим об одном отказе, остальные фигуры игнорируем
            }
            created.addShape(extra);
            added++;
        }

        if (added > 0) {
            blueMapService.upsert(created, created.getFillColor());
            saveZonesToConfig();
        }
        // синхронизируем в любом случае (даже если added==0, т.е. все доп. фигуры
        // отклонены валидатором) — иначе первая фигура так и останется несинхронизированной,
        // т.к. buildZoneCore выше специально пропустил sync (syncFirstImmediately=false)
        syncZoneToWebView(created);

        String msg = ChatColor.GREEN + "Зона \"" + zoneName + "\" создана"
                + (added > 0 ? " (" + (added + 1) + " фигур(ы))" : "") + "!"
                + (rejectMsg != null ? ChatColor.RED + " Не все фигуры добавлены: " + rejectMsg : "");
        return ZoneOpResult.ok(msg, created.getMarkerID());
    }

    /** Сообщения из ZoneOpResult содержат §-коды ChatColor - на сайте они не нужны. */
    private static String stripColor(String msg) {
        return msg == null ? null : ChatColor.stripColor(msg);
    }
    /** Точка входа для веб-заявок. Требует, чтобы игрок был онлайн. */
    public ZoneOpResult handleWebRequest(UUID playerUuid, ZoneWebRequestService.Action action,
                                         ZoneType type, String zoneName, String worldName,
                                         List<double[]> xzPoints, String markerId, String colorHex,
                                         String targetPlayer, int shapeIndex) {

        String playerName = Bukkit.getOfflinePlayer(playerUuid).getName();
        if (playerName == null) {
            return ZoneOpResult.fail("Не удалось определить игрока по UUID.");
        }

        if (action != ZoneWebRequestService.Action.CREATE) {
            ZoneInfo zi = zoneList.get(markerId);
            if (zi == null) return ZoneOpResult.fail("Зона не найдена.");
            if (!NameUtil.eqCi(zi.getOwner(), playerName)) {
                return ZoneOpResult.fail("Вы не владелец этой зоны.");
            }
        }

        World world = (worldName != null) ? Bukkit.getWorld(worldName) : null;
        List<Location> pts = toLocations(world, xzPoints);

        return switch (action) {
            case CREATE -> (type == ZoneType.COUNTRY)
                    ? buildZoneCountryCore(playerName, pts == null ? List.of() : List.of(pts), colorHex)
                    : buildZoneCore(playerUuid, playerName, type, zoneName, pts, colorHex);
            case UPDATE_CORNERS -> updateCornersCore(playerName, zoneList.get(markerId), pts, false, shapeIndex);
            case UPDATE_NAME -> updateNameCore(playerName, zoneList.get(markerId), zoneName);
            case UPDATE_COLOR -> updateColorCore(playerName, zoneList.get(markerId), colorHex);
            case DELETE -> removeZoneCore(playerName, zoneList.get(markerId), Bukkit.getPlayer(playerUuid));
            case UPGRADE_TYPE -> upgradeTypeCore(playerUuid, playerName, zoneList.get(markerId), type);
            case ADD_SHAPE -> addShapeCore(playerName, zoneList.get(markerId), pts);
            case ADD_MEMBER -> addMemberCore(playerName, zoneList.get(markerId), targetPlayer);
            case REMOVE_MEMBER -> removeMemberCore(playerName, zoneList.get(markerId), targetPlayer);
            case TRANSFER_OWNERSHIP -> transferOwnershipCore(playerName, zoneList.get(markerId), targetPlayer);
            // Перехватывается раньше, в handleWebRequest-обёртке (см. выше по файлу) — сюда не доходит,
            // но switch-выражение всё равно требует ветку на все значения enum.
            case SET_MILITARY_SPECIALIZATION -> ZoneOpResult.fail("Внутренняя ошибка: SET_MILITARY_SPECIALIZATION должен обрабатываться раньше.");
            case SET_MILITARY_DEFENSE_SUBTYPE -> ZoneOpResult.fail("Внутренняя ошибка: SET_MILITARY_DEFENSE_SUBTYPE должен обрабатываться раньше.");
        };
    }

    /** Y ставим по высоте рельефа — семантически не важен, только для красивого отображения. */
    private List<Location> toLocations(World world, List<double[]> xz) {
        if (world == null || xz == null) return List.of();
        List<Location> out = new ArrayList<>(xz.size());
        for (double[] p : xz) {
            int y = world.getHighestBlockYAt((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            out.add(new Location(world, p[0], y, p[1]));
        }
        return out;
    }

    private void syncZoneToWebView(ZoneInfo z) {
        // считаем на главном потоке (billing state дешёвое, а ZoneInfo не
        // потокобезопасен) — сама запись в БД всё равно async ниже.
        // Активные апгрейды НЕ считаем и не храним здесь: они уже живут в
        // Countries.Upgrades/CountryInfo (и Users.StatsData.playerUpgrades для
        // личных зон без страны) — сайт достаёт их оттуда напрямую при чтении
        // zones_list.php, чтобы не дублировать одни и те же данные в двух местах.
        double dueCost = z.getDueSinceLastBill(LocalDate.now());
        java.sql.Date nextBilling = java.sql.Date.valueOf(z.getNextBillingDate());

        // extra_shapes_json — доп. фигуры зоны (мульти-полигон), НЕ включая основную
        // (та по-прежнему пишется в corners_json как раньше, чтобы старые потребители
        // на сайте, не знающие про мульти-полигон, продолжали работать без изменений)
        String extraShapesJson = extraShapesToJson(z);
        // members_json — реестр участников (НЕ владелец); "[]" для COUNTRY/COLONY
        // и для зон без участников. Формат такой же простой список ников, как и
        // все остальные ..._json колонки здесь.
        String membersJson = membersToJson(z);

        Bukkit.getScheduler().runTaskAsynchronously(ul, () -> {
            String sql = """
            INSERT INTO zones_view (marker_id, zone_id, type, name, owner, owner_country, world_name, corners_json, extra_shapes_json, members_json, color_rgb, corners_locked_until, due_cost, next_billing_date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                zone_id=VALUES(zone_id), type=VALUES(type), name=VALUES(name), owner=VALUES(owner),
                owner_country=VALUES(owner_country), world_name=VALUES(world_name),
                corners_json=VALUES(corners_json), extra_shapes_json=VALUES(extra_shapes_json),
                members_json=VALUES(members_json), color_rgb=VALUES(color_rgb),
                corners_locked_until=VALUES(corners_locked_until),
                due_cost=VALUES(due_cost), next_billing_date=VALUES(next_billing_date)
            """;
            try (Connection con = DBConnect()) {
                if (con == null) return;
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, z.getMarkerID());
                    ps.setString(2, z.getID());
                    ps.setString(3, z.getType().name());
                    ps.setString(4, z.getName());
                    ps.setString(5, z.getOwner());
                    ps.setString(6, z.getCountryName());
                    ps.setString(7, z.getWorld() != null ? z.getWorld().getName() : null);
                    ps.setString(8, cornersToJson(z.getCorners()));
                    ps.setString(9, extraShapesJson);
                    ps.setString(10, membersJson);
                    ps.setInt(11, z.getFillColor() != null ? z.getFillColor().asRGB() : 0xFF0000);
                    Long lockedUntil = null;
                    if (z.getType() == ZoneType.COUNTRY || z.getType() == ZoneType.COLONY) {
                        Long lastEdit = lastCornersEditByMarker.get(z.getMarkerID());
                        if (lastEdit != null) lockedUntil = lastEdit + CORNERS_EDIT_COOLDOWN_MS;
                    }
                    ps.setTimestamp(12, lockedUntil != null ? new Timestamp(lockedUntil) : null);
                    ps.setDouble(13, dueCost);
                    ps.setDate(14, nextBilling);
                    ps.executeUpdate();
                }
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[ZoneManager] syncZoneToWebView error: " + t);
            }
        });
    }

    /** Публичная обёртка — используется биллинг-тиком, чтобы освежить due_cost/дату во всех зонах раз в день. */
    public void refreshWebView(ZoneInfo z) {
        syncZoneToWebView(z);
    }

    private static final Gson WEB_GSON = new Gson();

    private String cornersToJson(List<Location> corners) {
        JsonArray arr = new JsonArray();
        for (Location l : corners) {
            JsonObject o = new JsonObject();
            o.addProperty("x", l.getX());
            o.addProperty("z", l.getZ());
            arr.add(o);
        }
        return WEB_GSON.toJson(arr);
    }

    /** [[{x,z},...], ...] для всех фигур КРОМЕ основной (shapes[0]); "[]" если фигура одна. */
    private String extraShapesToJson(ZoneInfo z) {
        List<List<Location>> shapes = z.getShapes();
        JsonArray outer = new JsonArray();
        for (int i = 1; i < shapes.size(); i++) {
            JsonArray inner = new JsonArray();
            for (Location l : shapes.get(i)) {
                JsonObject o = new JsonObject();
                o.addProperty("x", l.getX());
                o.addProperty("z", l.getZ());
                inner.add(o);
            }
            outer.add(inner);
        }
        return WEB_GSON.toJson(outer);
    }

    /** ["nick1","nick2",...] — участники зоны (без владельца); "[]" если участников нет. */
    private String membersToJson(ZoneInfo z) {
        JsonArray arr = new JsonArray();
        for (String m : z.getMembers()) arr.add(m);
        return WEB_GSON.toJson(arr);
    }

}
