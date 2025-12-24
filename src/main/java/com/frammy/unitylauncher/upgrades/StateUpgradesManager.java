package com.frammy.unitylauncher.upgrades;

import com.frammy.unitylauncher.MoneyManager;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.countryrelations.CountryRelationshipDao;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

/**
 * Менеджер государственных апгрейдов:
 * 1. Гос.заказы (State Contracts)
 * 2. Налог на роскошь (Luxury Tax)
 * 3. Платные магистрали (Toll Roads)
 * 4. Экспортный ребейт (Export Rebate)
 * 5. Ресурсный фокус (Resource Focus)
 * 6. Партийная пропаганда (Party Propaganda)
 * 7. Цензура (Censorship)
 * 8. Комендантский час (Curfew)
 * 9. Ремонтная артель (Repair Guild)
 * 10. Торговая зона (Trading Zone)
 * 11. Пробник (Sampler)
 * 12. Happy Hour
 */
public class StateUpgradesManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final UpgradesConfig config;
    private final MoneyManager moneyManager;
    private final CountryRelationshipDao countryDao; // для listAllCountries в пропаганде
    private final Map<UUID, Long> tollCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastTollCountry = new ConcurrentHashMap<>();

    private final File dataFile;
    private final YamlConfiguration dataCfg;

    private final File tollFile;
    private final YamlConfiguration tollCfg;

    private final File stateCfgFile;
    private final YamlConfiguration stateCfg;

    // Ключи для PDC
    private final NamespacedKey KEY_SAMPLE_ITEM;
    private final NamespacedKey KEY_HAPPY_HOUR_SHOP;

    // Хранение активных гос.заказов: CountryName -> List<Contract>
    private final Map<String, List<StateContract>> activeContracts = new ConcurrentHashMap<>();

    // Хранение фокуса ресурсов: CountryName -> ResourceType
    private final Map<String, String> resourceFocus = new ConcurrentHashMap<>();

    // Кулдауны для пробников: PlayerUUID -> LastSampleTime
    private final Map<UUID, Long> samplerCooldowns = new ConcurrentHashMap<>();

    // Happy Hour расписания: ShopLocation -> HappyHourSchedule
    private final Map<Location, HappyHourSchedule> happyHourSchedules = new ConcurrentHashMap<>();

    // Таск для пропаганды и комендантского часа
    private BukkitTask propagandaTask;
    private BukkitTask curfewTask;


    public StateUpgradesManager(UnityLauncher plugin, ZoneManager zoneManager, UpgradesConfig config,
                                MoneyManager moneyManager, CountryRelationshipDao countryDao) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.config = config;
        this.moneyManager = moneyManager;
        this.countryDao = countryDao;

        this.KEY_SAMPLE_ITEM = new NamespacedKey(plugin, "state.sample");
        this.KEY_HAPPY_HOUR_SHOP = new NamespacedKey(plugin, "state.happy_hour");

        this.dataFile = new File(plugin.getDataFolder(), "state_upgrades_data.yml");
        this.dataCfg = YamlConfiguration.loadConfiguration(dataFile);

        this.tollFile = new File(plugin.getDataFolder(), "toll_roads.yml");
        this.tollCfg = YamlConfiguration.loadConfiguration(tollFile);

        this.stateCfgFile = new File(plugin.getDataFolder(), "state_upgrades.yml");
        this.stateCfg = YamlConfiguration.loadConfiguration(stateCfgFile);
    }

    public void start() {
        loadData();
        ensureTollDefaults();

        // Запускаем таск пропаганды (каждые N минут)
        if (propagandaTask != null) propagandaTask.cancel();
        long propagandaPeriod = Math.max(20L, config.statePropagandaPeriodTicks);
        propagandaTask = new BukkitRunnable() {
            @Override
            public void run() {
                processPropaganda();
            }
        }.runTaskTimer(plugin, 6000L, propagandaPeriod);


        // Запускаем таск комендантского часа (каждую минуту проверяем время)
        if (curfewTask != null) curfewTask.cancel();
        curfewTask = new BukkitRunnable() {
            @Override
            public void run() {
                processCurfew();
            }
        }.runTaskTimer(plugin, 100L, 1200L); // каждую минуту

        if (config.debug) plugin.getLogger().info("[StateUpgrades] Started");
    }

    public void stop() {
        if (propagandaTask != null) {
            propagandaTask.cancel();
            propagandaTask = null;
        }
        if (curfewTask != null) {
            curfewTask.cancel();
            curfewTask = null;
        }
        saveData();
    }

    // =====================================================================
    //  1. ГОС.ЗАКАЗЫ (State Contracts)
    //  Страна ставит задания с наградой
    // =====================================================================

    public boolean canCreateContract(String countryName) {
        if (countryName == null || countryName.isBlank()) return false;
        if (countryMaxLevel(countryName, config.stateContractsPerm, 1) < 1) return false;

        List<StateContract> contracts = activeContracts.getOrDefault(countryName, new ArrayList<>());
        return contracts.size() < config.stateContractsMaxActive;
    }

    public void createContract(String countryName, String description, double reward) {
        if (!canCreateContract(countryName)) return;

        List<StateContract> contracts = activeContracts.computeIfAbsent(countryName, k -> new ArrayList<>());
        contracts.add(new StateContract(description, reward, System.currentTimeMillis()));

        if (config.debug) {
            plugin.getLogger().info("[StateUpgrades] Contract created for " + countryName + ": " + description);
        }
    }

    public List<StateContract> getActiveContracts(String countryName) {
        return activeContracts.getOrDefault(countryName, new ArrayList<>());
    }

    // =====================================================================
    //  2. НАЛОГ НА РОСКОШЬ (Luxury Tax)
    //  Дополнительный налог на редкие вещи
    // =====================================================================

    public double calculateLuxuryTax(String countryName, ItemStack item, double salePrice) {
        if (countryName == null || countryName.isBlank()) return 0.0;
        if (countryMaxLevel(countryName, config.stateLuxuryTaxPerm, 1) < 1) return 0.0;

        if (item != null && config.stateLuxuryTaxItems.contains(item.getType())) {
            return salePrice * (config.stateLuxuryTaxPercent / 100.0);
        }

        return 0.0;
    }

    // =====================================================================
    //  3. ПЛАТНЫЕ МАГИСТРАЛИ (Toll Roads)
    //  Плата за проход через границы
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent e) {
        if (!tollEnabled()) return;

        if (e.getTo() == null) return;
        if (e.getFrom().getChunk().equals(e.getTo().getChunk())) return;

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // антиспам/анти-дребезг: не чаще чем раз в N мс
        long now = System.currentTimeMillis();
        long cdMs = tollCooldownMs(); // из отдельного yaml, см. ниже
        long last = tollCooldown.getOrDefault(id, 0L);
        if (now - last < cdMs) return;

        ZoneInfo zFrom = zoneManager.getZoneAt(e.getFrom());
        ZoneInfo zTo   = zoneManager.getZoneAt(e.getTo());

        String countryFrom = (zFrom == null) ? null : UpgradeCondition.zoneCountryCanonical(zFrom);
        String countryTo   = (zTo   == null) ? null : UpgradeCondition.zoneCountryCanonical(zTo);

        // пошлина только при ВЪЕЗДЕ в страну
        if (countryTo == null || countryTo.isBlank()) return;
        if (countryFrom != null && countryFrom.equals(countryTo)) return;

        // чтобы не платить 20 раз при “тряске” на границе
        String lastCountry = lastTollCountry.get(id);
        if (countryTo.equals(lastCountry)) return;

        // есть ли апгрейд у страны назначения
        if (countryMaxLevel(countryTo, config.stateTollRoadsPerm, 1) < 1) return;

        double toll = tollFee();
        if (toll <= 0) return;

        tollCooldown.put(id, now);
        lastTollCountry.put(id, countryTo);

        // списываем с БАНКА игрока, зачисляем в казну страны назначения
        moneyManager.tryWithdraw(p.getName(), toll, ok -> {
            new BukkitRunnable() {
                @Override public void run() {
                    if (ok) {
                        plugin.getCountryRegistryJdbc().addCountryMoney(countryTo, toll); // async внутри :contentReference[oaicite:6]{index=6}
                        p.sendMessage(ChatColor.YELLOW + "⚠ Въезд в " + countryTo + ": пошлина " + toll + " Ⓕ");
                    } else {
                        p.sendMessage(ChatColor.RED + "Недостаточно средств для оплаты пошлины (" + toll + " Ⓕ)");
                        if (tollDenyIfCantPay()) {
                            // откатываем назад (безопаснее на from)
                            p.teleport(e.getFrom());
                        }
                    }
                }
            }.runTask(plugin);
        });
    }

    // =====================================================================
    //  4. ЭКСПОРТНЫЙ РЕБЕЙТ (Export Rebate)
    //  Возврат налога за экспорт
    // =====================================================================

    public double calculateExportRebate(String sellerCountry, Location saleLocation, double salePrice) {
        if (sellerCountry == null || sellerCountry.isBlank()) return 0.0;
        if (countryMaxLevel(sellerCountry, config.stateExportRebatePerm, 1) < 1) return 0.0;

        // Проверяем что продажа происходит вне страны продавца
        ZoneInfo zone = zoneManager.getZoneAt(saleLocation);
        if (zone == null) return 0.0;

        String saleCountry = UpgradeCondition.zoneCountryCanonical(zone);
        if (saleCountry == null || saleCountry.equals(sellerCountry)) return 0.0;

        // Возвращаем процент от продажи
        return salePrice * (config.stateExportRebatePercent / 100.0);
    }

    // =====================================================================
    //  5. РЕСУРСНЫЙ ФОКУС (Resource Focus)
    //  Бонус к одному ресурсу, штраф к остальным
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent e) {
        Player player = e.getPlayer();
        Block block = e.getBlock();

        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
        if (playerCountry == null) return;

        String focus = getResourceFocus(playerCountry);
        if (focus == null) return;

        // Определяем тип добываемого ресурса
        String resourceType = getResourceType(block.getType());
        if (resourceType == null) return;

        double multiplier = getResourceMultiplier(playerCountry, resourceType);

        // Применяем бонус/штраф к дропу
        if (multiplier != 1.0 && Math.random() < Math.abs(multiplier - 1.0)) {
            if (multiplier > 1.0) {
                // Бонус - дублируем дроп
                Collection<ItemStack> drops = block.getDrops(player.getInventory().getItemInMainHand());
                for (ItemStack drop : drops) {
                    block.getWorld().dropItemNaturally(block.getLocation(), drop.clone());
                }

                if (config.debug) {
                    plugin.getLogger().info("[StateUpgrades] Resource focus bonus: " + player.getName() +
                        " +" + (multiplier - 1.0) * 100 + "% for " + resourceType);
                }
            }
            // Штраф уже учтён уменьшенным шансом
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player player = e.getPlayer();
        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
        if (playerCountry == null) return;

        double multiplier = getResourceMultiplier(playerCountry, "fish");

        if (multiplier > 1.0 && Math.random() < (multiplier - 1.0)) {
            // Бонус к рыбалке - дополнительный улов
            if (e.getCaught() instanceof org.bukkit.entity.Item item) {
                ItemStack caught = item.getItemStack();
                player.getWorld().dropItemNaturally(player.getLocation(), caught.clone());

                if (config.debug) {
                    plugin.getLogger().info("[StateUpgrades] Resource focus bonus: " + player.getName() +
                        " extra fish catch");
                }
            }
        }
    }

    private String getResourceType(Material material) {
        String name = material.name();

        // Руды
        if (name.contains("_ORE") || name.equals("ANCIENT_DEBRIS")) {
            return "ore";
        }

        // Древесина
        if (name.contains("_LOG") || name.contains("_WOOD")) {
            return "wood";
        }

        return null;
    }

    public void setResourceFocus(String countryName, String resourceType) {
        if (countryName == null || countryName.isBlank()) return;
        if (countryMaxLevel(countryName, config.stateResourceFocusPerm, 1) < 1) return;

        resourceFocus.put(countryName, resourceType);

        if (config.debug) {
            plugin.getLogger().info("[StateUpgrades] Resource focus: " + countryName + " -> " + resourceType);
        }
    }

    public String getResourceFocus(String countryName) {
        return resourceFocus.get(countryName);
    }

    public double getResourceMultiplier(String countryName, String resourceType) {
        if (countryName == null || countryName.isBlank()) return 1.0;
        if (countryMaxLevel(countryName, config.stateResourceFocusPerm, 1) < 1) return 1.0;

        String focus = resourceFocus.get(countryName);
        if (focus == null) return 1.0;

        if (focus.equalsIgnoreCase(resourceType)) {
            return 1.0 + (config.stateResourceFocusBonusPercent / 100.0);
        } else {
            return 1.0 - (config.stateResourceFocusPenaltyPercent / 100.0);
        }
    }

    // =====================================================================
    //  6. ПАРТИЙНАЯ ПРОПАГАНДА (Party Propaganda)
    //  Периодические объявления
    // =====================================================================

    private void processPropaganda() {
        for (String countryName : getAllCountriesWithUpgrade(config.statePropagandaPerm)) {
            // Формируем сообщение с статистикой
            String message = ChatColor.GOLD + "【" + countryName + "】" + ChatColor.YELLOW +
                " Новости страны: Наши граждане процветают! Слава " + countryName + "!";

            // Отправляем всем игрокам страны
            for (Player player : Bukkit.getOnlinePlayers()) {
                String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
                if (countryName.equals(playerCountry)) {
                    player.sendMessage(message);
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.0f);
                }
            }

            if (config.debug) {
                plugin.getLogger().info("[StateUpgrades] Propaganda sent for " + countryName);
            }
        }
    }

    // =====================================================================
    //  7. ЦЕНЗУРА (Censorship)
    //  Фильтрация чата и табличек
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        Player player = e.getPlayer();
        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());

        if (playerCountry == null) return;
        if (countryMaxLevel(playerCountry, config.stateCensorshipPerm, 1) < 1) return;

        String message = e.getMessage();
        String filtered = applyCensorship(message);

        if (!message.equals(filtered)) {
            e.setMessage(filtered);
            player.sendMessage(ChatColor.RED + "⚠ Ваше сообщение было отфильтровано.");

            if (config.debug) {
                plugin.getLogger().info("[StateUpgrades] Censorship: " + player.getName() +
                    " message filtered: " + message + " -> " + filtered);
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSignChange(SignChangeEvent e) {
        Player player = e.getPlayer();
        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());

        if (playerCountry == null) return;
        if (countryMaxLevel(playerCountry, config.stateCensorshipPerm, 1) < 1) return;

        for (int i = 0; i < 4; i++) {
            String line = e.getLine(i);
            if (line != null) {
                String filtered = applyCensorship(line);
                if (!line.equals(filtered)) {
                    e.setLine(i, filtered);
                }
            }
        }
    }

    private String applyCensorship(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;
        for (String triggerWord : config.stateCensorshipTriggerWords) {
            if (triggerWord.isEmpty()) continue;
            result = result.replaceAll("(?i)" + Pattern.quote(triggerWord), "***");
        }

        return result;
    }

    // =====================================================================
    //  8. КОМЕНДАНТСКИЙ ЧАС (Curfew)
    //  Ночные ограничения
    // =====================================================================

    private void processCurfew() {
        World world = Bukkit.getWorld("world");
        if (world == null) return;

        long time = world.getTime();
        int hour = (int) ((time / 1000 + 6) % 24);

        boolean isCurfewTime;
        int start = config.stateCurfewStartHour;
        int end   = config.stateCurfewEndHour;

        if (start < end) {
            isCurfewTime = hour >= start && hour < end;
        } else {
            isCurfewTime = hour >= start || hour < end;
        }

        if (!isCurfewTime) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
            if (playerCountry == null) continue;

            if (countryMaxLevel(playerCountry, config.stateCurfewPerm, 1) < 1) continue;

            // Проверяем что игрок в своей стране
            Location loc = player.getLocation();
            ZoneInfo zone = zoneManager.getZoneAt(loc);
            if (zone == null) continue;

            String zoneCountry = UpgradeCondition.zoneCountryCanonical(zone);
            if (!playerCountry.equals(zoneCountry)) continue;

            // Применяем эффекты комендантского часа
            player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                1300, // чуть больше минуты
                0,
                true,
                false,
                false
            ));

            player.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS,
                1300,
                0,
                true,
                false,
                false
            ));
        }
    }

    // =====================================================================
    //  9. РЕМОНТНАЯ АРТЕЛЬ (Repair Guild)
    //  Переработка поломанных инструментов
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerBreakTool(PlayerInteractEvent e) {
        Player player = e.getPlayer();
        ItemStack item = e.getItem();

        if (item == null || !item.hasItemMeta()) return;

        // Проверяем что инструмент сломан (0 durability)
        if (item.getType().getMaxDurability() > 0) {
            org.bukkit.inventory.meta.Damageable damageable = (org.bukkit.inventory.meta.Damageable) item.getItemMeta();
            if (damageable.getDamage() >= item.getType().getMaxDurability() - 1) {
                // Инструмент почти сломан, предлагаем переработку
                ItemStack recycled = recycleItem(player, item);
                if (recycled != null) {
                    player.getInventory().remove(item);
                    player.getInventory().addItem(recycled);
                }
            }
        }
    }

    public ItemStack recycleItem(Player player, ItemStack brokenItem) {
        String playerCountry = UpgradeCondition.playerCountryCanonical(player.getName());
        if (playerCountry == null) return null;

        if (countryMaxLevel(playerCountry, config.stateRepairGuildPerm, 1) < 1) return null;

        // Определяем материал для возврата
        Material baseMaterial = getBaseMaterial(brokenItem.getType());
        if (baseMaterial == null) return null;

        // Вычисляем количество материала
        double returnPercent = config.stateRepairGuildReturnPercent / 100.0;
        int baseAmount = getBaseAmount(brokenItem.getType());
        int returnAmount = Math.max(1, (int) (baseAmount * returnPercent));

        ItemStack result = new ItemStack(baseMaterial, returnAmount);
        player.sendMessage(ChatColor.GREEN + "♻ Ремонтная артель: получено " + returnAmount + "x " +
            baseMaterial.name());

        if (config.debug) {
            plugin.getLogger().info("[StateUpgrades] Repair guild: " + player.getName() +
                " recycled " + brokenItem.getType() + " -> " + returnAmount + "x " + baseMaterial);
        }

        return result;
    }

    private Material getBaseMaterial(Material itemType) {
        String name = itemType.name();
        if (name.contains("DIAMOND")) return Material.DIAMOND;
        if (name.contains("IRON")) return Material.IRON_INGOT;
        if (name.contains("GOLD")) return Material.GOLD_INGOT;
        if (name.contains("NETHERITE")) return Material.NETHERITE_SCRAP;
        if (name.contains("STONE")) return Material.COBBLESTONE;
        if (name.contains("WOOD")) return Material.STICK;
        return null;
    }

    private int getBaseAmount(Material itemType) {
        String name = itemType.name();
        if (name.contains("PICKAXE") || name.contains("AXE")) return 3;
        if (name.contains("SWORD")) return 2;
        if (name.contains("SHOVEL") || name.contains("HOE")) return 2;
        if (name.contains("HELMET")) return 5;
        if (name.contains("CHESTPLATE")) return 8;
        if (name.contains("LEGGINGS")) return 7;
        if (name.contains("BOOTS")) return 4;
        return 1;
    }

    // =====================================================================
    //  10. ТОРГОВАЯ ЗОНА (Trading Zone)
    //  Увеличение лимита магазинов - интеграция с SignManager
    // =====================================================================

    public int getExtraShopSlots(String countryName) {
        if (countryName == null || countryName.isBlank()) return 0;
        if (countryMaxLevel(countryName, config.stateTradingZonePerm, 1) < 1) return 0;

        return config.stateTradingZoneExtraSlots;
    }

    // =====================================================================
    //  11. ПРОБНИК (Sampler)
    //  Бесплатный образец с кулдауном
    // =====================================================================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSampleItemClick(InventoryClickEvent e) {
        ItemStack item = e.getCurrentItem();
        if (item == null) return;

        // Блокируем перемещение образцов в другие инвентари
        if (isSampleItem(item)) {
            if (e.getClickedInventory() != e.getWhoClicked().getInventory()) {
                e.setCancelled(true);
                e.getWhoClicked().sendMessage(ChatColor.RED + "Образцы нельзя продавать или передавать!");

                if (config.debug) {
                    plugin.getLogger().info("[StateUpgrades] Sample item transfer blocked");
                }
            }
        }
    }

    public boolean canTakeSample(Player player) {
        UUID uuid = player.getUniqueId();
        Long lastSample = samplerCooldowns.get(uuid);

        if (lastSample == null) return true;

        long cooldownMs = config.stateSamplerCooldownHours * 60L * 60L * 1000L;
        return (System.currentTimeMillis() - lastSample) >= cooldownMs;
    }

    public ItemStack createSampleItem(ItemStack original) {
        ItemStack sample = original.clone();
        sample.setAmount(1);

        ItemMeta meta = sample.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore == null) lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Образец (непродаваемый)");
            meta.setLore(lore);

            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(KEY_SAMPLE_ITEM, PersistentDataType.BYTE, (byte) 1);

            sample.setItemMeta(meta);
        }

        return sample;
    }

    public void recordSampleTaken(Player player) {
        samplerCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
    }

    public boolean isSampleItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(KEY_SAMPLE_ITEM, PersistentDataType.BYTE);
    }

    // =====================================================================
    //  12. HAPPY HOUR
    //  Расписание скидок в магазине
    // =====================================================================

    public void setHappyHour(Location shopLocation, int startHour, int endHour, int discount) {
        if (shopLocation == null) return;
        Location key = shopLocation.getBlock().getLocation();
        happyHourSchedules.put(key, new HappyHourSchedule(startHour, endHour, discount));

        if (config.debug) {
            plugin.getLogger().info("[StateUpgrades] Happy Hour set at " + encodeBlockLoc(key) +
                    ": " + startHour + "-" + endHour + ", -" + discount + "%");
        }
    }

    public int getHappyHourDiscount(Location shopLocation) {
        if (shopLocation == null) return 0;
        Location key = shopLocation.getBlock().getLocation();

        HappyHourSchedule schedule = happyHourSchedules.get(key);
        if (schedule == null) return 0;

        World world = key.getWorld();
        if (world == null) return 0;

        long time = world.getTime();
        int hour = (int) ((time / 1000 + 6) % 24);

        return schedule.isActive(hour) ? schedule.discount() : 0;
    }

    public HappyHourSchedule getHappyHour(Location shopLocation) {
        if (shopLocation == null) return null;
        Location key = shopLocation.getBlock().getLocation();
        return happyHourSchedules.get(key);
    }

    public boolean removeHappyHour(Location shopLocation) {
        if (shopLocation == null) return false;
        Location key = shopLocation.getBlock().getLocation();
        return happyHourSchedules.remove(key) != null;
    }

    // =====================================================================
    //  ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // =====================================================================

    private List<String> getAllCountriesWithUpgrade(String perm) {
        List<String> out = new ArrayList<>();
        for (String c : countryDao.listAllCountries()) {
            if (c == null || c.isBlank()) continue;
            if (countryMaxLevel(c, perm, 1) >= 1) out.add(c);
        }
        return out;
    }

    private static String encodeBlockLoc(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getName() + ";" + loc.getBlockX() + ";" + loc.getBlockY() + ";" + loc.getBlockZ();
    }

    private static Location decodeBlockLoc(String key) {
        if (key == null || key.isBlank()) return null;
        String[] p = key.split(";");
        if (p.length != 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            int x = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            int z = Integer.parseInt(p[3]);
            return new Location(w, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void loadData() {
        happyHourSchedules.clear();

        var sec = dataCfg.getConfigurationSection("happy_hour");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                Location loc = decodeBlockLoc(k);
                if (loc == null) continue;

                int start = sec.getInt(k + ".start", 0);
                int end = sec.getInt(k + ".end", 0);
                int disc = sec.getInt(k + ".discount", 0);

                // clamp, чтобы не было мусора из конфига
                start = Math.max(0, Math.min(23, start));
                end   = Math.max(0, Math.min(23, end));
                disc  = Math.max(0, Math.min(100, disc));

                happyHourSchedules.put(loc, new HappyHourSchedule(start, end, disc));
            }
        }

        if (config.debug) plugin.getLogger().info("[StateUpgrades] Data loaded: happyHour=" + happyHourSchedules.size());
    }

    private void saveData() {
        dataCfg.set("happy_hour", null);

        for (var e : happyHourSchedules.entrySet()) {
            String key = encodeBlockLoc(e.getKey());
            if (key == null) continue;

            HappyHourSchedule s = e.getValue();
            dataCfg.set("happy_hour." + key + ".start", s.startHour());
            dataCfg.set("happy_hour." + key + ".end", s.endHour());
            dataCfg.set("happy_hour." + key + ".discount", s.discount());
        }

        saveYamlQuiet(dataCfg, dataFile);

        if (config.debug) plugin.getLogger().info("[StateUpgrades] Data saved: happyHour=" + happyHourSchedules.size());
    }

    private void ensureTollDefaults() {
        if (!tollCfg.contains("enabled")) tollCfg.set("enabled", true);
        if (!tollCfg.contains("fee")) tollCfg.set("fee", 5.0);
        if (!tollCfg.contains("cooldown_ms")) tollCfg.set("cooldown_ms", 15000);
        if (!tollCfg.contains("deny_entry_if_cant_pay")) tollCfg.set("deny_entry_if_cant_pay", false);

        saveYamlQuiet(tollCfg, tollFile);
    }

    private void saveYamlQuiet(YamlConfiguration cfg, File file) {
        try { cfg.save(file); }
        catch (IOException e) { plugin.getLogger().warning("[StateUpgrades] Failed to save " + file.getName() + ": " + e.getMessage()); }
    }

    private boolean tollEnabled() {
        return tollCfg.getBoolean("enabled", true);
    }
    private double tollFee() {
        return tollCfg.getDouble("fee", 5.0);
    }
    private long tollCooldownMs() {
        return tollCfg.getLong("cooldown_ms", 15000L);
    }
    private boolean tollDenyIfCantPay() {
        return tollCfg.getBoolean("deny_entry_if_cant_pay", false);
    }

    public boolean canEditHappyHour(Player player) {
        if (player == null) return false;

        // админ всегда может
        if (player.hasPermission("unitylauncher.reload") || player.hasPermission("unitylauncher.admin")) return true;

        int minIndex = stateCfg.getInt("happy_hour.min_role_index", 700);

        // игрок должен быть в стране (каноническое имя у тебя уже есть в UpgradeCondition)
        String country = UpgradeCondition.playerCountryCanonical(player.getName());
        if (country == null || country.isBlank()) return false;

        int idx = plugin.getCountryRegistryJdbc().getPlayerRoleIndex(player.getName());
        return idx >= minIndex;
    }

    public int getHappyHourTargetRange() {
        return stateCfg.getInt("happy_hour.target_range", 6);
    }

    public void saveDataNow() {
        saveData(); // твой существующий метод
    }

    // =====================================================================
    //  ВНУТРЕННИЕ КЛАССЫ
    // =====================================================================

    public record StateContract(String description, double reward, long createdTime) {
    }

    public record HappyHourSchedule(int startHour, int endHour, int discount) {

        public boolean isActive(int currentHour) {
                if (startHour < endHour) {
                    return currentHour >= startHour && currentHour < endHour;
                } else {
                    return currentHour >= startHour || currentHour < endHour;
                }
            }
        }
}
