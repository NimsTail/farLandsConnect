package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * infra/military-diplomacy-design.md §4.1/§14.2, GH#24 п.2-3 — per-zone
 * "which military upgrade is actually active here" (a country can buy
 * several military upgrades, but each MILITARY zone runs at most ONE of
 * them at a time), plus the cooldown/inactive-during-switch rule from п.3.
 *
 * Stateless — all the actual state lives on ZoneInfo itself (see its
 * "Военная специализация/якорь" fields) so it survives a plugin restart via
 * the normal zones.yml persistence; this class is just the rules around it.
 * Every method that can mutate a zone's state calls back into ZoneManager
 * to persist (saveZonesToConfig()) — same as every other zone mutation in
 * this codebase.
 */
public final class MilitarySpecializationService {

    // Тестовые значения (GH#24, фидбек 2026-08-14 p.s. — "снизь всё до 10
    // секунд пока тестируем"). Реальные черновые числа (по аналогии с
    // остальным балансом §14.5/§15.5 — не согласованы жёстко, править по
    // первому фидбеку с реального сервера) закомментированы рядом — вернуть их после тестирования.
    public static final long SWITCH_LOCK_MS = TimeUnit.SECONDS.toMillis(10);
    // public static final long SWITCH_LOCK_MS = TimeUnit.MINUTES.toMillis(10);
    public static final long SWITCH_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(10);
    // public static final long SWITCH_COOLDOWN_MS = TimeUnit.HOURS.toMillis(24);

    private final ZoneManager zoneManager;

    public MilitarySpecializationService(ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
    }

    public record SwitchOutcome(boolean success, String message) {
        static SwitchOutcome ok(String msg) { return new SwitchOutcome(true, msg); }
        static SwitchOutcome fail(String msg) { return new SwitchOutcome(false, msg); }
    }

    /**
     * Если окно переключения уже истекло — применяет отложенную
     * специализацию и сохраняет. Вызывается лениво изнутри current()/
     * isActiveAs(), а не по таймеру — не нужен отдельный BukkitTask ради
     * этого, объект всё равно неактивен всё окно независимо от того, когда
     * именно применится финальное значение.
     */
    private boolean resolvePending(ZoneInfo zone) {
        if (zone.getPendingMilitarySpecialization() == null) return false;
        if (System.currentTimeMillis() < zone.getSpecializationSwitchLockedUntil()) return false;

        zone.setMilitarySpecialization(zone.getPendingMilitarySpecialization());
        zone.setPendingMilitarySpecialization(null);
        zone.setSpecializationChangedAt(System.currentTimeMillis());
        return true;
    }

    /** Текущая устоявшаяся специализация, или null — если не назначена ИЛИ прямо сейчас идёт переключение (GH#24 п.3: объект временно неактивен). */
    public MilitarySpecialization current(ZoneInfo zone) {
        if (zone == null || zone.getType() != ZoneType.MILITARY) return null;
        if (resolvePending(zone)) zoneManager.saveZonesToConfig();
        if (zone.isSpecializationSwitching()) return null;
        return zone.getMilitarySpecialization();
    }

    /** Активна ли ИМЕННО эта специализация на этой зоне ПРЯМО СЕЙЧАС — учитывает и переключение, и страновой апгрейд-анлок. */
    public boolean isActiveAs(ZoneInfo zone, MilitarySpecialization type) {
        if (type == null || current(zone) != type) return false;
        String country = UpgradeCondition.zoneCountryCanonical(zone);
        return type.unlockedFor(country);
    }

    /**
     * Запрашивает переключение специализации. Не проверяет права на зону —
     * это ответственность вызывающего (владелец/роль в стране), эта функция
     * только про сами правила специализации/кулдауна.
     */
    public SwitchOutcome requestSwitch(ZoneInfo zone, MilitarySpecialization target) {
        if (zone == null || zone.getType() != ZoneType.MILITARY) {
            return SwitchOutcome.fail("Специализация есть только у военных объектов.");
        }
        if (target == null) {
            return SwitchOutcome.fail("Не указан тип специализации.");
        }

        resolvePending(zone); // подчистить хвост от прошлого переключения, если окно уже прошло

        if (zone.isSpecializationSwitching()) {
            long remainSec = (zone.getSpecializationSwitchLockedUntil() - System.currentTimeMillis()) / 1000L + 1;
            return SwitchOutcome.fail("Уже идёт переключение специализации, объект неактивен ещё ~" + remainSec + " с.");
        }
        if (target == zone.getMilitarySpecialization()) {
            return SwitchOutcome.fail("У объекта уже эта специализация.");
        }

        String country = UpgradeCondition.zoneCountryCanonical(zone);
        if (!target.unlockedFor(country)) {
            return SwitchOutcome.fail("Ваша страна не купила апгрейд для специализации "
                    + target.name().toLowerCase(Locale.ROOT) + ".");
        }

        // GH#24 (правки из комментария, п.3) — квота на специализацию, та же
        // логика, что и квота типов зон: N куплено -> максимум N объектов
        // страны одновременно с этой специализацией. Эта же зона исключена
        // из подсчёта (она либо не несёт target вовсе — раннее возвращение
        // выше отсекло "уже эта специализация" — либо переключается на
        // target прямо сейчас, что не должно блокировать само себя).
        // Фидбек 2026-08-14 — quota == -1 значит "без лимита" (плоские
        // одноразовые специализации, см. MilitarySpecialization.javadoc) —
        // пропускаем проверку количества вообще, а не сравниваем с -1
        // (иначе inUse >= -1 всегда true и блокировало бы вообще всё).
        int quota = target.purchasedQuota(country);
        if (quota != -1) {
            int inUse = countInUse(country, target, zone.getMarkerID());
            if (inUse >= quota) {
                return SwitchOutcome.fail("Лимит объектов страны со специализацией "
                        + target.name().toLowerCase(Locale.ROOT) + " достигнут: " + inUse + "/" + quota + ".");
            }
        }

        long now = System.currentTimeMillis();
        if (zone.getSpecializationChangedAt() > 0) {
            long sinceLast = now - zone.getSpecializationChangedAt();
            if (sinceLast < SWITCH_COOLDOWN_MS) {
                long remainSec = (SWITCH_COOLDOWN_MS - sinceLast) / 1000L + 1;
                return SwitchOutcome.fail("Слишком рано переключать специализацию повторно — ещё ~" + remainSec + " с.");
            }
        }

        zone.setPendingMilitarySpecialization(target);
        zone.setSpecializationSwitchLockedUntil(now + SWITCH_LOCK_MS);
        zoneManager.saveZonesToConfig();

        long lockSec = SWITCH_LOCK_MS / 1000L;
        return SwitchOutcome.ok("Переключение на " + target.name().toLowerCase(Locale.ROOT)
                + " начато. Объект неактивен ~" + lockSec + " с, затем специализация применится сама.");
    }

    /**
     * Сколько военных объектов страны прямо сейчас числятся под этой
     * специализацией — считает и устоявшиеся (getMilitarySpecialization),
     * и те, что уже переключаются НА неё (getPendingMilitarySpecialization),
     * чтобы нельзя было гонкой запросов проскочить квоту через два
     * одновременных переключения. excludeMarkerId — сама зона-инициатор
     * запроса, не должна сама себя блокировать.
     */
    public int countInUse(String canonicalCountry, MilitarySpecialization spec, String excludeMarkerId) {
        if (canonicalCountry == null || spec == null) return 0;
        int count = 0;
        for (ZoneInfo z : zoneManager.getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            if (excludeMarkerId != null && excludeMarkerId.equals(z.getMarkerID())) continue;
            if (!canonicalCountry.equals(UpgradeCondition.zoneCountryCanonical(z))) continue;
            if (z.getMilitarySpecialization() == spec || z.getPendingMilitarySpecialization() == spec) count++;
        }
        return count;
    }

    /**
     * Сколько ещё объектов страны можно назначить на эту специализацию прямо
     * сейчас (для дропдауна на сайте) — -1 значит "без лимита" (см.
     * MilitarySpecialization.unlimitedOnceUnlocked), фронтенд трактует это
     * отдельно, не как обычное число.
     */
    public int availableQuota(String canonicalCountry, MilitarySpecialization spec) {
        if (canonicalCountry == null || spec == null) return 0;
        int quota = spec.purchasedQuota(canonicalCountry);
        if (quota == -1) return -1;
        return Math.max(0, quota - countInUse(canonicalCountry, spec, null));
    }

    /** Военная зона, физически содержащая эту точку (без учёта приоритета типов — только MILITARY). */
    public ZoneInfo militaryZoneAt(org.bukkit.Location loc) {
        if (loc == null) return null;
        for (ZoneInfo z : zoneManager.getAllZonesSnapshot()) {
            if (z.getType() == ZoneType.MILITARY && z.contains2D(loc)) return z;
        }
        return null;
    }
}
