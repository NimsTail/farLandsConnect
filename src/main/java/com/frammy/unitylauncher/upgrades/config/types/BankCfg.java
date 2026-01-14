package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;
import static com.frammy.unitylauncher.upgrades.config.CfgIO.str;

public record BankCfg(
        SafeDepositCfg safeDeposit,
        DepositInterestCfg depositInterest,
        AtmFeesCfg atmFees
) {
    public static BankCfg defaults() {
        return new BankCfg(
                SafeDepositCfg.defaults(),
                DepositInterestCfg.defaults(),
                AtmFeesCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        var sd = SafeDepositCfg.defaults();
        dirty |= def(c, "bank.safeDeposit.enabled", sd.enabled());
        dirty |= def(c, "bank.safeDeposit.permBase", sd.permBase());
        dirty |= def(c, "bank.safeDeposit.maxPerPlayer", sd.maxPerPlayer());

        var di = DepositInterestCfg.defaults();
        dirty |= def(c, "bank.depositInterest.enabled", di.enabled());
        dirty |= def(c, "bank.depositInterest.permBase", di.permBase());
        dirty |= def(c, "bank.depositInterest.dailyPercent", di.dailyPercent());
        dirty |= def(c, "bank.depositInterest.periodsPerDay", di.periodsPerDay());
        dirty |= def(c, "bank.depositInterest.periodTicks", di.periodTicks());
        dirty |= def(c, "bank.depositInterest.minPayout", di.minPayout());

        var af = AtmFeesCfg.defaults();
        dirty |= def(c, "bank.atmFees.enabled", af.enabled());
        dirty |= def(c, "bank.atmFees.atmNetworkPermBase", af.atmNetworkPermBase());
        dirty |= def(c, "bank.atmFees.freeTransferPermBase", af.freeTransferPermBase());
        dirty |= def(c, "bank.atmFees.feeInBank", af.feeInBank());
        dirty |= def(c, "bank.atmFees.feeInCountry", af.feeInCountry());
        dirty |= def(c, "bank.atmFees.feeForeign", af.feeForeign());

        return dirty;
    }

    public static BankCfg read(FileConfiguration c) {
        var d = defaults();

        var sdD = SafeDepositCfg.defaults();
        var sd = new SafeDepositCfg(
                c.getBoolean("bank.safeDeposit.enabled", sdD.enabled()),
                str(c, "bank.safeDeposit.permBase", sdD.permBase()),
                c.getInt("bank.safeDeposit.maxPerPlayer", sdD.maxPerPlayer())
        );

        var diD = DepositInterestCfg.defaults();
        var di = new DepositInterestCfg(
                c.getBoolean("bank.depositInterest.enabled", diD.enabled()),
                str(c, "bank.depositInterest.permBase", diD.permBase()),
                c.getDouble("bank.depositInterest.dailyPercent", diD.dailyPercent()),
                c.getInt("bank.depositInterest.periodsPerDay", diD.periodsPerDay()),
                c.getLong("bank.depositInterest.periodTicks", diD.periodTicks()),
                c.getDouble("bank.depositInterest.minPayout", diD.minPayout())
        );

        var afD = AtmFeesCfg.defaults();
        var af = new AtmFeesCfg(
                c.getBoolean("bank.atmFees.enabled", afD.enabled()),
                str(c, "bank.atmFees.atmNetworkPermBase", afD.atmNetworkPermBase()),
                str(c, "bank.atmFees.freeTransferPermBase", afD.freeTransferPermBase()),
                c.getDouble("bank.atmFees.feeInBank", afD.feeInBank()),
                c.getDouble("bank.atmFees.feeInCountry", afD.feeInCountry()),
                c.getDouble("bank.atmFees.feeForeign", afD.feeForeign())
        );

        return new BankCfg(sd, di, af);
    }

    public record SafeDepositCfg(
            boolean enabled,
            String permBase,
            int maxPerPlayer
    ) {
        public static SafeDepositCfg defaults() {
            return new SafeDepositCfg(
                    true,
                    "unity.bank.safe_deposit",
                    1
            );
        }
    }

    public record DepositInterestCfg(
            boolean enabled,
            String permBase,
            double dailyPercent,
            int periodsPerDay,
            long periodTicks,
            double minPayout
    ) {
        public static DepositInterestCfg defaults() {
            return new DepositInterestCfg(
                    true,
                    "unity.bank.deposit_interest",
                    1.0,          // 1% в день
                    24,           // раз в час
                    20L * 60L * 60L, // 1 час
                    0.1
            );
        }
    }

    public record AtmFeesCfg(
            boolean enabled,
            String atmNetworkPermBase,
            String freeTransferPermBase,
            double feeInBank,
            double feeInCountry,
            double feeForeign
    ) {
        public static AtmFeesCfg defaults() {
            return new AtmFeesCfg(
                    true,
                    "unity.bank.atm_network",
                    "unity.bank.atm_free_transfer",
                    0.0,   // внутри банка
                    0.5,   // внутри страны (не в банке)
                    2.0    // чужая страна
            );
        }
    }
}
