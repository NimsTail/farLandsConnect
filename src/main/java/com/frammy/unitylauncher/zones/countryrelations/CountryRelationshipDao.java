package com.frammy.unitylauncher.zones.countryrelations;

import com.google.gson.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import java.util.*;
import java.util.Map;

import static com.frammy.unitylauncher.UnityLauncher.DBConnect;

public class CountryRelationshipDao {


    public List<String> listAllCountries() {
        String sql = "SELECT name FROM Countries";
        try (Connection con = DBConnect();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    // Читает JSON отношений конкретной страны -> Map<ДругаяСтрана, Статус>
    public Map<String, String> loadRelationsFor(String countryName) {
        String sql = "SELECT relationship FROM Countries WHERE name = ?";
        try (Connection con = DBConnect();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, countryName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new HashMap<>();
                String json = rs.getString(1);
                return parseJson(json);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    // Сохраняет JSON отношений страны
    public void saveRelationsFor(String countryName, Map<String, String> map) {
        String json = toJson(map);
        String upd = "UPDATE Countries SET relationship = ? WHERE name = ?";
        try (Connection con = DBConnect();
             PreparedStatement ps = con.prepareStatement(upd)) {
            ps.setString(1, json);
            ps.setString(2, countryName);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                String ins = "INSERT INTO Countries(name, relationship) VALUES(?,?)";
                try (PreparedStatement insPs = con.prepareStatement(ins)) {
                    insPs.setString(1, countryName);
                    insPs.setString(2, json);
                    insPs.executeUpdate();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------- JSON helpers ----------
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // Формат: {"version":1,"relations":{"France":"HOSTILE","Spain":"FRIENDLY"}}
    private Map<String, String> parseJson(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("relations")) return out;
            JsonObject rel = root.getAsJsonObject("relations");
            for (Map.Entry<String, JsonElement> e : rel.entrySet()) {
                out.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private String toJson(Map<String, String> map) {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject rel = new JsonObject();
        for (Map.Entry<String, String> e : map.entrySet()) {
            rel.addProperty(e.getKey(), e.getValue());
        }
        root.add("relations", rel);
        return GSON.toJson(root);
    }
}
