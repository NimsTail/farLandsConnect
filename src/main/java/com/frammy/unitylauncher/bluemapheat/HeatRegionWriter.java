package com.frammy.unitylauncher.bluemapheat;

import com.frammy.unitylauncher.UnityLauncher;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class HeatRegionWriter {

    public static final int REGION_SIZE_CHUNKS = 32;
    private static final int CHUNK = 16;

    private final UnityLauncher plugin;
    private final Path bluemapWebRoot;
    private final BlueMapHeatService service;

    private final Set<String> dirty = ConcurrentHashMap.newKeySet();

    // простой “визитный” счетчик, чтобы на раннем этапе уже было что показать.
    // ключ: world:cx,cz -> visits
    private final ConcurrentHashMap<String, Long> visits = new ConcurrentHashMap<>();

    public HeatRegionWriter(UnityLauncher plugin, Path bluemapWebRoot, BlueMapHeatService service) {
        this.plugin = Objects.requireNonNull(plugin);
        this.bluemapWebRoot = Objects.requireNonNull(bluemapWebRoot);
        this.service = Objects.requireNonNull(service);
    }

    public void markDirty(String world, int chunkX, int chunkZ) {
        int rx = floorDiv(chunkX, REGION_SIZE_CHUNKS);
        int rz = floorDiv(chunkZ, REGION_SIZE_CHUNKS);
        dirty.add(world + ":" + rx + ":" + rz);
    }

    public void bumpVisits(String world, int cx, int cz, long delta) {
        String k = world + ":" + cx + "," + cz;
        visits.merge(k, delta, Long::sum);
    }

    public int flushDirty(int maxRegionsPerFlush) {
        if (dirty.isEmpty()) return 0;

        List<String> batch = new ArrayList<>(Math.min(maxRegionsPerFlush, dirty.size()));
        Iterator<String> it = dirty.iterator();
        while (it.hasNext() && batch.size() < maxRegionsPerFlush) {
            String k = it.next();
            it.remove();
            batch.add(k);
        }

        int n = 0;
        for (String key : batch) {
            String[] p = key.split(":");
            if (p.length != 3) continue;
            String world = p[0];
            int rx = parseIntSafe(p[1]);
            int rz = parseIntSafe(p[2]);

            try {
                writeRegion(world, rx, rz);
                n++;
            } catch (Throwable t) {
                plugin.getLogger().warning("[BlueMapHeat] Failed write region " + key + ": " + t.getMessage());
            }
        }
        return n;
    }

    private void writeRegion(String world, int rx, int rz) throws IOException {
        int startCx = rx * REGION_SIZE_CHUNKS;
        int startCz = rz * REGION_SIZE_CHUNKS;

        long updatedAt = Instant.now().getEpochSecond();

        StringBuilder cells = new StringBuilder(8192);
        cells.append('[');

        boolean first = true;

        BlueMapHeatService svc = this.service;

        for (int dx = 0; dx < REGION_SIZE_CHUNKS; dx++) {
            for (int dz = 0; dz < REGION_SIZE_CHUNKS; dz++) {
                int cx = startCx + dx;
                int cz = startCz + dz;

                long a = svc.getActivityValue(world, cx, cz);

                // добавим “визиты” как небольшой буст, чтобы карта реагировала на беготню сразу
                Long v = visits.get(world + ":" + cx + "," + cz);
                if (v != null && v > 0) a += Math.min(10_000L, v * 25L);

                long t = svc.getTaxValue(world, cx, cz);

                if (a == 0 && t == 0) continue;

                if (!first) cells.append(',');
                first = false;

                cells.append("{\"cx\":").append(cx)
                        .append(",\"cz\":").append(cz)
                        .append(",\"a\":").append(a)
                        .append(",\"t\":").append(t)
                        .append('}');
            }
        }

        cells.append(']');

        String json = "{"
                + "\"ok\":true,"
                + "\"world\":\"" + escapeJson(world) + "\","
                + "\"rx\":" + rx + ","
                + "\"rz\":" + rz + ","
                + "\"regionSizeChunks\":" + REGION_SIZE_CHUNKS + ","
                + "\"updatedAt\":" + updatedAt + ","
                + "\"cells\":" + cells
                + "}";

        Path outDir = bluemapWebRoot.resolve("farlands-heat").resolve(world);
        Files.createDirectories(outDir);

        Path tmp = outDir.resolve("r." + rx + "." + rz + ".json.tmp");
        Path dst = outDir.resolve("r." + rx + "." + rz + ".json");

        Files.writeString(tmp, json,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static int floorDiv(int a, int b) {
        int r = a / b;
        if ((a ^ b) < 0 && (r * b != a)) r--;
        return r;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
