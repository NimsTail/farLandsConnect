package com.frammy.unitylauncher.zones.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class ZoneWebRequestService {

    public enum Action { CREATE, UPDATE_CORNERS, UPDATE_NAME, UPDATE_COLOR, DELETE, UPGRADE_TYPE, ADD_SHAPE, ADD_MEMBER, REMOVE_MEMBER, TRANSFER_OWNERSHIP }

    public record ZoneWebRequest(
            long id,
            UUID playerUuid,
            Action action,
            String markerId,
            String zoneType,
            String zoneName,
            String worldName,
            JsonObject payload
    ) {}

    private static final int BATCH_LIMIT = 10;
    private static final Gson GSON = new Gson();

    private final JavaPlugin plugin;
    private final ZoneWebRequestHandler handler; // сюда позже подключим ZoneManager

    public ZoneWebRequestService(JavaPlugin plugin, ZoneWebRequestHandler handler) {
        this.plugin = plugin;
        this.handler = handler;
    }

    /** Запускаем раз в ~2 секунды (period в тиках, 20 тиков = 1 сек). */
    public void start(long periodTicks) {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::pollOnce, periodTicks, periodTicks);
    }

    private void pollOnce() {
        List<ZoneWebRequest> batch = fetchPending();
        if (batch.isEmpty()) return;

        for (ZoneWebRequest req : batch) {
            // переключаемся на главный поток для каждой заявки отдельно -
            // так не блокируем игровой тик обработкой всей пачки разом
            Bukkit.getScheduler().runTask(plugin, () -> processOnMainThread(req));
        }
    }

    private List<ZoneWebRequest> fetchPending() {
        List<ZoneWebRequest> out = new ArrayList<>();
        final String sql = """
            SELECT id, player_uuid, action, marker_id, zone_type, zone_name, world_name, payload_json
            FROM zone_requests
            WHERE status = 'PENDING'
            ORDER BY created_at ASC
            LIMIT ?
            """;
        try (Connection con = DBConnect()) {
            if (con == null) {
                Bukkit.getLogger().severe("[ZoneWebRequestService] DBConnect()==null");
                return out;
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setInt(1, BATCH_LIMIT);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new ZoneWebRequest(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("player_uuid")),
                                Action.valueOf(rs.getString("action")),
                                rs.getString("marker_id"),
                                rs.getString("zone_type"),
                                rs.getString("zone_name"),
                                rs.getString("world_name"),
                                GSON.fromJson(rs.getString("payload_json"), JsonObject.class)
                        ));
                    }
                }
            }
            // сразу помечаем как "в обработке", чтобы следующий опрос их не подхватил повторно
            markInProgress(con, out);
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[ZoneWebRequestService] fetchPending error: " + t);
        }
        return out;
    }

    private void markInProgress(Connection con, List<ZoneWebRequest> batch) throws SQLException {
        if (batch.isEmpty()) return;
        final String sql = "UPDATE zone_requests SET status = 'PROCESSING' WHERE id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            for (ZoneWebRequest r : batch) {
                ps.setLong(1, r.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void processOnMainThread(ZoneWebRequest req) {
        ZoneWebRequestHandler.Result result;
        try {
            result = handler.handle(req);
        } catch (Throwable t) {
            result = ZoneWebRequestHandler.Result.error("Внутренняя ошибка: " + t.getMessage());
        }

        final ZoneWebRequestHandler.Result finalResult = result;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeResult(req.id(), finalResult));
    }

    private void writeResult(long requestId, ZoneWebRequestHandler.Result result) {
        final String sql = """
            UPDATE zone_requests
            SET status = ?, result_message = ?, result_marker_id = ?, processed_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """;
        try (Connection con = DBConnect()) {
            if (con == null) return;
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, result.success() ? "DONE" : "ERROR");
                ps.setString(2, result.message());
                ps.setString(3, result.markerId());
                ps.setLong(4, requestId);
                ps.executeUpdate();
            }
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[ZoneWebRequestService] writeResult error: " + t);
        }
    }
}