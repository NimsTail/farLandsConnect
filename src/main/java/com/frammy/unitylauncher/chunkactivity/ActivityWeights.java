package com.frammy.unitylauncher.chunkactivity;

public class ActivityWeights {
    public double timeWeight = 0.025;          // за 1 секунду
    public double blockPlacedWeight = 0.003;   // за каждый поставленный блок
    public double blockBrokenWeight = 0.0018;   // за каждый сломанный блок

    public double calculateValue(ChunkStats stats) {
        return stats.timeSpent / 1000.0 * timeWeight +
                stats.blocksPlaced * blockPlacedWeight +
                stats.blocksBroken * blockBrokenWeight;
    }
}
