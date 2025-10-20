package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Единая точка проверок апгрейдов / пермишенов.
 * Теперь страна игрока берётся из кэша CountryRegistryJdbc,
 * который обновляется фоново (без SQL в горячем треде).
 */
public class UpgradeCondition {

    public static boolean DEBUG = false;

    private static void dbg(String msg) {
        if (DEBUG) Bukkit.getLogger().info("[UpgradeCondition] " + msg);
    }

    private static ZoneManager zones() {
        return UnityLauncher.getInstance().getZoneManager();
    }

    /* ============================================================
       ОСНОВНЫЕ ПРОВЕРКИ
       ============================================================ */

    /**
     * Проверка глобального апгрейда у страны, в которой состоит игрок.
     * Если страна не найдена — false.
     */
    public static boolean hasGlobalUpgrade(Player player, String permissionKey) {
        if (player == null || permissionKey == null) return false;

        UnityLauncher ul = UnityLauncher.getInstance();
        String country = ul.countryRegistryJdbc.getCountryCached(player.getName());
        if (country == null || country.isBlank()) {
            ul.countryRegistryJdbc.ensureScheduledRefresh(player.getName());
            dbg("Игрок " + player.getName() + " без страны — нет доступа к " + permissionKey);
            return false;
        }

        boolean result = countryHasPermission(country, permissionKey);
        dbg("hasGlobalUpgrade: player=" + player.getName() + ", country=" + country + ", key=" + permissionKey + " => " + result);
        return result;
    }

    /**
     * Проверка апгрейда в зоне — если зона принадлежит игроку,
     * используется страна создателя (из кэша CountryRegistryJdbc).
     * Если зона — государственная, берётся её собственная страна.
     */
    public static boolean hasUpgradeInZone(Player player, ZoneInfo zone, String permissionKey) {
        if (player == null || zone == null || permissionKey == null) return false;

        String country = resolveCountryForZone(zone);
        if (country == null) {
            // fallback: попробуем по игроку
            country = UnityLauncher.getInstance().countryRegistryJdbc.getCountryCached(player.getName());
        }
        if (country == null) {
            dbg("hasUpgradeInZone: нет страны ни у зоны, ни у игрока.");
            return false;
        }

        boolean result = countryHasPermission(country, permissionKey);
        dbg("hasUpgradeInZone: player=" + player.getName() + ", zone=" + zone.getName()
                + ", country=" + country + ", key=" + permissionKey + " => " + result);
        return result;
    }

    /**
     * Проверка апгрейда по типу зоны, где находится игрок (например, INDUSTRIAL).
     * Автоматически определяет зону по координатам игрока.
     */
    public static boolean hasUpgradeInZoneType(Player player, ZoneType type, String permissionKey) {
        if (player == null || type == null || permissionKey == null) return false;

        ZoneInfo zi = zones().getZoneAt(player.getLocation());
        if (zi == null || zi.getType() != type) return false;

        return hasUpgradeInZone(player, zi, permissionKey);
    }

    /* ============================================================
       НИЗКОУРОВНЕВАЯ ПРОВЕРКА ПРАВ У ГРУППЫ СТРАНЫ
       ============================================================ */

    /**
     * Проверка через LuckPerms у группы страны (group.<country>).
     * Возвращает true, если пермишен есть у группы страны.
     */
    public static boolean countryHasPermission(String country, String permissionKey) {
        if (country == null || permissionKey == null) return false;

        try {
            var lp = LuckPermsProvider.get();
            String groupName = normalizeGroupName(country); // БЕЗ "group."
            Group group = lp.getGroupManager().getGroup(groupName);
            if (group == null) {
                dbg("countryHasPermission: группа '" + groupName + "' не найдена.");
                return false;
            }

            // Контексты LP
            var opts = lp.getContextManager().getStaticQueryOptions();
            boolean result = group.getCachedData()
                    .getPermissionData(opts)
                    .checkPermission(permissionKey)
                    .asBoolean();

            dbg("countryHasPermission: " + groupName + " -> " + permissionKey + " = " + result);
            return result;
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[UpgradeCondition] Ошибка проверки пермишена: " + t.getMessage());
            return false;
        }
    }


    /* ============================================================
       УТИЛИТЫ
       ============================================================ */

    /** Определяет страну, которой принадлежит зона (государство или через создателя). */
    private static String resolveCountryForZone(ZoneInfo zi) {
        if (zi == null) return null;

        if (zi.getType() == ZoneType.COUNTRY && zi.hasCountry()) {
            return zi.getCountryName();
        }

        String owner = zi.getOwner();
        if (owner == null || owner.isBlank()) return null;

        UnityLauncher ul = UnityLauncher.getInstance();
        String country = ul.countryRegistryJdbc.getCountryCached(owner);
        if (country == null) ul.countryRegistryJdbc.ensureScheduledRefresh(owner);

        return country;
    }

    /** Нормализует имя страны в формат group.<normalized> */
    private static String normalizeGroupName(String country) {
        return country.trim().toLowerCase()
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_\\-.]", "");
    }
    // Публичный адаптер: получить страну-владельца для зоны (или null)
    public static String resolveCountryForZonePublic(com.frammy.unitylauncher.zones.ZoneInfo zi) {
        return resolveCountryForZone(zi);
    }

}
