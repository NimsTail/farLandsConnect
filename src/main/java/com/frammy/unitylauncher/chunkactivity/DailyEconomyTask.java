package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.bank.BankInvoicesDao;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
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
    private final ZonesEconomyConfig econCfg;
    private final BankInvoicesDao bankInvoicesDao;

    private BukkitTask task;

    public DailyEconomyTask(UnityLauncher plugin,
                            CountryRegistryJdbc countryRegistry,
                            UserActivityJdbc userActivityJdbc,
                            ZonesEconomyConfig econCfg,
                            BankInvoicesDao bankInvoicesDao) {
        this.plugin = plugin;
        this.countryRegistry = countryRegistry;
        this.userActivityJdbc = userActivityJdbc;
        this.econCfg = econCfg;
        this.bankInvoicesDao = bankInvoicesDao;
    }

    public void start() {
        if (task != null) task.cancel();

        long delayTicks = computeInitialDelayTicks(econCfg.activityBonus.payoutHour);
        long periodTicks = 20L * 60L * 60L * 24L; // сутки

        // ВАЖНО: планируем СИНХРОННО, а тяжелые части внутри сами уйдут в async.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::kickoffRunOncePipeline, delayTicks, periodTicks);

        plugin.getLogger().info("[DailyEconomyTask] Запущен ежедневный экономический тик, первый запуск через "
                + delayTicks + " тиков.");
    }

    /**
     * Запуск пайплайна:
     *  1) async: читаем activity stats по странам из БД
     *  2) sync : считаем rawByCountry по зонам/heatmap (ZoneManager + ZoneActivityCalculations)
     *  3) async: применяем множители и пишем в БД (countryRegistry.*)
     */
    private void kickoffRunOncePipeline() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                long dayAgo = now - 24L * 60L * 60L * 1000L;

                // (1) async DB
                Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry =
                        userActivityJdbc.loadCountryActivityStats(dayAgo);

                // (2) sync расчёт зон/heatmap
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Map<String, Double> rawByCountry;
                    try {
                        rawByCountry = calculateRawZoneTaxByCountrySync();
                    } catch (Throwable t) {
                        plugin.getLogger().severe("[DailyEconomyTask] Ошибка расчёта rawByCountry (sync): " + t);
                        t.printStackTrace();
                        return;
                    }

                    // (3) async применение множителей + DB updates
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            if (econCfg.activityBonus.enabled) {
                                runActivityBonus(statsByCountry);
                            }
                            runZoneTaxesFromRaw(rawByCountry, statsByCountry);
                        } catch (Throwable t) {
                            plugin.getLogger().severe("[DailyEconomyTask] Ошибка в async части пайплайна: " + t);
                            t.printStackTrace();
                        }
                    });
                });

            } catch (Throwable t) {
                plugin.getLogger().severe("[DailyEconomyTask] Ошибка в kickoffRunOncePipeline: " + t);
                t.printStackTrace();
            }
        });
    }

    /**
     * СИНХРОННО: трогаем ZoneManager и ZoneActivityCalculations.
     * Возвращает "базовую" сумму дневного налога по странам (ещё без населения/оффлайна).
     */
    private Map<String, Double> calculateRawZoneTaxByCountrySync() {
        ZoneManager zm = plugin.getZoneManager();
        if (zm == null) {
            plugin.getLogger().warning("[DailyEconomyTask] ZoneManager == null, rawByCountry пуст.");
            return Collections.emptyMap();
        }
        if (zm.activityTracker == null) {
            plugin.getLogger().warning("[DailyEconomyTask] activityTracker == null в ZoneManager, rawByCountry пуст.");
            return Collections.emptyMap();
        }

        Map<String, ChunkStats> chunkStats = zm.activityTracker.getChunkStatsMap();
        ActivityWeights weights = zm.activityTracker.getWeights();

        Map<String, Double> rawByCountry = new HashMap<>();

        for (ZoneInfo zone : zm.getAllZonesSnapshot()) {
            if (zone == null || !zone.hasCountry()) continue;

            String countryName = zone.getCountryName();
            if (countryName == null || countryName.isBlank()) continue;

            double zoneDailyCost = plugin.getZoneActivityCalculations()
                    .calculateZoneDailyCostCached(zone, chunkStats, weights);

            if (zoneDailyCost <= 0.0) continue;

            String countryLower = countryName.toLowerCase(Locale.ROOT);
            rawByCountry.merge(countryLower, zoneDailyCost, Double::sum);
        }

        return rawByCountry;
    }

    /**
     * ASYNC: применяем множители населения/оффлайна и пишем в БД.
     */
    private void runZoneTaxesFromRaw(
            Map<String, Double> rawByCountry,
            Map<String, UserActivityJdbc.CountryActivityStats> statsByCountry
    ) {
        if (rawByCountry == null || rawByCountry.isEmpty()) {
            if (econCfg.activityBonus.debugLog) {
                plugin.getLogger().info("[DailyEconomyTask] zoneTaxes: rawByCountry пуст.");
            }
            return;
        }

        for (Map.Entry<String, Double> e : rawByCountry.entrySet()) {
            String countryLower = e.getKey();
            double baseTax = e.getValue();
            if (baseTax <= 0.0) continue;

            UserActivityJdbc.CountryActivityStats st = statsByCountry.get(countryLower);
            int citizens = (st != null ? st.totalPlayers() : 0);

            // множитель населения
            double popMult = 1.0 + citizens * econCfg.countryPopulation.citizensTaxPerPlayer;
            if (popMult > econCfg.countryPopulation.maxMultiplier) {
                popMult = econCfg.countryPopulation.maxMultiplier;
            }

            // оффлайн множитель
            double offlineMult = computeOfflineBillingMultiplier(countryLower, statsByCountry);

            double dailyTax = baseTax * popMult * offlineMult;
            if (dailyTax <= 0.0) continue;

            // DB updates
            int fromUserId = 0;
            Instant dueAt = Instant.now().plusSeconds(7 * 24 * 3600L);

            bankInvoicesDao.createToCountryAsync(
                    fromUserId,
                    countryLower, // или displayName, но лучше канонический ключ
                    dailyTax,
                    "Налог/содержание зон за сутки",
                    dueAt
            );

            if (econCfg.activityBonus.debugLog) {
                plugin.getLogger().info(String.format(Locale.ROOT,
                        "[DailyEconomyTask] zoneTaxes: %s base=%.2f popMult=%.3f offlineMult=%.3f => dailyTax=%.2f",
                        countryLower, baseTax, popMult, offlineMult, dailyTax));
            }
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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
        if (!plugin.getConfig().getBoolean("economy.activity_bonus.enabled", false)) {
            return;
        }
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
