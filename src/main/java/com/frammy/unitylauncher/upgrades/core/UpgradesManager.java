package com.frammy.unitylauncher.upgrades.core;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.config.UpgradesCfg;
import com.frammy.unitylauncher.upgrades.config.UpgradesConfig;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UpgradesManager {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;

    private UpgradesCfg  config;
    private UpgradeContext ctx;

    private final Map<UpgradeKey, Upgrade> upgrades = new LinkedHashMap<>();
    private final List<Upgrade> enabled = new ArrayList<>();
    private final Map<UpgradeKey, Listener> registeredListeners = new LinkedHashMap<>();

    public UpgradesManager(UnityLauncher plugin) {
        this.plugin = plugin;
        this.zoneManager = plugin.getZoneManager();
    }

    public UpgradesCfg config() { return config; }

    /** Достать включённый апгрейд по классу (самый нужный метод для интеграций). */
    public <T extends Upgrade> T getEnabled(Class<T> type) {
        if (type == null) return null;
        for (Upgrade u : enabled) {
            if (type.isInstance(u)) return type.cast(u);
        }
        return null;
    }

    public void register(Upgrade upgrade) {
        UpgradeKey key = upgrade.key();
        if (upgrades.containsKey(key)) {
            throw new IllegalStateException("Duplicate upgrade key: " + key);
        }
        upgrades.put(key, upgrade);
    }

    public void start() {
        this.config = UpgradesConfig.load(plugin);

        // важное: старт должен начинаться с чистого состояния
        this.enabled.clear();
        this.registeredListeners.clear();

        if (!config.core().enabled()) {
            if (config.core().debug()) plugin.getLogger().info("[Upgrades] core.enabled=false -> upgrades are disabled");
            this.ctx = null;
            return;
        }

        this.ctx = new UpgradeContext(plugin, zoneManager, config);

        for (Upgrade u : upgrades.values()) {
            if (!u.enabledByConfig(ctx)) {
                if (config.core().debug()) plugin.getLogger().info("[Upgrades] disabled by config: " + u.key());
                continue;
            }

            try {
                Listener l = u.listener();
                if (l != null) {
                    Bukkit.getPluginManager().registerEvents(l, plugin);
                    registeredListeners.put(u.key(), l);
                }

                u.enable(ctx);
                enabled.add(u);

                if (config.core().debug()) plugin.getLogger().info("[Upgrades] enabled: " + u.key());
            } catch (Throwable t) {
                plugin.getLogger().warning("[Upgrades] enable failed: " + u.key() + " -> " + t.getMessage());

                // откатываем апгрейд, если он успел что-то запустить
                try { u.disable(); } catch (Throwable ignored) {}

                // отписываем листенер, если он успел зарегистрироваться
                try {
                    Listener l = registeredListeners.remove(u.key());
                    if (l != null) HandlerList.unregisterAll(l);
                } catch (Throwable ignored) {}
            }
        }
    }

    public void stop() {
        for (int i = enabled.size() - 1; i >= 0; i--) {
            Upgrade u = enabled.get(i);
            try {
                try {
                    u.disable();
                } finally {
                    Listener l = registeredListeners.remove(u.key());
                    if (l != null) HandlerList.unregisterAll(l);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[Upgrades] disable failed: " + u.key() + " -> " + t.getMessage());
            }
        }
        registeredListeners.clear();
        enabled.clear();
        this.ctx = null;
    }

    public void reload() {
        stop();
        start();
    }
}
