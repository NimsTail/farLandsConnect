package com.frammy.unitylauncher.zones;

import de.bluecolored.bluemap.api.math.Color;
import org.bukkit.Location;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

public class ZoneInfo {

    public ZoneType zoneType;
    public String zoneID;
    public String zoneName;
    public String zoneOwner;
    public String markerID;
    public List<Location> zoneCorners;
    public org.bukkit.Color fillColor;
    private String ownerCountry;

    public String getOwnerCountry() { return ownerCountry; }
    public void setOwnerCountry(String ownerCountry) { this.ownerCountry = ownerCountry; }

    // === История и биллинг ===
    public static class DailyEntry {
        public final LocalDate date;
        public final double cost;

        public DailyEntry(LocalDate date, double cost) {
            this.date = date;
            this.cost = cost;
        }
    }

    // Храним последние 14 дней (можно увеличить, если нужно)
    public final Deque<DailyEntry> dailyHistory = new ArrayDeque<>(14);

    private LocalDate lastBilledDate = null; // последний оплаченный день (включительно)
    private LocalDate nextBillingDate = null; // когда планируем следующий платёж

    public ZoneInfo(ZoneType zoneType, String zoneID, String zoneName, String markerID, List<Location> zoneCorners, String zoneOwner, org.bukkit.Color fillColor) {
        this.zoneType = zoneType;
        this.zoneID = zoneID;
        this.zoneName = zoneName;
        this.markerID = markerID;
        this.zoneCorners = zoneCorners;
        this.zoneOwner = zoneOwner;
        this.fillColor = fillColor;
    }

    // === Геттеры/сеттеры ===
    public ZoneType getType() {
        return zoneType;
    }

    public String getID() {
        return zoneID;
    }

    public String getName() {
        return zoneName;
    }

    public String getMarkerID() {
        return markerID;
    }

    public List<Location> getCorners() {
        return zoneCorners;
    }

    public String getOwner() {
        return zoneOwner;
    }

    public org.bukkit.Color getFillColor() {
        return fillColor;
    }

    public void setFillColor() {
        this.fillColor = fillColor;
    }

    public void setType(ZoneType type) {
        this.zoneType = type;
    }

    public void setID(String id) {
        this.zoneID = id;
    }

    public void setName(String name) {
        this.zoneName = name;
    }

    public void setMarkerID(String markerID) {
        this.markerID = markerID;
    }

    public void setCorners(List<Location> corners) {
        this.zoneCorners = corners;
    }

    public void setOwner(String owner) {
        this.zoneOwner = owner;
    }
    public double getCachedCost() {
        if (dailyHistory.isEmpty()) return 0.0;
        return dailyHistory.getLast().cost;
    }

    // === Логика биллинга ===

    /**
     * Добавляет дневную стоимость в историю.
     * Если за этот день уже была запись — заменяем.
     */
    public void addDailyCost(LocalDate date, double cost) {
        if (!dailyHistory.isEmpty() && dailyHistory.getLast().date.equals(date)) {
            dailyHistory.removeLast();
        }
        dailyHistory.addLast(new DailyEntry(date, cost));
        while (dailyHistory.size() > 14) {
            dailyHistory.removeFirst();
        }
    }

    /**
     * Сумма к оплате за дни после последнего биллинга, до указанной даты включительно.
     */
    public double getDueSinceLastBill(LocalDate upToDate) {
        double sum = 0.0;
        for (DailyEntry e : dailyHistory) {
            boolean afterLastBill = (lastBilledDate == null) || e.date.isAfter(lastBilledDate);
            boolean notAfterUpTo = !e.date.isAfter(upToDate);
            if (afterLastBill && notAfterUpTo) {
                sum += e.cost;
            }
        }
        return sum;
    }

    /**
     * Сколько дней неоплачено с момента последнего биллинга.
     */
    public int getDueDaysCount(LocalDate upToDate) {
        int n = 0;
        for (DailyEntry e : dailyHistory) {
            boolean afterLastBill = (lastBilledDate == null) || e.date.isAfter(lastBilledDate);
            boolean notAfterUpTo = !e.date.isAfter(upToDate);
            if (afterLastBill && notAfterUpTo) {
                n++;
            }
        }
        return n;
    }

    /**
     * Отметить, что зона оплачена на текущий день (сдвинуть дату следующего биллинга на +7).
     */
    public void markBilled(LocalDate today) {
        this.lastBilledDate = today;
        this.nextBillingDate = today.plusDays(7);
    }

    /**
     * Получить дату следующего планового биллинга.
     */
    public LocalDate getNextBillingDate() {
        if (nextBillingDate == null) {
            // если ни разу не было оплаты, назначаем через 7 дней от текущей даты
            return LocalDate.now().plusDays(7);
        }
        return nextBillingDate;
    }
}
