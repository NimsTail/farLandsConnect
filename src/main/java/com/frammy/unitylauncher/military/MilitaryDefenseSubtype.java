package com.frammy.unitylauncher.military;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;

/**
 * infra/military-diplomacy-design.md §14.6, GH#24 (фидбек 2026-08-14 п.1/4) —
 * "тип сооружения" WITHIN the DEFENSE specialization. Before this, a country
 * buying e.g. "Живой оборонительный пост" made EVERY DEFENSE-specialized
 * zone run it at once (alongside any other purchased type) — no per-object
 * choice at all. This is the same one-active-thing-per-zone + purchasable-
 * quota pattern as MilitarySpecialization, just one level deeper: only
 * meaningful on a zone whose current MilitarySpecialization is DEFENSE.
 *
 * Deliberately a sibling of MilitarySpecialization, not a value inside it —
 * the switch/cooldown/quota machinery is identical (see
 * MilitaryDefenseSubtypeService, copy of MilitarySpecializationService) but
 * the two axes are independent: switching specialization away from DEFENSE
 * and back doesn't require re-picking a subtype (ZoneInfo just keeps it).
 */
public enum MilitaryDefenseSubtype {
    LIVE_DEFENSE("unity.military.live_defense"),
    AURA("unity.military.aura"),
    SCORCH("unity.military.scorch"),
    CROSSBOW("unity.military.crossbow");

    // Квота (сколько объектов страны одновременно могут нести этот тип) —
    // тот же паттерн unity.military.<slug>.<N>, что и у MilitarySpecialization
    // (см. её MAX_QUOTA_LEVEL).
    private static final int MAX_QUOTA_LEVEL = 5;

    /** Country-level LuckPerms permission base gating the QUOTA (unity.military.<slug>.<N> = максимум N объектов одновременно). */
    private final String permBase;

    // Раньше здесь был ещё и levelPermBase — единый узел "усиление" на тип
    // (сила эффекта, отдельно от квоты). Фидбек 2026-08-14 убрал эту идею
    // целиком: "усиление" не нужно, прокачка идёт через параметрические
    // апгрейды конкретно под каждый тип (скорострельность/эффекты у
    // Арбалета, интервал/сила у Ореола, длительность/молния у Жгучего и
    // т.д. — см. соответствующие Upgrade-классы, каждый сам читает свои
    // permission-базы напрямую через UpgradeCondition.countryMaxLevel, без
    // общего поля в этом enum).
    MilitaryDefenseSubtype(String permBase) {
        this.permBase = permBase;
    }

    /** Максимум объектов страны, которые могут одновременно нести этот тип обороны — 0, если апгрейд не куплен вообще. */
    public int purchasedQuota(String canonicalCountryId) {
        if (canonicalCountryId == null) return 0;
        return UpgradeCondition.countryMaxLevel(canonicalCountryId, permBase, MAX_QUOTA_LEVEL);
    }

    public boolean unlockedFor(String canonicalCountryId) {
        return purchasedQuota(canonicalCountryId) >= 1;
    }
}
