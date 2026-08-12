package com.frammy.unitylauncher.upgrades.config;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.config.types.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class UpgradesConfig {

    private UpgradesConfig() {}

    public static UpgradesCfg load(UnityLauncher plugin) {
        File file = new File(plugin.getDataFolder(), "upgrades.yml");
        FileConfiguration yml = YamlConfiguration.loadConfiguration(file);

        boolean existed = file.exists();

        boolean dirty = false;

        dirty |= CoreCfg.addDefaults(yml);
        dirty |= IndustrialCfg.addDefaults(yml);
        dirty |= BankCfg.addDefaults(yml);
        dirty |= CountryCfg.addDefaults(yml);
        dirty |= ColonyCfg.addDefaults(yml);
        dirty |= HospitalCfg.addDefaults(yml);
        dirty |= LibraryCfg.addDefaults(yml);
        dirty |= ParkCfg.addDefaults(yml);
        dirty |= MilitaryCfg.addDefaults(yml);

        if (!existed || dirty) {
            try {
                if (!file.exists()) {
                    plugin.getDataFolder().mkdirs();
                    file.createNewFile();
                }
                yml.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning("[UpgradesConfig] failed to save upgrades.yml: " + e.getMessage());
            }
        }

        CoreCfg core = CoreCfg.read(yml);
        CountryCfg country = CountryCfg.read(yml);
        ColonyCfg colony = ColonyCfg.read(yml);
        IndustrialCfg industrial = IndustrialCfg.read(yml);
        BankCfg bank = BankCfg.read(yml);
        HospitalCfg hospital = HospitalCfg.read(yml);
        LibraryCfg library = LibraryCfg.read(yml);
        ParkCfg park = ParkCfg.read(yml);
        MilitaryCfg military = MilitaryCfg.read(yml);

        return new UpgradesCfg(core, country, colony, industrial, bank, hospital, library, park, military);
    }
}
