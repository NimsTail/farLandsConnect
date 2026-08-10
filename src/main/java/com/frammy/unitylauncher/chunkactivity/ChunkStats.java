package com.frammy.unitylauncher.chunkactivity;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChunkStats {
    public long timeSpent = 0;
    public int blocksPlaced = 0;
    public int blocksBroken = 0;

    // Новые метрики
    public int itemDrops = 0;
    public int entitySpawns = 0;
    public double tickLoad = 0.0;
    public double playerActivity = 0.0;
    public double structureBonus = 0.0;

    public long lastUpdated = System.currentTimeMillis();

    // ===== "Ценность земли" — намеренно НЕ угасают и не сбрасываются часовым
    // срезом, в отличие от полей выше. Это отдельный от "активности" сигнал:
    // сколько сюда реально вложено (стройка) и насколько это место посещаемо
    // (трафик чужих игроков), а не сколько лично играл владелец сегодня. =====

    // Накопительный ВЗВЕШЕННЫЙ чистый объём стройки (поставлено-сломано),
    // никогда не угасает. Взвешенный — потому что дешёвые сырые материалы
    // (BuildMaterialWeights) считаются по низкому весу, иначе тупая закладка
    // диртом/камнем накручивала бы это так же дёшево, как настоящая стройка.
    public double netBuildVolume = 0.0;

    // Разные материалы, которые тут когда-либо клали — используется для
    // штрафа за "один и тот же блок подряд" (см. LandValueWeights).
    private final Set<Material> materialsPlaced = ConcurrentHashMap.newKeySet();

    // Уникальные посетители за текущее окно (сбрасывается раз в неделю
    // ActivityTracker'ом) — трафик места, а не время владельца. Вес визита
    // обычно 1.0, но для владельца/гражданина страны ЭТОЙ ЖЕ зоны — сильно
    // ниже (см. ActivityTracker.computeVisitorWeight): иначе налог на землю
    // растёт от собственной же игры хозяина, а не от реального чужого трафика.
    private final Map<UUID, Double> visitorWeights = new ConcurrentHashMap<>();

    public void addBuildVolume(double placedWeighted, double brokenWeighted) {
        netBuildVolume = Math.max(0.0, netBuildVolume + placedWeighted - brokenWeighted);
    }

    public void recordMaterialPlaced(Material material) {
        if (material != null) materialsPlaced.add(material);
    }

    public int getDistinctMaterialCount() {
        return materialsPlaced.size();
    }

    public Set<Material> getMaterialsPlaced() {
        return Collections.unmodifiableSet(materialsPlaced);
    }

    /** Старый вызов без веса — вес по умолчанию (обычный посторонний визит). Используется при загрузке старых данных. */
    public void addVisitor(UUID uuid) {
        addVisitor(uuid, 1.0);
    }

    /** Перезаписывает вес визита этим uuid текущим (последний визит определяет вес — статус владения меняется редко). */
    public void addVisitor(UUID uuid, double weight) {
        if (uuid != null) visitorWeights.put(uuid, weight);
    }

    /** Сырое кол-во уникальных посетителей БЕЗ учёта веса — для информации/отладки. Для налога используйте getVisitorTrafficScore(). */
    public int getUniqueVisitorCount() {
        return visitorWeights.size();
    }

    /** Взвешенная сумма трафика — то, что реально используется в LandValueWeights. */
    public double getVisitorTrafficScore() {
        double sum = 0.0;
        for (double w : visitorWeights.values()) sum += w;
        return sum;
    }

    public Set<UUID> getUniqueVisitors() {
        return Collections.unmodifiableSet(visitorWeights.keySet());
    }

    public Map<UUID, Double> getVisitorWeights() {
        return Collections.unmodifiableMap(visitorWeights);
    }

    public void resetVisitorWindow() {
        visitorWeights.clear();
    }

    // Храним последние 24 часовых среза
    public final Deque<Double> hourlySamples = new ArrayDeque<>(24);

    public void addTime(long millis) {
        timeSpent += millis;
        lastUpdated = System.currentTimeMillis();
    }

    // охлаждение (фикс багов с приведениями типов)
    public void applyCooling(long now) {
        long delta = now - lastUpdated;
        if (delta <= 0) return;

        double hours = delta / 3600000.0;
        double decay = getSigmoidDecay(hours);

        if (decay >= 1.0) return;

        if (decay <= 0.0) {
            timeSpent = 0;
            blocksPlaced = 0;
            blocksBroken = 0;
            itemDrops = 0;
            entitySpawns = 0;
            tickLoad = 0.0;
            playerActivity = 0.0;
            structureBonus = 0.0;
            return;
        }

        timeSpent       = Math.round(timeSpent * decay);
        blocksPlaced    = (int)  Math.round(blocksPlaced * decay);
        blocksBroken    = (int)  Math.round(blocksBroken * decay);
        itemDrops       = (int)  Math.round(itemDrops * decay);
        entitySpawns    = (int)  Math.round(entitySpawns * decay);
        tickLoad        =         tickLoad * decay;
        playerActivity  =         playerActivity * decay;
        structureBonus  =         structureBonus * decay;
        // lastUpdated не двигаем — это "последняя реальная активность"
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
        itemDrops = 0;
        entitySpawns = 0;
        tickLoad = 0.0;
        playerActivity = 0.0;
        structureBonus = 0.0;
    }

    // Среднее за "сутки" (по имеющимся часам), с фолбэком
    public double getDailyAverage(ActivityWeights weights) {
        if (hourlySamples.isEmpty()) {
            // ещё нет срезов — берём текущее “мгновенное” значение
            return weights.calculateValue(this);
        }
        return hourlySamples.stream().mapToDouble(d -> d).average().orElse(0.0);
    }

    public ChunkStats copy() {
        ChunkStats c = new ChunkStats();
        c.timeSpent = this.timeSpent;
        c.blocksPlaced = this.blocksPlaced;
        c.blocksBroken = this.blocksBroken;
        c.itemDrops = this.itemDrops;
        c.entitySpawns = this.entitySpawns;
        c.tickLoad = this.tickLoad;
        c.playerActivity = this.playerActivity;
        c.structureBonus = this.structureBonus;
        c.lastUpdated = this.lastUpdated;
        c.hourlySamples.clear();
        c.hourlySamples.addAll(this.hourlySamples);
        c.netBuildVolume = this.netBuildVolume;
        c.materialsPlaced.addAll(this.materialsPlaced);
        c.visitorWeights.putAll(this.visitorWeights);
        return c;
    }

}
