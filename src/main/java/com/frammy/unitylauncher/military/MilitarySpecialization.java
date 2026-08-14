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
    DEFENSE("unity.military.defense"),
    HOSPITAL("unity.military.hospital_regen"),
    RECON("unity.military.recon"),
    ATTACK_SUPPORT("unity.military.attack_support"),
    LOGISTICS("unity.military.logistics");

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

    MilitarySpecialization(String permBase) {
        this.permBase = permBase;
    }

    /** Максимум объектов страны, которые могут одновременно нести эту специализацию — 0, если апгрейд не куплен вообще. */
    public int purchasedQuota(String canonicalCountryId) {
        if (canonicalCountryId == null) return 0;
        return UpgradeCondition.countryMaxLevel(canonicalCountryId, permBase, MAX_QUOTA_LEVEL);
    }

    /** True if the zone's country has actually bought (level >= 1) the upgrade this specialization requires. */
    public boolean unlockedFor(String canonicalCountryId) {
        return purchasedQuota(canonicalCountryId) >= 1;
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
