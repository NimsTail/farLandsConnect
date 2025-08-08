package com.frammy.unitylauncher.chunkactivity;

import java.util.ArrayDeque;
import java.util.Deque;

public class ChunkStats {
    public long timeSpent = 0;
    public int blocksPlaced = 0;
    public int blocksBroken = 0;

    public long lastUpdated = System.currentTimeMillis();
    public final Deque<Double> dailySamples = new ArrayDeque<>();

    public void recordDailySample(ActivityWeights weights) {
        double dailyValue = weights.calculateValue(this);
        dailySamples.addLast(dailyValue);

        // Храним только 7 последних дней
        if (dailySamples.size() > 7) {
            dailySamples.removeFirst();
        }

        // Сбросим счётчики на новый день
        timeSpent = 0;
        blocksPlaced = 0;
        blocksBroken = 0;
    }

    public void addTime(long millis) {
        timeSpent += millis;
        lastUpdated = System.currentTimeMillis();
    }

    public void incrementPlace() {
        blocksPlaced++;
        lastUpdated = System.currentTimeMillis();
    }

    public void incrementBreak() {
        blocksBroken++;
        lastUpdated = System.currentTimeMillis();
    }

    public void applyCooling(long now) {
        long delta = now - lastUpdated;
        double hours = delta / 3600000.0;

        double decay = getSigmoidDecay(hours);

        timeSpent *= decay;
        blocksPlaced *= decay;
        blocksBroken *= decay;
    }

    private double getSigmoidDecay(double hoursSinceLastActivity) {
        double k = 1.2;
        double t0 = 5.0;
        return 1.0 / (1.0 + Math.exp(k * (hoursSinceLastActivity - t0)));
    }
}
