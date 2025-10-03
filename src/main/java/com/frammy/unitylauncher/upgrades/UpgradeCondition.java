package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.util.Tristate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class UpgradeCondition implements TabExecutor {

    public static boolean DEBUG = true; // включаем дебаг

    private static void dbg(String msg) {
        if (DEBUG) Bukkit.getLogger().info("[UpgradeCondition] " + msg);
    }

    /* === СЮДА ПОДКЛЮЧАЕМ АВТО-ОБНОВЛЕНИЕ === */
    public static void initTestScheduler() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    applyZoneEffects(p);
                    if (DEBUG) {
                        String country = getCountryAt(p.getLocation());
                        dbg("Player=" + p.getName() + ", country=" + country);
                    }
                }
            }
        }.runTaskTimer(UnityLauncher.getInstance(), 20L, 20L); // раз в секунду
    }

    /* === Команда для ручной проверки === */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Команда только для игроков");
            return true;
        }

        Location loc = p.getLocation();
        String country = getCountryAt(loc);
        p.sendMessage("§7--- §aПроверка апгрейдов §7---");
        p.sendMessage("Страна на локации: " + (country == null ? "§cнет" : "§e" + country));

        // тестовые апгрейды (замени своими ключами)
        String[] testKeys = {
                "unity.zone.haste.basic",
                "unity.zone.haste.advanced",
                "unity.zone.speed.basic",
                "unity.zone.speed.advanced",
                "unity.zone.resistance.basic",
                "unity.zone.resistance.advanced"
        };

        for (String key : testKeys) {
            boolean has = hasGlobalUpgrade(p, key) || hasGlobalUpgrade(country, key);
            p.sendMessage(" - " + key + ": " + (has ? "§aДА" : "§cнет"));
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return List.of();
    }

    // === ТВОЙ ИСХОДНЫЙ КОД НИЖЕ (я его не правил) ===

    private static String resolveCountryFromZone(ZoneInfo zone) {
        if (zone == null) return null;
        try {
            Method m = zone.getClass().getMethod("getOwnerCountry");
            Object v = m.invoke(zone);
            if (v instanceof String s && !s.isBlank()) return s;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            dbg("resolveCountryFromZone:getOwnerCountry error: " + t.getMessage());
        }
        try {
            Method m = zone.getClass().getMethod("getCountry");
            Object v = m.invoke(zone);
            if (v instanceof String s && !s.isBlank()) return s;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            dbg("resolveCountryFromZone:getCountry error: " + t.getMessage());
        }
        try {
            ZoneType type = zone.getType();
            if (type == ZoneType.COUNTRY) {
                try {
                    Method m = zone.getClass().getMethod("getName");
                    Object v = m.invoke(zone);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Throwable t) {
                    dbg("resolveCountryFromZone:getName error: " + t.getMessage());
                }
            }
            if (type == ZoneType.COLONY) {
                try {
                    Method m = zone.getClass().getMethod("getOwnerCountry");
                    Object v = m.invoke(zone);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            dbg("resolveCountryFromZone:getType error: " + t.getMessage());
        }
        return null;
    }

    public static String getCountryAt(Location loc) {
        if (loc == null) return null;
        var zm = UnityLauncher.getInstance().getZoneManager();
        ZoneInfo zone = zm.getZoneAt(loc);
        if (zone == null) return null;
        String country = resolveCountryFromZone(zone);
        if (country != null) dbg("getCountryAt: " + country + " @" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
        return country;
    }

    public static boolean hasGlobalUpgradeAt(Location loc, String permissionKey) {
        String country = getCountryAt(loc);
        if (country == null) return false;
        return hasGlobalUpgrade(country, permissionKey);
    }

    public static int getTieredGlobalUpgradeAt(Location loc, String baseKey, int maxLevel) {
        for (int lvl = maxLevel; lvl >= 1; lvl--) {
            if (hasGlobalUpgradeAt(loc, baseKey + "." + lvl)) return lvl;
        }
        return 0;
    }

    public static boolean hasGlobalUpgrade(Player player, String permissionKey) {
        if (player == null || !player.isOnline()) return false;
        boolean result = player.hasPermission(permissionKey);
        dbg("hasGlobalUpgrade(player=" + player.getName() + ", key=" + permissionKey + ") -> " + result);
        return result;
    }

    public static boolean hasGlobalUpgrade(String country, String permissionKey) {
        if (country == null || permissionKey == null || permissionKey.isEmpty()) return false;
        String groupName = countryToGroup(country);
        try {
            LuckPerms lp = LuckPermsProvider.get();
            Group group = lp.getGroupManager().getGroup(groupName);
            if (group == null) {
                Optional<Group> opt = lp.getGroupManager().loadGroup(groupName).join();
                group = opt.orElse(null);
                if (group == null) {
                    dbg("hasGlobalUpgrade(country): group not found: " + groupName);
                    return false;
                }
            }
            QueryOptions opts = lp.getContextManager().getStaticQueryOptions();
            Tristate tri = group.getCachedData().getPermissionData(opts).checkPermission(permissionKey);
            boolean result = tri.asBoolean();
            dbg("hasGlobalUpgrade(country=" + country + ", group=" + groupName + ", key=" + permissionKey + ") -> " + result);
            return result;
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UpgradeCondition] LuckPerms check failed for country=" + country + ", key=" + permissionKey + " : " + t.getMessage());
            return false;
        }
    }

    private static String countryToGroup(String country) {
        return country.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    public static boolean hasZoneUpgrade(Player player, String zoneType, String permissionKey) {
        if (player == null || !player.isOnline()) return false;
        var zm = UnityLauncher.getInstance().getZoneManager();
        ZoneInfo zone = zm.getZoneAt(player.getLocation());
        if (zone == null) {
            dbg("hasZoneUpgrade: zone=null for " + player.getName());
            return false;
        }
        if (!zoneType.equalsIgnoreCase(zone.getType().toString())) {
            dbg("hasZoneUpgrade: expected=" + zoneType + ", found=" + zone.getType());
            return false;
        }
        boolean result = player.hasPermission(permissionKey);
        dbg("hasZoneUpgrade(player=" + player.getName() + ", zoneType=" + zoneType + ", key=" + permissionKey + ") -> " + result);
        return result;
    }

    public static void applyZoneEffects(Player p) {
        if (p == null || !p.isOnline()) return;
        if (hasZoneUpgrade(p, "INDUSTRIAL", "unity.zone.haste.advanced")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 20 * 12, 1, true, false));
        } else if (hasZoneUpgrade(p, "INDUSTRIAL", "unity.zone.haste.basic")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 20 * 12, 0, true, false));
        } else {
            p.removePotionEffect(PotionEffectType.FAST_DIGGING);
        }
        if (hasZoneUpgrade(p, "RESIDENTIAL", "unity.zone.speed.advanced")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 12, 1, true, false));
        } else if (hasZoneUpgrade(p, "RESIDENTIAL", "unity.zone.speed.basic")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 12, 0, true, false));
        } else {
            p.removePotionEffect(PotionEffectType.SPEED);
        }
        if (hasZoneUpgrade(p, "MILITARY", "unity.zone.resistance.advanced")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 12, 1, true, false));
        } else if (hasZoneUpgrade(p, "MILITARY", "unity.zone.resistance.basic")) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 12, 0, true, false));
        } else {
            p.removePotionEffect(PotionEffectType.DAMAGE_RESISTANCE);
        }
    }
}
