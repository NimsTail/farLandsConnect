package com.frammy.unitylauncher.zones;

/**
 * Данные по типу зоны: лимиты, флаги и пр.
 * Совместимо с вызовами из ZoneManager.
 *
 * @param displayName        "Торговая точка", "Банк", ...
 * @param areaLimit          макс. площадь
 * @param index              приоритет (для выбора "внутренней" зоны)
 * @param minSize            мин. площадь, начиная с 3 точек
 * @param allowOverlap       можно ли пересекаться с другими типами
 * @param costMultiplier     множитель стоимости (1.0 / 1.15 / 0.85 / 0.7)
 * @param quota              напр., 10 / 150 / 200 / 50 / 0
 * @param requiredPermission "unityLauncher.createZone.shop" и т.п.
 */
public record ZoneTypeData(String displayName, double areaLimit, int index, double minSize, boolean allowOverlap,
                           double costMultiplier, int quota, String requiredPermission) {

    /** Возвращает множитель стоимости зоны (alias для costMultiplier). */
    public double priceMultiplier() {
        return costMultiplier;
    }
}