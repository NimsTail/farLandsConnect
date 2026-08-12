package com.frammy.unitylauncher.zones.quota;
import net.luckperms.api.model.user.User;
import java.util.UUID;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Objects;

public final class ZoneQuotaService {

    public interface ZoneSnapshot {
        Collection<ZoneInfo> allZones();
    }

    private static final String SHOP_PERM_PREFIX = "unity.shop.";
    private final ZoneSnapshot snapshot;

    public ZoneQuotaService(ZoneSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    // ------------------- SHOP personal quota -------------------

    public int getAllowedShopZonesByPerm(Player p) {
        if (p == null) return 0;

        int max = 0;
        try {
            for (var info : p.getEffectivePermissions()) {
                if (info == null || !info.getValue()) continue;
                String perm = info.getPermission();

                if (!perm.startsWith(SHOP_PERM_PREFIX)) continue;

                String tail = perm.substring(SHOP_PERM_PREFIX.length()).trim();
                try {
                    int n = Integer.parseInt(tail);
                    if (n > max) max = n;
                } catch (NumberFormatException ignored) { }
            }
        } catch (Throwable ignored) { }

        return max;
    }

    public int countOwnedZones(Player owner, ZoneType type) {
        if (owner == null || type == null) return 0;

        String name = owner.getName();
        int count = 0;
        for (ZoneInfo z : snapshot.allZones()) {
            if (z.getType() != type) continue;
            if (!eqCi(z.getOwner(), name)) continue;
            count++;
        }
        return count;
    }

    public boolean checkPersonalShopQuota(Player p) {
        int allowed = getAllowedShopZonesByPerm(p);
        int have = countOwnedZones(p, ZoneType.SHOP);

        if (allowed <= 0) {
            p.sendMessage(ChatColor.RED + "У вас нет разрешения на создание магазинов."
                    + ChatColor.GRAY + " Нужно право " + ChatColor.YELLOW + "unity.shop.<N>"
                    + ChatColor.GRAY + " (например unity.shop.1).");
            return false;
        }

        if (have >= allowed) {
            p.sendMessage(ChatColor.RED + "Лимит личных магазинов достигнут: "
                    + ChatColor.YELLOW + have + ChatColor.RED + "/" + ChatColor.YELLOW + allowed + ChatColor.RED + ".");
            return false;
        }

        return true;
    }

    // ------------------- COUNTRY quota via LuckPerms group -------------------

    private static String lpTypeKey(ZoneType type) {
        return switch (type) {
            case INDUSTRIAL -> "industrial";
            case BANK -> "bank";
            case HOSPITAL -> "hospital";
            case PARK -> "park";
            case CHURCH -> "church";
            case LIBRARY -> "library";
            case GREENHOUSE -> "greenhouse";
            case COLONY -> "colony";
            case COUNTRY -> "country";
            case SHOP -> "shop";
            case PLOT -> "plot";
            case MILITARY -> "military";
        };
    }

    private LuckPerms getLuckPerms() {
        try {
            var reg = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
            return reg != null ? reg.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ВАЖНО: имя LP-группы страны — "country_<Countries.Id>" (см. addCountry.php), а не
    // транслитерированное имя — иначе имя группы зависело бы от алфавита названия страны.
    private Group resolveCountryGroup(LuckPerms lp, String countryName) {
        if (lp == null || countryName == null || countryName.isBlank()) return null;

        String id = com.frammy.unitylauncher.upgrades.UpgradeCondition.resolveCountryGroupId(countryName);
        if (id == null) return null;

        return lp.getGroupManager().getGroup("country_" + id);
    }

    /** Читаем лимит из пермишенов страны: unity.zone.<type>.<N> */
    private int getCountryQuotaFromLuckPerms(String countryName, ZoneType type) {
        if (type == ZoneType.SHOP) return Integer.MAX_VALUE;

        LuckPerms lp = getLuckPerms();
        Group g = resolveCountryGroup(lp, countryName);
        if (g == null) return 0;

        String prefix = "unity.zone." + lpTypeKey(type) + ".";
        int max = 0;

        for (Node n : g.getNodes()) {
            if (!(n instanceof PermissionNode pn)) continue;
            if (!pn.getValue()) continue;

            String key = pn.getKey();
            if (!key.startsWith(prefix)) continue;

            String tail = key.substring(prefix.length());
            try {
                int val = Integer.parseInt(tail);
                if (val > max) max = val;
            } catch (NumberFormatException ignore) { }
        }
        return max;
    }

    /** Страна зоны: сначала ownerCountry, для COUNTRY — fallback к имени зоны */
    private static String zoneCountry(ZoneInfo z) {
        if (z == null) return null;
        String c = z.getCountryName();
        if (c != null && !c.isBlank()) return c;
        return (z.getType() == ZoneType.COUNTRY) ? z.getName() : null;
    }

    private int countCountryZones(String countryName, ZoneType type) {
        if (countryName == null || countryName.isBlank()) return 0;
        String target = normCountry(countryName);

        int count = 0;
        for (ZoneInfo z : snapshot.allZones()) {
            if (z.getType() != type) continue;

            String zc = zoneCountry(z);
            if (zc == null || zc.isBlank()) continue;

            if (Objects.equals(normCountry(zc), target)) {
                count++;
            }
        }
        return count;
    }

    public int getEffectiveCountryQuota(String countryName, ZoneType type) {
        if (type == ZoneType.SHOP) return Integer.MAX_VALUE;
        return getCountryQuotaFromLuckPerms(countryName, type);
    }

    /** How many more zones of this type the country can still create right now. */
    public int getAvailableCountryQuota(String countryName, ZoneType type) {
        if (type == ZoneType.SHOP) return Integer.MAX_VALUE;
        int quota = getEffectiveCountryQuota(countryName, type);
        int owned = countCountryZones(countryName, type);
        return Math.max(0, quota - owned);
    }

    public boolean checkCountryZoneQuota(Player p, String countryName, ZoneType type) {
        if (type == ZoneType.SHOP) return true;

        int quota = getEffectiveCountryQuota(countryName, type);
        if (quota <= 0) {
            p.sendMessage(ChatColor.RED + "У вашей страны нет разрешения на создание зон типа "
                    + ChatColor.GOLD + type
                    + ChatColor.RED + ". Обратитесь к правительству страны.");
            return false;
        }

        int owned = countCountryZones(countryName, type);
        if (owned >= quota) {
            p.sendMessage(ChatColor.RED + "Лимит страны на зоны типа " + ChatColor.GOLD + type +
                    ChatColor.RED + " достигнут: " + ChatColor.YELLOW + quota +
                    ChatColor.RED + ". Уже создано: " + ChatColor.YELLOW + owned + ChatColor.RED + ".");
            return false;
        }
        return true;
    }

    // ------------------- tiny utils -------------------

    private static String normCountry(String s) {
        return com.frammy.unitylauncher.zones.CountryNameUtil.normalizeCountry(s);
    }

    private static boolean eqCi(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    public enum QuotaCheck { ALLOWED, DENIED, PENDING }

    public record QuotaOutcome(QuotaCheck check, String message) {
        public static QuotaOutcome allowed() { return new QuotaOutcome(QuotaCheck.ALLOWED, null); }
        public static QuotaOutcome denied(String msg) { return new QuotaOutcome(QuotaCheck.DENIED, msg); }
        public static QuotaOutcome pending() { return new QuotaOutcome(QuotaCheck.PENDING, null); }
    }

    /** Не блокирует поток. Если LuckPerms ещё не закешировал игрока - просит подождать. */
    public QuotaOutcome checkPersonalShopQuotaSafe(UUID uuid, String playerName) {
        LuckPerms lp = getLuckPerms();
        if (lp == null) return QuotaOutcome.denied("LuckPerms недоступен.");

        User user = lp.getUserManager().getUser(uuid); // не блокирует, просто читает кеш
        if (user == null) {
            lp.getUserManager().loadUser(uuid); // fire-and-forget, прогреет кеш к следующей попытке
            return QuotaOutcome.pending();
        }

        int allowed = extractShopQuota(user);
        int have = countOwnedZones(playerName, ZoneType.SHOP);

        if (allowed <= 0) return QuotaOutcome.denied("У вас нет разрешения на создание магазинов (unity.shop.<N>).");
        if (have >= allowed) return QuotaOutcome.denied("Лимит личных магазинов достигнут: " + have + "/" + allowed + ".");
        return QuotaOutcome.allowed();
    }

    private int extractShopQuota(User user) {
        int max = 0;
        for (var e : user.getCachedData().getPermissionData().getPermissionMap().entrySet()) {
            if (!e.getValue()) continue;
            String perm = e.getKey();
            if (!perm.startsWith(SHOP_PERM_PREFIX)) continue;
            try {
                int n = Integer.parseInt(perm.substring(SHOP_PERM_PREFIX.length()).trim());
                if (n > max) max = n;
            } catch (NumberFormatException ignored) {}
        }
        return max;
    }

    /** countCountryZones/resolveCountryGroup работают по имени страны, а не по игроку - тут блокировки нет вообще. */
    public QuotaOutcome checkCountryZoneQuotaSafe(String countryName, ZoneType type) {
        if (type == ZoneType.SHOP) return QuotaOutcome.allowed();
        int quota = getEffectiveCountryQuota(countryName, type);
        if (quota <= 0) {
            return QuotaOutcome.denied("У вашей страны нет разрешения на создание зон типа " + type + ".");
        }
        int owned = countCountryZones(countryName, type);
        if (owned >= quota) {
            return QuotaOutcome.denied("Лимит страны на зоны типа " + type + " достигнут: " + quota + ". Уже создано: " + owned + ".");
        }
        return QuotaOutcome.allowed();
    }

    /** Перегрузка по имени владельца - без Player, нужна и старым, и новым методам. */
    public int countOwnedZones(String ownerName, ZoneType type) {
        if (ownerName == null || type == null) return 0;
        int count = 0;
        for (ZoneInfo z : snapshot.allZones()) {
            if (z.getType() != type) continue;
            if (!eqCi(z.getOwner(), ownerName)) continue;
            count++;
        }
        return count;
    }
}
