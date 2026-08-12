package com.frammy.unitylauncher;

import com.frammy.unitylauncher.auth.AuthBossbarManager;
import com.frammy.unitylauncher.auth.AuthListener;
import com.frammy.unitylauncher.auth.AuthService;
import com.frammy.unitylauncher.auth.LoginRateLimiter;
import com.frammy.unitylauncher.bank.BankInvoicesDao;
import com.frammy.unitylauncher.bluemapheat.BlueMapHeatService;
import com.frammy.unitylauncher.chunkactivity.*;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.signs.features.trash.TrashSellConfig;
import com.frammy.unitylauncher.tab.LuckPermsPrefixService;
import com.frammy.unitylauncher.tab.TabPrefixService;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.core.Upgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradesManager;
import com.frammy.unitylauncher.upgrades.impl.*;
import com.frammy.unitylauncher.zones.WeeklyCountryInvoiceService;
import com.frammy.unitylauncher.zones.ZoneActivityCalculations;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import com.frammy.unitylauncher.zones.countryrelations.CountryRelationshipDao;
import com.frammy.unitylauncher.zones.countryrelations.DiplomacyService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UnityLauncher extends JavaPlugin implements Listener {

    // === singleton ===
    private static UnityLauncher instance;
    public static UnityLauncher getInstance() { return instance; }

    // === managers / services ===
    private final Set<UUID> awaitingCorrectCommand = ConcurrentHashMap.newKeySet();
    public final List<String> commandCategories = new ArrayList<>();
    public LuckPermsPrefixService luckPermsPrefixService;
    public MoneyManager moneyManager;
    private ZoneManager zoneManager;
    public ZoneActivityCalculations zoneActivityCalculations;
    private SignManager signManager;
    private ActivityTracker activityTracker;
    private WebSocketManager webSocketManager;
    private BlueMapIntegration blueMapIntegration;
    private LoginRateLimiter loginLimiter;
    public BlueMapHeatService blueMapHeatService;

    public DiplomacyService diplomacy;
    public CountryRegistryJdbc countryRegistryJdbc;
    // military-diplomacy-design.md §13 Фаза 4.
    public com.frammy.unitylauncher.auth.WarStatusCache warStatusCache;
    public CountryRelationshipDao countryRelationshipDao;

    public AuthService authService;
    public AuthListener authListener;
    public AuthBossbarManager authBossbars;
    private DailyEconomyTask dailyEconomyTask;
    private UpgradesManager upgradesManager;
    private BankInvoicesDao bankInvoicesDao;

    // server messages (join/quit/advancement)
    private ServerMessagesListener serverMessagesListener;

    public ServerMessagesListener getServerMessagesListener() {
        return serverMessagesListener;
    }

    // DB pool
    private HikariDataSource hikari;

    // expose some stuff
    public BlueMapIntegration getBlueMapIntegration() { return blueMapIntegration; }
    public SignManager getSignManager() { return signManager; }
    public ZoneManager getZoneManager() { return zoneManager; }
    public ZoneActivityCalculations getZoneActivityCalculations() { return zoneActivityCalculations; }
    public DataSource getDataSource() { return hikari; }
    public Set<UUID> getAwaitingCorrectCommand() { return awaitingCorrectCommand; }
    public CountryRegistryJdbc getCountryRegistryJdbc() { return countryRegistryJdbc; }
    public AuthListener getAuthListener() { return authListener; }
    public AuthService getAuthService() { return authService; }
    public LoginRateLimiter getLoginLimiter() { return loginLimiter; }
    public UpgradesManager getUpgradesManager() { return upgradesManager; }
    public ActivityTracker getActivityTracker() { return activityTracker; }
    public MoneyManager getMoneyManager() { return moneyManager; }
    public BankInvoicesDao getBankInvoicesDao() { return bankInvoicesDao; }

    // cached db.properties + driver status
    private static volatile Properties DB_PROPS;
    private static final AtomicBoolean DRIVER_LOADED = new AtomicBoolean(false);

    // --- AUTH config defaults (можно переопределить из secrets.properties) ---
    private static long AUTH_TTL_MS = 24L * 60 * 60 * 1000; // 24h
    private static int AUTH_ITER = 120_000;
    private static int AUTH_KEY_LEN = 256;
    private static byte[] AUTH_PEPPER = new byte[0];

    // --- bridge to the new farlandsconnect backend (auth mirror only, see FarLandsApiClient) ---
    private com.frammy.unitylauncher.auth.FarLandsApiClient farLandsApi;
    public com.frammy.unitylauncher.auth.FarLandsApiClient getFarLandsApi() { return farLandsApi; }

    @Override
    public void onEnable() {
        instance = this;

        TrashSellConfig.load(this);

        loadAuthSecrets();

        // --- init DB pool как можно раньше ---
        initDataSource();

        // --- базовые листенеры (сам плагин) ---
        Bukkit.getPluginManager().registerEvents(this, this);

        // --- money / balance accounting ---
        moneyManager = new MoneyManager(this);
        getServer().getPluginManager().registerEvents(moneyManager, this);

        activityTracker = new ActivityTracker(this);
        safeRegisterListener(activityTracker); // если ActivityTracker оставляешь Listener
        Bukkit.getPluginManager().registerEvents(new ChunkTimeListener(activityTracker), this);
        safeRegisterListener(new ChunkActivityEventsListener(activityTracker));

        // military-diplomacy-design.md §2.2.1/§13 Фаза 4 — always-on, not
        // gated by any upgrade (the incident log itself is a base mechanic).
        Bukkit.getPluginManager().registerEvents(new com.frammy.unitylauncher.military.MilitaryIncidentListener(), this);

        bankInvoicesDao = new BankInvoicesDao(this);

        // --- websocket manager for the external launcher bridge ---
        webSocketManager = new WebSocketManager(getLogger());

        // --- BlueMap integration ---
        blueMapIntegration = new BlueMapIntegration(this, getLogger(), getDataFolder());

        // --- zone manager (claims / stores / regions / country borders) ---
        zoneManager = new ZoneManager(this, null, blueMapIntegration, activityTracker);
        // обратная ссылка — нужна ActivityTracker'у, чтобы приглушать вес визита
        // владельца/согражданина зоны в её же собственный трафик (см. computeVisitorWeight)
        activityTracker.setZoneManager(zoneManager);

        // --- веб-заявки на создание/редактирование зон с сайта ---
        com.frammy.unitylauncher.zones.web.ZoneWebRequestService zoneWebRequestService =
                new com.frammy.unitylauncher.zones.web.ZoneWebRequestService(this, zoneManager);
        zoneWebRequestService.start(100L); // 40 тиков ≈ 2 секунды

        // чистка "осиротевших" территорий Государств — страна могла быть удалена на
        // сайте (напр. выход последнего лидера, countryOperations.php) без уведомления
        // плагина; раз в 2 минуты сверяем COUNTRY-зоны со списком существующих стран
        zoneManager.startOrphanCountryZoneCleanup(2400L); // 2400 тиков ≈ 2 минуты

        // --- sign manager (shops, scroll text etc.) ---
        signManager = new SignManager(
                this,
                getDataFolder(),
                zoneManager,
                blueMapIntegration
        );
        zoneManager.setSignManager(signManager);
        getServer().getPluginManager().registerEvents(signManager, this);
        signManager.enable();

        // --- activity-based billing & overlap multipliers for zones ---
        zoneActivityCalculations = new ZoneActivityCalculations(zoneManager, this, bankInvoicesDao);

        // --- command registration & /ul help wiring ---
        HelpCommandManager helpManager = new HelpCommandManager();
        Unity unityCmd = new Unity(helpManager, webSocketManager, activityTracker, zoneManager);

        Objects.requireNonNull(getCommand("unityLauncher"))
                .setExecutor(unityCmd);
        Objects.requireNonNull(getCommand("unityLauncher"))
                .setTabCompleter(new CommandCompleter());
        Objects.requireNonNull(getCommand("login"))
                .setExecutor(unityCmd);
        Objects.requireNonNull(getCommand("register"))
                .setExecutor(unityCmd);

        // категории и помощь
        commandCategories.clear();

        /* ======================================================
         *  Авторизация
         * ====================================================== */
        commandCategories.add("Авторизация");
        helpManager.addCommand("/ul change", "Подсказка по смене пароля", "Авторизация");
        helpManager.addCommand("/ul change <старый> <новый>", "Сменить пароль аккаунта", "Авторизация");

        /* ======================================================
         *  Финансы
         * ====================================================== */
        commandCategories.add("Финансы");
        helpManager.addCommand("/ul balance", "Показать твой личный баланс", "Финансы");
        helpManager.addCommand("/ul zone price", "Посчитать дневную стоимость текущей зоны", "Финансы");


        /* ======================================================
         *  Уведомления
         * ====================================================== */
        commandCategories.add("Уведомления");
        helpManager.addCommand("/ul notifications", "Список уведомлений", "Уведомления");
        helpManager.addCommand("/ul notifications ON", "Включить уведомления", "Уведомления");
        helpManager.addCommand("/ul notifications OFF", "Выключить уведомления", "Уведомления");

        /* ======================================================
         *  Страна
         * ====================================================== */
        commandCategories.add("Страна");
        helpManager.addCommand("/ul country", "Информация о твоей стране", "Страна");
        helpManager.addCommand("/ul relations", "Международные отношения (скоро)", "Страна");

        /* ======================================================
         *  Зоны
         * ====================================================== */
        commandCategories.add("Зоны");
        helpManager.addCommand("/ul zone addcorner <тип>", "Добавить точку новой зоны", "Зоны");
        helpManager.addCommand("/ul zone removecorner", "Удалить последнюю точку контура", "Зоны");
        helpManager.addCommand("/ul zone build <тип> [название]", "Построить зону", "Зоны");
        helpManager.addCommand("/ul zone update corners +/-", "Расширить/сузить границы", "Зоны");
        helpManager.addCommand("/ul zone update name <новое>", "Переименовать зону", "Зоны");
        helpManager.addCommand("/ul zone update color R,G,B", "Изменить цвет зоны", "Зоны");
        helpManager.addCommand("/ul zone remove", "Запросить удаление зоны", "Зоны");
        helpManager.addCommand("/ul zone confirmremove", "Подтвердить удаление зоны", "Зоны");
        helpManager.addCommand("/ul zone cancelremove", "Отменить удаление зоны", "Зоны");


        /* ======================================================
         *  Админ
         * ====================================================== */
        commandCategories.add("Админ");
        helpManager.addCommand("/ul reload", "Перезагрузить конфиг, апгрейды, зоны", "Админ");
        helpManager.addCommand("/ul expo", "Экспортировать тепловую карту активностей", "Админ");
        helpManager.addCommand("/ul fsnap", "Принудительный подсчёт зоны и биллинг", "Админ");
        helpManager.addCommand("/ul blist", "Показать очередь биллинга зон", "Админ");
        helpManager.addCommand("/ul fpslink <url>", "Отправить ссылку в лаунчер", "Админ");

        // --- diplomacy / international relations (COUNTRIES table) ---
        countryRelationshipDao = new CountryRelationshipDao();
        diplomacy = new DiplomacyService(countryRelationshipDao);
        Bukkit.getScheduler().runTaskAsynchronously(this, diplomacy::loadAll);

        // --- countries registry / who leads which country / what country owns a player ---
        countryRegistryJdbc = new CountryRegistryJdbc(this);

        // --- lazy BlueMap load (restore saved markers etc. after world is ready) ---
        new LazyBlueMapLoader(this).scheduleLazyLoad();
        // --- BlueMap viewport heat overlay (regions json + client-side markers) ---
        this.blueMapHeatService = new com.frammy.unitylauncher.bluemapheat.BlueMapHeatService(this);
        this.blueMapHeatService.start();

        // --- tab prefix sync for LuckPerms/scoreboard ---
        TabPrefixService tabPrefixService = getTabPrefixService();
        Bukkit.getScheduler().runTaskLater(this, tabPrefixService::applyForAllOnlinePlayers, 40L);

        // --- PlaceholderAPI expansion for %unity_prefix% ---
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new com.frammy.unitylauncher.tab.UnityPrefixExpansion(this, countryRegistryJdbc).register();
        }

        // --- LuckPerms presence probe (diagnostic only) ---
        boolean lpOk;
        try {
            net.luckperms.api.LuckPerms ignored = net.luckperms.api.LuckPermsProvider.get();
            lpOk = true;
        } catch (Throwable t) {
            lpOk = false;
        }
        logInfo("LuckPerms present = " + lpOk);

        if (countryRegistryJdbc == null) {
            logWarn("countryRegistryJdbc == null (апгрейды не узнают страну)");
        } else {
            logInfo("countryRegistryJdbc OK");
        }
        if (zoneManager == null) {
            logWarn("ZoneManager == null (getZoneAt не сработает)");
        } else {
            logInfo("ZoneManager OK");
        }

        var weekly = new WeeklyCountryInvoiceService(
                this,
                countryRegistryJdbc,
                bankInvoicesDao,
                java.time.ZoneId.of("Europe/Riga"),
                0 // или твой serverUserId
        );
        weekly.start();

        // === upgrades (single entrypoint) ===
        upgradesManager = new UpgradesManager(this);

        try {
            LuckPerms lp = LuckPermsProvider.get();
            lp.getEventBus().subscribe(this, net.luckperms.api.event.group.GroupDataRecalculateEvent.class, e -> {
                String name = e.getGroup().getName();

                if (name.equalsIgnoreCase("default")) {
                    UpgradeCondition.invalidateDefaultNodeCache();
                } else {
                    // группа страны называется "country_<Countries.Id>", а кэш в UpgradeCondition
                    // ключуется по голому Id — снимаем префикс перед инвалидацией
                    String canonId = name.regionMatches(true, 0, "country_", 0, "country_".length())
                            ? name.substring("country_".length())
                            : name;
                    UpgradeCondition.invalidateCountryNodeCache(canonId);
                }
            });
        } catch (Throwable t) {
            getLogger().severe("[UL] LuckPerms not available, upgrade cache invalidation disabled: " + t.getMessage());
        }

        // --- zone billing scheduler (daily cost calc + auto-billing async logic) ---
        zoneActivityCalculations.startZoneBillingScheduler();

        // --- async ping DB for health logging ---
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection c = DBConnect()) {
                if (c == null) {
                    logError("DB ping: no connection (DBConnect() returned null)");
                } else {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                        if (rs.next()) logInfo("DB ping: OK");
                        else logWarn("DB ping: no result from SELECT 1");
                    }
                }
            } catch (Throwable t) {
                logError("DB ping failed", t);
            }
        });
        this.luckPermsPrefixService = new LuckPermsPrefixService(this);

        ZonesEconomyConfig zonesEconomyConfig = ZonesEconomyConfig.load(this);

        UserActivityJdbc userActivityJdbc = new UserActivityJdbc(this);
        this.dailyEconomyTask = new DailyEconomyTask(this, countryRegistryJdbc, userActivityJdbc, zonesEconomyConfig, bankInvoicesDao);
        this.dailyEconomyTask.start();

        // --- AUTH ---
        this.authService  = new AuthService(farLandsApi);
        this.authBossbars = new AuthBossbarManager(this);
        this.authListener = new AuthListener(this, this.authService, this.authBossbars);
        this.loginLimiter = new LoginRateLimiter();
        getServer().getPluginManager().registerEvents(this.authListener, this);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> authService.preloadAllAuth());

        // --- site-initiated money requests (transfers/invoice-pay/salary-claim from
        // farlands.in) — see infra/game-integration-architecture.md in the farlandsconnect repo
        new com.frammy.unitylauncher.auth.MoneyRequestPoller(this, farLandsApi, getLogger()).start(40L); // ~2s

        // --- site-initiated zone/country requests (create/edit/delete zones from
        // farlands.in) — HTTP counterpart of the MySQL-direct ZoneWebRequestService
        // below, both feed the same ZoneManager.handle(...)
        new com.frammy.unitylauncher.auth.ZoneRequestPoller(this, farLandsApi, zoneManager, getLogger()).start(40L); // ~2s

        // --- site-initiated password changes (farlands.in account settings) ---
        new com.frammy.unitylauncher.auth.PasswordChangeRequestPoller(this, farLandsApi, authService, getLogger()).start(40L); // ~2s

        // --- site-initiated upgrade purchases/toggles/choices (farlands.in
        // /upgrades) — carries the resulting LuckPerms state to the country's
        // group (or the player's own user) so UpgradeCondition actually sees
        // it. See infra/game-integration-architecture.md "апгрейды".
        new com.frammy.unitylauncher.auth.UpgradeGrantPoller(this, farLandsApi, getLogger()).start(40L); // ~2s

        // --- site-initiated country creation (farlands.in "Создать страну")
        // — mirrors the new country into this plugin's own Countries table +
        // LuckPerms group, without which it's invisible to ZoneManager/
        // UpgradeCondition entirely. See infra/game-integration-architecture.md.
        new com.frammy.unitylauncher.auth.CountryCreateRequestPoller(this, farLandsApi, getLogger()).start(40L); // ~2s

        // --- site-initiated country disband/settings edits (GH #10) — the
        // create poller above only ever covered creation; delete/rename/
        // prefix-change on the site never reached this plugin's own
        // Countries table at all until these two. ---
        new com.frammy.unitylauncher.auth.CountryDeleteRequestPoller(this, farLandsApi, getLogger()).start(40L); // ~2s
        new com.frammy.unitylauncher.auth.CountrySyncRequestPoller(this, farLandsApi, getLogger()).start(40L); // ~2s
        // GH #10 round 3: kick/leave never told the plugin either — a
        // departed player kept their LuckPerms country group/prefix forever.
        new com.frammy.unitylauncher.auth.CountryMemberLeaveRequestPoller(this, farLandsApi, getLogger()).start(40L); // ~2s

        // --- chunk activity heatmap reporting (feeds the site's /stats) ---
        new com.frammy.unitylauncher.chunkactivity.HeatmapReporter(this, farLandsApi, activityTracker).start(6000L); // 5min

        // --- Plan plugin stats mirror (playtime/mob kills/deaths, feeds the
        // site's /stats "Мой перформанс"/"Игроки" tabs) — no-ops quietly
        // until the Plan plugin is actually installed on the server.
        new com.frammy.unitylauncher.auth.PlanStatsReporter(this, farLandsApi, getLogger()).start(12000L); // 10min

        // --- military-diplomacy-design.md §13 Фаза 4: which country pairs
        // are currently at war (consumed by AttackSupportUpgrade etc.) ---
        warStatusCache = new com.frammy.unitylauncher.auth.WarStatusCache(this, farLandsApi);
        warStatusCache.start(200L); // ~10s — war status doesn't need second-level freshness

        // military-diplomacy-design.md §16/§13 Фаза 5 — must match the
        // backend's FRONTIER_REPORT_PERIOD_MS (lib/frontierPressure.ts).
        new com.frammy.unitylauncher.military.FrontierPresenceReporter(this, zoneManager, farLandsApi).start(1200L); // ~60s

        // --- server messages (join/quit/advancement phrases) ---
        this.serverMessagesListener = ServerMessagesListener.init(this);

        // --- Я тут втиснусь со своими ачивками, ок? ---
        AdvancementsManager advancementsManager = new AdvancementsManager(this);
        advancementsManager.init();

        // --- daily deal reset on day change ---
        DailyDealService dailyDealService = new DailyDealService(this);
        dailyDealService.runOnStartup();

        // регистрируем ВСЕ апгрейды ТОЛЬКО здесь, подряд:
        var brandCmd = new com.frammy.unitylauncher.upgrades.impl.BrandCommand(this);

        var b = getCommand("brand");
        if (b != null) {
            b.setExecutor(brandCmd);
            b.setTabCompleter(brandCmd);
        } else {
            getLogger().severe("Command 'brand' is missing in plugin.yml");
        }

        // Автоматическая регистрация всех апгрейдов
        registerAllUpgrades();
        upgradesManager.start();
        logInfo("UnityLauncher enabled!");
    }

    private @NotNull TabPrefixService getTabPrefixService() {
        TabPrefixService tabPrefixService =
                new TabPrefixService(this, this::computeTabPrefixFromCache);
        LuckPermsPrefixService lpPrefixService =
                new LuckPermsPrefixService(this);
        tabPrefixService.setLuckPermsPrefixService(lpPrefixService);
        return tabPrefixService;
    }


    @Override
    public void onDisable() {
        safeShutdown("BlueMapHeatService", blueMapHeatService, BlueMapHeatService::stop);
        safeShutdown("SignManager", signManager, m -> {
            m.saveSignData();
            logInfo("signData.yml saved (" + m.store().signs().size() + " signs)");
        });
        safeShutdown("ZoneManager", zoneManager, ZoneManager::saveZonesToConfig);
        safeShutdown("ActivityTracker", activityTracker, a -> {
            a.forceSampleNow();
            a.saveAllToDisk();
            a.stop();
        });
        safeShutdown("WebSocketManager", webSocketManager, WebSocketManager::disconnectAll);
        safeShutdown("DiplomacyService", diplomacy, d -> d.snapshot().keySet().forEach(d::save));
        safeShutdown("BlueMapIntegration", blueMapIntegration, b -> {
            b.saveAllBlueMapMarkersByPrefix("zones_");
            b.saveBlueMapMarkers("zones_signs");
            b.saveBlueMapMarkers("services");
            b.saveBlueMapMarkers("shops");
        });
        safeShutdown("DailyEconomyTask", dailyEconomyTask, DailyEconomyTask::stop);

        if (hikari != null) {
            try {
                hikari.close();
                logInfo("HikariCP pool closed");
            } catch (Throwable ignore) {}
            hikari = null;
        }

        safeShutdown("UpgradesManager", upgradesManager, UpgradesManager::stop);
        instance = null;
        logInfo("Plugin disabled successfully");
    }

    /* ===================== Player-related events ===================== */

    @EventHandler(ignoreCancelled = true)
    public void onChatGateAndFps(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String message = event.getMessage();

        // 1) Жёсткий гейт: если игрок в awaiting — блокируем чат
        if (awaitingCorrectCommand.contains(p.getUniqueId())) {
            // НО: разрешим fps://, чтобы не ломать твою фичу (или запрети тоже — на выбор)
            if (!message.startsWith("fps://")) {
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (p.isOnline()) {
                        p.sendMessage(ChatColor.RED +
                                "Ты не можешь отправлять сообщения, пока не укажешь границы магазина. Используй: /ul shop addcorner");
                    }
                });
                return;
            }
        }

        // 2) fps:// → кликабельное сообщение
        if (message.startsWith("fps://")) {
            event.setCancelled(true);

            TextComponent clickableMessage = new TextComponent("§a" + message);
            clickableMessage.setHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    new Text("§aОткрыть ссылку §7" + message)
            ));
            clickableMessage.setClickEvent(new ClickEvent(
                    ClickEvent.Action.RUN_COMMAND,
                    "/ul fpslink " + message
            ));

            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline()) p.spigot().sendMessage(clickableMessage);
            });
        }
    }

    // update action bar zone name only when player crosses block boundary
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        if (zoneManager != null) {
            zoneManager.checkPlayerZone(event.getPlayer());
        }

        // heat overlay: обновляем только при смене чанка
        if (blueMapHeatService != null) {
            int fromCx = event.getFrom().getBlockX() >> 4;
            int fromCz = event.getFrom().getBlockZ() >> 4;
            int toCx   = event.getTo().getBlockX() >> 4;
            int toCz   = event.getTo().getBlockZ() >> 4;

            if (fromCx != toCx || fromCz != toCz) {
                blueMapHeatService.onPlayerEnteredChunk(event.getPlayer(), toCx, toCz);
            }
        }
    }

    // register websocket session on join
    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        if (webSocketManager != null) {
            webSocketManager.connectPlayer(e.getPlayer().getName());
        }

        // прогреваем кэш LuckPerms сразу при заходе — чтобы веб-заявки не спотыкались на первой попытке
        try {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            lp.getUserManager().loadUser(e.getPlayer().getUniqueId());
        } catch (Throwable ignored) {}
    }

    /* ===================== Small helpers ===================== */

    // Логирование с единым префиксом
    private void logInfo(String msg) {
        getLogger().info("[UL] " + msg);
    }

    private void logWarn(String msg) {
        getLogger().warning("[UL] " + msg);
    }

    private void logError(String msg) {
        getLogger().severe("[UL] " + msg);
    }

    private void logError(String msg, Throwable t) {
        getLogger().severe("[UL] " + msg);
        if (t != null) t.printStackTrace();
    }

    // Безопасное выключение компонента
    private <T> void safeShutdown(String name, T component, CheckedConsumer<T> action) {
        if (component == null) return;
        try {
            action.accept(component);
            logInfo(name + " shutdown OK");
        } catch (Throwable t) {
            logError(name + " shutdown failed: " + t.getMessage());
            t.printStackTrace();
        }
    }

    @FunctionalInterface
    private interface CheckedConsumer<T> {
        void accept(T t) throws Throwable;
    }

    public String encodeLocation(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    /**
     * Возвращает {prefix, suffix} для TAB по UUID игрока.
     */
    public String[] computeTabPrefixFromCache(UUID uuid) {
        try {
            if (countryRegistryJdbc == null) {
                getLogger().warning("[TAB] countryRegistryJdbc == null");
                return new String[]{null, null};
            }

            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            String name = off.getName();
            if (name == null || name.isBlank()) {
                return new String[]{null, null};
            }

            String rolePrefixRaw = countryRegistryJdbc.getPlayerRolePrefix(name);

            String finalPrefix = null;
            if (rolePrefixRaw != null && !rolePrefixRaw.isBlank()) {
                finalPrefix = rolePrefixRaw.replace('&', '§').trim() + " ";
            }

            return new String[]{finalPrefix, null};

        } catch (Throwable t) {
            getLogger().warning("[TAB] Exception while computing prefix: " + t.getMessage());
            t.printStackTrace();
            return new String[]{null, null};
        }
    }

    /* ===================== DATABASE ===================== */

    @Nullable
    public static Connection DBConnect() {
        try {
            UnityLauncher inst = UnityLauncher.getInstance();
            if (inst != null && inst.getDataSource() != null) {
                return inst.getDataSource().getConnection();
            }

            Properties props = loadDbProps();
            String url  = props.getProperty("db.url");
            String user = props.getProperty("db.user");
            String pass = props.getProperty("db.password");

            if (url == null || user == null || pass == null) {
                Bukkit.getLogger().severe("[UL] db.properties: missing db.url/db.user/db.password");
                onError("DBError", null);
                return null;
            }

            if (DRIVER_LOADED.compareAndSet(false, true)) {
                Class.forName("com.mysql.cj.jdbc.Driver");
            }

            return DriverManager.getConnection(url, user, pass);

        } catch (Throwable t) {
            logDbException(t);
            onError("DBError", null);
            return null;
        }
    }

    /** Hikari pool init (called from onEnable). */
    private void initDataSource() {
        try {
            Properties props = loadDbProps();
            String url  = props.getProperty("db.url");
            String user = props.getProperty("db.user");
            String pass = props.getProperty("db.password");

            if (url == null || user == null || pass == null) {
                logError("db.properties: missing db.url/db.user/db.password — pool not initialized");
                return;
            }

            HikariConfig cfg = buildHikariConfig(url, user, pass);
            hikari = new HikariDataSource(cfg);
            logInfo("HikariCP initialized: " + cfg.getPoolName());
        } catch (Throwable t) {
            logError("Failed to initialize HikariCP", t);
        }
    }

    private static @NotNull HikariConfig buildHikariConfig(String url, String user, String pass) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(8000);
        cfg.setIdleTimeout(15_000);
        cfg.setMaxLifetime(25 * 60_000);
        // БД сейчас удалённая (Hostinger), а сервер хостится локально — сеть/БД
        // рвёт простаивающие соединения примерно через ~20с. Без keepalive
        // единственное "запасное" (minimumIdle=1) соединение просто умирает в
        // пуле и ловится только когда кто-то пытается им воспользоваться —
        // отсюда "No operations allowed after connection closed". Пингуем
        // каждые 10с, с запасом до дедлайна в ~20с.
        cfg.setKeepaliveTime(10_000);
        cfg.setPoolName("UnityLauncher-DBPool");
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return cfg;
    }

    private static Properties loadDbProps() throws Exception {
        Properties cached = DB_PROPS;
        if (cached != null) return cached;

        Properties props = new Properties();
        try (InputStream input = UnityLauncher.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (input == null) {
                Bukkit.getLogger().severe("[UL] db.properties not found in resources!");
                throw new IllegalStateException("db.properties not found");
            }
            props.load(input);
        }
        DB_PROPS = props;
        return props;
    }

    private static void logDbException(Throwable t) {
        Bukkit.getLogger().severe("[UL] DB connection error: " + t);
        if (t instanceof SQLException se) {
            while (se != null) {
                Bukkit.getLogger().severe("  SQLState=" + se.getSQLState() + " ErrorCode=" + se.getErrorCode()
                        + " Message=" + se.getMessage());
                se = se.getNextException();
            }
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        Bukkit.getLogger().severe(sw.toString());
    }

    public static long getAuthTtlMs() { return AUTH_TTL_MS; }
    public static int getAuthIter() { return AUTH_ITER; }
    public static int getAuthKeyLen() { return AUTH_KEY_LEN; }
    public static byte[] getAuthPepper() { return AUTH_PEPPER; }

    private void loadAuthSecrets() {
        try {
            var props = getProperties();

            String b64 = props.getProperty("auth.pepper.base64", "").trim();
            if (!b64.isEmpty()) AUTH_PEPPER = java.util.Base64.getDecoder().decode(b64);
            AUTH_ITER = Integer.parseInt(props.getProperty("auth.iter", "120000").trim());
            AUTH_KEY_LEN = Integer.parseInt(props.getProperty("auth.keyLen", "256").trim());
            AUTH_TTL_MS = Long.parseLong(props.getProperty("auth.ttlMs", "86400000").trim());

            getLogger().info("[Auth] secrets loaded: iter=" + AUTH_ITER + " keyLen=" + AUTH_KEY_LEN + " ttlMs=" + AUTH_TTL_MS);

            String apiBaseUrl = props.getProperty("backend.apiBaseUrl", "").trim();
            String apiToken = props.getProperty("backend.apiToken", "").trim();
            farLandsApi = new com.frammy.unitylauncher.auth.FarLandsApiClient(getLogger(), apiBaseUrl, apiToken);
            getLogger().info(farLandsApi.isEnabled()
                    ? "[FarLandsApi] enabled, target=" + apiBaseUrl
                    : "[FarLandsApi] disabled (set backend.apiBaseUrl / backend.apiToken in secrets.properties to enable)");
        } catch (Throwable t) {
            getLogger().warning("[Auth] failed to load secrets.properties: " + t);
            farLandsApi = new com.frammy.unitylauncher.auth.FarLandsApiClient(getLogger(), "", "");
        }
    }

    private @NotNull Properties getProperties() throws IOException {
        java.io.File f = new java.io.File(getDataFolder(), "secrets.properties");
        if (!f.exists()) {
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            try (var out = new PrintWriter(f, java.nio.charset.StandardCharsets.UTF_8)) {
                out.println("# UnityLauncher auth secrets");
                out.println("# auth.pepper.base64=       # <- заполни Base64-строкой");
                out.println("auth.iter=120000");
                out.println("auth.keyLen=256");
                out.println("auth.ttlMs=86400000");
                out.println();
                out.println("# farlandsconnect backend bridge (auth mirror) — leave empty to disable");
                out.println("# backend.apiBaseUrl=https://farlands.frammy.lat");
                out.println("# backend.apiToken=");
            }
        }

        var props = new Properties();
        try (var in = new java.io.FileInputStream(f)) { props.load(in); }
        return props;
    }

    /* ===================== User-facing error helper ===================== */

    public static void onError(String reason, @Nullable Player p) {
        String prefix = ChatColor.RED + "[UnityLauncher] ";

        switch (reason) {
            case "NotInBase":
                if (p != null) p.sendMessage(prefix + "Вас не существует в базе!");
                else Bukkit.getLogger().warning("[UnityLauncher] Игрок не найден в базе!");
                break;

            case "SignErr":
                if (p != null) p.sendMessage(prefix + "Ошибка при оплате по табличке!");
                else Bukkit.getLogger().warning("[UnityLauncher] Ошибка при оплате по табличке!");
                break;

            case "DBError":
                if (p != null) p.sendMessage(prefix + "Ошибка при соединении с базой!");
                Bukkit.getLogger().severe("[UnityLauncher] Ошибка соединения с базой данных!");
                break;

            default:
                Bukkit.getLogger().warning("[UnityLauncher] Неизвестная ошибка: " + reason);
                break;
        }
    }

    private void safeRegisterListener(Listener listener) {
        if (listener == null) {
            getLogger().severe("[UL] Attempted to register null listener");
            return;
        }
        getServer().getPluginManager().registerEvents(listener, this);
        getLogger().info("[UL] Registered listener: " + listener.getClass().getSimpleName()
                + " (" + listener.getClass().getName() + ")");
    }

    /**
     * Автоматическая регистрация всех апгрейдов из пакета impl
     */
    private void registerAllUpgrades() {
        try {
            // Массив всех классов апгрейдов (явный список, но в одном месте)
            Class<?>[] upgradeClasses = {
                AntiPhantomUpgrade.class, BrewSpeedUpgrade.class, ChurchUpgrade.class,
                CountryEffectsUpgrade.class, EcoFuelUpgrade.class, FarmlandProtectionUpgrade.class,
                FoodRationUpgrade.class, FurnaceHeatBoostUpgrade.class, FurnaceOreBonusUpgrade.class,
                GoldenFoodUpgrade.class, HopperSmartUpgrade.class, LivestockUpgrade.class,
                LoaderUpgrade.class, NetheriteAndBeaconUpgrade.class, RedstoneGatingUpgrade.class,
                OutpostUpgrade.class, RecyclerUpgrade.class, TntQuarryUpgrade.class,
                BloodGiftUpgrade.class, DietUpgrade.class, PsychSupportUpgrade.class,
                RegenPulseUpgrade.class, SafeZoneUpgrade.class, SanitaryZoneUpgrade.class,
                TriageUpgrade.class, CalmUpgrade.class, EducationUpgrade.class,
                ScrollsUpgrade.class, GardenerUpgrade.class, QuietGuardUpgrade.class,
                PondBedsUpgrade.class, QuietHourUpgrade.class, BenchesUpgrade.class,
                DepositInterestUpgrade.class, AtmFeesUpgrade.class, SafeDepositUpgrade.class,
                EnergySavingUpgrade.class, FestivalOfLightsUpgrade.class, TruePondsAndFlowerbedsUpgrade.class,
                ReturnOfTheSparkUpgrade.class, HolyAuraUpgrade.class, CitizenBenefitsUpgrade.class,
                // military-diplomacy-design.md §3.3/§13 Фаза 2 — не завязаны на войну
                DefensePatrolUpgrade.class, MilitaryHospitalRegenUpgrade.class,
                AttackSupportUpgrade.class, LogisticsUpgrade.class
            };

            int registered = 0;
            for (Class<?> clazz : upgradeClasses) {
                try {
                    Upgrade upgrade = (Upgrade) clazz.getDeclaredConstructor().newInstance();
                    upgradesManager.register(upgrade);
                    registered++;
                } catch (Exception e) {
                    logWarn("Failed to register " + clazz.getSimpleName() + ": " + e.getMessage());
                }
            }

            logInfo("Registered " + registered + "/" + upgradeClasses.length + " upgrades");
        } catch (Exception e) {
            logError("Failed to register upgrades", e);
        }
    }

}
