package com.frammy.unitylauncher.chunkactivity;

/**
 * Куда мы складываем сырые метрики по зонам.
 * Здесь ты интегрируешься со своей существующей логикой в папке chunkactivity.
 */
public interface ChunkMetricsSink {

    void incBlocksChanged(String zoneId, String zoneType, int blocksDelta, double structureBonus);

    void incItemDrops(String zoneId, String zoneType, int items);

    void incEntityCount(String zoneId, String zoneType, int deltaMobs);

    void incTickLoad(String zoneId, String zoneType, double loadUnits);

    /**
     * Игрок совершил осмысленное действие в зоне:
     * движение, клик, взаимодействие и т.д.
     *
     * @param activityScore можно считать 1.0 за событие, а дальше агрегировать уже у себя.
     */
    void recordPlayerActivity(String zoneId, String zoneType, String playerName, double activityScore);
}
