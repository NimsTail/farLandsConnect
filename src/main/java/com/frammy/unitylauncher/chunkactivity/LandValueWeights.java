package com.frammy.unitylauncher.chunkactivity;

/**
 * "Ценность земли" — намеренно ОТДЕЛЬНЫЙ от {@link ActivityWeights} сигнал.
 * Активность отражает "сколько тут играли сегодня" (и от неё же зависит
 * зарплата игрока — те же самые события), а земельная ценность отражает
 * "сколько сюда вложено" (стройка, не угасает) и "насколько это популярное
 * место" (уникальные посетители, а не время самого владельца) — так налог
 * на зону не превращается в самообложение за собственную игру.
 */
public class LandValueWeights {

    // Применяется к sqrt(netBuildVolume) — корень даёт убывающую отдачу:
    // большая застройка всё ещё дороже, но не растёт по цене линейно вечно.
    public double buildVolumeWeight = 0.3;

    // Применяется к количеству уникальных посетителей за окно (сбрасывается
    // раз в неделю) — линейно, т.к. реалистичные числа тут небольшие.
    public double uniqueVisitorWeight = 1.0;

    // Сколько РАЗНЫХ материалов нужно использовать, чтобы застройка
    // засчиталась по полному весу. Меньше разнообразия — штраф (см. ниже).
    public int diversityTargetMaterials = 8;

    // Даже если использован всего 1 материал — не обнуляем вклад совсем,
    // просто сильно режем (защита от "один и тот же блок массово").
    public double minDiversityFactor = 0.25;

    public double calculateValue(ChunkStats stats) {
        double diversity = Math.min(1.0, (double) stats.getDistinctMaterialCount() / diversityTargetMaterials);
        diversity = Math.max(minDiversityFactor, diversity);

        // netBuildVolume уже взвешен по материалу при накоплении
        // (BuildMaterialWeights) — здесь ещё накладываем штраф за
        // однообразие, чтобы "10000 однотипных блоков вразброс" не давали
        // почти такую же цену, как разнообразная настоящая постройка.
        double buildComponent = Math.sqrt(Math.max(0.0, stats.netBuildVolume)) * buildVolumeWeight * diversity;
        double trafficComponent = stats.getVisitorTrafficScore() * uniqueVisitorWeight;
        return buildComponent + trafficComponent;
    }
}
