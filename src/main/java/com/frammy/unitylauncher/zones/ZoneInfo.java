package com.frammy.unitylauncher.zones;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

import java.time.LocalDate;
import java.util.*;

/** Минимальная и удобная модель зоны. */
public class ZoneInfo {

    // ===== Core =====
    private final ZoneType type;
    private final String id;
    private String name;
    private final String markerID;
    private String owner;                 // текстовый владелец (игрок/страна по старой логике)
    private final List<Location> corners; // ЖИВОЙ список (можно изменять)
    private Color fillColor;

    /** Нормализованное имя страны-владельца (LuckPerms-группа). */
    private String ownerCountry;

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
        this.corners = (corners != null) ? new ArrayList<>(corners) : new ArrayList<>();
        this.owner = owner;
        this.fillColor = fillColor;
    }

    // ===== Getters / setters (только нужные) =====
    public ZoneType getType()           { return type; }
    public String getID()               { return id; }
    public String getName()             { return name; }
    public void setName(String name)    { this.name = name != null ? name : ""; }

    public String getMarkerID()         { return markerID; }

    /** Возвращает ЖИВОЙ список углов (можно add/remove). */
    public List<Location> getCorners()  { return corners; }
    public void setCorners(List<Location> pts) {
        this.corners.clear();
        if (pts != null) this.corners.addAll(pts);
    }

    public String getOwner()            { return owner; }
    public void setOwner(String owner)  { this.owner = owner; }

    public Color getFillColor()         { return fillColor; }
    public void setFillColor(Color c)   { this.fillColor = c; }

    // ===== Country / LuckPerms =====
    public void setOwnerCountry(String country) { this.ownerCountry = country; }

    /** Имя страны-владельца (как записано в зоне). Ник владельца НЕ является страной. */
    public String getCountryName() {
        return notBlank(ownerCountry) ? ownerCountry : null;
    }

    public boolean hasCountry() { return notBlank(ownerCountry); }

    /** Мир зоны по первому углу (или null). */
    public World getWorld() {
        if (corners.isEmpty()) return null;
        for (Location l : corners) {
            if (l != null && l.getWorld() != null) return l.getWorld();
        }
        return null;
    }

    /** Проверка попадания точки внутрь полигона зоны (XZ), с проверкой мира. */
    public boolean contains2D(Location loc) {
        if (loc == null || corners.size() < 3) return false;
        World w = getWorld();
        if (w == null || loc.getWorld() == null || !w.getUID().equals(loc.getWorld().getUID())) return false;

        double x = loc.getX(), z = loc.getZ();
        boolean inside = false;
        for (int i = 0, j = corners.size() - 1; i < corners.size(); j = i++) {
            Location a = corners.get(i), b = corners.get(j);
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
        return "ZoneInfo{type=" + type + ", id='" + id + "', name='" + name +
                "', owner='" + owner + "', country='" + ownerCountry +
                "', corners=" + corners.size() + '}';
    }

    // Быстрый AABB по XZ для текущих углов зоны.
    public BoundingBox getBoundingBoxXZ() {
        if (corners.isEmpty()) {
            // пустая зона — нулевая рамка
            return new BoundingBox(0, 0, 0, 0, 0, 0);
        }

        double minX = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        World w = null;

        for (Location loc : corners) {
            if (loc == null) continue;
            if (w == null) w = loc.getWorld();
            double x = loc.getX();
            double z = loc.getZ();
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }

        // если вдруг все loc == null
        if (Double.isInfinite(minX) || Double.isInfinite(minZ)) {
            return new BoundingBox(0, 0, 0, 0, 0, 0);
        }

        int maxY = (w != null) ? w.getMaxHeight() : 256;
        return new BoundingBox(minX, 0, minZ, maxX, maxY, maxZ);
    }

    /** Центр зоны (примерно): центр AABB по XZ + y по highestBlock. */
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
