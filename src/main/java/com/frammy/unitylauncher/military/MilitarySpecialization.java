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

    /** Country-level LuckPerms permission base gating this specialization at all (see upgrades_country.json). */
    private final String permBase;

    MilitarySpecialization(String permBase) {
        this.permBase = permBase;
    }

    /** True if the zone's country has actually bought (level >= 1) the upgrade this specialization requires. */
    public boolean unlockedFor(String canonicalCountryId) {
        return canonicalCountryId != null && UpgradeCondition.countryMaxLevel(canonicalCountryId, permBase, 1) >= 1;
    }
}
