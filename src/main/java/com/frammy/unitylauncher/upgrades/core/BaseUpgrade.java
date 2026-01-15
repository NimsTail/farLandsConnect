package com.frammy.unitylauncher.upgrades.core;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.config.UpgradesCfg;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

public abstract class BaseUpgrade implements Upgrade {

    protected UpgradeContext ctx;
    protected final TaskGroup tasks = new TaskGroup();

    protected final UnityLauncher plugin() {
        if (ctx == null) throw new IllegalStateException(getClass().getSimpleName() + " is not enabled");
        return ctx.plugin();
    }
    protected final ZoneManager zones()    { return ctx.zoneManager(); }
    protected final UpgradesCfg C() { return ctx.config(); }

    @Override
    public final void enable(UpgradeContext ctx) {
        if (this.ctx != null) throw new IllegalStateException("Upgrade already enabled: " + key());
        this.ctx = ctx;
        onEnable();
    }

    @Override
    public final void disable() {
        if (ctx == null) {
            tasks.cancelAll();
            return;
        }
        try { onDisable(); } finally {
            tasks.cancelAll();
            ctx = null;
        }
    }

    protected void onEnable() { }
    protected void onDisable() { }

    @Override
    public @Nullable Listener listener() { return null; }
}
