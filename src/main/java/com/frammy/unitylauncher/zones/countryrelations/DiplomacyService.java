package com.frammy.unitylauncher.zones.countryrelations;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.Bukkit;

import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DiplomacyService {
    private final CountryRelationshipDao dao;
    private final Map<String, Map<String, RelationStatus>> cache = new ConcurrentHashMap<>();

    public DiplomacyService(CountryRelationshipDao dao) { this.dao = dao; }

    public void loadAll() {
        for (String name : dao.listAllCountries()) {
            Map<String, String> raw = dao.loadRelationsFor(name);
            Map<String, RelationStatus> typed = new ConcurrentHashMap<>();
            raw.forEach((k, v) -> typed.put(k, RelationStatus.from(v)));
            cache.put(name, typed);
        }
        enforceSymmetry();
    }

    public RelationStatus getRelation(String a, String b) {
        if (a.equalsIgnoreCase(b)) return RelationStatus.NEUTRAL;
        Map<String, RelationStatus> m = cache.get(a);
        return (m == null) ? RelationStatus.NEUTRAL : m.getOrDefault(b, RelationStatus.NEUTRAL);
    }

    public void setRelation(String a, String b, RelationStatus status) {
        if (a.equalsIgnoreCase(b)) return;
        setOneWay(a, b, status);
        setOneWay(b, a, status);
        // сохраняем обе стороны (можешь обернуть в async, если хочешь)
        save(a);
        save(b);
    }

    public void save(String country) {
        Map<String, RelationStatus> m = cache.getOrDefault(country, Collections.emptyMap());
        Map<String, String> raw = new HashMap<>();
        m.forEach((k, v) -> { if (v != RelationStatus.NEUTRAL) raw.put(k, v.name()); });
        dao.saveRelationsFor(country, raw);
    }

    public Map<String, Map<String, RelationStatus>> snapshot() {
        Map<String, Map<String, RelationStatus>> out = new HashMap<>();
        cache.forEach((k, v) -> out.put(k, new HashMap<>(v)));
        return out;
    }

    private void setOneWay(String from, String to, RelationStatus status) {
        cache.computeIfAbsent(from, k -> new ConcurrentHashMap<>());
        if (status == RelationStatus.NEUTRAL) cache.get(from).remove(to);
        else cache.get(from).put(to, status);
    }

    private void enforceSymmetry() {
        for (String a : cache.keySet()) {
            Map<String, RelationStatus> ma = cache.get(a);
            for (Map.Entry<String, RelationStatus> e : ma.entrySet()) {
                setOneWay(e.getKey(), a, e.getValue());
            }
        }
    }
}
