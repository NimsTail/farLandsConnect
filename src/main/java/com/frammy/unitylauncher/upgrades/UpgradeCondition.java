package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class UpgradeCondition {

    private UpgradeCondition() {}
    public enum GatingAction {
        CRAFT,
        USE
    }

    private static boolean debug() {
        try {
            var pl = UnityLauncher.getInstance();
            if (pl == null) return false;
            var um = pl.getUpgradesManager();
            if (um == null) return false;
            var c = um.config();
            if (c == null) return false;
            return c.core().debug();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void d(String msg) {
        if (debug()) Bukkit.getLogger().info("[UL/UpgradeCondition] " + msg);
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

    public static String countryCanonicalAt(org.bukkit.Location loc) {
        if (loc == null) return null;
        var ul = com.frammy.unitylauncher.UnityLauncher.getInstance();
        if (ul == null || ul.getZoneManager() == null) return null;
        return ul.getZoneManager().getCountryCanonicalAt(loc);
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
    private static final long NODE_CACHE_MS = 1500L;
    private static final ConcurrentHashMap<String, Long> NODE_TRUE_CACHE = new ConcurrentHashMap<>();
    private static String nk(String c, String n) { return c + "§" + n; }
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

        long now = System.currentTimeMillis();
        Long hit = NODE_TRUE_CACHE.get(nk(canonicalCountry, node));
        if (hit != null && now - hit < NODE_CACHE_MS) return true;

        Group g = getCountryGroup(canonicalCountry);
        if (g == null) return false;

        try {
            LuckPerms lp = LuckPermsProvider.get();
            var opts = lp.getContextManager().getStaticQueryOptions();
            boolean ok = g.getCachedData().getPermissionData(opts).checkPermission(node).asBoolean();
            if (ok) {
                if (NODE_TRUE_CACHE.size() > 50_000) NODE_TRUE_CACHE.clear();
                NODE_TRUE_CACHE.put(nk(canonicalCountry, node), now);
            }

            return ok;
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

    // кто владеет локацией (COUNTRY или COLONY), вернуть канон. имя страны
    // ВАЖНО: работает "сквозь" дочерние зоны (industrial/shop/church/...)
    public static String locationCountryOwner(Location loc) {
        if (loc == null) return null;

        try {
            var zm = UnityLauncher.getInstance().getZoneManager();
            if (zm == null) {
                d("locationCountryOwner(" + p(loc) + ") -> zoneManager == null");
                return null;
            }

            // ZoneManager уже умеет "глубоко" резолвить страну через родителя
            String canon = zm.getCountryCanonicalAt(loc);
            if (canon == null || canon.isBlank()) return null;
            return canon;
        } catch (Throwable t) {
            d("locationCountryOwner EX " + t);
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

    public static String locationCountryOwnerDeep(Location loc) {
        if (loc == null) return null;

        var zm = UnityLauncher.getInstance().getZoneManager();
        if (zm == null) return null;

        // 1) COUNTRY
        for (ZoneInfo z : zm.getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.COUNTRY) continue;
            if (z.contains2D(loc)) return z.getCountryName();
        }

        // 2) COLONY
        for (ZoneInfo z : zm.getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.COLONY) continue;
            if (z.contains2D(loc)) return z.getCountryName();
        }

        return null;

    }

    private static String p(Location l) {
        if (l == null) return "null";
        return String.format(
                "%s@%d,%d,%d",
                l.getWorld() != null ? l.getWorld().getName() : "?",
                l.getBlockX(), l.getBlockY(), l.getBlockZ()
        );
    }

    /* =====================
   GATING HELPERS
   ===================== */

    public static Set<Material> parseMaterialSet(java.util.List<String> list) {
        if (list == null || list.isEmpty()) return java.util.Set.of();

        java.util.HashSet<Material> out = new java.util.HashSet<>();
        for (String s : list) {
            if (s == null || s.isBlank()) continue;
            try {
                out.add(Material.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {}
        }
        return java.util.Set.copyOf(out);
    }

    /**
     * Универсальная проверка: можно ли игроку выполнять действие (CRAFT/USE) с данным материалом,
     * если материал находится в blocked-списке. Разрешение выдаётся по стране игрока через пермишен basePermBase.<lvl>.
     *
     * Возвращает true если:
     *  - материал не заблокирован для этого действия
     *  - или у игрока есть unlock по стране (countryMaxLevel >= requiredLevel)
     */
    public static boolean gatingAllowedByCountry(
            Player p,
            Material mat,
            GatingAction action,
            Set<Material> blockedCraft,
            Set<Material> blockedUse,
            String unlockPermBase,
            int requiredLevel
    ) {
        if (p == null || mat == null || action == null) return true;

        boolean blocked = switch (action) {
            case CRAFT -> blockedCraft != null && blockedCraft.contains(mat);
            case USE -> blockedUse != null && blockedUse.contains(mat);
        };

        if (!blocked) return true;

        String pc = playerCountryCanonical(p.getName());
        if (pc == null || pc.isBlank()) return false;

        return countryMaxLevel(pc, unlockPermBase, requiredLevel) >= requiredLevel;
    }

        /* =====================
       POTION HELPERS
       ===================== */

    /**
     * Умно применяет эффект:
     * - если уже есть эффект этого типа с amplifier выше -> ничего не делаем
     * - если amplifier такой же и длительность текущего >= новой -> ничего не делаем
     * - иначе применяем (перезаписываем)
     *
     * @return true если эффект был применён/перезаписан, false если пропущен
     */
    public static boolean applyPotionSmart(
            Player p,
            PotionEffectType type,
            int durationTicks,
            int amplifier,
            boolean ambient,
            boolean particles,
            boolean icon
    ) {
        if (p == null || type == null) return false;

        if (durationTicks <= 0) {
            // duration<=0: считаем что "нечего вешать"
            return false;
        }

        if (amplifier < 0) {
            // amplifier<0: считаем что "нечего вешать"
            return false;
        }

        PotionEffect current = p.getPotionEffect(type);
        if (current != null) {
            int curAmp = current.getAmplifier();
            int curDur = current.getDuration();

            // есть более сильный эффект -> не трогаем
            if (curAmp > amplifier) return false;

            // тот же уровень и текущий дольше/равен -> не трогаем
            if (curAmp == amplifier && curDur >= durationTicks) return false;
        }

        PotionEffect eff = new PotionEffect(type, durationTicks, amplifier, ambient, particles, icon);
        // force=true: чтобы гарантированно перезаписать, когда мы решили что надо
        p.addPotionEffect(eff, true);
        return true;
    }

    /**
     * Удобная обёртка: если amplifier>=0 -> applyPotionSmart, иначе remove.
     *
     * @return true если было применено/удалено, false если пропущено
     */
    public static boolean applyOrRemovePotionSmart(
            Player p,
            PotionEffectType type,
            int amplifier,
            int durationTicks,
            boolean ambient,
            boolean particles,
            boolean icon
    ) {
        if (p == null || type == null) return false;

        if (amplifier >= 0) {
            return applyPotionSmart(p, type, durationTicks, amplifier, ambient, particles, icon);
        }

        if (p.hasPotionEffect(type)) {
            p.removePotionEffect(type);
            return true;
        }
        return false;
    }

}