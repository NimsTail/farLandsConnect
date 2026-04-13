package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.IndustrialCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class RecyclerUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("industrial.recycler");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private volatile Set<Material> inputs = Set.of();
    private volatile Map<Material, Double> extraDrops = Map.of();

    // PDC key для чанка: "в этом чанке есть позиции блоков, которые игрок поставил"
    private NamespacedKey placedKey;

    // RAM cache: один set на чанк, dirty-флаг чтобы не писать лишний раз
    // Ключ: (world UUID, chunkX, chunkZ)
    private final Map<ChunkId, ChunkCache> cache = new HashMap<>(256);

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        IndustrialCfg.RecyclerCfg cfg = ctx.config().industrial().recycler();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().industrial().recycler();
        inputs = UpgradeCondition.parseMaterialSet(cfg.inputs());
        extraDrops = parseChanceMap(cfg.extraDrops());

        placedKey = new NamespacedKey(plugin(), "recycler_player_placed");
        cache.clear();
    }

    @Override
    protected void onDisable() {
        // На всякий случай сбросим всё грязное в PDC
        for (var en : cache.entrySet()) {
            ChunkId id = en.getKey();
            ChunkCache cc = en.getValue();
            if (cc == null || !cc.dirty) continue;

            World w = plugin().getServer().getWorld(id.world);
            if (w == null) continue;

            if (!w.isChunkLoaded(id.x, id.z)) continue;
            Chunk ch = w.getChunkAt(id.x, id.z);
            savePlacedSet(ch, cc.placed);
            cc.dirty = false;
        }
        cache.clear();
    }

    // ====== PLACE: помечаем как "поставлено игроком" ======

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        markPlaced(e.getBlockPlaced());
    }

    private void markPlaced(Block b) {
        if (b == null) return;

        Material type = b.getType();
        if (!inputs.contains(type)) return;

        Location loc = b.getLocation();

        // Помечаем только в INDUSTRIAL (иначе копим мусор в PDC)
        if (!UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL)) return;

        Chunk ch = loc.getChunk();
        ChunkCache cc = getOrLoadCache(ch);

        int code = encodeBlockPos(loc);
        if (cc.placed.add(code)) {
            cc.dirty = true;
            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/Recycler] mark placed " + type + " at " + loc);
            }
        }
    }

    // ====== BREAK: если блок поставленный — бонус не даём ======

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreakRecycler(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material type = b.getType();
        if (!inputs.contains(type)) return;

        Location loc = b.getLocation();

        // Только внутри INDUSTRIAL, даже если поверх/внутри есть другие зоны
        if (!UpgradeCondition.isInsideZoneTypeRaw(loc, ZoneType.INDUSTRIAL)) return;

        // Проверяем "поставлен игроком" (чтоб не фармили бесконечно)
        ChunkCache cc = getOrLoadCache(loc.getChunk());
        int code = encodeBlockPos(loc);

        if (cc.placed.remove(code)) {
            // Это был поставленный блок — чистим метку и НИЧЕГО не выдаём
            cc.dirty = true;
            if (C().core().debug()) {
                plugin().getLogger().info("[Upgrades/Recycler] blocked bonus (player-placed) at " + loc + " type=" + type);
            }
            return;
        }

        // Владелец территории (сквозь дочерние зоны)
        String country = UpgradeCondition.locationCountryOwner(loc);
        if (country == null || country.isBlank()) return;

        var cfg = C().industrial().recycler();
        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        var rnd = ThreadLocalRandom.current();

        for (var en : extraDrops.entrySet()) {
            Material dropMat = en.getKey();
            double prob = en.getValue();
            if (prob <= 0.0) continue;

            if (rnd.nextDouble() < prob) {
                b.getWorld().dropItemNaturally(loc, new ItemStack(dropMat, 1));
                if (C().core().debug()) {
                    plugin().getLogger().info("[Upgrades/Recycler] extra " + dropMat + " at " + loc + " from " + type + " country=" + country);
                }
            }
        }
    }

    // ====== CHUNK UNLOAD: сохраняем только если dirty ======

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk ch = e.getChunk();
        if (ch == null) return;

        ChunkId id = ChunkId.of(ch);
        ChunkCache cc = cache.get(id);
        if (cc == null) return;

        if (cc.dirty) {
            savePlacedSet(ch, cc.placed);
            cc.dirty = false;
        }

        // убираем из RAM, чтобы не раздувать память
        cache.remove(id);
    }

    // ====== Cache + PDC storage ======

    private ChunkCache getOrLoadCache(Chunk ch) {
        ChunkId id = ChunkId.of(ch);
        ChunkCache cached = cache.get(id);
        if (cached != null) return cached;

        Set<Integer> placed = loadPlacedSet(ch);
        ChunkCache cc = new ChunkCache(placed, false);
        cache.put(id, cc);
        return cc;
    }

    private Set<Integer> loadPlacedSet(Chunk ch) {
        try {
            PersistentDataContainer pdc = ch.getPersistentDataContainer();
            byte[] data = pdc.get(placedKey, PersistentDataType.BYTE_ARRAY);
            if (data == null || data.length == 0) return new HashSet<>(32);

            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
                int n = in.readInt();
                if (n <= 0 || n > 200_000) { // защита от мусора/коррупции
                    return new HashSet<>(32);
                }
                HashSet<Integer> set = new HashSet<>(Math.min(n * 2, 16384));
                for (int i = 0; i < n; i++) set.add(in.readInt());
                return set;
            }
        } catch (Throwable t) {
            // если что-то пошло не так — не ломаем игру
            return new HashSet<>(32);
        }
    }

    private void savePlacedSet(Chunk ch, Set<Integer> set) {
        try {
            PersistentDataContainer pdc = ch.getPersistentDataContainer();

            if (set == null || set.isEmpty()) {
                pdc.remove(placedKey);
                return;
            }

            // защита от раздувания PDC (игроки могут заспамить placement)
            // можешь поднять/убрать лимит, но я бы держал разумный потолок
            int n = Math.min(set.size(), 50_000);

            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(4 + n * 4, 256_000));
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeInt(n);
                int i = 0;
                for (int v : set) {
                    out.writeInt(v);
                    if (++i >= n) break;
                }
            }

            pdc.set(placedKey, PersistentDataType.BYTE_ARRAY, baos.toByteArray());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Кодируем позицию блока внутри чанка.
     * Храним (localX 4 bits, localZ 4 bits) + (yOffset до 16 bits).
     * yOffset = y - worldMinHeight, чтобы не было отрицательных Y.
     */
    private static int encodeBlockPos(Location loc) {
        int lx = loc.getBlockX() & 15;
        int lz = loc.getBlockZ() & 15;

        World w = loc.getWorld();
        int minY = (w != null ? w.getMinHeight() : -64);
        int yOff = loc.getBlockY() - minY;

        // yOff в верхние 16 бит, xz в нижние 8
        return ((yOff & 0xFFFF) << 8) | ((lx & 0xF) << 4) | (lz & 0xF);
    }

    private record ChunkId(UUID world, int x, int z) {
        static ChunkId of(Chunk ch) {
            return new ChunkId(ch.getWorld().getUID(), ch.getX(), ch.getZ());
        }
    }

    private static final class ChunkCache {
        final Set<Integer> placed;
        boolean dirty;

        ChunkCache(Set<Integer> placed, boolean dirty) {
            this.placed = placed;
            this.dirty = dirty;
        }
    }

    // ====== parse config ======

    private static Map<Material, Double> parseChanceMap(List<String> raw) {
        if (raw == null || raw.isEmpty()) return Map.of();

        Map<Material, Double> out = new EnumMap<>(Material.class);

        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;

            // формат: MATERIAL=0.05
            int eq = t.indexOf('=');
            if (eq <= 0 || eq >= t.length() - 1) continue;

            String ms = t.substring(0, eq).trim();
            String ps = t.substring(eq + 1).trim();

            Material m;
            try { m = Material.valueOf(ms); }
            catch (Throwable ignored) { continue; }

            double p;
            try { p = Double.parseDouble(ps); }
            catch (Throwable ignored) { continue; }

            if (Double.isNaN(p) || Double.isInfinite(p)) continue;
            if (p <= 0.0) continue;

            // ограничим 0..1 (как вероятность)
            p = Math.max(0.0, Math.min(1.0, p));
            out.put(m, p);
        }

        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }
}