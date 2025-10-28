package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class UpgradeCondition {

    private UpgradeCondition() {}

    public static boolean DEBUG = true;
    private static void d(String msg) {
        if (DEBUG) Bukkit.getLogger().info("[UL/UpgradeCondition] " + msg);
    }

    /* =====================
       НОРМАЛИЗАЦИЯ СТРАНЫ
       ===================== */

    // каноническое имя страны = имя LP-группы
    public static String normalizeCountry(String raw) {
        if (raw == null) return null;
        String t = raw.trim().toLowerCase(java.util.Locale.ROOT);
        t = t.replace(' ', '_');
        return t.replaceAll("[^a-z0-9_\\-.]", "");
    }

    // для игрока
    public static String playerCountryCanonical(String playerName) {
        String c = UnityLauncher.getInstance()
                .countryRegistryJdbc
                .getCountryOfPlayer(playerName);
        if (c == null || c.isBlank()) return null;
        return normalizeCountry(c);
    }

    // для зоны
    public static String zoneCountryCanonical(ZoneInfo z) {
        if (z == null) return null;
        String cn = z.getCountryName();
        if (cn == null || cn.isBlank()) return null;
        return normalizeCountry(cn);
    }

    // кто владеет локацией (COUNTRY или COLONY), вернуть канон. имя страны
    public static String locationCountryOwner(Location loc) {
        ZoneInfo z = zoneAt(loc);
        if (z == null) return null;
        ZoneType t = z.getType();
        if (t != ZoneType.COUNTRY && t != ZoneType.COLONY) return null;

        return zoneCountryCanonical(z);
    }

    /* =====================
       ДОСТУП К ГРУППЕ СТРАНЫ
       ===================== */

    private static Group getCountryGroup(String canonicalCountry) {
        if (canonicalCountry == null || canonicalCountry.isBlank()) return null;
        try {
            LuckPerms lp = LuckPermsProvider.get();
            Group g = lp.getGroupManager().getGroup(canonicalCountry);
            if (g == null) {
                d("getCountryGroup: group not found -> " + canonicalCountry);
            }
            return g;
        } catch (Throwable t) {
            d("getCountryGroup EX " + t);
            return null;
        }
    }

    /* =====================
       ПРОВЕРКИ АПГРЕЙДОВ
       ===================== */

    /**
     * Проверяет строго одну ноду permission.
     * node обязан быть в формате "<prefix>.<feature>.<lvl>"
     * примеры:
     *   "unity.zone.haste.1"
     *   "unity.hopper.smart.2"
     *   "unity.tnt.quarry.1"
     *
     * Возвращает true, если у группы страны есть эта нода.
     */
    public static boolean countryHasNode(String canonicalCountry, String node) {
        if (canonicalCountry == null || canonicalCountry.isBlank()) return false;
        if (node == null || node.isBlank()) return false;

        Group g = getCountryGroup(canonicalCountry);
        if (g == null) return false;

        try {
            LuckPerms lp = LuckPermsProvider.get();
            var opts = lp.getContextManager().getStaticQueryOptions();

            return g.getCachedData()
                    .getPermissionData(opts)
                    .checkPermission(node)
                    .asBoolean();
        } catch (Throwable t) {
            d("countryHasNode EX " + t);
            return false;
        }
    }

    /**
     * Получить максимальный уровень апгрейда для страны по базовому префиксу без уровня.
     *
     * basePrefix — всё КРОМЕ <lvl>.
     *   например:
     *     "unity.zone.haste"
     *     "unity.zone.speed"
     *     "unity.zone.resistance"
     *     "unity.hopper.smart"
     *     "unity.tnt.quarry"
     *
     * maxCheckLevel — до какого уровня вообще имеет смысл проверять (текущий максимум дизайна).
     *
     * Алгоритм:
     *   идём от maxCheckLevel вниз к 1
     *   и спрашиваем countryHasNode(country, basePrefix + "." + lvl)
     *   первый найденный -> возвращаем
     *   иначе 0
     *
     * Примеры:
     *   countryMaxLevel("nautilus", "unity.zone.haste", 2) -> 2 или 1 или 0
     *   countryMaxLevel("nautilus", "unity.hopper.smart", 2) -> 2 или 1 или 0
     *   countryMaxLevel("nautilus", "unity.tnt.quarry", 1) -> 1 или 0
     */
    public static int countryMaxLevel(String canonicalCountry, String basePrefix, int maxCheckLevel) {
        if (canonicalCountry == null || canonicalCountry.isBlank()) return 0;
        if (basePrefix == null || basePrefix.isBlank()) return 0;
        if (maxCheckLevel <= 0) return 0;

        for (int lvl = maxCheckLevel; lvl >= 1; lvl--) {
            String node = basePrefix + "." + lvl;
            if (countryHasNode(canonicalCountry, node)) {
                return lvl;
            }
        }
        return 0;
    }

    /* =====================
       ПРОЧЕЕ, КОТОРЫМ УЖЕ ПОЛЬЗУЕМСЯ
       ===================== */

    public static ZoneInfo zoneAt(Location loc) {
        try {
            var zm = UnityLauncher.getInstance().getZoneManager();
            if (zm == null) {
                d("zoneAt(" + p(loc) + ") -> zoneManager == null");
                return null;
            }
            return zm.getZoneAt(loc);
        } catch (Throwable t) {
            d("zoneAt EX: " + t);
            return null;
        }
    }

    /**
     * Грубая проверка: есть ли ХОТЬ ОДНА зона нужного типа,
     * внутри которой находится эта локация, игнорируя приоритет.
     *
     * Например: локация внутри COLONY и внутри INDUSTRIAL.
     * zoneAt() вернёт COLONY, но этот метод для type=INDUSTRIAL вернёт true.
     */
    public static boolean isInsideZoneTypeRaw(Location loc, ZoneType type) {
        if (loc == null || type == null) return false;

        try {
            var zm = UnityLauncher.getInstance().getZoneManager();
            if (zm == null) {
                d("isInsideZoneTypeRaw: zoneManager == null");
                return false;
            }

            // берём снапшот всех зон (есть getAllZonesSnapshot() в ZoneManager)
            for (ZoneInfo z : zm.getAllZonesSnapshot()) {
                if (z.getType() != type) continue;
                // мир должен совпадать
                if (z.getWorld() == null || loc.getWorld() == null) continue;
                if (!z.getWorld().getUID().equals(loc.getWorld().getUID())) continue;

                // вхождение по XZ
                if (z.contains2D(loc)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            d("isInsideZoneTypeRaw EX " + t);
        }
        return false;
    }

    public static boolean isInsideCountryOrColony(Location loc) {
        ZoneInfo z = zoneAt(loc);
        return z != null && (z.getType() == ZoneType.COUNTRY || z.getType() == ZoneType.COLONY);
    }

    public static boolean hasRedstoneUnlocked(Player p) {
        if (p == null) return false;
        boolean ok = p.isOp() || p.hasPermission("unity.upgrade.redstone.1") || p.hasPermission("unity.upgrade.redstone.2");
        d("hasRedstoneUnlocked(" + p.getName() + ") -> " + ok);
        return ok;
    }

    private static String p(Location l) {
        if (l == null) return "null";
        return String.format(
                "%s@%d,%d,%d",
                l.getWorld() != null ? l.getWorld().getName() : "?",
                l.getBlockX(), l.getBlockY(), l.getBlockZ()
        );
    }
}