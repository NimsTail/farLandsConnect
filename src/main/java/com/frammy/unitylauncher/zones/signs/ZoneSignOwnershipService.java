package com.frammy.unitylauncher.zones.signs;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import com.frammy.unitylauncher.zones.geom.ZoneGeometry;
import org.bukkit.Bukkit;
import org.bukkit.Location;

import java.util.*;

public final class ZoneSignOwnershipService {

    public interface ZonesView {
        List<ZoneInfo> getAllZonesSnapshot();
    }

    private final UnityLauncher plugin;
    private final ZonesView zonesView;

    public ZoneSignOwnershipService(UnityLauncher plugin, ZonesView zonesView) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.zonesView = Objects.requireNonNull(zonesView, "zonesView");
    }

    /** Плавно пересчитать ownerName у всех табличек по полигонам зон, не душа главный поток. */
    public void scheduleSignOwnershipRecalc(SignManager signManager, int signsPerTick) {
        if (signManager == null || signManager.store().signs().isEmpty()) return;

        var signsMap = signManager.store().signs(); // Map<Location, SignVariables>

        // плоский список табличек (block location)
        List<Location> allSigns = new ArrayList<>();
        for (Location loc : signsMap.keySet()) {
            if (loc != null && loc.getWorld() != null) {
                allSigns.add(loc.getBlock().getLocation());
            }
        }

        // Ключ: owner + '|' + type → display
        Map<String, String> ownerTypeDisplay = new HashMap<>();
        for (ZoneInfo z : zonesView.getAllZonesSnapshot()) {
            String owner = z.getOwner();
            if (owner == null) continue;
            String display = (z.getCountryName() != null && !z.getCountryName().isBlank())
                    ? z.getCountryName()
                    : owner;
            ownerTypeDisplay.put(owner + "|" + z.getType().name(), display);
        }

        record ZonePoly(String owner, ZoneType type, List<Vector2d> poly) {}

        // Кэш полигонов по миру
        Map<String, List<ZonePoly>> worldZones = new HashMap<>();
        for (ZoneInfo z : zonesView.getAllZonesSnapshot()) {
            var cs = z.getCorners();
            if (cs == null || cs.isEmpty() || cs.getFirst().getWorld() == null) continue;
            String w = cs.getFirst().getWorld().getName();
            worldZones.computeIfAbsent(w, k -> new ArrayList<>())
                    .add(new ZonePoly(z.getOwner(), z.getType(), ZoneGeometry.poly2D(cs)));
        }

        final int total = allSigns.size();
        final int batch = Math.max(10, signsPerTick);

        Bukkit.getLogger().info("[Zones] Пересчёт владельцев табличек: миров " + worldZones.size()
                + ", табличек " + total + ", батч " + batch + "/тик");

        new org.bukkit.scheduler.BukkitRunnable() {
            int idx = 0;

            @Override public void run() {
                int end = Math.min(idx + batch, total);

                for (int i = idx; i < end; i++) {
                    Location signLoc = allSigns.get(i);
                    String world = (signLoc.getWorld() != null) ? signLoc.getWorld().getName() : null;
                    if (world == null) continue;

                    List<ZonePoly> zp = worldZones.get(world);
                    if (zp == null || zp.isEmpty()) continue;

                    Vector2d p = new Vector2d(signLoc.getX(), signLoc.getZ());

                    for (ZonePoly zpp : zp) {
                        if (ZoneGeometry.pointInPolygon(p, zpp.poly())) {
                            Location key = signLoc.getBlock().getLocation();
                            SignVariables sv = signsMap.get(key);
                            if (sv != null) {
                                String k = zpp.owner() + "|" + zpp.type().name();
                                String ownerDisplay = ownerTypeDisplay.getOrDefault(k, zpp.owner() != null ? zpp.owner() : "—");
                                sv.setOwnerName(ownerDisplay);
                            }
                            break;
                        }
                    }
                }

                idx = end;

                if (idx >= total) {
                    cancel();
                    Bukkit.getLogger().info("[Zones] Пересчёт владельцев табличек завершён. Всего: " + total);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}
