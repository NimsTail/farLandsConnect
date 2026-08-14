package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * infra/military-diplomacy-design.md §4.1/§14.2, GH#24 вопрос №15 — физический
 * анкер (Колокол) военного объекта, привязанный к его текущей специализации.
 * Сейчас реально используется только Разведпунктом (RECON): без якоря
 * разведка с объекта недоступна вообще (см. §4.1) — сама эта проверка живёт
 * на сайте (backend initiateRecon), этот класс только поддерживает состояние
 * якоря (жив/сломан/где стоит) актуальным на стороне плагина.
 *
 * Первая реальная реализация анкер-биндинга для военных объектов — раньше
 * была только заглушка в MilitaryCfg ("открытый пункт на будущее"). По
 * образцу уже готового биндинга сундука магазина (SignStore.bindContainer/
 * unbindContainer) — разница в том, что тут анкер хранится прямо на
 * ZoneInfo, а не в отдельном SignStore, т.к. у военного объекта нет вывески.
 *
 * ВАЖНО про нагрузку: BlockPlaceEvent/BlockBreakEvent летят на КАЖДЫЙ
 * поставленный/сломанный блок сервера (уже существующая штатная нагрузка —
 * этот плагин и так слушает эти события в 40+ местах, см. например
 * advs/GoldAboveGroundListener). Проверка типа блока — первая строка
 * каждого хендлера, дешёвый early-exit для 99.9% событий (не Колокол — не
 * идём дальше). Никакого сканирования мира тут нет вообще: только реакция
 * на уже произошедшее событие плюс one-off point-in-polygon/координатное
 * сравнение по маленькому списку военных зон.
 */
public final class MilitaryAnchorService implements Listener {

    private final MilitarySpecializationService specializationService;
    // GH#24 (фидбек 2026-08-14 п.4) — Арбалет теперь тоже требует якорь
    // (тот же Колокол), не только Разведпункт — см. isAnchorCapable ниже.
    private final MilitaryDefenseSubtypeService defenseSubtypeService;

    public MilitaryAnchorService(MilitarySpecializationService specializationService, MilitaryDefenseSubtypeService defenseSubtypeService) {
        this.specializationService = specializationService;
        this.defenseSubtypeService = defenseSubtypeService;
    }

    /** RECON специализация, либо DEFENSE-объект, реально вкачанный в CROSSBOW — единственные два потребителя якоря сейчас. */
    private boolean isAnchorCapable(ZoneInfo zone) {
        if (specializationService.current(zone) == MilitarySpecialization.RECON) return true;
        return defenseSubtypeService.isActiveAs(zone, MilitaryDefenseSubtype.CROSSBOW);
    }

    // GH#24 (твоя заметка про закапывание) — анкер обязан быть либо выше
    // уровня моря (Y>=63), либо открыт небу (никто не закопал его выше) —
    // иначе его можно спрятать так, что враг физически не сможет найти и
    // сломать (BREAK_ANCHOR, §14.2), что убивает саму нейтрализацию как
    // механику. "Открыт небу" — самый высокий непустой блок в этом столбце
    // не выше самого анкера (Bukkit-хайтмап чанка, O(1), без ручного
    // сканирования колонны). Публичный статик — этим же правилом
    // проверяется "жив ли якорь" при каждом zones_sync (см.
    // ZoneRequestPoller.zoneToJson), не только в момент установки: если
    // анкер закопали ПОСЛЕ того как он уже стоял, он перестаёт считаться
    // присутствующим на следующем же опросе, без отдельного слушателя на
    // "кто-то что-то построил над ним".
    public static boolean isExposed(Location loc) {
        if (loc.getWorld() == null) return false;
        if (loc.getBlockY() >= 63) return true;
        return loc.getBlockY() >= loc.getWorld().getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.BELL) return;

        Location loc = e.getBlockPlaced().getLocation();
        ZoneInfo zone = specializationService.militaryZoneAt(loc);
        if (zone == null) return; // колокол вне военного объекта — нас не касается

        if (!isAnchorCapable(zone)) {
            // Ни Разведпункт, ни Арбалет — колокол просто обычный блок здесь,
            // никакой ошибки: не все специализации/типы обороны (пока)
            // требуют именно колокол, а раскрывать игроку внутреннюю
            // механику "это не сработает" без причины не нужно — он и не
            // ожидает эффекта.
            return;
        }

        Player p = e.getPlayer();
        if (!isExposed(loc)) {
            p.sendMessage(ChatColor.RED + "Колокол должен стоять выше Y63 либо быть открыт небу (не закопан) — иначе не считается якорем.");
            return;
        }

        boolean replacing = zone.getMilitaryAnchorLocation() != null;
        zone.setMilitaryAnchorLocation(loc);
        saveZones();

        p.sendMessage(ChatColor.GREEN + "Колокол закреплён как якорь для \"" + zone.getName() + "\"."
                + (replacing ? ChatColor.GRAY + " (заменил предыдущий)" : ""));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.BELL) return;

        Location loc = e.getBlock().getLocation();
        ZoneInfo zone = findZoneByAnchor(loc);
        if (zone == null) return; // сломанный колокол не был чьим-то якорем

        zone.setMilitaryAnchorLocation(null);
        saveZones();

        Player breaker = e.getPlayer();
        String zoneCountry = zone.getCountryName();
        String breakerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(breaker.getName());

        boolean ownAnchor = zoneCountry == null || breakerCountry == null || breakerCountry.equalsIgnoreCase(zoneCountry);
        if (ownAnchor) {
            breaker.sendMessage(ChatColor.YELLOW + "Якорь \"" + zone.getName() + "\" снят.");
            return;
        }

        // BREAK_ANCHOR (§14.2/§14.4) — чужой якорь ломают только во время
        // войны (тот же паттерн атрибуции, что и DefensePatrolUpgrade); вне
        // войны это просто грабёж чужого блока, не военная нейтрализация.
        if (!UnityLauncher.getInstance().warStatusCache.isAtWar(breakerCountry, zoneCountry)) return;

        var api = UnityLauncher.getInstance().getFarLandsApi();
        if (api != null) api.reportMilitaryNeutralize(zone.getMarkerID(), breakerCountry);
    }

    private ZoneInfo findZoneByAnchor(Location broken) {
        for (ZoneInfo z : UnityLauncher.getInstance().getZoneManager().getAllZonesSnapshot()) {
            Location anchor = z.getMilitaryAnchorLocation();
            if (anchor == null) continue;
            if (sameBlock(anchor, broken)) return z;
        }
        return null;
    }

    private static boolean sameBlock(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) return false;
        return a.getWorld().getUID().equals(b.getWorld().getUID())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }

    private void saveZones() {
        UnityLauncher.getInstance().getZoneManager().saveZonesToConfig();
    }
}
