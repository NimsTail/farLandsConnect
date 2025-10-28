package com.frammy.unitylauncher;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Безопасный менеджер WebSocket:
 * - не создаёт параллельных коннектов на одного игрока;
 * - ставит экспоненциальный backoff при ошибках/закрытии;
 * - ограничивает спам в лог;
 * - даёт tryForceConnect() с уважением к backoff-окну.
 */
public class WebSocketManager {

    private enum State { IDLE, CONNECTING, OPEN }

    private static final long BASE_BACKOFF_SEC = 5;     // стартовая задержка
    private static final long MAX_BACKOFF_SEC  = 300;   // потолок (5 минут)

    private final Logger log;
    private final Plugin plugin;
    private final URI endpointUri;

    /** Держим состояние на игрока. */
    private static final class Holder {
        volatile WebSocketClient client;
        volatile State state = State.IDLE;
        volatile int attempts = 0;             // сколько подряд фейлов (для backoff)
        volatile long nextAllowedConnectMs = 0; // когда можно снова пробовать
    }

    private final Map<String, Holder> holders = new ConcurrentHashMap<>();

    public WebSocketManager(Plugin plugin, Logger logger, String wsUrl) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.log = Objects.requireNonNull(logger, "logger");
        try {
            this.endpointUri = new URI(wsUrl != null ? wsUrl : "ws://localhost:1337/link");
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad WS URL: " + wsUrl, e);
        }
    }

    // Удобный старый конструктор (совместимость): ws://localhost:1337/link
    public WebSocketManager(Logger logger) {
        this(Bukkit.getPluginManager().getPlugin("UnityLauncher"), logger, "ws://localhost:1337/link");
    }

    /* ===================== ПУБЛИЧНОЕ API ===================== */

    /** Явная попытка подключиться (игрок попросил). Уважает backoff и единственность подключения. */
    public void tryForceConnect(Player player) {
        if (player == null) return;
        connectPlayer(player.getName(), true);
        player.sendMessage("§7Попытка подключения к лаунчеру…");
    }

    /** Мягкая попытка (например, при онлайне / периодически). */
    public void connectPlayer(String playerName) {
        connectPlayer(playerName, false);
    }

    public void disconnectPlayer(String playerName) {
        if (playerName == null) return;
        Holder h = holders.remove(key(playerName));
        if (h != null && h.client != null) {
            try { h.client.close(); } catch (Throwable ignore) {}
            log.info("WS: " + playerName + " отключён вручную.");
        }
    }

    public void disconnectAll() {
        for (String k : holders.keySet()) {
            disconnectPlayer(k);
        }
        holders.clear();
    }

    /** Отправка сообщения, если открыт канал. */
    public void sendMessageToPlayer(String playerName, String message) {
        Holder h = holders.get(key(playerName));
        if (h != null && h.client != null && h.client.isOpen()) {
            try {
                h.client.send(message);
                log.fine("WS -> [" + playerName + "]: " + message);
            } catch (Throwable t) {
                log.warning("WS send failed [" + playerName + "]: " + t.getMessage());
            }
        } else {
            log.fine("WS send skipped [" + playerName + "]: not connected.");
        }
    }

    /** Проверка состояния. */
    public boolean isPlayerConnected(String playerName) {
        Holder h = holders.get(key(playerName));
        return h != null && h.state == State.OPEN && h.client != null && h.client.isOpen();
    }

    /* ===================== ВНУТРЕННЕЕ ===================== */

    private void connectPlayer(String playerName, boolean forceUserAsk) {
        if (playerName == null || playerName.isBlank()) return;
        final String k = key(playerName);
        final long now = System.currentTimeMillis();
        final Holder h = holders.computeIfAbsent(k, kk -> new Holder());

        // уже подключаемся/подключены
        if (h.state == State.CONNECTING || h.state == State.OPEN) {
            return;
        }

        // backoff окно (если не force)
        if (!forceUserAsk && now < h.nextAllowedConnectMs) {
            // слишком рано для новой попытки
            return;
        }

        // создаём клиент один раз и сразу фиксируем состояние
        h.state = State.CONNECTING;

        WebSocketClient client = new WebSocketClient(endpointUri) {
            @Override public void onOpen(ServerHandshake handshakedata) {
                h.attempts = 0;
                h.state = State.OPEN;
                h.nextAllowedConnectMs = 0;
                log.info("WS: [" + k + "] подключен.");
            }

            @Override public void onMessage(String message) {
                log.fine("WS <- [" + k + "]: " + message);
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                log.warning("WS: [" + k + "] закрыт (" + code + "): " + reason);
                scheduleReconnect(k, h); // планируем переподключение
            }

            @Override public void onError(Exception ex) {
                log.warning("WS: [" + k + "] ошибка: " + ex.getMessage());
                // тут же будет onClose в большинстве стеков, но на всякий случай:
                if (h.state != State.OPEN) {
                    scheduleReconnect(k, h);
                }
            }
        };

        h.client = client;

        try {
            client.connect();
        } catch (Throwable t) {
            log.warning("WS connect throw [" + k + "]: " + t.getMessage());
            scheduleReconnect(k, h);
        }
    }

    /** Планируем реконнект с экспоненциальной задержкой. */
    private void scheduleReconnect(String k, Holder h) {
        // закрываем старый клиент и переходим в IDLE
        try { if (h.client != null) h.client.close(); } catch (Throwable ignore) {}
        h.client = null;
        h.state = State.IDLE;

        // растим попытку и высчитываем следующую паузу
        int attempt = Math.min(h.attempts + 1, 30);
        h.attempts = attempt;

        long delaySec = Math.min(MAX_BACKOFF_SEC, (long) (BASE_BACKOFF_SEC * Math.pow(2, attempt - 1)));
        long delayTicks = Duration.ofSeconds(delaySec).toMillis() / 50L;
        h.nextAllowedConnectMs = System.currentTimeMillis() + delaySec * 1000L;

        log.info("WS: [" + k + "] повторная попытка через " + delaySec + " сек (attempt=" + attempt + ").");

        // Планируем ленивую попытку
        Bukkit.getScheduler().runTaskLater(plugin, () -> connectPlayer(k, false), Math.max(1L, delayTicks));
    }

    private static String key(String playerName) {
        return playerName.toLowerCase(Locale.ROOT);
    }
}
