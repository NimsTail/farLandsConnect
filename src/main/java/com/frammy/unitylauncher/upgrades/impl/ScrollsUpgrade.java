package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.LibraryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class ScrollsUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("library.scrolls");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private static final long CACHE_TTL_MS = 10_000L;

    private final Map<UUID, Cache> cache = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        LibraryCfg.ScrollsCfg cfg = ctx.config().library().scrolls();
        return cfg != null && cfg.enabled();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        Player p = e.getEnchanter();
        if (p == null) return;

        Location tableLoc = e.getEnchantBlock().getLocation();

        ZoneInfo z = UpgradeCondition.zoneAt(tableLoc);
        if (z == null || z.getType() != ZoneType.LIBRARY) return;

        String country = UpgradeCondition.zoneCountryCanonical(z);
        if (country == null || country.isBlank()) return;

        var cfg = C().library().scrolls();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        cache.put(p.getUniqueId(), new Cache(country, System.currentTimeMillis() + CACHE_TTL_MS));

        if (C().core().debug()) {
            plugin().getLogger().info("[Library/Scrolls] prepared enchant for " + p.getName() + " country=" + country);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEnchant(EnchantItemEvent e) {
        Player p = e.getEnchanter();
        if (p == null) return;

        UUID uuid = p.getUniqueId();
        Cache c = cache.remove(uuid);
        if (c == null || c.expiryMs < System.currentTimeMillis()) return;

        var cfg = C().library().scrolls();

        // ещё раз проверим право
        if (countryMaxLevel(c.countryCanon, cfg.permBase(), 1) < 1) return;

        // Шанс возврата уровней (0..100)
        double chance = clamp01(cfg.refundChancePercent() / 100.0);
        if (chance <= 0.0) return;

        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll >= chance) {
            if (C().core().debug()) {
                plugin().getLogger().info("[Library/Scrolls] no proc for " + p.getName()
                        + " chance=" + chance + " roll=" + roll);
            }
            return;
        }

        // Сколько реально сняло уровней — фиксируем до/после через тик
        final int before = p.getLevel();

        plugin().getServer().getScheduler().runTask(plugin(), () -> {
            if (!p.isOnline()) return;

            int after = p.getLevel();
            int taken = Math.max(0, before - after); // обычно 1..3

            if (taken <= 0) {
                if (C().core().debug()) {
                    plugin().getLogger().info("[Library/Scrolls] proc but taken<=0 for " + p.getName()
                            + " before=" + before + " after=" + after);
                }
                return;
            }

            int maxRefund = Math.min(3, taken);
            int refund = 1 + ThreadLocalRandom.current().nextInt(maxRefund); // равномерно 1..maxRefund

            p.giveExpLevels(refund);

            p.sendActionBar(Component.text("✦ Свитки экономии: +" + refund + " уров.",
                    NamedTextColor.LIGHT_PURPLE));

            if (C().core().debug()) {
                plugin().getLogger().info("[Library/Scrolls] PROC " + p.getName()
                        + " taken=" + taken + " refund=" + refund
                        + " chance=" + chance
                        + " country=" + c.countryCanon);
            }
        });
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private record Cache(String countryCanon, long expiryMs) {}
}
