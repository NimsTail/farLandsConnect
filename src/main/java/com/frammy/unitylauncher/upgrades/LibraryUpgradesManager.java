package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * Library upgrades:
 * 1) Scrolls of Economy — cheaper enchanting (XP cost multiplier)
 * 2) Calm — chance to cancel hunger loss inside library
 * 3) Education Initiative — reward multiplier for quest payouts inside library
 */
public class LibraryUpgradesManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final UpgradesConfig config;

    // анти-спам для голода
    private final Map<UUID, Long> lastHungerCheck = new ConcurrentHashMap<>();

    // кеш: игрок начал чарить в библиотеке -> применяем скидку при фактическом зачаре
    private final Map<UUID, EnchantmentContext> enchantmentCache = new ConcurrentHashMap<>();

    public LibraryUpgradesManager(UnityLauncher plugin, ZoneManager zoneManager, UpgradesConfig config) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.config = config;
    }

    public void start() {
        if (config.debug) plugin.getLogger().info("[LibraryUpgrades] Started");
    }

    public void stop() {
        lastHungerCheck.clear();
        enchantmentCache.clear();
    }

    // =====================================================================
    //  1) SCROLLS OF ECONOMY — cheaper enchanting (XP cost multiplier)
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        Player player = e.getEnchanter();
        Location loc = e.getEnchantBlock().getLocation();

        ZoneInfo zone = zoneManager.getZoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.LIBRARY) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.libraryScrollsPerm, 1) < 1) return;

        enchantmentCache.put(player.getUniqueId(), new EnchantmentContext(zone, System.currentTimeMillis() + 10_000L));

        if (config.debug) {
            plugin.getLogger().info("[LibraryUpgrades] Scrolls: prepared enchant for " + player.getName());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEnchant(EnchantItemEvent e) {
        Player player = e.getEnchanter();
        UUID uuid = player.getUniqueId();

        EnchantmentContext ctx = enchantmentCache.get(uuid);
        if (ctx == null || ctx.expiry < System.currentTimeMillis()) {
            enchantmentCache.remove(uuid);
            return;
        }

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(ctx.zone);
        if (zoneCountry == null) {
            enchantmentCache.remove(uuid);
            return;
        }

        if (countryMaxLevel(zoneCountry, config.libraryScrollsPerm, 1) < 1) {
            enchantmentCache.remove(uuid);
            return;
        }

        int originalExpCost = e.getExpLevelCost();
        double mult = config.libraryScrollsExpCostMultiplier;

        if (mult <= 0) mult = 0.01;
        if (mult >= 1.0) { // скидки нет
            enchantmentCache.remove(uuid);
            return;
        }

        int newExpCost = Math.max(1, (int) Math.ceil(originalExpCost * mult));
        e.setExpLevelCost(newExpCost);

        int saved = Math.max(0, originalExpCost - newExpCost);
        if (saved > 0) {
            player.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Свитки экономии: -" + saved + " уровней опыта");
        }

        if (config.debug) {
            plugin.getLogger().info("[LibraryUpgrades] Scrolls: " + player.getName() +
                    " exp cost " + originalExpCost + " -> " + newExpCost);
        }

        enchantmentCache.remove(uuid);
    }

    // =====================================================================
    //  2) CALM — chance to cancel hunger loss inside library
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFoodLevelChange(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;

        // только снижение голода
        if (e.getFoodLevel() >= player.getFoodLevel()) return;

        ZoneInfo zone = zoneManager.getZoneAt(player.getLocation());
        if (zone == null || zone.getType() != ZoneType.LIBRARY) return;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return;

        if (countryMaxLevel(zoneCountry, config.libraryCalmPerm, 1) < 1) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastHungerCheck.get(uuid);
        if (last != null && (now - last) < 750) return; // анти-спам

        double chance = config.libraryCalmChanceToCancelHunger;
        if (chance <= 0) return;
        if (chance >= 1.0) {
            e.setCancelled(true);
            lastHungerCheck.put(uuid, now);
            return;
        }

        if (Math.random() < chance) {
            e.setCancelled(true);
            lastHungerCheck.put(uuid, now);

            if (config.debug) {
                plugin.getLogger().info("[LibraryUpgrades] Calm: hunger loss prevented for " + player.getName());
            }
        }
    }

    // =====================================================================
    //  3) EDUCATION — reward multiplier for quest payouts inside library
    // =====================================================================

    /** API: вызывать из системы квестов при выдаче награды */
    @SuppressWarnings("unused")
    public double applyEducationBonus(Player player, double baseAmount) {
        ZoneInfo zone = zoneManager.getZoneAt(player.getLocation());
        if (zone == null || zone.getType() != ZoneType.LIBRARY) return baseAmount;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return baseAmount;

        if (countryMaxLevel(zoneCountry, config.libraryEducationPerm, 1) < 1) return baseAmount;

        double mult = config.libraryEducationRewardMultiplier;
        if (mult <= 0) mult = 1.0;

        double total = baseAmount * mult;

        double bonusPct = (mult - 1.0) * 100.0;
        if (bonusPct > 0.01) {
            player.sendMessage(ChatColor.AQUA + "✦ Образовательная инициатива: +" +
                    String.format("%.0f", bonusPct) + "% к награде");
        }

        if (config.debug) {
            plugin.getLogger().info("[LibraryUpgrades] Education: " + player.getName() +
                    " reward " + baseAmount + " -> " + total);
        }

        return total;
    }

    @SuppressWarnings("unused")
    public boolean hasEducationBonus(Player player) {
        ZoneInfo zone = zoneManager.getZoneAt(player.getLocation());
        if (zone == null || zone.getType() != ZoneType.LIBRARY) return false;

        String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (zoneCountry == null) return false;

        return countryMaxLevel(zoneCountry, config.libraryEducationPerm, 1) >= 1;
    }

    @SuppressWarnings("unused")
    public double getEducationBonusMultiplier(Player player) {
        return hasEducationBonus(player) ? config.libraryEducationRewardMultiplier : 1.0;
    }

    // =====================================================================
    //  Internal
    // =====================================================================

    private record EnchantmentContext(ZoneInfo zone, long expiry) {
    }
}
