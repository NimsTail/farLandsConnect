package com.frammy.unitylauncher.marketplace;

import com.frammy.unitylauncher.auth.FarLandsApiClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Чат на карте BlueMap (сайт: frontend/public/bluemap-chat.js, routes/plugin/mapChat.ts).
 * Игра → сайт: каждое реально отправленное сообщение чата (MONITOR, уже не
 * отменённое гейтами авторизации). Сайт → игра: опрос pending и рассылка всем.
 */
public final class MapChatBridge implements Listener {

    private final JavaPlugin plugin;
    private final FarLandsApiClient api;
    private final AtomicBoolean busy = new AtomicBoolean();

    public MapChatBridge(JavaPlugin plugin, FarLandsApiClient api) {
        this.plugin = plugin;
        this.api = api;
    }

    /** periodTicks: 20 = 1с. No-op, если API-мост выключен. */
    public void start(long periodTicks) {
        if (!api.isEnabled()) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, periodTicks, periodTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String text = event.getMessage();
        if (text.isBlank() || text.startsWith("fps://")) return;
        api.postMapChatMessage(event.getPlayer().getName(), text);
    }

    private void pollOnce() {
        if (!busy.compareAndSet(false, true)) return;
        boolean handedOff = false;
        try {
            List<FarLandsApiClient.MapChatMessage> batch = api.fetchPendingMapChat();
            if (batch.isEmpty()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    for (var m : batch) {
                        // Component.text — без интерпретации §-кодов из текста с сайта.
                        Bukkit.broadcast(Component.text("[Карта] ", NamedTextColor.AQUA)
                                .append(Component.text(m.authorName() + ": ", NamedTextColor.GRAY))
                                .append(Component.text(m.text(), NamedTextColor.WHITE)));
                        api.reportMapChatDelivered(m.id());
                    }
                } finally {
                    busy.set(false);
                }
            });
            handedOff = true;
        } finally {
            if (!handedOff) busy.set(false);
        }
    }
}
