package com.frammy.unitylauncher.chunkactivity;

public class ActivityWeights {
    public double timeWeight = 1.0;          // за 1 секунду
    public double blockPlacedWeight = 2.0;   // за каждый поставленный блок
    public double blockBrokenWeight = 1.0;   // за каждый сломанный блок

    public double calculateValue(ChunkStats stats) {
        return stats.timeSpent / 1000.0 * timeWeight +
                stats.blocksPlaced * blockPlacedWeight +
                stats.blocksBroken * blockBrokenWeight;
    }
}
