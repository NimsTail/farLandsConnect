package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.BankCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class AtmFeesUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("bank.atm_fees");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public org.bukkit.event.Listener listener() { return null; }

    private final Map<UUID, Long> msgCooldown = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        BankCfg.AtmFeesCfg cfg = ctx.config().bank().atmFees();
        return cfg != null && cfg.enabled();
    }

    /**
     * @return комиссия в долях: 0.01 = 1%
     */
    public double calculateAtmFee(String playerName, Location atmLocation, double amount) {
        if (!enabled()) return 0.0;

        if (playerName == null || playerName.isBlank()) return 0.0;
        if (atmLocation == null || amount <= 0.0) return 0.0;

        var cfg = C().bank().atmFees();

        String pc = UpgradeCondition.playerCountryCanonical(playerName);
        if (pc == null || pc.isBlank()) return 0.0;

        ZoneInfo zone = UpgradeCondition.zoneAt(atmLocation);

        // 1) ATM стоит в BANK зоне
        if (zone != null && zone.getType() == ZoneType.BANK) {
            boolean hasAtmNetwork = countryMaxLevel(pc, cfg.atmNetworkPermBase(), 1) >= 1;
            return hasAtmNetwork ? cfg.feeInBank() : cfg.feeForeign();
        }

        // 2) ATM стоит на территории той же страны (включая любые внутренние зоны)
        String locCountry = UpgradeCondition.countryCanonicalAt(atmLocation); // новый helper, см. ниже
        if (pc.equals(locCountry)) {
            boolean free = countryMaxLevel(pc, cfg.freeTransferPermBase(), 1) >= 1;
            return free ? 0.0 : cfg.feeInCountry();
        }

        // 3) чужая/нейтралка
        return cfg.feeForeign();
    }

    /**
     * @return итог после комиссии (amount - fee)
     */
    public double applyAtmFee(Player player, Location atmLocation, double amount) {
        if (player == null) return amount;

        double rate = calculateAtmFee(player.getName(), atmLocation, amount);
        if (rate <= 1e-9) return amount;

        double fee = amount * rate;
        double result = amount - fee;

        // спам-контроль сообщений
        long now = System.currentTimeMillis();
        Long last = msgCooldown.get(player.getUniqueId());
        if (last == null || (now - last) > 5000) {
            if (fee >= 0.01) {
                player.sendMessage(ChatColor.YELLOW + "Комиссия банкомата: "
                        + ChatColor.RED + String.format(java.util.Locale.ROOT, "%.2f", fee) + " Ⓕ "
                        + ChatColor.GRAY + "(" + String.format(java.util.Locale.ROOT, "%.1f", rate * 100.0) + "%)");
            }
            msgCooldown.put(player.getUniqueId(), now);
        }

        return result;
    }

    private boolean enabled() {
        var cfg = C().bank().atmFees();
        return cfg != null && cfg.enabled();
    }
}
