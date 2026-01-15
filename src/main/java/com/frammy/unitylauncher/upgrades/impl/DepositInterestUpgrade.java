package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.BankCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class DepositInterestUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("bank.deposit_interest");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public org.bukkit.event.Listener listener() { return null; }

    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        BankCfg.DepositInterestCfg cfg = ctx.config().bank().depositInterest();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().bank().depositInterest();
        long period = Math.max(20L, cfg.periodTicks());
        if (task != null) task.cancel();

        task = new BukkitRunnable() {
            @Override public void run() {
                processInterest();
            }
        }.runTaskTimer(plugin(), period, period);

        if (C().core().debug()) plugin().getLogger().info("[Bank/DepositInterest] Started periodTicks=" + period);
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void processInterest() {
        var cfg = C().bank().depositInterest();

        SafeDepositUpgrade safesUpgrade =
                plugin().getUpgradesManager().getEnabled(SafeDepositUpgrade.class);
        if (safesUpgrade == null) {
            if (C().core().debug()) {
                plugin().getLogger().warning("[Bank/DepositInterest] BankSafeDepositUpgrade is not enabled (interest skipped)");
            }
            return;
        }

        int processed = 0;

        for (Map.Entry<Location, String> entry : safesUpgrade.safesSnapshot().entrySet()) {
            Location loc = entry.getKey();
            String ownerName = entry.getValue();
            if (loc == null || ownerName == null || ownerName.isBlank()) continue;

            // апгрейд должен быть у страны владельца
            String pc = UpgradeCondition.playerCountryCanonical(ownerName);
            if (pc == null || countryMaxLevel(pc, cfg.permBase(), 1) < 1) continue;

            // сундук должен быть загружен
            if (!loc.isWorldLoaded() || !loc.getChunk().isLoaded()) continue;

            Block b = loc.getBlock();
            if (b.getType() != Material.CHEST) continue;
            if (!(b.getState() instanceof Chest chest)) continue;

            Inventory inv = chest.getInventory();
            double totalCash = countCashInInventory(inv);
            if (totalCash <= 0.0) continue;

            // dailyPercent / periodsPerDay
            double perTickPercent = cfg.dailyPercent() / Math.max(1, cfg.periodsPerDay());
            double interest = totalCash * (perTickPercent / 100.0);

            if (interest + 1e-9 < cfg.minPayout()) continue;

            addMoneyToPlayerAccount(ownerName, interest);
            processed++;

            if (C().core().debug()) {
                plugin().getLogger().info("[Bank/DepositInterest] " + ownerName
                        + " +" + round2(interest) + " from cash=" + round2(totalCash)
                        + " pc=" + pc);
            }
        }

        if (processed > 0) {
            plugin().getLogger().info("[Bank/DepositInterest] Processed interest for " + processed + " safes");
        }
    }

    private double countCashInInventory(Inventory inv) {
        if (inv == null) return 0.0;

        double sum = 0.0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            if (!plugin().moneyManager.isMoneyItem(it)) continue;

            double unit = plugin().moneyManager.getMoneyValue(it); // <-- новый public метод
            if (unit <= 0.0) continue;

            sum += unit * it.getAmount();
        }
        return sum;
    }

    private void addMoneyToPlayerAccount(String playerName, double amount) {
        new BukkitRunnable() {
            @Override public void run() {
                UnityCommands.getInstance().getPlayerInfo(playerName, data -> {
                    if (data == null) return;
                    double newBalance = round2(data.money + amount);
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("money", newBalance);
                    UnityCommands.getInstance().mergeAndUpdatePlayerData(playerName, "GeneralData", upd);
                });
            }
        }.runTaskAsynchronously(plugin());
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
