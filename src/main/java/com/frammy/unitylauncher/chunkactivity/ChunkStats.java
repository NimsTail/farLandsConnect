package com.frammy.unitylauncher.chunkactivity;

import java.util.ArrayDeque;
import java.util.Deque;

public class ChunkStats {
    public long timeSpent = 0;
    public int blocksPlaced = 0;
    public int blocksBroken = 0;

    public long lastUpdated = System.currentTimeMillis();

    // Храним последние 24 часовых среза
    public final Deque<Double> hourlySamples = new ArrayDeque<>(24);

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

    // cooling (сигмоида как у тебя)
    public void applyCooling(long now) {
        long delta = now - lastUpdated;
        double hours = delta / 3600000.0;
        double decay = getSigmoidDecay(hours);
        timeSpent *= (long) decay;
        blocksPlaced *= (int) decay;
        blocksBroken *= (int) decay;
        // lastUpdated не трогаем
    }

    private double getSigmoidDecay(double hoursSinceLastActivity) {
        double k = 1.2;
        double t0 = 5.0;
        return 1.0 / (1.0 + Math.exp(k * (hoursSinceLastActivity - t0)));
    }

    // Запись часового среза и обнуление текущих счётчиков
    public void recordHourlySample(ActivityWeights weights) {
        double snapshot = weights.calculateValue(this);
        hourlySamples.addLast(snapshot);
        while (hourlySamples.size() > 24) hourlySamples.removeFirst();

        // начинаем новый час с нуля
        timeSpent = 0;
        blocksPlaced = 0;
        blocksBroken = 0;
    }

    // Среднее за "сутки" (по имеющимся часам), с фолбэком
    public double getDailyAverage(ActivityWeights weights) {
        if (hourlySamples.isEmpty()) {
            // ещё нет срезов — берём текущее “мгновенное” значение
            return weights.calculateValue(this);
        }
        return hourlySamples.stream().mapToDouble(d -> d).average().orElse(0.0);
    }
}
