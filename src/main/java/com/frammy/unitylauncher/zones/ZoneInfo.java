package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.military.MilitaryDefenseSubtype;
import com.frammy.unitylauncher.military.MilitarySpecialization;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.time.LocalDate;
import java.util.*;

/**
 * Минимальная и удобная модель зоны.
 *
 * ===== Мульти-полигон =====
 * Зона может состоять из НЕСКОЛЬКИХ отдельных фигур ("shapes") — например,
 * магазин с двумя отдельными участками застройки. Первая фигура (shapes[0])
 * называется "основной" и остаётся полностью эквивалентна старому полю
 * corners — весь код, написанный ДО введения мульти-полигонов и работающий
 * через getCorners()/setCorners(), продолжает работать без изменений (для
 * подавляющего большинства зон, у которых ровно одна фигура, поведение
 * идентично старому). Доп. фигуры добавляются только через addShape()/
 * ZoneManager.ADD_SHAPE и учитываются явно в тех местах, где это важно для
 * геймплея/биллинга (контейнмент, пересечения, площадь, BlueMap-маркеры).
 *
 * COLONY принципиально ОДНОполигональна (фундаментальный контейнер, для
 * неё мульти-полигон не поддерживается). COUNTRY, наоборот, поддерживает
 * несколько отдельных эксклавов территории — см. ZoneManager.buildZoneCountryCore
 * и ZoneOverlapRules (containment проверяется по ОБЪЕДИНЕНИЮ всех фигур).
 */
public class ZoneInfo {

    // ===== Core =====
    private ZoneType type;
    private final String id;
    private String name;
    private final String markerID;
    private String owner;                 // текстовый владелец (игрок/страна по старой логике)
    /** shapes.get(0) — основная фигура (= старое поле corners), shapes.size() всегда >= 1. */
    private final List<List<Location>> shapes;
    private Color fillColor;

    /** Нормализованное имя страны-владельца (LuckPerms-группа). */
    private String ownerCountry;

    /**
     * Участники личной зоны (плоский список ников, без иерархии/ролей) —
     * только для PLOT/обычных зон, НЕ для COUNTRY/COLONY (у них своя система
     * прав через Countries.Players/Permissions). Реестр — на игровые права
     * (строительство и т.п.) сейчас не влияет.
     */
    private final Set<String> members = new LinkedHashSet<>();

    // ===== Военная специализация/якорь (GH#24, military-diplomacy-design.md §4.1/§14.2) =====
    // Только для type == MILITARY, но храним прямо на ZoneInfo (не отдельной
    // мапой) — тот же выбор, что и для остальных полей, специфичных для
    // конкретного типа зоны (billing, members) в этом классе.
    /** Текущий (уже применённый/устоявшийся) "главный тип" объекта. null = не назначено. Актуален только когда !isSpecializationSwitching(). */
    private MilitarySpecialization militarySpecialization;
    /** Цель незавершённого переключения — применяется вместо militarySpecialization, когда истечёт specializationSwitchLockedUntil. */
    private MilitarySpecialization pendingMilitarySpecialization;
    /** Пока > System.currentTimeMillis() — объект СЕЙЧАС переключается и временно неактивен (GH#24 п.3), никакая специализация не работает. */
    private long specializationSwitchLockedUntil;
    /** Когда последний раз ЗАВЕРШИЛОСЬ переключение специализации — основа кулдауна на следующее. 0 = ни разу не переключали. */
    private long specializationChangedAt;
    /** Физический анкер текущей специализации (например, Колокол у Разведпункта) — игрок ставит сам, null = якоря нет/сломан. */
    private Location militaryAnchorLocation;

    // ===== Тип обороны (GH#24, фидбек 2026-08-14 п.1/4) =====
    // Тот же паттерн переключения, что и специализация выше, одним уровнем
    // ниже — осмысленно только пока militarySpecialization == DEFENSE (см.
    // MilitaryDefenseSubtypeService), но независимая ось: не сбрасывается
    // при уходе с DEFENSE и возврате обратно.
    private MilitaryDefenseSubtype militaryDefenseSubtype;
    private MilitaryDefenseSubtype pendingMilitaryDefenseSubtype;
    private long defenseSubtypeSwitchLockedUntil;
    private long defenseSubtypeChangedAt;

    public MilitaryDefenseSubtype getMilitaryDefenseSubtype() { return militaryDefenseSubtype; }
    public void setMilitaryDefenseSubtype(MilitaryDefenseSubtype s) { this.militaryDefenseSubtype = s; }

    public MilitaryDefenseSubtype getPendingMilitaryDefenseSubtype() { return pendingMilitaryDefenseSubtype; }
    public void setPendingMilitaryDefenseSubtype(MilitaryDefenseSubtype s) { this.pendingMilitaryDefenseSubtype = s; }

    public long getDefenseSubtypeSwitchLockedUntil() { return defenseSubtypeSwitchLockedUntil; }
    public void setDefenseSubtypeSwitchLockedUntil(long ts) { this.defenseSubtypeSwitchLockedUntil = ts; }

    public long getDefenseSubtypeChangedAt() { return defenseSubtypeChangedAt; }
    public void setDefenseSubtypeChangedAt(long ts) { this.defenseSubtypeChangedAt = ts; }

    public boolean isDefenseSubtypeSwitching() {
        return System.currentTimeMillis() < defenseSubtypeSwitchLockedUntil;
    }

    public MilitarySpecialization getMilitarySpecialization() { return militarySpecialization; }
    public void setMilitarySpecialization(MilitarySpecialization s) { this.militarySpecialization = s; }

    public MilitarySpecialization getPendingMilitarySpecialization() { return pendingMilitarySpecialization; }
    public void setPendingMilitarySpecialization(MilitarySpecialization s) { this.pendingMilitarySpecialization = s; }

    public long getSpecializationSwitchLockedUntil() { return specializationSwitchLockedUntil; }
    public void setSpecializationSwitchLockedUntil(long ts) { this.specializationSwitchLockedUntil = ts; }

    public long getSpecializationChangedAt() { return specializationChangedAt; }
    public void setSpecializationChangedAt(long ts) { this.specializationChangedAt = ts; }

    public Location getMilitaryAnchorLocation() { return militaryAnchorLocation; }
    public void setMilitaryAnchorLocation(Location loc) { this.militaryAnchorLocation = loc; }

    /** true пока идёт окно переключения (GH#24 п.3) — специализация временно не в счёт, объект неактивен. */
    public boolean isSpecializationSwitching() {
        return System.currentTimeMillis() < specializationSwitchLockedUntil;
    }

    // ===== Billing (последние 14 дней) =====
    public record DailyEntry(LocalDate date, double cost) {}
    private final Deque<DailyEntry> dailyHistory = new ArrayDeque<>(14);
    private LocalDate lastBilledDate;   // последний оплаченный день (включительно)
    private LocalDate nextBillingDate;  // плановая дата следующего биллинга

    // ===== Ctor =====
    public ZoneInfo(ZoneType type, String id, String name, String markerID,
                    List<Location> corners, String owner, Color fillColor) {
        this.type = Objects.requireNonNull(type, "type");
        this.id = Objects.requireNonNull(id, "id");
        this.name = name != null ? name : "";
        this.markerID = Objects.requireNonNull(markerID, "markerID");
        this.shapes = new ArrayList<>();
        this.shapes.add((corners != null) ? new ArrayList<>(corners) : new ArrayList<>());
        this.owner = owner;
        this.fillColor = fillColor;
    }

    // ===== Getters / setters (только нужные) =====
    public ZoneType getType()           { return type; }
    /** Смена типа зоны — только для повышения PLOT ("Участок") до целевого типа (ZoneManager.upgradeTypeCore). */
    public void setType(ZoneType type)  { this.type = Objects.requireNonNull(type, "type"); }
    public String getID()               { return id; }
    public String getName()             { return name; }
    public void setName(String name)    { this.name = name != null ? name : ""; }

    public String getMarkerID()         { return markerID; }

    /** Возвращает ЖИВОЙ список углов ОСНОВНОЙ фигуры (можно add/remove). Для многофигурных зон — только shapes[0]. */
    public List<Location> getCorners()  { return shapes.get(0); }
    public void setCorners(List<Location> pts) {
        setShapeAt(0, pts);
    }

    /** Как getCorners(), но для ЛЮБОЙ фигуры зоны по индексу (0 = основная). */
    public List<Location> getShapeAt(int index) {
        if (index < 0 || index >= shapes.size()) return getCorners();
        return shapes.get(index);
    }

    /** Как setCorners(), но для ЛЮБОЙ фигуры зоны по индексу (0 = основная). */
    public void setShapeAt(int index, List<Location> pts) {
        if (index < 0 || index >= shapes.size()) index = 0;
        List<Location> target = shapes.get(index);
        target.clear();
        if (pts != null) target.addAll(pts);
    }

    /** Неизменяемый снимок ВСЕХ фигур зоны (всегда >= 1 элемент). */
    public List<List<Location>> getShapes() {
        List<List<Location>> copy = new ArrayList<>(shapes.size());
        for (List<Location> s : shapes) copy.add(List.copyOf(s));
        return List.copyOf(copy);
    }

    public int getShapeCount() { return shapes.size(); }

    /** Полностью заменяет набор фигур (используется при создании многофигурной зоны). */
    public void setShapes(List<List<Location>> newShapes) {
        if (newShapes == null || newShapes.isEmpty()) {
            throw new IllegalArgumentException("У зоны должна быть хотя бы одна фигура.");
        }
        shapes.clear();
        for (List<Location> s : newShapes) shapes.add(new ArrayList<>(s));
    }

    /** Добавляет ещё одну (доп.) фигуру к зоне — см. ZoneManager.addShapeCore. */
    public void addShape(List<Location> pts) {
        Objects.requireNonNull(pts, "pts");
        shapes.add(new ArrayList<>(pts));
    }

    public String getOwner()            { return owner; }
    public void setOwner(String owner)  { this.owner = owner; }

    // ===== Участники (реестр, без игровых прав) =====
    /** Неизменяемый снимок ников участников (владелец в список не входит). */
    public Set<String> getMembers() { return Set.copyOf(members); }

    public boolean isMember(String playerName) {
        if (playerName == null) return false;
        for (String m : members) if (m.equalsIgnoreCase(playerName)) return true;
        return false;
    }

    /** @return true если реально добавлен (false — если уже был участником или совпал с owner) */
    public boolean addMember(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        if (playerName.equalsIgnoreCase(owner)) return false;
        if (isMember(playerName)) return false;
        members.add(playerName);
        return true;
    }

    /** @return true если реально удалён */
    public boolean removeMember(String playerName) {
        if (playerName == null) return false;
        return members.removeIf(m -> m.equalsIgnoreCase(playerName));
    }

    /** Полностью заменяет список участников (используется при загрузке из БД). */
    public void setMembers(Collection<String> names) {
        members.clear();
        if (names != null) {
            for (String n : names) {
                if (n != null && !n.isBlank() && !n.equalsIgnoreCase(owner)) members.add(n);
            }
        }
    }

    public Color getFillColor()         { return fillColor; }
    public void setFillColor(Color c)   { this.fillColor = c; }

    // ===== Country / LuckPerms =====
    public void setOwnerCountry(String country) { this.ownerCountry = country; }

    /** Имя страны-владельца (как записано в зоне). Ник владельца НЕ является страной. */
    public String getCountryName() {
        return notBlank(ownerCountry) ? ownerCountry : null;
    }

    public boolean hasCountry() { return notBlank(ownerCountry); }

    /** Мир зоны по первому непустому углу любой из фигур (или null). */
    public World getWorld() {
        for (List<Location> shape : shapes) {
            for (Location l : shape) {
                if (l != null && l.getWorld() != null) return l.getWorld();
            }
        }
        return null;
    }

    /** Проверка попадания точки внутрь ЛЮБОЙ из фигур зоны (XZ), с проверкой мира. */
    public boolean contains2D(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        for (List<Location> shape : shapes) {
            if (shape.size() < 3) continue;
            World w = shape.getFirst().getWorld();
            if (w == null || !w.getUID().equals(loc.getWorld().getUID())) continue;
            if (pointInRing(shape, loc.getX(), loc.getZ())) return true;
        }
        return false;
    }

    private static boolean pointInRing(List<Location> ring, double x, double z) {
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            Location a = ring.get(i), b = ring.get(j);
            double xi = a.getX(), zi = a.getZ();
            double xj = b.getX(), zj = b.getZ();
            boolean inter = ((zi > z) != (zj > z)) &&
                    (x < (xj - xi) * (z - zi) / (zj - zi + 0.0) + xi);
            if (inter) inside = !inside;
        }
        return inside;
    }

    // ===== Billing =====
    /** Добавляет/заменяет стоимость за день, хранит максимум 14 последних записей. */
    public void addDailyCost(LocalDate date, double cost) {
        if (date == null) return;
        if (!dailyHistory.isEmpty() && Objects.equals(dailyHistory.getLast().date(), date)) {
            dailyHistory.removeLast();
        }
        dailyHistory.addLast(new DailyEntry(date, cost));
        while (dailyHistory.size() > 14) dailyHistory.removeFirst();
    }

    /** Сумма к оплате за дни после последнего биллинга до upToDate включительно. */
    public double getDueSinceLastBill(LocalDate upToDate) {
        if (upToDate == null) upToDate = LocalDate.now();
        double sum = 0.0;
        for (DailyEntry e : dailyHistory) {
            boolean afterLast = (lastBilledDate == null) || e.date().isAfter(lastBilledDate);
            if (afterLast && !e.date().isAfter(upToDate)) sum += e.cost();
        }
        return sum;
    }

    /** Кол-во неоплаченных дней (как выше). */
    public int getDueDaysCount(LocalDate upToDate) {
        if (upToDate == null) upToDate = LocalDate.now();
        int n = 0;
        for (DailyEntry e : dailyHistory) {
            boolean afterLast = (lastBilledDate == null) || e.date().isAfter(lastBilledDate);
            if (afterLast && !e.date().isAfter(upToDate)) n++;
        }
        return n;
    }

    /** Отмечаем оплату на today и планируем следующий биллинг через 7 дней. */
    public void markBilled(LocalDate today) {
        if (today == null) today = LocalDate.now();
        this.lastBilledDate = today;
        this.nextBillingDate = today.plusDays(7);
    }

    public LocalDate getNextBillingDate() {
        return nextBillingDate != null ? nextBillingDate : LocalDate.now().plusDays(7);
    }

    // ===== Utils =====
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    @Override public String toString() {
        int totalPoints = 0;
        for (List<Location> s : shapes) totalPoints += s.size();
        return "ZoneInfo{type=" + type + ", id='" + id + "', name='" + name +
                "', owner='" + owner + "', country='" + ownerCountry +
                "', shapes=" + shapes.size() + ", points=" + totalPoints + '}';
    }

    // Быстрый AABB по XZ по ВСЕМ фигурам зоны.
    public BoundingBox getBoundingBoxXZ() {
        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        World w = null;

        for (List<Location> shape : shapes) {
            for (Location loc : shape) {
                if (loc == null) continue;
                if (w == null) w = loc.getWorld();
                double x = loc.getX();
                double z = loc.getZ();
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }

        if (Double.isInfinite(minX) || Double.isInfinite(minZ)) {
            return new BoundingBox(0, 0, 0, 0, 0, 0);
        }

        int maxY = (w != null) ? w.getMaxHeight() : 256;
        return new BoundingBox(minX, 0, minZ, maxX, maxY, maxZ);
    }

    /** Центр зоны (примерно): центр AABB по XZ (по всем фигурам) + y по highestBlock. */
    public Location getCenter() {
        World w = getWorld();
        if (w == null) return null;

        BoundingBox bb = getBoundingBoxXZ();
        double cx = (bb.getMinX() + bb.getMaxX()) / 2.0;
        double cz = (bb.getMinZ() + bb.getMaxZ()) / 2.0;

        int y = w.getHighestBlockYAt((int) Math.floor(cx), (int) Math.floor(cz));
        return new Location(w, cx, y, cz);
    }

}
