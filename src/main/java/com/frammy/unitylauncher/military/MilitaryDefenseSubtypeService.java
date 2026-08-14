package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * infra/military-diplomacy-design.md §14.6, GH#24 (фидбек 2026-08-14 п.1/4) —
 * "тип сооружения" под специализацией DEFENSE. Line-for-line the same
 * pattern as MilitarySpecializationService (switch window + cooldown +
 * per-type quota), one level deeper — see MilitaryDefenseSubtype's javadoc
 * for why this is a sibling service rather than folded into that one.
 */
public final class MilitaryDefenseSubtypeService {

    // Тестовые значения (GH#24, фидбек 2026-08-14 p.s. — "снизь всё до 10
    // секунд пока тестируем"). Реальные черновые числа закомментированы
    // рядом — вернуть их после тестирования.
    public static final long SWITCH_LOCK_MS = TimeUnit.SECONDS.toMillis(10);
    // public static final long SWITCH_LOCK_MS = TimeUnit.MINUTES.toMillis(5);
    public static final long SWITCH_COOLDOWN_MS = TimeUnit.SECONDS.toMillis(10);
    // public static final long SWITCH_COOLDOWN_MS = TimeUnit.HOURS.toMillis(12);

    private final ZoneManager zoneManager;

    public MilitaryDefenseSubtypeService(ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
    }

    public record SwitchOutcome(boolean success, String message) {
        static SwitchOutcome ok(String msg) { return new SwitchOutcome(true, msg); }
        static SwitchOutcome fail(String msg) { return new SwitchOutcome(false, msg); }
    }

    private boolean resolvePending(ZoneInfo zone) {
        if (zone.getPendingMilitaryDefenseSubtype() == null) return false;
        if (System.currentTimeMillis() < zone.getDefenseSubtypeSwitchLockedUntil()) return false;

        zone.setMilitaryDefenseSubtype(zone.getPendingMilitaryDefenseSubtype());
        zone.setPendingMilitaryDefenseSubtype(null);
        zone.setDefenseSubtypeChangedAt(System.currentTimeMillis());
        return true;
    }

    /** Текущий устоявшийся тип обороны, или null — не назначен ИЛИ прямо сейчас переключается. Не учитывает, активна ли вообще специализация DEFENSE — см. isActiveAs. */
    public MilitaryDefenseSubtype current(ZoneInfo zone) {
        if (zone == null || zone.getType() != ZoneType.MILITARY) return null;
        if (resolvePending(zone)) zoneManager.saveZonesToConfig();
        if (zone.isDefenseSubtypeSwitching()) return null;
        return zone.getMilitaryDefenseSubtype();
    }

    /** Активен ли ИМЕННО этот тип обороны на этой зоне ПРЯМО СЕЙЧАС — требует и назначенный тип, и что зона реально несёт специализацию DEFENSE (не переключается сама), и что тип куплен страной. */
    public boolean isActiveAs(ZoneInfo zone, MilitaryDefenseSubtype type) {
        if (type == null || current(zone) != type) return false;
        var specService = com.frammy.unitylauncher.UnityLauncher.getInstance().militarySpecializationService;
        if (!specService.isActiveAs(zone, MilitarySpecialization.DEFENSE)) return false;
        String country = UpgradeCondition.zoneCountryCanonical(zone);
        return type.unlockedFor(country);
    }

    public SwitchOutcome requestSwitch(ZoneInfo zone, MilitaryDefenseSubtype target) {
        if (zone == null || zone.getType() != ZoneType.MILITARY) {
            return SwitchOutcome.fail("Тип обороны есть только у военных объектов.");
        }
        if (target == null) {
            return SwitchOutcome.fail("Не указан тип обороны.");
        }

        var specService = com.frammy.unitylauncher.UnityLauncher.getInstance().militarySpecializationService;
        if (specService.current(zone) != MilitarySpecialization.DEFENSE) {
            return SwitchOutcome.fail("Тип обороны можно назначить только объекту со специализацией «оборона».");
        }

        resolvePending(zone);

        if (zone.isDefenseSubtypeSwitching()) {
            long remainSec = (zone.getDefenseSubtypeSwitchLockedUntil() - System.currentTimeMillis()) / 1000L + 1;
            return SwitchOutcome.fail("Уже идёт переключение типа обороны, объект неактивен ещё ~" + remainSec + " с.");
        }
        if (target == zone.getMilitaryDefenseSubtype()) {
            return SwitchOutcome.fail("У объекта уже этот тип обороны.");
        }

        String country = UpgradeCondition.zoneCountryCanonical(zone);
        if (!target.unlockedFor(country)) {
            return SwitchOutcome.fail("Ваша страна не купила тип обороны " + target.name().toLowerCase(Locale.ROOT) + ".");
        }

        int quota = target.purchasedQuota(country);
        int inUse = countInUse(country, target, zone.getMarkerID());
        if (inUse >= quota) {
            return SwitchOutcome.fail("Лимит объектов страны с типом обороны "
                    + target.name().toLowerCase(Locale.ROOT) + " достигнут: " + inUse + "/" + quota + ".");
        }

        long now = System.currentTimeMillis();
        if (zone.getDefenseSubtypeChangedAt() > 0) {
            long sinceLast = now - zone.getDefenseSubtypeChangedAt();
            if (sinceLast < SWITCH_COOLDOWN_MS) {
                long remainSec = (SWITCH_COOLDOWN_MS - sinceLast) / 1000L + 1;
                return SwitchOutcome.fail("Слишком рано менять тип обороны повторно — ещё ~" + remainSec + " с.");
            }
        }

        zone.setPendingMilitaryDefenseSubtype(target);
        zone.setDefenseSubtypeSwitchLockedUntil(now + SWITCH_LOCK_MS);
        zoneManager.saveZonesToConfig();

        long lockSec = SWITCH_LOCK_MS / 1000L;
        return SwitchOutcome.ok("Переключение на " + target.name().toLowerCase(Locale.ROOT)
                + " начато. Объект неактивен ~" + lockSec + " с, затем тип применится сам.");
    }

    public int countInUse(String canonicalCountry, MilitaryDefenseSubtype type, String excludeMarkerId) {
        if (canonicalCountry == null || type == null) return 0;
        int count = 0;
        for (ZoneInfo z : zoneManager.getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            if (excludeMarkerId != null && excludeMarkerId.equals(z.getMarkerID())) continue;
            if (!canonicalCountry.equals(UpgradeCondition.zoneCountryCanonical(z))) continue;
            if (z.getMilitaryDefenseSubtype() == type || z.getPendingMilitaryDefenseSubtype() == type) count++;
        }
        return count;
    }

    public int availableQuota(String canonicalCountry, MilitaryDefenseSubtype type) {
        if (canonicalCountry == null || type == null) return 0;
        int quota = type.purchasedQuota(canonicalCountry);
        return Math.max(0, quota - countInUse(canonicalCountry, type, null));
    }
}
