package com.frammy.unitylauncher.chunkactivity;

public class ChunkStats {
    public long timeSpent = 0;
    public int blocksPlaced = 0;
    public int blocksBroken = 0;

    public void addTime(long millis) {
        this.timeSpent += millis;
    }
}
