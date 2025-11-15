package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Ежедневная задача:
 *  - бонус за активность стран (economy.activityBonus)
 *  - (позже можно сюда же добавить расчёт налогов по зонам)
 */
public class DailyEconomyTask {

    private final UnityLauncher plugin;
    private final CountryRegistryJdbc countryRegistry;
    private final UserActivityJdbc userActivityJdbc;
    private ZonesEconomyConfig econCfg;

    private BukkitTask task;

    public DailyEconomyTask(UnityLauncher plugin,
                            CountryRegistryJdbc countryRegistry,
                            UserActivityJdbc userActivityJdbc,
                            ZonesEconomyConfig econCfg) {
        this.plugin = plugin;
        this.countryRegistry = countryRegistry;
        this.userActivityJdbc = userActivityJdbc;
        this.econCfg = econCfg;
    }

    public void start() {
        if (task != null) task.cancel();

        // ВРЕМЯ ВЫПЛАТЫ БЕРЁМ ИЗ activityBonus.payoutHour
        long delayTicks = computeInitialDelayTicks(econCfg.activityBonus.payoutHour);
        long periodTicks = 20L * 60L * 60L * 24L; // сутки

        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runOnce, delayTicks, periodTicks);
        plugin.getLogger().info("[DailyEconomyTask] Запущен ежедневный экономический тик, первый запуск через " + delayTicks + " тиков.");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Вызывай при /ul reload, когда zones-economy.yml перечитан. */
    public void updateConfig(ZonesEconomyConfig newCfg) {
        this.econCfg = newCfg;
        // Можно перезапланировать на новый payoutHour, если хочешь.
        start();
    }

    private void runOnce() {
        try {
            long now = System.currentTimeMillis();
            long dayAgo = now - 24L * 60L * 60L * 1000L;

            // один раз считаем активность игроков по странам
            Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry =
                    userActivityJdbc.loadCountryActivityStats(dayAgo);

            if (econCfg.activityBonus.enabled) {
                runActivityBonus(statsByCountry);
            }

            // сюда цепляем налоги по зонам
            runZoneTaxes(statsByCountry);

        } catch (Throwable t) {
            plugin.getLogger().severe("[DailyEconomyTask] Ошибка в ежедневной задаче: " + t);
        }
    }

    private void runActivityBonus(Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry) {
        if (statsByCountry.isEmpty()) {
            if (econCfg.activityBonus.debugLog) {
                plugin.getLogger().info("[DailyEconomyTask] activityBonus: активных игроков по странам не найдено.");
            }
            return;
        }

        double perActive    = econCfg.activityBonus.rewardPerActivePlayer;
        double maxPerCountry = econCfg.activityBonus.maxRewardPerCountryPerDay;
        double threshold     = econCfg.billing.minOnlineFractionForFullBilling;

        for (Map.Entry<String, UserActivityJdbc.CountryActivityStats> e : statsByCountry.entrySet()) {
            String countryLower = e.getKey();
            UserActivityJdbc.CountryActivityStats st = e.getValue();

            int totalPlayers = st.totalPlayers();
            int activeCount  = st.activePlayers();
            if (totalPlayers <= 0) continue;

            double frac = (double) activeCount / (double) totalPlayers;

            if (frac < threshold) {
                if (econCfg.activityBonus.debugLog) {
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "[DailyEconomyTask] activityBonus: страна %s пропущена: активных %d из %d (%.1f%%, порог %.1f%%)",
                            countryLower, activeCount, totalPlayers,
                            frac * 100.0, threshold * 100.0));
                }
                continue;
            }

            double rawReward = activeCount * perActive;
            double reward    = Math.min(rawReward, maxPerCountry);

            if (reward > 0.0) {
                countryRegistry.addCountryMoney(countryLower, reward);

                if (econCfg.activityBonus.debugLog) {
                    plugin.getLogger().info(String.format(Locale.ROOT,
                            "[DailyEconomyTask] activityBonus: страна %s, активных %d/%d (%.1f%%), бонус %.2f Ⓕ (сырой %.2f)",
                            countryLower, activeCount, totalPlayers,
                            frac * 100.0, reward, rawReward));
                }
            }
        }
    }

    /**
     * Считает дневной налог по странам и:
     *  - добавляет его в WeeklyTaxDue,
     *  - пишет CountryInfo.Taxes = последний dailyTax.
     *
     * СЮДА ты подаёшь уже готовые цифры dailyTaxByCountry,
     * полученные из chunkactivity + зон (COUNTRY / COLONY / INDUSTRIAL / PARK и т.д.).
     */
    private void runZoneTaxes(Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry) {
        Map<String, Double> dailyTaxByCountry = calculateDailyTaxByCountry(statsByCountry);

        if (dailyTaxByCountry.isEmpty()) {
            if (econCfg.activityBonus.debugLog) {
                plugin.getLogger().info("[DailyEconomyTask] zoneTaxes: нет стран с рассчитанными налогами.");
            }
            return;
        }

        for (Map.Entry<String, Double> e : dailyTaxByCountry.entrySet()) {
            String countryLower = e.getKey();
            double dailyTax     = e.getValue();

            if (dailyTax <= 0.0) continue;

            // аккумулируем недельный счёт
            countryRegistry.addWeeklyTaxDue(countryLower, dailyTax);
            // общий счётчик налогов
            countryRegistry.addCountryTaxes(countryLower, dailyTax);

            if (econCfg.activityBonus.debugLog) {
                plugin.getLogger().info(String.format(Locale.ROOT,
                        "[DailyEconomyTask] zoneTaxes: страна %s, dailyTax=%.2f, добавлено в WeeklyTaxDue и общий Taxes",
                        countryLower, dailyTax));
            }
        }
    }


    /**
     * Считает дневной налог по странам:
     *   1) Суммируем дневную стоимость ВСЕХ зон страны по heatmap’у.
     *   2) Применяем множитель населения.
     *   3) Применяем оффлайн-множитель.
     *
     * @param statsByCountry агрегированная активность по странам (для кол-ва граждан и онлайна)
     * @return карта: countryLower -> dailyTax
     */
    private Map<String, Double> calculateDailyTaxByCountry(
            Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry
    ) {
        ZoneManager zm = plugin.getZoneManager();
        if (zm == null) {
            plugin.getLogger().warning("[DailyEconomyTask] ZoneManager == null, пропускаем расчёт налогов по зонам.");
            return Collections.emptyMap();
        }

        if (zm.activityTracker == null) {
            plugin.getLogger().warning("[DailyEconomyTask] activityTracker == null в ZoneManager, пропускаем расчёт налогов по зонам.");
            return Collections.emptyMap();
        }

        // снапшот статистики чанков и весов
        Map<String, ChunkStats> chunkStats = zm.activityTracker.getChunkStatsMap();
        ActivityWeights weights = zm.activityTracker.getWeights();

        // 1) сырая сумма по странам без населения/оффлайна
        Map<String, Double> rawByCountry = new HashMap<>();

        for (ZoneInfo zone : zm.getAllZonesSnapshot()) {
            if (zone == null || !zone.hasCountry()) continue;

            String countryName = zone.getCountryName();
            if (countryName == null || countryName.isBlank()) continue;

            // дневная стоимость зоны по heatmap
            double zoneDailyCost = plugin.getZoneActivityCalculations()
                    .calculateZoneDailyCostCached(zone, chunkStats, weights);

            if (zoneDailyCost <= 0.0) continue;

            String countryLower = countryName.toLowerCase(Locale.ROOT);
            rawByCountry.merge(countryLower, zoneDailyCost, Double::sum);
        }

        if (rawByCountry.isEmpty()) {
            return Collections.emptyMap();
        }

        // 2) применяем множитель населения и оффлайн-грейс
        Map<String, Double> result = new HashMap<>();

        for (Map.Entry<String, Double> e : rawByCountry.entrySet()) {
            String countryLower = e.getKey();
            double baseTax      = e.getValue();

            UserActivityJdbc.CountryActivityStats st = statsByCountry.get(countryLower);
            int citizens = (st != null ? st.totalPlayers() : 0);

            // -- множитель населения --
            double popMult = 1.0 + citizens * econCfg.countryPopulation.citizensTaxPerPlayer;
            if (popMult > econCfg.countryPopulation.maxMultiplier) {
                popMult = econCfg.countryPopulation.maxMultiplier;
            }

            // -- оффлайн-множитель (скидка) --
            double offlineMult = computeOfflineBillingMultiplier(countryLower, statsByCountry);

            double dailyTax = baseTax * popMult * offlineMult;
            if (dailyTax <= 0.0) continue;

            result.put(countryLower, dailyTax);
        }

        return result;
    }

    /**
     * Считает задержку до ближайшего econCfg.activityBonus.payoutHour по системному времени JVM.
     */
    private long computeInitialDelayTicks(int targetHour) {
        if (targetHour < 0 || targetHour > 23) targetHour = 4;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime target = now.withHour(targetHour).withMinute(0).withSecond(0).withNano(0);

        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }

        long seconds = ChronoUnit.SECONDS.between(now, target);
        if (seconds < 0) seconds = 0;

        return seconds * 20L;
    }

    /**
     * Рассчитывает коэффициент "оффлайн-скидки" для страны.
     *
     * Логика:
     *  - считаем долю активных игроков за последние сутки:
     *        frac = activePlayers / totalPlayers
     *  - если frac >= minOnlineFractionForFullBilling → страна считается "живой",
     *    платит полный налог (1.0).
     *  - иначе → страна почти полностью оффлайн, платит лишь offlineGraceMultiplier.
     *
     * Если статистики по стране нет или totalPlayers == 0, считаем её оффлайн
     * и применяем offlineGraceMultiplier.
     */
    public double computeOfflineFactor(String countryLower,
                                       Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry) {
        UserActivityJdbc.CountryActivityStats st = statsByCountry.get(countryLower);
        if (st == null || st.totalPlayers() <= 0) {
            return econCfg.billing.offlineGraceMultiplier;
        }

        double frac = (double) st.activePlayers() / (double) st.totalPlayers();
        double threshold = econCfg.billing.minOnlineFractionForFullBilling;

        if (frac >= threshold) {
            return 1.0; // достаточно людей заходило, платят полный налог
        } else {
            return econCfg.billing.offlineGraceMultiplier; // почти все оффлайн, включаем "щадящий режим"
        }
    }

    /**
     * Множитель к дневному счёту страны с учётом онлайна.
     *
     * @param countryLower      имя страны в lowerCase
     * @param statsByCountry    карта countryLower -> (totalPlayers, activePlayers) за последние сутки
     *
     * @return 1.0   если онлайн >= порога (страна живая, платит полный счёт)
     *         offlineGraceMultiplier если онлайн < порога или нет данных (страна мёртвая, платит со скидкой)
     */
    public double computeOfflineBillingMultiplier(
            String countryLower,
            Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry
    ) {
        UserActivityJdbc.CountryActivityStats st = statsByCountry.get(countryLower);
        if (st == null || st.totalPlayers() <= 0) {
            // вообще нет игроков -> страну не выжигаем, берём "щадящий" множитель
            return econCfg.billing.offlineGraceMultiplier;
        }

        double frac = (double) st.activePlayers() / (double) st.totalPlayers();
        double threshold = econCfg.billing.minOnlineFractionForFullBilling;

        if (frac >= threshold) {
            // достаточно людей заходило — страна живая, платит 100%
            return 1.0;
        } else {
            // почти все оффлайн — умножаем счёт на offlineGraceMultiplier
            return econCfg.billing.offlineGraceMultiplier;
        }
    }
}
