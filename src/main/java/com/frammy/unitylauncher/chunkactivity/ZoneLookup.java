package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Адаптер между твоим ZoneManager и системой метрик.
 * Реализацию делаешь сам: внутри можешь дергать zoneManager.getZoneAt(loc) или что у тебя есть.
 */
public interface ZoneLookup {

    /**
     * @param loc локация блока/энтити/игрока
     * @return ZoneAt или null, если зоны нет
     */
    @Nullable
    ZoneAt lookup(Location loc);

    /**
     * Минимальная инфа о зоне, которую нужно знать метрикам.
     * zoneId — любой стабильный ключ (имя зоны, её internal ID, country+kind, как тебе удобно)
     * zoneType — "COUNTRY", "COLONY", "INDUSTRIAL", "PARK" и т.д. — для применения разных коэффициентов.
     */
    record ZoneAt(String zoneId, String zoneType) {}
}
