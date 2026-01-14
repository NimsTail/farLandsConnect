package com.frammy.unitylauncher.upgrades.config;

import com.frammy.unitylauncher.upgrades.config.types.*;

public record UpgradesCfg(
        CoreCfg core,
        CountryCfg country,
        ColonyCfg colony,
        IndustrialCfg industrial,
        BankCfg bank,
        HospitalCfg hospital,
        LibraryCfg library,
        ParkCfg park
) {}