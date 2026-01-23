package com.frammy.unitylauncher.zones;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.geom.ZoneGeometry;
import com.frammy.unitylauncher.zones.geom.ZoneOverlapRules;
import com.frammy.unitylauncher.zones.quota.ZoneQuotaService;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

import static com.frammy.unitylauncher.UnityCommands.calculateSurfaceArea;

public final class ZoneValidationService {

    public record Result(boolean success, String message) {
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String msg) { return new Result(false, msg); }
    }

    private static final EnumSet<ZoneType> PARENTS = EnumSet.of(ZoneType.COUNTRY, ZoneType.COLONY);
    private static final EnumSet<ZoneType> FREE = EnumSet.of(ZoneType.SHOP, ZoneType.COUNTRY, ZoneType.COLONY);

    private final UnityLauncher ul;
    private final ZoneQuotaService quotaService;
    private final Map<ZoneType, ZoneTypeData> zoneLimits;
    private final Map<String, ZoneInfo> zoneList;

    public ZoneValidationService(
            UnityLauncher ul,
            ZoneQuotaService quotaService,
            Map<ZoneType, ZoneTypeData> zoneLimits,
            Map<String, ZoneInfo> zoneList
    ) {
        this.ul = ul;
        this.quotaService = quotaService;
        this.zoneLimits = zoneLimits;
        this.zoneList = zoneList;
        // чтобы не плодить eqCi где попало
    }

    /* =========================
       Общие хелперы
       ========================= */

    public static boolean mustBeInsideCountryOrColony(ZoneType type) {
        return type != null && !FREE.contains(type);
    }

    private static String normCountry(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        t = t.replace(' ', '_');
        return t.replaceAll("[^a-z0-9_\\-.]", "");
    }

    /** Страна зоны: сначала ownerCountry, для COUNTRY — fallback к имени зоны */
    private static String zoneCountry(ZoneInfo z) {
        if (z == null) return null;
        String c = z.getCountryName();
        if (c != null && !c.isBlank()) return c;
        return (z.getType() == ZoneType.COUNTRY) ? z.getName() : null;
    }

    private static Result ensureSameWorld(List<Location> pts) {
        if (pts == null || pts.isEmpty()) return Result.ok();
        World w0 = pts.getFirst().getWorld();
        if (w0 == null) return Result.fail(ChatColor.RED + "Все точки должны быть в одном мире.");
        boolean ok = pts.stream().allMatch(l -> l.getWorld() != null && l.getWorld().equals(w0));
        return ok ? Result.ok() : Result.fail(ChatColor.RED + "Все точки должны быть в одном мире.");
    }

    private static Result ensureMin3(List<Location> pts) {
        return (pts != null && pts.size() >= 3)
                ? Result.ok()
                : Result.fail(ChatColor.RED + "Нужно минимум 3 точки!");
    }

    private Result ensureTypeKnown(ZoneType type) {
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return Result.fail(ChatColor.RED + "Неверный тип зоны!");
        return Result.ok();
    }

    private Result ensureAreaOk(List<Location> pts, ZoneType type) {
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return Result.fail(ChatColor.RED + "Для типа " + ChatColor.YELLOW + type + ChatColor.RED + " не задан ZoneTypeData.");
        double area = calculateSurfaceArea(pts);
        if (area > ztd.areaLimit()) {
            return Result.fail(ChatColor.RED + "Площадь превышает максимум для " + type + ": "
                    + (int) area + " > " + (int) ztd.areaLimit() + " блоков².");
        }
        return Result.ok();
    }

    private static Result ensureNoSelfIntersections(List<Location> pts) {
        if (ZoneGeometry.hasSelfIntersections(ZoneGeometry.poly2D(pts))) {
            return Result.fail(ChatColor.RED + "Фигура самопересекается.");
        }
        return Result.ok();
    }

    /* =========================
       Валидация старта (первой точки): /addcorner
       ========================= */

    public Result validateStartAddCorner(Player p, ZoneType type, boolean playerAlreadyHasCountryZone) {
        if (p == null || type == null) return Result.fail(ChatColor.RED + "Неверные параметры команды.");
        Result t = ensureTypeKnown(type);
        if (!t.success()) return t;

        // COUNTRY
        if (type == ZoneType.COUNTRY) {
            if (playerAlreadyHasCountryZone) {
                return Result.fail(ChatColor.RED + "У вас уже есть территория Государства. Нельзя создавать вторую.");
            }

            String country = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (country == null || country.isBlank()) {
                return Result.fail(ChatColor.RED + "Для зон типа COUNTRY требуется состоять в стране.");
            }

            if (p.getWorld().getEnvironment() != World.Environment.NORMAL) {
                return Result.fail(ChatColor.RED + "Государство можно создавать только в Overworld.");
            }

            // лидерство проверяешь в buildZoneCountry — оставляем там
            return Result.ok();
        }

        // COLONY
        if (type == ZoneType.COLONY) {
            String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
            if (playerCountry == null || playerCountry.isBlank()) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
            }
            if (!ul.countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
                return Result.fail(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Колонию.");
            }
            return Result.ok();
        }

        // SHOP — персональная квота
        if (type == ZoneType.SHOP) {
            return quotaService.checkPersonalShopQuota(p)
                    ? Result.ok()
                    : Result.fail(ChatColor.RED + "Превышена персональная квота магазинов.");
        }

        // Внутренние зоны — нужен country + country-quota
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());
        if (playerCountry == null || playerCountry.isBlank()) {
            return Result.fail(ChatColor.RED + "Нельзя создать зону: вы не состоите ни в одной стране.");
        }

        return quotaService.checkCountryZoneQuota(p, playerCountry, type)
                ? Result.ok()
                : Result.fail(ChatColor.RED + "Превышена квота страны на тип " + type + ".");
    }

    /* =========================
       Валидация добавления очередной точки: /addcorner
       (когда полигон уже можно “закрыть”)
       ========================= */

    public Result validateDraftAfterAddingCorner(Player p, ZoneType type, List<Location> draft) {
        if (draft == null) return Result.fail(ChatColor.RED + "Черновик полигона пуст.");

        // если черновик уже >= 3 точек и это “внутренняя” зона — должна помещаться внутрь одной страны/колонии
        if (mustBeInsideCountryOrColony(type) && draft.size() >= 3) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(draft, PARENTS, zoneList.values());
            if (parent == null) {
                return Result.fail(ChatColor.RED + "Зона " + type + " должна полностью находиться внутри Государства или Колонии.");
            }
        }

        // площадь по draft (мягкая проверка) + самопересечение
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return Result.fail(ChatColor.RED + "Неверный тип зоны!");

        if (!ZoneGeometry.areaOkDraft(draft, ztd)) {
            return Result.fail(ChatColor.GRAY + "Площадь превышает максимум " + (int) ztd.areaLimit() + " блоков².");
        }
        if (ZoneGeometry.hasSelfIntersections(ZoneGeometry.poly2D(draft))) {
            return Result.fail(ChatColor.RED + "Фигура самопересекается.");
        }

        return Result.ok();
    }

    /* =========================
       Валидация buildZone (обычные зоны + колонии + магазины)
       ========================= */

    public Result validateBuildZone(Player p, ZoneType type, String zoneName, List<Location> pts) {
        if (p == null || type == null) return Result.fail(ChatColor.RED + "Неверные параметры.");
        Result t = ensureTypeKnown(type);
        if (!t.success()) return t;

        if (type != ZoneType.COUNTRY && (zoneName == null || zoneName.isBlank())) {
            return Result.fail(ChatColor.RED + "Название не может быть пустым.");
        }

        Result m3 = ensureMin3(pts);
        if (!m3.success()) return m3;

        Result sw = ensureSameWorld(pts);
        if (!sw.success()) return sw;

        Result si = ensureNoSelfIntersections(pts);
        if (!si.success()) return si;

        Result ar = ensureAreaOk(pts, type);
        if (!ar.success()) return ar;

        // SHOP: персональная квота
        if (type == ZoneType.SHOP) {
            if (!quotaService.checkPersonalShopQuota(p)) {
                return Result.fail(ChatColor.RED + "Превышена персональная квота магазинов.");
            }
        }

        // Внутренние/колония: квота страны (если есть страна)
        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());

        if (type != ZoneType.SHOP) {
            if (playerCountry != null && !playerCountry.isBlank()) {
                if (!quotaService.checkCountryZoneQuota(p, playerCountry, type)) {
                    return Result.fail(ChatColor.RED + "Превышена квота страны на тип " + type + ".");
                }
            } else if (type == ZoneType.COLONY) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
            }
        }

        // COLONY: лидер + не пересекаться с любыми зонами
        if (type == ZoneType.COLONY) {
            if (playerCountry.isBlank()) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
            }
            if (!ul.countryRegistryJdbc.isCountryLeaderCached(p.getName())) {
                return Result.fail(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Колонию.");
            }
            if (ZoneOverlapRules.areaHasAnyZonesInsideOrIntersecting(pts, null, zoneList.values())) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: внутри/по границе уже есть другие зоны.");
            }
        }

        boolean mustInside = mustBeInsideCountryOrColony(type);

        // Внутренние: внутри одной COUNTRY/COLONY и в своей стране
        if (mustInside) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(pts, PARENTS, zoneList.values());
            if (parent == null) {
                return Result.fail(ChatColor.RED + "Зона " + type + " должна полностью находиться внутри Государства или Колонии.");
            }

            String parentCountry = zoneCountry(parent);
            if (playerCountry == null || playerCountry.isBlank()
                    || !Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                return Result.fail(ChatColor.RED + "Эта зона может быть создана только в пределах вашей страны/колонии.");
            }

            // и не пересекать чужие зоны, кроме своего родителя
            for (Location loc : pts) {
                ZoneInfo overlap = findOverlapAt(loc, p.getName(), type, parent.getMarkerID());
                if (overlap == null) continue;

                // если мы наткнулись на своего родителя — ок
                if (ZoneOverlapRules.isCountryOrColony(overlap) && Objects.equals(overlap.getMarkerID(), parent.getMarkerID())) {
                    continue;
                }

                return Result.fail(ChatColor.RED + "Нельзя создать зону: точка " + loc.toVector()
                        + " пересекается с другой зоной \"" + overlap.getName() + "\".");
            }
        }

        // SHOP: нельзя в чужой стране/колонии, и нельзя пересекать границы стран/колоний
        if (type == ZoneType.SHOP) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(pts, PARENTS, zoneList.values());
            if (parent != null) {
                String parentCountry = zoneCountry(parent);
                if (playerCountry == null || playerCountry.isBlank()
                        || !Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                    return Result.fail(ChatColor.RED + "Магазин нельзя строить в чужой стране/колонии.");
                }
            } else {
                if (ZoneOverlapRules.areaIntersectsAnyOfTypes(pts, PARENTS, zoneList.values())) {
                    return Result.fail(ChatColor.RED + "Магазин не должен пересекать границы стран/колоний.");
                }
            }
        }

        return Result.ok();
    }

    /* =========================
       Валидация update corners (+/-) для уже существующей зоны zi
       (всё, что касается геометрии/пересечений/страны)
       ========================= */

    public Result validateUpdateCornersDraft(Player p, ZoneInfo zi, List<Location> tmp, boolean isPlus) {
        if (p == null || zi == null || tmp == null) return Result.fail(ChatColor.RED + "Неверные параметры.");
        ZoneType type = zi.getType();

        // общие: самопересечения + площадь
        if (ZoneGeometry.hasSelfIntersections(ZoneGeometry.poly2D(tmp))) {
            return Result.fail(ChatColor.GRAY + "Фигура самопересекается.");
        }
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return Result.fail(ChatColor.RED + "Не задан лимит для " + type + ".");
        if (!ZoneGeometry.areaOk(tmp, ztd)) {
            return Result.fail(ChatColor.GRAY + "Площадь превышает лимит.");
        }

        boolean mustInside = mustBeInsideCountryOrColony(type);

        // внутренние типы всегда должны оставаться внутри страны/колонии
        if (mustInside) {
            if (ZoneOverlapRules.findSingleContainingZoneOfTypes(tmp, PARENTS, zoneList.values()) == null) {
                return Result.fail(ChatColor.RED + "Эта зона должна целиком оставаться внутри Государства или Колонии.");
            }
        }

        // SHOP: нельзя внутрь чужой страны/колонии и нельзя пересекать границы
        if (type == ZoneType.SHOP) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(tmp, PARENTS, zoneList.values());
            String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(p.getName());

            if (parent != null) {
                String parentCountry = zoneCountry(parent);
                if (playerCountry == null || playerCountry.isBlank()
                        || !Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                    return Result.fail(ChatColor.RED + "Магазин нельзя перемещать внутрь чужой страны/колонии.");
                }
            } else {
                if (ZoneOverlapRules.areaIntersectsAnyOfTypes(tmp, PARENTS, zoneList.values())) {
                    return Result.fail(ChatColor.RED + "Магазин не должен пересекать границы стран/колоний.");
                }
            }
        }

        // COUNTRY/COLONY: нельзя, чтобы новые границы включали/пересекали другие зоны
        if (type == ZoneType.COUNTRY || type == ZoneType.COLONY) {
            if (ZoneOverlapRules.areaHasAnyZonesInsideOrIntersecting(tmp, zi.getMarkerID(), zoneList.values())) {
                return Result.fail(ChatColor.RED + "Нельзя менять границы: внутри/по границе окажутся другие зоны.");
            }
        } else {
            // для НЕ-страны: если уменьшаем (-) — у тебя была жесткая проверка “не пересекать вообще”
            if (!isPlus) {
                if (ZoneOverlapRules.areaHasAnyZonesInsideOrIntersecting(tmp, zi.getMarkerID(), zoneList.values())) {
                    return Result.fail(ChatColor.RED + "Нельзя менять границы: новая форма зоны пересекается с другой зоной.");
                }
            }
        }

        return Result.ok();
    }

    /* =========================
       Локальный overlap-хелпер (скопирован из ZoneManager логикой один-в-один)
       ========================= */

    private ZoneInfo findOverlapAt(Location loc, String owner, ZoneType currentType, String ignoreMarkerId) {
        for (ZoneInfo z : zoneList.values()) {
            if (ignoreMarkerId != null && ignoreMarkerId.equals(z.getMarkerID())) continue;
            if (ZoneManager.NameUtil.eqCi(z.getOwner(), owner) && z.getType() == currentType) continue;

            if (!ZoneGeometry.worldOk(z.getCorners(), loc.getWorld())) continue;
            if (ZoneGeometry.pointInZone(loc, z.getCorners(), -64, 255)) return z;
        }
        return null;
    }
}
