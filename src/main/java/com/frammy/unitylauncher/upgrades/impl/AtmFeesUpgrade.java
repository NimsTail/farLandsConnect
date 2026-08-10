package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.BankCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Location;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * Комиссия ATM — только расчёт ставки, ни списаний, ни сообщений (это
 * делает AtmController: единая модель "получатель получает сумму целиком,
 * комиссию сверху платит отправитель", см. backend/src/routes/bank.ts —
 * плагин и сайт теперь зеркалят друг друга по ставкам, см. javadoc
 * calculateAtmFee).
 */
public final class AtmFeesUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("bank.atm_fees");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public org.bukkit.event.Listener listener() { return null; }

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        BankCfg.AtmFeesCfg cfg = ctx.config().bank().atmFees();
        return cfg != null && cfg.enabled();
    }

    /**
     * Три уровня, приоритет от дешёвого к дорогому — зеркалит сайтовые
     * SAME_COUNTRY_COMMISSION_RATE/FOREIGN_COMMISSION_RATE
     * (backend/src/routes/bank.ts):
     *   1. ATM в BANK-зоне своей страны — feeInBankBase, с "Сетью
     *      банкоматов" (atmNetworkPermBase) — feeInBank (обычно 0%).
     *      Единственный уровень, которого нет на сайте.
     *   2. ATM на остальной территории своей страны — feeInCountry, с
     *      "Бесплатными переводами" (freeTransferPermBase) — 0%. Та же
     *      ставка и то же улучшение, что и у сайтового перевода внутри
     *      страны — сайт никогда не выгоднее похода к местному банкомату.
     *   3. Чужая страна/нейтралка — feeForeign, ни одно улучшение не
     *      помогает (как и межстрановой перевод на сайте).
     *
     * @return комиссия в долях, зажата в [0, 1] независимо от того, что
     *         стоит в upgrades.yml (защита от опечаток вроде 2.0 = 200%,
     *         которые раньше уводили net в минус).
     */
    public double calculateAtmFee(String playerName, Location atmLocation, double amount) {
        double rate = rawRate(playerName, atmLocation, amount);
        if (rate < 0.0) return 0.0;
        if (rate > 1.0) return 1.0;
        return rate;
    }

    private double rawRate(String playerName, Location atmLocation, double amount) {
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
            return hasAtmNetwork ? cfg.feeInBank() : cfg.feeInBankBase();
        }

        // 2) ATM стоит на территории той же страны (включая любые внутренние зоны)
        String locCountry = UpgradeCondition.countryCanonicalAt(atmLocation);
        if (pc.equals(locCountry)) {
            boolean free = countryMaxLevel(pc, cfg.freeTransferPermBase(), 1) >= 1;
            return free ? 0.0 : cfg.feeInCountry();
        }

        // 3) чужая/нейтралка
        return cfg.feeForeign();
    }

    private boolean enabled() {
        var cfg = C().bank().atmFees();
        return cfg != null && cfg.enabled();
    }
}
