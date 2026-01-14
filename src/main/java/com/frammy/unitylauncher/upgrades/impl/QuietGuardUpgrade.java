package com.frammy.unitylauncher.upgrades.impl;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.ParkCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class QuietGuardUpgrade extends BaseUpgrade {

    private static final UpgradeKey KEY = UpgradeKey.of("park.quiet_guard");

    @Override public UpgradeKey key() { return KEY; }

    // Это парковый апгрейд, но право/уровень у тебя считается по стране → остаёмся на COUNTRY
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }

    // Bukkit Listener не нужен — работаем через ProtocolLib
    @Override public Listener listener() { return null; }

    private ProtocolManager protocol;
    private PacketAdapter adapter;

    // анти-спам логов
    private final ConcurrentHashMap<UUID, Long> debugCd = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        ParkCfg.QuietGuardCfg cfg = ctx.config().park().quietGuard();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().park().quietGuard();

        if (!isProtocolLibPresent()) {
            plugin().getLogger().warning("[Park/QuietGuard] ProtocolLib not found. Upgrade disabled (needs ProtocolLib).");
            return;
        }

        protocol = ProtocolLibrary.getProtocolManager();

        adapter = new PacketAdapter(
                plugin(),
                ListenerPriority.NORMAL,
                PacketType.Play.Server.NAMED_SOUND_EFFECT,
                PacketType.Play.Server.ENTITY_SOUND
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                tryHandle(event, cfg);
            }
        };

        protocol.addPacketListener(adapter);

        if (C().core().debug()) {
            plugin().getLogger().info("[Park/QuietGuard] Protocol listener enabled. multiplier=" + cfg.volumeMultiplier());
        }
    }

    @Override
    protected void onDisable() {
        debugCd.clear();

        if (protocol != null && adapter != null) {
            protocol.removePacketListener(adapter);
        }
        protocol = null;
        adapter = null;
    }

    private boolean isProtocolLibPresent() {
        return Bukkit.getPluginManager().getPlugin("ProtocolLib") != null;
    }

    private void tryHandle(PacketEvent event, ParkCfg.QuietGuardCfg cfg) {
        Player p = event.getPlayer();
        if (p == null || !p.isOnline()) return;

        Location ploc = p.getLocation();
        if (ploc.getWorld() == null) return;

        // Игрок должен быть в парке
        ZoneInfo pz = UpgradeCondition.zoneAt(ploc);
        if (pz == null || pz.getType() != ZoneType.PARK) return;

        // Пермишен проверяем по стране парка (как у тебя было)
        String country = UpgradeCondition.zoneCountryCanonical(pz);
        if (country == null || country.isBlank()) return;

        if (countryMaxLevel(country, cfg.permBase(), 1) < 1) return;

        // Получаем имя звука + позицию звука из пакета
        PacketContainer packet = event.getPacket();

        String soundKey = readSoundKey(packet, event.getPacketType());
        if (soundKey == null) return;

        if (!matches(soundKey, cfg.soundPrefixes(), cfg.soundKeys())) return;

        Location soundLoc = readSoundLocation(packet, event.getPacketType(), ploc);
        if (soundLoc != null) {
            // Доп. фильтр: звук тоже должен быть внутри парка (по смыслу “внутри парка”)
            ZoneInfo sz = UpgradeCondition.zoneAt(soundLoc);
            if (sz == null || sz.getType() != ZoneType.PARK) return;

            // Доп. фильтр по радиусу “влияния” (если хочешь)
            int r = Math.max(0, cfg.radiusBlocks());
            if (r > 0 && soundLoc.getWorld() == ploc.getWorld()) {
                if (soundLoc.distanceSquared(ploc) > (double) r * r) return;
            }
        }

        // Меняем громкость
        float oldVol = readVolume(packet, event.getPacketType());
        float mult = (float) clamp(cfg.volumeMultiplier(), 0.0, 1.0);
        float newVol = oldVol * mult;

        float minVol = (float) clamp(cfg.minVolume(), 0.0, 1.0);
        if (newVol < minVol) newVol = minVol;

        writeVolume(packet, event.getPacketType(), newVol);

        if (C().core().debug()) {
            debugLog(p, soundKey, oldVol, newVol);
        }
    }

    private void debugLog(Player p, String soundKey, float oldVol, float newVol) {
        long now = System.currentTimeMillis();
        long cdMs = 1000L;
        Long last = debugCd.get(p.getUniqueId());
        if (last != null && (now - last) < cdMs) return;
        debugCd.put(p.getUniqueId(), now);

        plugin().getLogger().info("[Park/QuietGuard] " + p.getName() +
                " sound=" + soundKey + " vol " + oldVol + " -> " + newVol);
    }

    private boolean matches(String soundKey, List<String> prefixes, List<String> keys) {
        String s = soundKey.toLowerCase(Locale.ROOT);

        if (keys != null) {
            for (String k : keys) {
                if (k == null) continue;
                if (s.equals(k.toLowerCase(Locale.ROOT))) return true;
            }
        }

        if (prefixes != null) {
            for (String pr : prefixes) {
                if (pr == null) continue;
                String p = pr.toLowerCase(Locale.ROOT);
                if (s.startsWith(p)) return true;
            }
        }

        return false;
    }

    // --- чтение soundKey/volume/location из пакетов (ProtocolLib менялся между версиями → делаем максимально живуче) ---

    private String readSoundKey(PacketContainer packet, PacketType type) {
        // ENTITY_SOUND_EFFECT и NAMED_SOUND_EFFECT в новых версиях обычно имеют SoundEffect/Sound в soundEffects()
        try {
            var seList = packet.getSoundEffects();
            if (seList != null && seList.size() > 0) {
                Object se = seList.read(0);
                if (se != null) {
                    // у ProtocolLib SoundEffect обычно имеет getName() или toString()
                    try {
                        var m = se.getClass().getMethod("getName");
                        Object name = m.invoke(se);
                        if (name != null) {
                            // MinecraftKey: getKey()
                            try {
                                var mk = name.getClass().getMethod("getKey");
                                Object key = mk.invoke(name);
                                if (key != null) return key.toString();
                            } catch (NoSuchMethodException ignored) {
                                return name.toString();
                            }
                        }
                    } catch (NoSuchMethodException ignored) {
                        return se.toString();
                    }
                }
            }
        } catch (Throwable ignored) {}

        // запасной путь: у некоторых сборок NAMED_SOUND_EFFECT может иметь строку напрямую
        try {
            var str = packet.getStrings();
            if (str != null && str.size() > 0) {
                String s = str.read(0);
                if (s != null && !s.isBlank()) return s;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private float readVolume(PacketContainer packet, PacketType type) {
        try {
            // Обычно volume лежит как float
            return packet.getFloat().read(0);
        } catch (Throwable ignored) {}

        // На всякий: иногда бывают разные индексы
        try {
            return packet.getFloat().read(1);
        } catch (Throwable ignored) {}

        return 1.0f;
    }

    private void writeVolume(PacketContainer packet, PacketType type, float vol) {
        try {
            packet.getFloat().write(0, vol);
            return;
        } catch (Throwable ignored) {}

        try {
            packet.getFloat().write(1, vol);
        } catch (Throwable ignored) {}
    }

    private Location readSoundLocation(PacketContainer packet, PacketType type, Location fallbackWorld) {
        // У обоих пакетов координаты обычно int (x,y,z) * 8 (фикс-точка)
        try {
            var ints = packet.getIntegers();
            if (ints != null && ints.size() >= 3) {
                int x = ints.read(0);
                int y = ints.read(1);
                int z = ints.read(2);

                double dx = x / 8.0;
                double dy = y / 8.0;
                double dz = z / 8.0;

                return new Location(fallbackWorld.getWorld(), dx, dy, dz);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private double clamp(double v, double a, double b) {
        if (v < a) return a;
        return Math.min(v, b);
    }
}
