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
    private static final EnumSet<ZoneType> FREE = EnumSet.of(ZoneType.SHOP, ZoneType.COUNTRY, ZoneType.COLONY, ZoneType.PLOT);
    // напрямую создавать можно только Участок (PLOT), Колонию и Государство — все
    // остальные типы (SHOP/BANK/HOSPITAL/INDUSTRIAL/PARK/CHURCH/LIBRARY/GREENHOUSE)
    // отныне доступны только через повышение уже созданного Участка (UPGRADE_TYPE)
    private static final EnumSet<ZoneType> DIRECTLY_CREATABLE = EnumSet.of(ZoneType.PLOT, ZoneType.COLONY, ZoneType.COUNTRY);

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
        return CountryNameUtil.normalizeCountry(s);
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

    // было: public Result validateStartAddCorner(Player p, ZoneType type, boolean playerAlreadyHasCountryZone)
    public Result validateStartAddCorner(UUID playerUuid, String playerName, World world, ZoneType type, boolean playerAlreadyHasCountryZone) {
        if (playerName == null || type == null) return Result.fail(ChatColor.RED + "Неверные параметры команды.");
        Result t = ensureTypeKnown(type);
        if (!t.success()) return t;
        if (!DIRECTLY_CREATABLE.contains(type)) {
            return Result.fail(ChatColor.RED + "Этот тип зоны нельзя создать напрямую — сначала создайте Участок, затем повысьте его.");
        }

        if (type == ZoneType.COUNTRY) {
            if (playerAlreadyHasCountryZone) {
                return Result.fail(ChatColor.RED + "У вас уже есть территория Государства. Нельзя создавать вторую.");
            }
            String country = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
            if (country == null || country.isBlank()) {
                return Result.fail(ChatColor.RED + "Для зон типа COUNTRY требуется состоять в стране.");
            }
            if (world != null && world.getEnvironment() != World.Environment.NORMAL) {
                return Result.fail(ChatColor.RED + "Государство можно создавать только в Overworld.");
            }
            return Result.ok();
        }

        if (type == ZoneType.COLONY) {
            String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
            if (playerCountry == null || playerCountry.isBlank()) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
            }
            if (!ul.countryRegistryJdbc.isCountryLeaderCached(playerName)) {
                return Result.fail(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Колонию.");
            }
            return Result.ok();
        }

        if (type == ZoneType.SHOP) {
            var q = quotaService.checkPersonalShopQuotaSafe(playerUuid, playerName);
            return switch (q.check()) {
                case ALLOWED -> Result.ok();
                case DENIED -> Result.fail(ChatColor.RED + q.message());
                case PENDING -> Result.fail(ChatColor.GRAY + "Проверяем ваши права, повторите через пару секунд.");
            };
        }

        if (type == ZoneType.PLOT) {
            // Участок — базовая единица, доступна без страны и без квоты.
            return Result.ok();
        }

        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
        if (playerCountry == null || playerCountry.isBlank()) {
            return Result.fail(ChatColor.RED + "Нельзя создать зону: вы не состоите ни в одной стране.");
        }
        var q = quotaService.checkCountryZoneQuotaSafe(playerCountry, type);
        return switch (q.check()) {
            case ALLOWED -> Result.ok();
            case DENIED -> Result.fail(ChatColor.RED + q.message());
            case PENDING -> Result.fail(ChatColor.GRAY + "Проверяем квоту, повторите через пару секунд.");
        };
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

    // было: public Result validateBuildZone(Player p, ZoneType type, String zoneName, List<Location> pts)
    public Result validateBuildZone(UUID playerUuid, String playerName, ZoneType type, String zoneName, List<Location> pts) {
        if (playerName == null || type == null) return Result.fail(ChatColor.RED + "Неверные параметры.");
        Result t = ensureTypeKnown(type);
        if (!t.success()) return t;
        if (!DIRECTLY_CREATABLE.contains(type)) {
            return Result.fail(ChatColor.RED + "Этот тип зоны нельзя создать напрямую — сначала создайте Участок, затем повысьте его.");
        }

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

        if (type == ZoneType.SHOP) {
            var q = quotaService.checkPersonalShopQuotaSafe(playerUuid, playerName);
            if (q.check() == ZoneQuotaService.QuotaCheck.PENDING) {
                return Result.fail(ChatColor.GRAY + "Проверяем ваши права, повторите через пару секунд.");
            }
            if (q.check() == ZoneQuotaService.QuotaCheck.DENIED) {
                return Result.fail(ChatColor.RED + q.message());
            }
        }

        String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);

        if (type != ZoneType.SHOP && type != ZoneType.PLOT) {
            if (playerCountry != null && !playerCountry.isBlank()) {
                var q = quotaService.checkCountryZoneQuotaSafe(playerCountry, type);
                if (q.check() == ZoneQuotaService.QuotaCheck.PENDING) {
                    return Result.fail(ChatColor.GRAY + "Проверяем квоту, повторите через пару секунд.");
                }
                if (q.check() == ZoneQuotaService.QuotaCheck.DENIED) {
                    return Result.fail(ChatColor.RED + q.message());
                }
            } else if (type == ZoneType.COLONY) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
            }
        }

        if (type == ZoneType.COLONY) {
            if (playerCountry == null || playerCountry.isBlank()) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: вы не состоите ни в одной стране.");
            }
            if (!ul.countryRegistryJdbc.isCountryLeaderCached(playerName)) {
                return Result.fail(ChatColor.RED + "Недостаточно прав: только лидер страны может создавать Колонию.");
            }
            if (ZoneOverlapRules.areaHasAnyZonesInsideOrIntersecting(pts, null, zoneList.values())) {
                return Result.fail(ChatColor.RED + "Нельзя создать Колонию: внутри/по границе уже есть другие зоны.");
            }
        }

        boolean mustInside = mustBeInsideCountryOrColony(type);

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
            ZoneInfo overlap = findOverlapAt(pts, playerName, type, parent.getMarkerID());
            if (overlap != null && !(ZoneOverlapRules.isCountryOrColony(overlap) && Objects.equals(overlap.getMarkerID(), parent.getMarkerID()))) {
                return Result.fail(ChatColor.RED + "Нельзя создать зону: она пересекается с другой зоной \"" + overlap.getName() + "\".");
            }
        }

        if (type == ZoneType.SHOP) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(pts, PARENTS, zoneList.values());
            if (parent != null) {
                String parentCountry = zoneCountry(parent);
                if (playerCountry == null || playerCountry.isBlank()
                        || !Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                    return Result.fail(ChatColor.RED + "Магазин нельзя строить в чужой стране/колонии.");
                }
            } else if (ZoneOverlapRules.areaIntersectsAnyOfTypes(pts, PARENTS, zoneList.values())) {
                return Result.fail(ChatColor.RED + "Магазин не должен пересекать границы стран/колоний.");
            }
        }

        if (type == ZoneType.PLOT) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(pts, PARENTS, zoneList.values());

            // внутри территории страны/колонии — участок можно строить только в СВОЕЙ
            // стране, и только если ты лидер либо твоя роль имеет право buildZones
            // (и не превышен лимит участков этой роли на игрока)
            if (parent != null) {
                String parentCountry = zoneCountry(parent);
                boolean sameCountry = playerCountry != null && !playerCountry.isBlank()
                        && Objects.equals(normCountry(playerCountry), normCountry(parentCountry));
                if (!sameCountry) {
                    return Result.fail(ChatColor.RED + "Нельзя строить участок на территории чужой страны/колонии.");
                }
                if (!ul.countryRegistryJdbc.isCountryLeaderCached(playerName)) {
                    var perm = ul.countryRegistryJdbc.getPlayerZoneBuildPermission(playerName);
                    if (!perm.allowed()) {
                        return Result.fail(ChatColor.RED + "Ваша роль в стране не даёт права строить участки на её территории.");
                    }
                    int owned = countOwnedPlotsInsideParent(playerName, parent.getMarkerID());
                    if (owned >= perm.limit()) {
                        return Result.fail(ChatColor.RED + "Достигнут лимит участков в этой стране для вашей роли: "
                                + perm.limit() + ".");
                    }
                }
                // общестрановой лимит площади под участки — действует на ВСЕХ, включая лидера
                // (это ограничение ресурса страны в целом, а не персональное право роли)
                Result capResult = checkCountryPlotAreaCap(parentCountry, parent.getMarkerID(), calculateSurfaceArea(pts), null);
                if (!capResult.success()) return capResult;
            } else if (ZoneOverlapRules.areaIntersectsAnyOfTypes(pts, PARENTS, zoneList.values())) {
                // не целиком внутри (parent == null), но при этом задевает территорию
                // страны/колонии частью себя — так нельзя, участок должен быть либо
                // целиком внутри, либо целиком снаружи, без промежуточного состояния
                return Result.fail(ChatColor.RED + "Участок не может находиться одновременно внутри и снаружи Государства/Колонии — сделайте его целиком внутри либо целиком снаружи.");
            }

            // пересечение с чужими зонами запрещено ВСЕГДА (и внутри, и вне страны).
            // Свои же зоны того же типа (не PLOT) пересекать можно — а вот два
            // Участка не пересекаются НИКОГДА, даже если оба принадлежат одному
            // игроку (раньше это разрешалось и позволяло дублировать/дробить
            // территорию бессмысленно). Страна/колония-"родитель" — это ожидаемое
            // вхождение, а не конфликт, поэтому COUNTRY/COLONY тут не учитываются.
            ZoneInfo candidate = new ZoneInfo(ZoneType.PLOT, "tmp_id", zoneName, "tmp_marker", pts, playerName, org.bukkit.Color.WHITE);
            for (ZoneInfo existing : zoneList.values()) {
                if (existing.getType() == ZoneType.COUNTRY || existing.getType() == ZoneType.COLONY) continue;
                boolean sameOwner = ZoneManager.NameUtil.eqCi(existing.getOwner(), playerName);
                if (sameOwner && existing.getType() != ZoneType.PLOT) continue;
                if (ZoneOverlapRules.zonesIntersect2D(candidate, existing)) {
                    return Result.fail(ChatColor.RED + (sameOwner
                            ? "Участок пересекается с другим вашим участком \"" + existing.getName() + "\"."
                            : "Участок пересекается с зоной \"" + existing.getName() + "\", которая принадлежит другому игроку."));
                }
            }
        }

        return Result.ok();
    }

    /* =========================
       Валидация update corners (+/-) для уже существующей зоны zi
       (всё, что касается геометрии/пересечений/страны)
       ========================= */

    public Result validateUpdateCornersDraft(String playerName, ZoneInfo zi, List<Location> tmp, boolean isPlus) {
        return validateUpdateCornersDraft(playerName, zi, tmp, isPlus, 0);
    }

    /** shapeIndex — какую ИМЕННО фигуру многофигурной зоны редактируем (0 = основная/единственная у обычных зон). */
    public Result validateUpdateCornersDraft(String playerName, ZoneInfo zi, List<Location> tmp, boolean isPlus, int shapeIndex) {
        if (playerName == null || zi == null || tmp == null) return Result.fail(ChatColor.RED + "Неверные параметры.");
        ZoneType type = zi.getType();

        // общие: самопересечения + площадь
        if (ZoneGeometry.hasSelfIntersections(ZoneGeometry.poly2D(tmp))) {
            return Result.fail(ChatColor.GRAY + "Фигура самопересекается.");
        }

        // границы зоны можно только РАСШИРЯТЬ — старая территория должна
        // полностью покрываться новой формой, форму уже существующей части
        // менять/срезать нельзя
        if (!ZoneGeometry.isExpansionOnly(zi.getShapeAt(shapeIndex), tmp)) {
            return Result.fail(ChatColor.RED + "Границы можно только расширять — новая форма должна полностью включать старую территорию.");
        }

        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return Result.fail(ChatColor.RED + "Не задан лимит для " + type + ".");
        if (type == ZoneType.COUNTRY) {
            double effectiveLimit = ul.getZoneManager().countryAreaLimitFor(ZoneType.COUNTRY, zoneCountry(zi));
            ztd = ztd.withAreaLimit(effectiveLimit);
        }
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
            String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);

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

        // PLOT: та же защита от "прилипания" к границе страны/колонии, что и при
        // создании (validateBuildZone) — расширение не должно оставить участок
        // одновременно внутри и снаружи
        if (type == ZoneType.PLOT) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(tmp, PARENTS, zoneList.values());
            if (parent == null && ZoneOverlapRules.areaIntersectsAnyOfTypes(tmp, PARENTS, zoneList.values())) {
                return Result.fail(ChatColor.RED + "Участок не может находиться одновременно внутри и снаружи Государства/Колонии — сделайте его целиком внутри либо целиком снаружи.");
            }
            if (parent != null) {
                List<List<Location>> shapesAfter = new ArrayList<>(zi.getShapes());
                if (shapeIndex >= 0 && shapeIndex < shapesAfter.size()) shapesAfter.set(shapeIndex, tmp);
                double areaAfter = ZoneGeometry.totalArea(shapesAfter);
                Result capResult = checkCountryPlotAreaCap(zoneCountry(zi), parent.getMarkerID(), areaAfter, zi.getMarkerID());
                if (!capResult.success()) return capResult;
            }
        }

        // COUNTRY/COLONY: нельзя, чтобы новые границы включали/пересекали другие зоны
        // (для COUNTRY — кроме её собственных дочерних зон и личных зон лидера, см.
        // ZoneOverlapRules.isCountryOverlapExempt)
        if (type == ZoneType.COUNTRY) {
            ZoneInfo conflict = ZoneOverlapRules.findConflictingZoneForCountryChange(
                    tmp, zi.getMarkerID(), zoneList.values(), zoneCountry(zi), zi.getOwner());
            if (conflict != null) {
                return Result.fail(ChatColor.RED + "Нельзя менять границы: пересекается с зоной \""
                        + conflict.getName() + "\" (" + conflict.getType() + ").");
            }
        } else if (type == ZoneType.COLONY) {
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
       Валидация ADD_SHAPE: добавление ЕЩЁ ОДНОЙ фигуры к уже существующей зоне
       (мульти-полигон — зона может состоять из нескольких отдельных участков)
       ========================= */

    public Result validateAddShapeDraft(String playerName, ZoneInfo zi, List<Location> newShape) {
        if (playerName == null || zi == null || newShape == null) return Result.fail(ChatColor.RED + "Неверные параметры.");
        ZoneType type = zi.getType();

        if (type == ZoneType.COLONY) {
            return Result.fail(ChatColor.RED + "У Колонии не может быть несколько отдельных территорий.");
        }

        Result m3 = ensureMin3(newShape);
        if (!m3.success()) return m3;
        Result sw = ensureSameWorld(newShape);
        if (!sw.success()) return sw;

        World zoneWorld = zi.getWorld();
        if (zoneWorld == null || !Objects.equals(newShape.getFirst().getWorld(), zoneWorld)) {
            return Result.fail(ChatColor.RED + "Новая фигура должна быть в том же мире, что и остальная часть зоны.");
        }

        Result si = ensureNoSelfIntersections(newShape);
        if (!si.success()) return si;

        // не пересекается с фигурами ЭТОЙ ЖЕ зоны (если должны соединиться — это "Расширить границы", не новая фигура)
        var newPoly = ZoneGeometry.toJtsPolygon(newShape);
        if (newPoly == null) return Result.fail(ChatColor.RED + "Некорректная фигура.");
        for (List<Location> own : zi.getShapes()) {
            var ownPoly = ZoneGeometry.toJtsPolygon(own);
            try {
                if (ownPoly != null && newPoly.intersects(ownPoly)) {
                    return Result.fail(ChatColor.RED + "Новая фигура пересекается с уже существующей частью этой же зоны. "
                            + "Если они должны соединиться — используйте \"Расширить границы\" вместо добавления фигуры.");
                }
            } catch (Throwable t) {
                return Result.fail(ChatColor.RED + "Не удалось проверить фигуру.");
            }
        }

        // зазор до ближайшей части этой же зоны — не слишком близко (де-факто одна фигура) и не слишком далеко (раскидано по карте)
        String spacingErr = ZoneGeometry.checkShapeSpacing(newShape, zi.getShapes());
        if (spacingErr != null) return Result.fail(ChatColor.RED + spacingErr);

        // суммарная площадь всех фигур зоны (существующие + новая) в пределах лимита типа
        ZoneTypeData ztd = zoneLimits.get(type);
        if (ztd == null) return Result.fail(ChatColor.RED + "Не задан лимит для " + type + ".");
        double areaLimit = (type == ZoneType.COUNTRY)
                ? ul.getZoneManager().countryAreaLimitFor(ZoneType.COUNTRY, zoneCountry(zi))
                : ztd.areaLimit();
        double totalArea = ZoneGeometry.totalArea(zi.getShapes()) + calculateSurfaceArea(newShape);
        if (totalArea > areaLimit) {
            return Result.fail(ChatColor.RED + "Суммарная площадь зоны превысит лимит: "
                    + (int) totalArea + " > " + (int) areaLimit + " блоков².");
        }

        boolean mustInside = mustBeInsideCountryOrColony(type);

        if (mustInside) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(newShape, PARENTS, zoneList.values());
            if (parent == null) {
                return Result.fail(ChatColor.RED + "Новая фигура должна полностью находиться внутри Государства или Колонии.");
            }
            String parentCountry = zoneCountry(parent);
            String zoneCountryName = zi.getCountryName();
            if (parentCountry == null || zoneCountryName == null
                    || !Objects.equals(normCountry(parentCountry), normCountry(zoneCountryName))) {
                return Result.fail(ChatColor.RED + "Новая фигура должна находиться в той же стране/колонии, что и остальная часть зоны.");
            }
            ZoneInfo overlap = findOverlapAt(newShape, playerName, type, zi.getMarkerID());
            if (overlap != null && !(ZoneOverlapRules.isCountryOrColony(overlap) && Objects.equals(overlap.getMarkerID(), parent.getMarkerID()))) {
                return Result.fail(ChatColor.RED + "Новая фигура пересекается с другой зоной \"" + overlap.getName() + "\".");
            }
        }

        if (type == ZoneType.SHOP || type == ZoneType.PLOT) {
            ZoneInfo parent = ZoneOverlapRules.findSingleContainingZoneOfTypes(newShape, PARENTS, zoneList.values());
            if (parent != null) {
                String parentCountry = zoneCountry(parent);
                String playerCountry = ul.countryRegistryJdbc.getCountryOfPlayer(playerName);
                if (playerCountry == null || playerCountry.isBlank()
                        || !Objects.equals(normCountry(playerCountry), normCountry(parentCountry))) {
                    return Result.fail(ChatColor.RED + "Нельзя достраивать фигуру в чужой стране/колонии.");
                }
                if (type == ZoneType.PLOT) {
                    Result capResult = checkCountryPlotAreaCap(parentCountry, parent.getMarkerID(), totalArea, zi.getMarkerID());
                    if (!capResult.success()) return capResult;
                }
            } else if (ZoneOverlapRules.areaIntersectsAnyOfTypes(newShape, PARENTS, zoneList.values())) {
                return Result.fail(ChatColor.RED + "Новая фигура не должна пересекать границы стран/колоний.");
            }
        }

        // как и при создании: доп. фигура участка не может пересекаться с чужой зоной,
        // а с чужим/своим Участком — не пересекается никогда (см. validateBuildZone)
        if (type == ZoneType.PLOT) {
            ZoneInfo candidate = new ZoneInfo(ZoneType.PLOT, "tmp_id", zi.getName(), "tmp_marker", newShape, playerName, org.bukkit.Color.WHITE);
            for (ZoneInfo existing : zoneList.values()) {
                if (Objects.equals(existing.getMarkerID(), zi.getMarkerID())) continue; // сама зона проверена выше отдельно
                if (existing.getType() == ZoneType.COUNTRY || existing.getType() == ZoneType.COLONY) continue;
                boolean sameOwner = ZoneManager.NameUtil.eqCi(existing.getOwner(), playerName);
                if (sameOwner && existing.getType() != ZoneType.PLOT) continue;
                if (ZoneOverlapRules.zonesIntersect2D(candidate, existing)) {
                    return Result.fail(ChatColor.RED + (sameOwner
                            ? "Новая фигура пересекается с другим вашим участком \"" + existing.getName() + "\"."
                            : "Новая фигура пересекается с зоной \"" + existing.getName() + "\", которая принадлежит другому игроку."));
                }
            }
        }

        // новый эксклав территории Государства не должен конфликтовать ни с одной
        // другой зоной на карте (та же проверка, что и при первичном создании
        // территории в ZoneManager.buildZoneCountryCore)
        if (type == ZoneType.COUNTRY) {
            ZoneInfo conflict = ZoneOverlapRules.findConflictingZoneForCountryChange(
                    newShape, zi.getMarkerID(), zoneList.values(), zoneCountry(zi), zi.getOwner());
            if (conflict != null) {
                return Result.fail(ChatColor.RED + "Новая часть территории пересекается с зоной \""
                        + conflict.getName() + "\" (" + conflict.getType() + ").");
            }
        }

        return Result.ok();
    }

    /* =========================
       Локальный overlap-хелпер (скопирован из ZoneManager логикой один-в-один)
       ========================= */

    /** Сколько участков (PLOT) уже принадлежит owner-у внутри территории конкретной страны/колонии (по marker_id родителя). */
    private int countOwnedPlotsInsideParent(String owner, String parentMarkerId) {
        int count = 0;
        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() != ZoneType.PLOT) continue;
            if (!ZoneManager.NameUtil.eqCi(z.getOwner(), owner)) continue;
            ZoneInfo p = ZoneOverlapRules.findSingleContainingZoneOfTypes(z.getCorners(), PARENTS, zoneList.values());
            if (p != null && Objects.equals(p.getMarkerID(), parentMarkerId)) count++;
        }
        return count;
    }

    /**
     * Суммарная площадь ВСЕХ участков (PLOT) любых игроков внутри territory parentMarkerId,
     * кроме excludeMarkerId (та зона, которую сейчас создаём/расширяем — её текущую площадь
     * учитываем отдельно через thisZoneAreaAfterChange, чтобы не посчитать её дважды).
     */
    private double totalOtherPlotAreaInsideParent(String parentMarkerId, String excludeMarkerId) {
        double total = 0;
        for (ZoneInfo z : zoneList.values()) {
            if (z.getType() != ZoneType.PLOT) continue;
            if (excludeMarkerId != null && excludeMarkerId.equals(z.getMarkerID())) continue;
            ZoneInfo p = ZoneOverlapRules.findSingleContainingZoneOfTypes(z.getCorners(), PARENTS, zoneList.values());
            if (p != null && Objects.equals(p.getMarkerID(), parentMarkerId)) {
                total += ZoneGeometry.totalArea(z.getShapes());
            }
        }
        return total;
    }

    /**
     * Общестрановой лимит площади под участки (настраивается лидером на country.html — либо
     * процент от площади территории страны, либо абсолютное число блоков², см.
     * CountryRegistryJdbc.getPlotAreaCapMode/Value). thisZoneAreaAfterChange — суммарная площадь
     * ВСЕХ фигур создаваемого/редактируемого участка ПОСЛЕ применения этого изменения.
     */
    private Result checkCountryPlotAreaCap(String countryName, String parentMarkerId, double thisZoneAreaAfterChange, String excludeMarkerId) {
        if (countryName == null || countryName.isBlank()) return Result.ok();

        String mode = ul.countryRegistryJdbc.getPlotAreaCapMode(countryName);
        if (mode == null || "none".equals(mode)) return Result.ok();

        double capValue = ul.countryRegistryJdbc.getPlotAreaCapValue(countryName);
        if (capValue <= 0) return Result.ok();

        double capBlocks;
        String capDesc;
        if ("percent".equals(mode)) {
            double area = ul.countryRegistryJdbc.getCountryArea(countryName);
            if (area <= 0) return Result.ok(); // площадь страны неизвестна — не блокируем вслепую
            capBlocks = area * (capValue / 100.0);
            capDesc = capValue + "% от площади страны (" + (int) capBlocks + " блоков²)";
        } else if ("blocks".equals(mode)) {
            capBlocks = capValue;
            capDesc = (int) capValue + " блоков²";
        } else {
            return Result.ok();
        }

        double newTotal = totalOtherPlotAreaInsideParent(parentMarkerId, excludeMarkerId) + thisZoneAreaAfterChange;
        if (newTotal > capBlocks) {
            return Result.fail(ChatColor.RED + "Превышен общестрановой лимит площади под участки: "
                    + (int) newTotal + " > " + (int) capBlocks + " блоков² (лимит: " + capDesc + ").");
        }
        return Result.ok();
    }

    /**
     * Ищет зону, с которой pts пересекается НАСТОЯЩЕЙ площадью. Раньше это проверялось
     * поточечно через ray-casting pointInPolygon по каждому углу — из-за чего угол,
     * ровно лежащий на границе соседней зоны (например, снаппнутый к её краю), мог
     * ложно засчитаться как "внутри" неё. Теперь используем JTS-пересечение по площади
     * для всей фигуры целиком — простое касание общей гранью/вершиной не блокирует.
     */
    private ZoneInfo findOverlapAt(List<Location> pts, String owner, ZoneType currentType, String ignoreMarkerId) {
        if (pts == null || pts.isEmpty()) return null;
        var candidate = ZoneGeometry.toJtsPolygon(pts);
        if (candidate == null) return null;
        World w = pts.getFirst().getWorld();

        for (ZoneInfo z : zoneList.values()) {
            if (ignoreMarkerId != null && ignoreMarkerId.equals(z.getMarkerID())) continue;
            if (ZoneManager.NameUtil.eqCi(z.getOwner(), owner) && z.getType() == currentType) continue;

            if (!ZoneGeometry.worldOkAny(z.getShapes(), w)) continue;
            for (List<Location> shape : z.getShapes()) {
                var shapePoly = ZoneGeometry.toJtsPolygon(shape);
                if (shapePoly != null && ZoneGeometry.trueAreaOverlap(candidate, shapePoly)) return z;
            }
        }
        return null;
    }
}
