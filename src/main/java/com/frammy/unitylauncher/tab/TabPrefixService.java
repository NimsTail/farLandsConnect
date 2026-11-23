package com.frammy.unitylauncher.tab;

import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TabPrefixService implements Listener {

    private final Function<UUID, String[]> prefixProvider;
    private final Plugin plugin;

    private LuckPermsPrefixService lpService;

    public TabPrefixService(Plugin plugin, Function<UUID, String[]> prefixProvider) {
        this.plugin = plugin;
        this.prefixProvider = prefixProvider;

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Раньше единственным триггером на джойне был PlayerJoinEvent + 1
        // тик — но TAB грузит своего внутреннего TabPlayer асинхронно, и
        // этого тика часто не хватало: NameTagManager.setPrefix/setSuffix
        // дергались ДО TabPlayer.isLoaded(), TAB ловил у себя
        // IllegalStateException("This player is not loaded yet") в
        // plugins/TAB/errors.log и молча ронял именно этот вызов — префикс
        // так и не применялся, пока не долетал следующий тик
        // applyForAllOnlinePlayers (раз в 15с, общий таймер, не per-player).
        // Игрок в моменте видел: сек 0-2 — что успело нарисовать само TAB
        // (обычно ничего/старое из tablist-кэша), дальше пусто, пока не
        // подоспеет таймер — отсюда и "старый префикс -> пропадает".
        // TAB.PlayerLoadEvent — штатный сигнал "точно догружен", ждём его
        // вместо угадывания задержки тиком.
        try {
            var bus = TabAPI.getInstance().getEventBus();
            if (bus != null) {
                bus.register(PlayerLoadEvent.class, e -> {
                    Player p = Bukkit.getPlayer(e.getPlayer().getUniqueId());
                    if (p != null) applyFromCache(p);
                });
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UnityLauncher] TAB PlayerLoadEvent subscribe failed: " + t.getMessage());
        }

        // таймер: периодически ре-апплаим, чтобы пережить /tab reload
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyForAllOnlinePlayers, 20L * 5, 20L * 15);
    }

    private static Component buildDisplayName(String playerName, String prefix, String suffix) {
        String p = (prefix != null) ? prefix.replace('§','&') : "";
        String s = (suffix != null) ? suffix.replace('§','&') : "";
        return LegacyComponentSerializer.legacyAmpersand().deserialize(p + playerName + s);
    }

    public void setPrefixSuffix(Player player, String prefix, String suffix) {
        if (player == null || !isTabAvailable()) return;

        try {
            TabAPI api = TabAPI.getInstance();
            TabPlayer tp = api.getPlayer(player.getUniqueId());
            if (tp == null) return;
            // Второй рубеж защиты помимо PlayerLoadEvent-подписки в
            // конструкторе (applyForAllOnlinePlayers/reload могут пройти
            // мимо неё) — nt.setPrefix/setSuffix на ещё не загруженном
            // TabPlayer у TAB кидает исключение ВНУТРИ своей же async-задачи
            // (см. plugins/TAB/errors.log), наш try/catch тут его не ловит,
            // и вызов просто теряется без следа. PlayerLoadEvent потом всё
            // равно перевызовет applyFromCache для этого игрока.
            if (!tp.isLoaded()) return;

            NameTagManager nt = api.getNameTagManager();
            if (nt != null) {
                nt.setPrefix(tp, prefix);
                nt.setSuffix(tp, suffix);
            }

            TabListFormatManager tl = api.getTabListFormatManager();
            if (tl != null) {
                tl.setPrefix(tp, prefix);
                tl.setSuffix(tp, suffix);
            }

            player.displayName(buildDisplayName(player.getName(), prefix, suffix));

            if (lpService != null) {
                lpService.applyOrClear(player.getUniqueId(), prefix);
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UnityLauncher] TAB apply failed for " + player.getName() + ": " + t.getMessage());
        }
    }

    public void applyFromCache(Player player) {
        if (player == null) return;
        String[] ps = safeFetch(player.getUniqueId());
        setPrefixSuffix(player, ps[0], ps[1]);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // даём TAB один тик прогрузиться
        Bukkit.getScheduler().runTask(plugin, () -> applyFromCache(e.getPlayer()));
    }

    public void applyForAllOnlinePlayers() {
        if (!isTabAvailable()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyFromCache(p);
        }
    }

    private boolean isTabAvailable() {
        try {
            TabAPI.getInstance();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String[] safeFetch(UUID uuid) {
        try {
            String[] ps = prefixProvider.apply(uuid);
            if (ps == null) return new String[]{null, null};
            if (ps.length == 1) return new String[]{ps[0], null};
            return ps;
        } catch (Throwable t) {
            return new String[]{null, null};
        }
    }

    public void setLuckPermsPrefixService(LuckPermsPrefixService svc) {
        this.lpService = svc;
    }
}
