package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.LibraryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.Locale;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class EducationUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("library.education");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return null; } // событий нет, это API-хелпер

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        LibraryCfg.EducationCfg cfg = ctx.config().library().education();
        return cfg != null && cfg.enabled();
    }

    /** Вызывать из системы квестов/награды. */
    public double applyEducationBonus(Player p, double baseAmount) {
        if (p == null) return baseAmount;
        if (baseAmount <= 0) return baseAmount;

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null || z.getType() != ZoneType.LIBRARY) return baseAmount;

        String country = UpgradeCondition.zoneCountryCanonical(z);
        if (country == null || country.isBlank()) return baseAmount;

        var cfg = C().library().education();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return baseAmount;

        double mult = cfg.rewardMultiplier();
        if (mult <= 0.0) mult = 1.0;

        double total = baseAmount * mult;

        double bonusPct = (mult - 1.0) * 100.0;
        if (bonusPct > 0.01) {
            p.sendMessage(ChatColor.AQUA + "✦ Образовательная инициатива: +"
                    + String.format(Locale.US, "%.0f", bonusPct) + "% к награде");
        }

        if (C().core().debug()) {
            plugin().getLogger().info("[Library/Education] " + p.getName()
                    + " reward " + baseAmount + " -> " + total
                    + " mult=" + mult + " country=" + country);
        }

        return total;
    }

    public boolean hasEducationBonus(Player p) {
        if (p == null) return false;

        ZoneInfo z = UpgradeCondition.zoneAt(p.getLocation());
        if (z == null || z.getType() != ZoneType.LIBRARY) return false;

        String country = UpgradeCondition.zoneCountryCanonical(z);
        if (country == null || country.isBlank()) return false;

        var cfg = C().library().education();
        return countryMaxLevel(country, cfg.permBase(), 1) >= 1;
    }

    public double getEducationBonusMultiplier(Player p) {
        if (!hasEducationBonus(p)) return 1.0;
        double m = C().library().education().rewardMultiplier();
        return m <= 0.0 ? 1.0 : m;
    }
}
