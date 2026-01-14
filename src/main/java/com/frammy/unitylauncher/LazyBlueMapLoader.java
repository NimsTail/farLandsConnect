package com.frammy.unitylauncher;

import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.zones.ZoneInfo;
import de.bluecolored.bluemap.api.BlueMapAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Плавная подгрузка BlueMap-маркеров без блокировки старта.
 * ВАЖНО: все вызовы Bukkit/BlueMap выполняются ТОЛЬКО на главном потоке.
 * Тяжёлая часть — применение маркеров — делается порциями по N задач за тик.
 */
public record LazyBlueMapLoader(UnityLauncher plugin) {

    // Параметры (вынесите в config.yml при желании)
    private static final long STARTUP_DELAY_TICKS = 40L;     // ~2 секунды после старта
    private static final int MARKERS_PER_TICK = 250;     // сколько задач выполнять за тик
    private static final long SHOP_LIST_DELAY = 20L * 5; // задержка перед массовым обновлением SHOP_LIST

    /**
     * Планирует мягкую загрузку: ждём BlueMap и чуть откладываем, чтобы сервер «встал».
     */
    public void scheduleLazyLoad() {
        if (!Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            plugin.getLogger().warning("[LazyLoad] BlueMap не включён. Пропускаем загрузку маркеров.");
            return;
        }

        BlueMapAPI.onEnable(api ->
                Bukkit.getScheduler().runTaskLater(plugin, this::loadAndApplyOnMainThread, STARTUP_DELAY_TICKS)
        );
    }

    /**
     * Вся логика на главном потоке: безопасно для Bukkit/BlueMap, но порционно.
     */
    private void loadAndApplyOnMainThread() {
        long t0 = System.currentTimeMillis();

        // 1) Грузим/обновляем данные (Bukkit API внутри этих методов, поэтому — MAIN thread)
        try {
            plugin.getSignManager().loadSignData();
            plugin.getZoneManager().loadZonesFromConfig();
            plugin.getLogger().info("[UL] Zones loaded (lazy): " + plugin.getZoneManager().getZones().size());
        } catch (Throwable t) {
            plugin.getLogger().severe("[LazyLoad] Ошибка загрузки данных: " + t.getMessage());
            t.printStackTrace();
            return;
        }

        // 2) Делаем снимки коллекций, чтобы не шарить «живые» мапы в раннер
        Map<Location, SignVariables> signsSnapshot = new HashMap<>();
        var sm = plugin.getSignManager();
        if (sm != null && sm.store() != null && sm.store().signs() != null) {
            signsSnapshot.putAll(sm.store().signs()); // Map<Location, SignVariables>
        }
        List<ZoneInfo> zonesSnapshot = plugin.getZoneManager().getAllZonesSnapshot();

        long prepDt = System.currentTimeMillis() - t0;

        // 3) Инициализируем MarkerSet'ы один раз
        try {
            plugin.getBlueMapIntegration().initializeBlueMapMarkerStorage("zones_shop");
        } catch (Throwable t) {
            t.printStackTrace();
        }

        // 4) Собираем список коротких задач применения (никаких тяжёлых расчётов внутри!)
        final List<Runnable> tasks = getRunnables(zonesSnapshot, signsSnapshot);

        // 5) Бежим порциями по MARKERS_PER_TICK задач за тик — мягко для TPS
        final int total = tasks.size();
        final long tApplyStart = System.currentTimeMillis();
        AtomicInteger idx = new AtomicInteger(0);

        new BukkitRunnable() {
            @Override
            public void run() {
                int start = idx.get();
                int end = Math.min(start + MARKERS_PER_TICK, total);

                for (int i = start; i < end; i++) {
                    tasks.get(i).run();
                }
                idx.set(end);

                if (end >= total) {
                    cancel();
                    long dt = System.currentTimeMillis() - tApplyStart;
                    plugin.getLogger().info("[LazyLoad] Применение BlueMap-маркеров завершено. Всего задач: " + total + ", заняло ~" + dt + " мс.");

                    // 6) Отложенно обновим SHOP_LIST таблички, чтобы не было лавины перерисовок
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            // 1) Плавный пересчёт владельцев табличек
                            plugin.getZoneManager().scheduleSignOwnershipRecalc(plugin.getSignManager(), /*signsPerTick*/ 400);

                            // 2) Обновление SHOP_LIST ещё позже — когда владельцы уже проставятся
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    plugin.getSignManager().rebuildAllShopListsLater();
                                } catch (Throwable t) {
                                    plugin.getLogger().severe("[LazyLoad] Ошибка обновления SHOP_LIST: " + t.getMessage());
                                }
                            }, 20L * 10); // дольше чем раньше (например, 10с), чтобы пересчёт владельцев точно закончился
                        } catch (Throwable t) {
                            plugin.getLogger().severe("[LazyLoad] Ошибка scheduleSignOwnershipRecalc: " + t.getMessage());
                        }
                    });
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private @NotNull List<Runnable> getRunnables(List<ZoneInfo> zonesSnapshot, Map<Location, SignVariables> signsSnapshot) {
        final List<Runnable> tasks = new ArrayList<>(zonesSnapshot.size() + signsSnapshot.size());

        for (ZoneInfo z : zonesSnapshot) {
            tasks.add(() -> {
                try {
                    plugin.getBlueMapIntegration().applyZoneMarker(z);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[LazyLoad] applyZoneMarker fail: " + (z != null ? z.getID() : "null") + " -> " + t.getMessage());
                }
            });
        }

        for (Map.Entry<Location, SignVariables> e : signsSnapshot.entrySet()) {
            final Location loc = e.getKey();
            final SignVariables vars = e.getValue();
            tasks.add(() -> {
                try {
                    plugin.getBlueMapIntegration().applySignMarker(loc, vars);
                } catch (Throwable t) {
                    plugin.getLogger().warning("[LazyLoad] applySignMarker fail @" + loc + " -> " + t.getMessage());
                }
            });
        }
        return tasks;
    }
}
