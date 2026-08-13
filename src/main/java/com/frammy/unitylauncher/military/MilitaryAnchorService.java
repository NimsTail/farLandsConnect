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

    public MilitaryAnchorService(MilitarySpecializationService specializationService) {
        this.specializationService = specializationService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.BELL) return;

        Location loc = e.getBlockPlaced().getLocation();
        ZoneInfo zone = specializationService.militaryZoneAt(loc);
        if (zone == null) return; // колокол вне военного объекта — нас не касается

        if (specializationService.current(zone) != MilitarySpecialization.RECON) {
            // Не Разведпункт — колокол просто обычный блок здесь, никакой
            // ошибки: не все специализации (пока) требуют именно колокол,
            // а раскрывать игроку внутреннюю механику "это не сработает"
            // без причины не нужно — он и не ожидает эффекта.
            return;
        }

        boolean replacing = zone.getMilitaryAnchorLocation() != null;
        zone.setMilitaryAnchorLocation(loc);
        saveZones();

        Player p = e.getPlayer();
        p.sendMessage(ChatColor.GREEN + "Колокол закреплён как якорь разведки для \"" + zone.getName() + "\"."
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
            breaker.sendMessage(ChatColor.YELLOW + "Якорь разведки \"" + zone.getName() + "\" снят.");
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
