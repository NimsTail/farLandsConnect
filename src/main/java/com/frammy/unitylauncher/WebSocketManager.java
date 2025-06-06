package com.frammy.unitylauncher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.bukkit.entity.PolarBear;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class WebSocketManager {
    private final Logger logger;
    private final Map<String, WebSocketClient> clients = new ConcurrentHashMap<>();

    public WebSocketManager(Logger logger) {
        this.logger = logger;
    }
    public Set<String> getConnectedPlayers() {
        return clients.keySet();
    }
    public void tryForceConnect(Player player) {
        connectPlayer(player.getName());
        player.sendMessage("§7Попытка повторного подключения..");
    }
    // 🔹 Подключаем игрока по его никнейму
    public void connectPlayer(String playerName) {
        if (clients.containsKey(playerName)) {
            logger.warning("⚠️ Игрок " + playerName + " уже подключен.");
            return;
        }

        try {
            WebSocketClient client = new WebSocketClient(new URI("ws://localhost:1337/link")) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    logger.info("✅ " + playerName + " подключился к WebSocket.");
                    clients.put(playerName, this); // Добавляем в список ТОЛЬКО после успешного соединения
                }

                @Override
                public void onMessage(String message) {
                    logger.info("📩 [" + playerName + "] Сообщение от лаунчера: " + message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    logger.warning("⚠️ [" + playerName + "] Отключен от WebSocket: " + reason);
                    clients.remove(playerName);
                }

                @Override
                public void onError(Exception ex) {
                    logger.severe("❌ [" + playerName + "] Ошибка WebSocket: " + ex.getMessage());
                }
            };

            client.connect();
        } catch (URISyntaxException e) {
            logger.severe("❌ Ошибка URL WebSocket для " + playerName + ": " + e.getMessage());
        }
    }

    // 🔹 Отключаем игрока
    public void disconnectPlayer(String playerName) {
        WebSocketClient client = clients.remove(playerName);
        if (client != null) {
            client.close();
            logger.info("❌ " + playerName + " отключился от WebSocket.");
        } else {
            logger.warning("⚠️ Игрок " + playerName + " не найден среди подключенных.");
        }
    }

    // 🔹 Отправка сообщения определенному игроку
    public void sendMessageToPlayer(String playerName, String message) {
        WebSocketClient client = clients.get(playerName);
        if (client != null && client.isOpen()) {
            client.send(message);
            logger.info("📤 [" + playerName + "] Отправлено сообщение: " + message);
        } else {
            logger.warning("⚠️ Игрок " + playerName + " не подключен.");
        }
    }

    // 🔹 Отправка сообщения всем игрокам
    public void broadcastMessage(String message) {
        for (Map.Entry<String, WebSocketClient> entry : clients.entrySet()) {
            WebSocketClient client = entry.getValue();
            if (client.isOpen()) {
                client.send(message);
                logger.info("📤 [" + entry.getKey() + "] Получил сообщение: " + message);
            }
        }
    }

    // 🔹 Проверяем подключен ли игрок
    public boolean isPlayerConnected(String playerName) {
        WebSocketClient client = clients.get(playerName);
        boolean isConnected = client != null && client.isOpen();
        logger.info("🔍 Проверка подключения: " + playerName + " -> " + isConnected);
        return isConnected;
    }

    // 🔹 Отключаем всех игроков (например, при остановке сервера)
    public void disconnectAll() {
        for (String player : clients.keySet()) {
            disconnectPlayer(player);
        }
        clients.clear();
    }
}