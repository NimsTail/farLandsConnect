package com.frammy.unitylauncher.chunkactivity;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Конфиг экономики зон: читает zones-economy.yml, докидывает отсутствующие параметры
 * дефолтами и даёт удобный доступ к значениям.
 *
 * Формат соответствует тому, что ты прислал:
 *
 * economy:
 *   billing: ...
 *   weights: ...
 *   country_population: ...
 *   non_industrial_penalties: ...
 *   beauty: ...
 *   zone_types: ...
 *   activityBonus: ...
 *   trashSell: ...
 */
public final class ZonesEconomyConfig {

    private static ZonesEconomyConfig INSTANCE;

    // Обычный доступ
    public static ZonesEconomyConfig get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("ZonesEconomyConfig.get() вызван до load().");
        }
        return INSTANCE;
    }

    // Первичная загрузка (onEnable)
    public static ZonesEconomyConfig load(UnityLauncher plugin) {
        INSTANCE = new ZonesEconomyConfig(plugin);
        return INSTANCE;
    }

    // === Сырые поля ===
    public final Billing billing;
    public final Weights weights;
    public final CountryPopulation countryPopulation;
    public final NonIndustrialPenalties nonIndustrialPenalties;
    public final Beauty beauty;
    public final Map<ZoneType, ZonePreset> zonePresets;
    public final ActivityBonus activityBonus;
    public final TrashSell trashSell;

    private ZonesEconomyConfig(UnityLauncher plugin) {
        File file = new File(plugin.getDataFolder(), "zones-economy.yml");

        if (!file.exists()) {
            // Файл могли не положить в jar — создаём пустой и будем заполнять дефолтами
            plugin.getLogger().warning("[ZonesEconomyConfig] zones-economy.yml не найден, создаю новый с дефолтами.");
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        boolean changed = applyDefaults(cfg);
        if (changed) {
            try {
                cfg.save(file);
            } catch (IOException e) {
                plugin.getLogger().severe("[ZonesEconomyConfig] Не удалось сохранить zones-economy.yml: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // ==== ЧТЕНИЕ СТРУКТУР ====
        this.billing               = new Billing(cfg.getConfigurationSection("economy.billing"));
        this.weights               = new Weights(cfg.getConfigurationSection("economy.weights"));
        this.countryPopulation     = new CountryPopulation(cfg.getConfigurationSection("economy.country_population"));
        this.nonIndustrialPenalties = new NonIndustrialPenalties(cfg.getConfigurationSection("economy.non_industrial_penalties"));
        this.beauty                = new Beauty(cfg.getConfigurationSection("economy.beauty"));
        this.zonePresets           = loadZonePresets(cfg.getConfigurationSection("economy.zone_types"));
        this.activityBonus         = new ActivityBonus(cfg.getConfigurationSection("economy.activityBonus"));
        this.trashSell             = new TrashSell(cfg.getConfigurationSection("economy.trashSell"));
    }

    // =====================================================================
    //  DEFAULTS
    // =====================================================================

    private boolean applyDefaults(YamlConfiguration c) {
        boolean changed = false;

        // --- billing ---
        changed |= setIfMissing(c, "economy.billing.offline_grace_multiplier", 0.10D);
        changed |= setIfMissing(c, "economy.billing.daily_to_weekly_accumulate", true);
        changed |= setIfMissing(c, "economy.billing.min_online_fraction_for_full_billing", 0.10D);

        // --- weights ---
        changed |= setIfMissing(c, "economy.weights.player_activity", 1.0D);
        changed |= setIfMissing(c, "economy.weights.blocks_changed", 1.0D);
        changed |= setIfMissing(c, "economy.weights.item_drops", 1.0D);
        changed |= setIfMissing(c, "economy.weights.entity_count", 1.0D);
        changed |= setIfMissing(c, "economy.weights.tick_load", 1.0D);
        changed |= setIfMissing(c, "economy.weights.structure_complexity", 1.0D);

        // --- country_population ---
        changed |= setIfMissing(c, "economy.country_population.citizens_tax_per_player", 0.015D);
        changed |= setIfMissing(c, "economy.country_population.max_multiplier", 2.0D);

        // --- non_industrial_penalties ---
        changed |= setIfMissing(c, "economy.non_industrial_penalties.item_drops_multiplier", 4.0D);
        changed |= setIfMissing(c, "economy.non_industrial_penalties.entity_count_multiplier", 3.0D);
        changed |= setIfMissing(c, "economy.non_industrial_penalties.tick_load_multiplier", 3.0D);

        // --- beauty ---
        changed |= setIfMissing(c, "economy.beauty.enabled", true);
        changed |= setIfMissing(c, "economy.beauty.max_bonus", -10.0D);
        changed |= setIfMissing(c, "economy.beauty.per_point", -0.5D);

        // --- zone_types.COUNTRY ---
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.base_cost_per_block", 0.0005D);
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.Kp", 0.2D);
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.Kb", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.Kd", 0.2D);
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.Ke", 0.2D);
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.Kr", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.COUNTRY.Ks", 0.2D);
        // beauty_bonus для COUNTRY не нужен, оставляем неуказанным

        // --- zone_types.COLONY ---
        changed |= setIfMissing(c, "economy.zone_types.COLONY.base_cost", 6.0D);
        changed |= setIfMissing(c, "economy.zone_types.COLONY.Kp", 0.4D);
        changed |= setIfMissing(c, "economy.zone_types.COLONY.Kb", 0.3D);
        changed |= setIfMissing(c, "economy.zone_types.COLONY.Kd", 0.5D);
        changed |= setIfMissing(c, "economy.zone_types.COLONY.Ke", 0.5D);
        changed |= setIfMissing(c, "economy.zone_types.COLONY.Kr", 0.4D);
        changed |= setIfMissing(c, "economy.zone_types.COLONY.Ks", 0.3D);

        // --- zone_types.INDUSTRIAL ---
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.base_cost", 15.0D);
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.Kp", 0.3D);
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.Kb", 0.2D);
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.Kd", 0.05D);
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.Ke", 0.15D);
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.Kr", 0.10D);
        changed |= setIfMissing(c, "economy.zone_types.INDUSTRIAL.Ks", 0.2D);

        // --- zone_types.PARK ---
        changed |= setIfMissing(c, "economy.zone_types.PARK.base_cost", 3.0D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.Kp", 0.2D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.Kb", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.Kd", 2.0D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.Ke", 1.0D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.Kr", 1.0D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.Ks", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.PARK.beauty_bonus", true);

        // --- zone_types.CHURCH ---
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.base_cost", 2.0D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.Kp", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.Kb", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.Kd", 2.5D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.Ke", 0.8D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.Kr", 1.0D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.Ks", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.CHURCH.beauty_bonus", false);

        // --- zone_types.LIBRARY ---
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.base_cost", 2.0D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.Kp", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.Kb", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.Kd", 2.5D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.Ke", 0.5D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.Kr", 1.0D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.Ks", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.LIBRARY.beauty_bonus", false);

        // --- zone_types.GREENHOUSE ---
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.base_cost", 2.0D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.Kp", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.Kb", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.Kd", 2.5D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.Ke", 1.2D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.Kr", 1.0D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.Ks", 0.1D);
        changed |= setIfMissing(c, "economy.zone_types.GREENHOUSE.beauty_bonus", true);

        // --- zone_types.SHOP ---
        changed |= setIfMissing(c, "economy.zone_types.SHOP.base_cost", 2.0D);
        changed |= setIfMissing(c, "economy.zone_types.SHOP.Kp", 0.5D);
        changed |= setIfMissing(c, "economy.zone_types.SHOP.Kb", 0.3D);
        changed |= setIfMissing(c, "economy.zone_types.SHOP.Kd", 1.0D);
        changed |= setIfMissing(c, "economy.zone_types.SHOP.Ke", 0.5D);
        changed |= setIfMissing(c, "economy.zone_types.SHOP.Kr", 0.5D);
        changed |= setIfMissing(c, "economy.zone_types.SHOP.Ks", 0.2D);

        // --- activityBonus ---
        changed |= setIfMissing(c, "economy.activityBonus.enabled", true);
        changed |= setIfMissing(c, "economy.activityBonus.minOnlineMinutes", 30);
        changed |= setIfMissing(c, "economy.activityBonus.maxAfkPercent", 40);
        changed |= setIfMissing(c, "economy.activityBonus.rewardPerActivePlayer", 5.0D);
        changed |= setIfMissing(c, "economy.activityBonus.maxRewardPerCountryPerDay", 200.0D);
        changed |= setIfMissing(c, "economy.activityBonus.payoutHour", 4);
        changed |= setIfMissing(c, "economy.activityBonus.debugLog", true);

        // --- trashSell ---
        changed |= setIfMissing(c, "economy.trashSell.enabled", true);
        changed |= setIfMissing(c, "economy.trashSell.dailyLimitPerPlayer", 50.0D);
        changed |= setIfMissing(c, "economy.trashSell.globalDailyLimit", 800.0D);
        changed |= setIfMissing(c, "economy.trashSell.minStackSize", 8);
        changed |= setIfMissing(c, "economy.trashSell.clampToRemainingLimit", true);

        // цены за мусор
        changed |= setIfMissing(c, "economy.trashSell.prices.COBBLESTONE", 0.03D);
        changed |= setIfMissing(c, "economy.trashSell.prices.STONE",       0.03D);
        changed |= setIfMissing(c, "economy.trashSell.prices.ANDESITE",    0.03D);
        changed |= setIfMissing(c, "economy.trashSell.prices.DIORITE",     0.03D);
        changed |= setIfMissing(c, "economy.trashSell.prices.GRANITE",     0.03D);

        changed |= setIfMissing(c, "economy.trashSell.prices.DIRT",        0.02D);
        changed |= setIfMissing(c, "economy.trashSell.prices.SAND",        0.02D);
        changed |= setIfMissing(c, "economy.trashSell.prices.NETHERRACK",  0.02D);

        changed |= setIfMissing(c, "economy.trashSell.prices.ROTTEN_FLESH",0.04D);
        changed |= setIfMissing(c, "economy.trashSell.prices.SPIDER_EYE",  0.04D);
        changed |= setIfMissing(c, "economy.trashSell.prices.BONE",        0.04D);

        // чёрный список
        if (!c.isList("economy.trashSell.blacklist")) {
            List<String> blacklist = Arrays.asList(
                    "DIAMOND",
                    "NETHERITE_INGOT",
                    "ANCIENT_DEBRIS",
                    "ENCHANTED_BOOK"
            );
            c.set("economy.trashSell.blacklist", blacklist);
            changed = true;
        }

        return changed;
    }

    private boolean setIfMissing(YamlConfiguration c, String path, Object value) {
        if (!c.isSet(path)) {
            c.set(path, value);
            return true;
        }
        return false;
    }

    // =====================================================================
    //  DATA CLASSES
    // =====================================================================

    public static final class Billing {
        public final double offlineGraceMultiplier;
        public final boolean dailyToWeeklyAccumulate;
        /**
         * Минимальная доля онлайн-граждан страны за сутки,
         * при которой страна платит ПОЛНЫЙ налог.
         * Если меньше — применяется offlineGraceMultiplier.
         * Например, 0.10 = 10% игроков.
         */
        public final double minOnlineFractionForFullBilling;

        Billing(ConfigurationSection sec) {
            if (sec == null) {
                this.offlineGraceMultiplier = 0.10D;
                this.dailyToWeeklyAccumulate = true;
                this.minOnlineFractionForFullBilling = 0.10D;
                return;
            }
            this.offlineGraceMultiplier = sec.getDouble("offline_grace_multiplier", 0.10D);
            this.dailyToWeeklyAccumulate = sec.getBoolean("daily_to_weekly_accumulate", true);
            this.minOnlineFractionForFullBilling =
                    sec.getDouble("min_online_fraction_for_full_billing", 0.10D);
        }
    }

    public static final class Weights {
        public final double playerActivity;
        public final double blocksChanged;
        public final double itemDrops;
        public final double entityCount;
        public final double tickLoad;
        public final double structureComplexity;

        Weights(ConfigurationSection sec) {
            if (sec == null) {
                playerActivity = blocksChanged = itemDrops = entityCount = tickLoad = structureComplexity = 1.0D;
                return;
            }
            this.playerActivity       = sec.getDouble("player_activity", 1.0D);
            this.blocksChanged       = sec.getDouble("blocks_changed", 1.0D);
            this.itemDrops           = sec.getDouble("item_drops", 1.0D);
            this.entityCount         = sec.getDouble("entity_count", 1.0D);
            this.tickLoad            = sec.getDouble("tick_load", 1.0D);
            this.structureComplexity = sec.getDouble("structure_complexity", 1.0D);
        }
    }

    public static final class CountryPopulation {
        public final double citizensTaxPerPlayer;
        public final double maxMultiplier;

        CountryPopulation(ConfigurationSection sec) {
            if (sec == null) {
                this.citizensTaxPerPlayer = 0.015D;
                this.maxMultiplier = 2.0D;
                return;
            }
            this.citizensTaxPerPlayer = sec.getDouble("citizens_tax_per_player", 0.015D);
            this.maxMultiplier        = sec.getDouble("max_multiplier", 2.0D);
        }
    }

    public static final class NonIndustrialPenalties {
        public final double itemDropsMultiplier;
        public final double entityCountMultiplier;
        public final double tickLoadMultiplier;

        NonIndustrialPenalties(ConfigurationSection sec) {
            if (sec == null) {
                this.itemDropsMultiplier   = 4.0D;
                this.entityCountMultiplier = 3.0D;
                this.tickLoadMultiplier    = 3.0D;
                return;
            }
            this.itemDropsMultiplier   = sec.getDouble("item_drops_multiplier", 4.0D);
            this.entityCountMultiplier = sec.getDouble("entity_count_multiplier", 3.0D);
            this.tickLoadMultiplier    = sec.getDouble("tick_load_multiplier", 3.0D);
        }
    }

    public static final class Beauty {
        public final boolean enabled;
        public final double maxBonus;
        public final double perPoint;

        Beauty(ConfigurationSection sec) {
            if (sec == null) {
                this.enabled  = true;
                this.maxBonus = -10.0D;
                this.perPoint = -0.5D;
                return;
            }
            this.enabled  = sec.getBoolean("enabled", true);
            this.maxBonus = sec.getDouble("max_bonus", -10.0D);
            this.perPoint = sec.getDouble("per_point", -0.5D);
        }
    }

    public static final class ZonePreset {
        public final double baseCost;
        public final double baseCostPerBlock;
        public final double Kp, Kb, Kd, Ke, Kr, Ks;
        public final boolean beautyBonus;

        ZonePreset(ConfigurationSection sec) {
            if (sec == null) {
                this.baseCost = 0.0D;
                this.baseCostPerBlock = 0.0D;
                this.Kp = this.Kb = this.Kd = this.Ke = this.Kr = this.Ks = 0.0D;
            } else {
                this.baseCost         = sec.getDouble("base_cost", 0.0D);
                this.baseCostPerBlock = sec.getDouble("base_cost_per_block", 0.0D);
                this.Kp               = sec.getDouble("Kp", 0.0D);
                this.Kb               = sec.getDouble("Kb", 0.0D);
                this.Kd               = sec.getDouble("Kd", 0.0D);
                this.Ke               = sec.getDouble("Ke", 0.0D);
                this.Kr               = sec.getDouble("Kr", 0.0D);
                this.Ks               = sec.getDouble("Ks", 0.0D);
            }
            this.beautyBonus = sec != null && sec.getBoolean("beauty_bonus", false);
        }
    }

    public static final class ActivityBonus {
        public final boolean enabled;
        public final int minOnlineMinutes;
        public final int maxAfkPercent;
        public final double rewardPerActivePlayer;
        public final double maxRewardPerCountryPerDay;
        public final int payoutHour;
        public final boolean debugLog;

        ActivityBonus(ConfigurationSection sec) {
            if (sec == null) {
                this.enabled = true;
                this.minOnlineMinutes = 30;
                this.maxAfkPercent = 40;
                this.rewardPerActivePlayer = 5.0D;
                this.maxRewardPerCountryPerDay = 200.0D;
                this.payoutHour = 4;
                this.debugLog = true;
                return;
            }
            this.enabled                   = sec.getBoolean("enabled", true);
            this.minOnlineMinutes          = sec.getInt("minOnlineMinutes", 30);
            this.maxAfkPercent             = sec.getInt("maxAfkPercent", 40);
            this.rewardPerActivePlayer     = sec.getDouble("rewardPerActivePlayer", 5.0D);
            this.maxRewardPerCountryPerDay = sec.getDouble("maxRewardPerCountryPerDay", 200.0D);
            this.payoutHour                = sec.getInt("payoutHour", 4);
            this.debugLog                  = sec.getBoolean("debugLog", true);
        }
    }

    public static final class TrashSell {
        public final boolean enabled;
        public final double dailyLimitPerPlayer;
        public final double globalDailyLimit;
        public final int minStackSize;
        public final boolean clampToRemainingLimit;

        public final Map<Material, Double> prices;
        public final Set<Material> blacklist;

        TrashSell(ConfigurationSection sec) {
            if (sec == null) {
                this.enabled = true;
                this.dailyLimitPerPlayer = 50.0D;
                this.globalDailyLimit = 800.0D;
                this.minStackSize = 8;
                this.clampToRemainingLimit = true;
                this.prices = Collections.emptyMap();
                this.blacklist = Collections.emptySet();
                return;
            }
            this.enabled               = sec.getBoolean("enabled", true);
            this.dailyLimitPerPlayer   = sec.getDouble("dailyLimitPerPlayer", 50.0D);
            this.globalDailyLimit      = sec.getDouble("globalDailyLimit", 800.0D);
            this.minStackSize          = sec.getInt("minStackSize", 8);
            this.clampToRemainingLimit = sec.getBoolean("clampToRemainingLimit", true);

            Map<Material, Double> tmpPrices = new EnumMap<>(Material.class);
            ConfigurationSection pSec = sec.getConfigurationSection("prices");
            if (pSec != null) {
                for (String key : pSec.getKeys(false)) {
                    try {
                        Material mat = Material.valueOf(key.toUpperCase(Locale.ROOT));
                        double price = pSec.getDouble(key, 0.0D);
                        if (price > 0.0D) {
                            tmpPrices.put(mat, price);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // неизвестный материал — просто игнор
                    }
                }
            }
            this.prices = Collections.unmodifiableMap(tmpPrices);

            Set<Material> tmpBlacklist = EnumSet.noneOf(Material.class);
            List<String> list = sec.getStringList("blacklist");
            for (String s : list) {
                try {
                    Material mat = Material.valueOf(s.toUpperCase(Locale.ROOT));
                    tmpBlacklist.add(mat);
                } catch (IllegalArgumentException ignored) {
                    // неизвестный материал — игнор
                }
            }
            this.blacklist = Collections.unmodifiableSet(tmpBlacklist);
        }
    }

    // =====================================================================
    //  HELPERS
    // =====================================================================

    private Map<ZoneType, ZonePreset> loadZonePresets(ConfigurationSection root) {
        Map<ZoneType, ZonePreset> map = new EnumMap<>(ZoneType.class);
        if (root == null) return map;

        for (ZoneType type : ZoneType.values()) {
            ConfigurationSection sec = root.getConfigurationSection(type.name());
            if (sec != null) {
                map.put(type, new ZonePreset(sec));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /** Безопасно вернуть пресет для типа зоны или null, если не задан. */
    public ZonePreset getPreset(ZoneType type) {
        if (type == null) return null;
        return zonePresets.get(type);
    }

    /** Пресет с дефолтами, если в конфиге нет секции для этого типа. */
    public ZonePreset getPresetOrDefault(ZoneType type) {
        ZonePreset p = getPreset(type);
        if (p != null) return p;
        // мягкий дефолт: ничего криминального, просто нули
        return new ZonePreset(null);
    }

    /** Цена продажи мусора этому серверу для данного материала, либо 0.0 если не продаётся. */
    public double getTrashPrice(Material mat) {
        if (mat == null || trashSell == null || trashSell.prices == null) return 0.0;
        return trashSell.prices.getOrDefault(mat, 0.0);
    }

    /** true, если предмет запрещён к продаже как мусор. */
    public boolean isTrashBlacklisted(Material mat) {
        if (mat == null || trashSell == null || trashSell.blacklist == null) return false;
        return trashSell.blacklist.contains(mat);
    }

}
