package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import java.util.Locale;

public class UpgradeCondition {

    /** Проверка апгрейда по стране, определённой на локации. */
    public static boolean hasGlobalUpgradeAt(Location loc, String permissionKey) {
        String country = getCountryAt(loc);
        return hasGlobalUpgrade(country, permissionKey);
    }

    /** Проверка апгрейда по игроку (использует его пермишены напрямую). */
    public static boolean hasGlobalUpgrade(Player player, String permissionKey) {
        return player != null && player.isOnline() && player.hasPermission(permissionKey);
    }

    /** Проверка апгрейда по стране (LP-группа). */
    public static boolean hasGlobalUpgrade(String country, String permissionKey) {
        if (country == null || country.isBlank() || permissionKey == null || permissionKey.isBlank()) return false;

        String groupName = country.startsWith("group.") ? country : country.trim().toLowerCase(Locale.ROOT).replace(' ', '_');

        try {
            var lp = LuckPermsProvider.get();
            var gm = lp.getGroupManager();
            Group group = gm.getGroup(groupName);
            if (group == null) {
                var opt = gm.loadGroup(groupName).join();
                group = opt.orElse(null);
                if (group == null) return false;
            }
            var opts = lp.getContextManager().getStaticQueryOptions();
            return group.getCachedData().getPermissionData(opts).checkPermission(permissionKey).asBoolean();
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UpgradeCondition] LuckPerms check failed for " + groupName + ": " + t.getMessage());
            return false;
        }
    }

    /** Глобальный апгрейд действует либо по стране на локации, либо по владельцу INDUSTRIAL-зоны. */
    public static boolean hasGlobalOrIndustrialUpgradeAt(Location loc, String permissionKey) {
        if (loc == null || permissionKey == null || permissionKey.isBlank()) return false;

        String countryAtLoc = getCountryAt(loc);
        if (countryAtLoc != null && !countryAtLoc.isBlank() && hasGlobalUpgrade(countryAtLoc, permissionKey)) return true;

        try {
            ZoneInfo zi = safeGetZoneAt(loc);
            if (zi != null && zi.getType() == ZoneType.INDUSTRIAL) {
                String lpCountryGroup = resolveZoneCountryGroup(zi, loc);
                return hasGlobalUpgrade(lpCountryGroup, permissionKey);
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UpgradeCondition] zone lookup failed: " + t.getMessage());
        }
        return false;
    }

    private static ZoneManager zoneManager() {
        try { return UnityLauncher.getInstance().getZoneManager(); }
        catch (Throwable ignored) { return null; }
    }

    private static ZoneInfo safeGetZoneAt(Location loc) {
        ZoneManager zm = zoneManager();
        return (zm != null && loc != null) ? zm.getZoneAt(loc) : null;
    }

    /** Определяет страну, которой принадлежит указанная локация. */
    public static String getCountryAt(Location loc) {
        if (loc == null) return null;
        ZoneManager z = zoneManager();
        if (z == null) return null;
        ZoneInfo zone = z.getZoneAt(loc);
        if (zone == null) return null;

        try {
            if (zone.getType() == ZoneType.COUNTRY && zone.getName() != null && !zone.getName().isBlank()) return zone.getName();
            if (zone.getOwnerCountry() != null && !zone.getOwnerCountry().isBlank()) return zone.getOwnerCountry();
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Возвращает имя LP-группы страны, владеющей зоной.
     * 1) Берёт сохранённое ownerCountry, если оно задано.
     * 2) Если пусто — ищет через владельца зоны (ownerName → страна из БД).
     * 3) Если не найдено — fallback на страну по локации.
     */
    private static String resolveZoneCountryGroup(ZoneInfo zi, Location loc) {
        try {
            String oc = zi.getOwnerCountry();
            if (oc != null && !oc.isBlank()) return oc;

            String ownerName = zi.zoneOwner;
            if (ownerName != null && !ownerName.isBlank()) {
                CountryRegistryJdbc reg = new CountryRegistryJdbc();
                String country = reg.getCountryByPlayerName(ownerName);
                if (country != null && !country.isBlank()) {
                    zi.setOwnerCountry(country);
                    return country;
                }
            }

            return getCountryAt(loc);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UpgradeCondition] resolveZoneCountryGroup failed: " + t.getMessage());
            return null;
        }
    }
}
