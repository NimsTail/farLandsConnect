package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;

/**
 * infra/military-diplomacy-design.md §14.2 "главный тип" / GH#24 п.2-3 —
 * which single upgrade a given MILITARY zone is currently specialized as.
 * A country can buy several military upgrades (Defense/Hospital/Recon/
 * Attack support/Logistics) at the country level, but before this each of
 * those effects blanket-applied to EVERY MILITARY zone the country owned —
 * this is the missing per-zone "which one applies HERE" axis.
 *
 * Deliberately separate from the site-only `militaryRole` (HQ/STRONGHOLD/
 * POST, §15.1) — that's the object's place in the War Score hierarchy and
 * has no in-game representation at all; this is what the object DOES.
 */
public enum MilitarySpecialization {
    // Фидбек 2026-08-14 ("зачем вообще ограничивать? мы же отказались от
    // уровней у Обороны и Опорного пункта") — Оборона/Госпиталь/Поддержка
    // атаки/Логистика это плоские одноразовые покупки на сайте (max_level:1,
    // без costs-прогрессии) — квота "куплен уровень N = максимум N объектов"
    // для них никогда не была осмысленной, а из-за max_level:1 фактически
    // навсегда запирала их на 1 объект страны. Разведка — единственная,
    // у которой реально есть 3 уровня (costs: 80/160/260 в seed-данных) —
    // там квота настоящая, оставлена как была.
    DEFENSE("unity.military.defense", true),
    HOSPITAL("unity.military.hospital_regen", true),
    RECON("unity.military.recon", false),
    ATTACK_SUPPORT("unity.military.attack_support", true),
    LOGISTICS("unity.military.logistics", true);

    // GH#24 (правки из комментария, п.3) — сайт хочет показывать в дропдауне
    // специализации "сколько доступно (куплено)", как у типов зон
    // (unity.zone.<type>.<N>). Раньше permBase был вкл/выкл-флагом (level
    // >= 1 = разрешено на ЛЮБОЕ число объектов одновременно) — теперь это
    // тот же паттерн уровневой квоты: unity.military.<slug>.<N> = максимум N
    // военных объектов страны одновременно с этой специализацией.
    // ВАЖНО (эксплуатация): старый флаг unity.military.<slug> (без числа)
    // больше НЕ считается — странам, у кого он уже выдан, нужно перевыдать
    // как unity.military.<slug>.<N> (например .2), иначе квота станет 0.
    private static final int MAX_QUOTA_LEVEL = 5;

    /** Country-level LuckPerms permission base gating this specialization at all (see upgrades_country.json). */
    private final String permBase;
    /** См. class-javadoc выше и комментарий у каждой константы. */
    private final boolean unlimitedOnceUnlocked;

    MilitarySpecialization(String permBase, boolean unlimitedOnceUnlocked) {
        this.permBase = permBase;
        this.unlimitedOnceUnlocked = unlimitedOnceUnlocked;
    }

    /**
     * Максимум объектов страны, которые могут одновременно нести эту
     * специализацию — 0, если апгрейд не куплен вообще; -1 ("без лимита"),
     * если это плоская специализация (unlimitedOnceUnlocked) и апгрейд куплен.
     */
    public int purchasedQuota(String canonicalCountryId) {
        if (canonicalCountryId == null) return 0;
        int level = UpgradeCondition.countryMaxLevel(canonicalCountryId, permBase, MAX_QUOTA_LEVEL);
        if (level <= 0) return 0;
        return unlimitedOnceUnlocked ? -1 : level;
    }

    /** True if the zone's country has actually bought (level >= 1) the upgrade this specialization requires. */
    public boolean unlockedFor(String canonicalCountryId) {
        return purchasedQuota(canonicalCountryId) != 0;
    }

    /** GH#24 (фидбек 2026-08-14 п.2) — русское имя для action bar при входе в зону.
     *  Зеркалит frontend/src/locales/ru.json militaryMap.specialization.* — держать в синхроне. */
    public String displayName() {
        return switch (this) {
            case DEFENSE -> "Оборона";
            case HOSPITAL -> "Военный госпиталь";
            case RECON -> "Разведпункт";
            case ATTACK_SUPPORT -> "Поддержка атаки";
            case LOGISTICS -> "Логистика";
        };
    }
}
