package com.frammy.unitylauncher.chunkactivity;

public class ActivityWeights {
    public double timeWeight = 0.025;          // за 1 секунду
    public double blockPlacedWeight = 0.003;   // за каждый поставленный блок
    public double blockBrokenWeight = 0.0018;  // за каждый сломанный блок

    // новые коэффициенты
    public double itemDropWeight = 0.0005;
    public double entitySpawnWeight = 0.002;
    public double tickLoadWeight = 0.0008;
    public double playerActivityWeight = 0.01;
    public double structureBonusWeight = 0.02;

    public double calculateValue(ChunkStats stats) {
        return
                stats.timeSpent / 1000.0 * timeWeight +
                        stats.blocksPlaced * blockPlacedWeight +
                        stats.blocksBroken * blockBrokenWeight +
                        stats.itemDrops * itemDropWeight +
                        stats.entitySpawns * entitySpawnWeight +
                        stats.tickLoad * tickLoadWeight +
                        stats.playerActivity * playerActivityWeight +
                        stats.structureBonus * structureBonusWeight;
    }
}
