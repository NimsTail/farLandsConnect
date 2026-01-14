package com.frammy.unitylauncher;

import com.frammy.unitylauncher.auth.AuthBossbarManager;
import com.frammy.unitylauncher.auth.AuthListener;
import com.frammy.unitylauncher.auth.AuthService;
import com.frammy.unitylauncher.auth.LoginRateLimiter;
import com.frammy.unitylauncher.bluemapheat.BlueMapHeatService;
import com.frammy.unitylauncher.chunkactivity.*;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.tab.LuckPermsPrefixService;
import com.frammy.unitylauncher.tab.TabPrefixService;
import com.frammy.unitylauncher.upgrades.core.UpgradesManager;
import com.frammy.unitylauncher.upgrades.impl.*;
import com.frammy.unitylauncher.zones.ZoneActivityCalculations;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import com.frammy.unitylauncher.zones.countryrelations.CountryRelationshipDao;
import com.frammy.unitylauncher.zones.countryrelations.DiplomacyService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
    private ActivityTracker tracker;
    private WebSocketManager webSocketManager;
    private BlueMapIntegration blueMapIntegration;
    private LoginRateLimiter loginLimiter;
    public BlueMapHeatService blueMapHeatService;

    public DiplomacyService diplomacy;
    public CountryRegistryJdbc countryRegistryJdbc;
    public CountryRelationshipDao countryRelationshipDao;

    public AuthService authService;
    public AuthListener authListener;
    public AuthBossbarManager authBossbars;
    private ZonesEconomyConfig zonesEconomyConfig;
    private DailyEconomyTask dailyEconomyTask;
    private UpgradesManager upgradesManager;

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
    public ActivityTracker getActivityTracker() { return tracker; }
    public MoneyManager getMoneyManager() { return moneyManager; }

    // cached db.properties + driver status
    private static volatile Properties DB_PROPS;
    private static final AtomicBoolean DRIVER_LOADED = new AtomicBoolean(false);

    // --- AUTH config defaults (можно переопределить из secrets.properties) ---
    private static long AUTH_TTL_MS = 24L * 60 * 60 * 1000; // 24h
    private static int AUTH_ITER = 120_000;
    private static int AUTH_KEY_LEN = 256;
    private static byte[] AUTH_PEPPER = new byte[0];

    @Override
    public void onEnable() {
        instance = this;
        loadAuthSecrets();

        // --- init DB pool как можно раньше ---
        initDataSource();

        // --- базовые листенеры (сам плагин) ---
        Bukkit.getPluginManager().registerEvents(this, this);

        // --- money / balance accounting ---
        moneyManager = new MoneyManager(this);
        getServer().getPluginManager().registerEvents(moneyManager, this);

        // --- activity tracker (chunk activity sampling) ---
        tracker = new ActivityTracker(this);
        // --- events → ActivityTracker (blocks, items, mobs, tick load, players) ---
        safeRegisterListener(
                new ChunkActivityEventsListener(this, tracker));

        // --- websocket manager for the external launcher bridge ---
        webSocketManager = new WebSocketManager(getLogger());

        // --- BlueMap integration ---
        blueMapIntegration = new BlueMapIntegration(this, getLogger(), getDataFolder());

        // --- zone manager (claims / stores / regions / country borders) ---
        zoneManager = new ZoneManager(this, null, blueMapIntegration, tracker);

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
        zoneActivityCalculations = new ZoneActivityCalculations(zoneManager);

        // --- command registration & /ul help wiring ---
        HelpCommandManager helpManager = new HelpCommandManager();
        Unity unityCmd = new Unity(helpManager, webSocketManager, tracker, zoneManager);

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
        getLogger().info("[UL] LuckPerms present = " + lpOk);

        if (countryRegistryJdbc == null) {
            getLogger().warning("[UL] countryRegistryJdbc == null (апгрейды не узнают страну)");
        } else {
            getLogger().info("[UL] countryRegistryJdbc OK");
        }
        if (zoneManager == null) {
            getLogger().warning("[UL] ZoneManager == null (getZoneAt не сработает)");
        } else {
            getLogger().info("[UL] ZoneManager OK");
        }

        // === upgrades (single entrypoint) ===
        upgradesManager = new UpgradesManager(this);

        // --- zone billing scheduler (daily cost calc + auto-billing async logic) ---
        zoneActivityCalculations.startZoneBillingScheduler();

        // --- async ping DB for health logging ---
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection c = DBConnect()) {
                if (c == null) {
                    Bukkit.getLogger().severe("[UnityLauncher] DB ping: нет подключения (DBConnect() вернул null)");
                } else {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                        if (rs.next()) Bukkit.getLogger().info("[UnityLauncher] DB ping: OK");
                        else Bukkit.getLogger().warning("[UnityLauncher] DB ping: нет результата SELECT 1");
                    }
                }
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[UnityLauncher] DB ping: ошибка — " + t.getMessage());
                logDbException(t);
            }
        });
        this.luckPermsPrefixService = new LuckPermsPrefixService(this);

        this.zonesEconomyConfig = ZonesEconomyConfig.load(this);

        UserActivityJdbc userActivityJdbc = new UserActivityJdbc(this);
        this.dailyEconomyTask = new DailyEconomyTask(this, countryRegistryJdbc, userActivityJdbc, zonesEconomyConfig);
        this.dailyEconomyTask.start();

        // --- AUTH ---
        this.authService  = new AuthService();
        this.authBossbars = new AuthBossbarManager(this);
        this.authListener = new AuthListener(this, this.authService, this.authBossbars);
        this.loginLimiter = new LoginRateLimiter();
        getServer().getPluginManager().registerEvents(this.authListener, this);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> authService.preloadAllAuth());

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

        upgradesManager.register(new AntiPhantomUpgrade());
        upgradesManager.register(new BrewSpeedUpgrade());
        upgradesManager.register(new ChurchUpgrade());
        upgradesManager.register(new CountryEffectsUpgrade());

        upgradesManager.register(new EcoFuelUpgrade());
        upgradesManager.register(new FarmlandProtectionUpgrade());
        upgradesManager.register(new FoodRationUpgrade());
        upgradesManager.register(new FurnaceHeatBoostUpgrade());

        upgradesManager.register(new FurnaceOreBonusUpgrade());
        upgradesManager.register(new GoldenFoodUpgrade());
        upgradesManager.register(new HopperSmartUpgrade());
        upgradesManager.register(new LivestockUpgrade());
        upgradesManager.register(new LoaderUpgrade());

        upgradesManager.register(new NetheriteAndBeaconUpgrade());
        upgradesManager.register(new RedstoneGatingUpgrade());

        upgradesManager.register(new OutpostUpgrade());
        upgradesManager.register(new RecyclerUpgrade());
        upgradesManager.register(new TntQuarryUpgrade());

        upgradesManager.register(new BloodGiftUpgrade());
        upgradesManager.register(new DietUpgrade());
        upgradesManager.register(new PsychSupportUpgrade());
        upgradesManager.register(new RegenPulseUpgrade());
        upgradesManager.register(new SafeZoneUpgrade());
        upgradesManager.register(new SanitaryZoneUpgrade());
        upgradesManager.register(new TriageUpgrade());

        upgradesManager.register(new CalmUpgrade());
        upgradesManager.register(new EducationUpgrade());
        upgradesManager.register(new ScrollsUpgrade());

        upgradesManager.register(new GardenerUpgrade());
        upgradesManager.register(new QuietGuardUpgrade());
        upgradesManager.register(new PondBedsUpgrade());
        upgradesManager.register(new QuietHourUpgrade());
        upgradesManager.register(new BenchesUpgrade());

        upgradesManager.register(new DepositInterestUpgrade());
        upgradesManager.register(new AtmFeesUpgrade());
        upgradesManager.register(new SafeDepositUpgrade());

        upgradesManager.start();
        getLogger().info("UnityLauncher enabled!");
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

        try {
            if (blueMapHeatService != null) blueMapHeatService.stop();
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] blueMapHeatService.stop() failed: " + t.getMessage());
        }

        // --- save sign data (shops etc.) ---
        try {
            if (signManager != null) {
                signManager.saveSignData();
                getLogger().info("[UnityLauncher] signData.yml сохранён ("
                        + signManager.store().signs().size()
                        + " табличек)");
            } else {
                getLogger().warning("[UnityLauncher] signManager is null on disable — skipping saveSignData()");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] saveSignData() failed: " + t.getMessage());
            t.printStackTrace();
        }

        // --- save zones.yml ---
        try {
            if (zoneManager != null) {
                zoneManager.saveZonesToConfig();
            } else {
                getLogger().warning("[UnityLauncher] zoneManager is null on disable — skipping saveZonesToConfig()");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] saveZonesToConfig() failed: " + t.getMessage());
            t.printStackTrace();
        }

        // --- persist chunk activity to disk ---
        try {
            if (tracker != null) {
                tracker.forceSampleNow();
                tracker.saveAllToDisk();
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] tracker save failed: " + t.getMessage());
            t.printStackTrace();
        }

        // --- close websocket sessions ---
        try {
            if (webSocketManager != null) {
                webSocketManager.disconnectAll();
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] webSocketManager disconnect failed: " + t.getMessage());
            t.printStackTrace();
        }

        // --- save diplomacy state ---
        try {
            if (diplomacy != null) {
                diplomacy.snapshot().keySet().forEach(diplomacy::save);
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] diplomacy save failed: " + t.getMessage());
            t.printStackTrace();
        }

        // --- persist BlueMap markers ---
        try {
            if (blueMapIntegration != null) {
                blueMapIntegration.saveAllBlueMapMarkersByPrefix("zones_");
                blueMapIntegration.saveBlueMapMarkers("zones_signs");
                blueMapIntegration.saveBlueMapMarkers("services");
                blueMapIntegration.saveBlueMapMarkers("shops");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] blueMapIntegration save failed: " + t.getMessage());
            t.printStackTrace();
        }

        // --- stop daily economy task, но без падения onDisable ---
        try {
            if (dailyEconomyTask != null) dailyEconomyTask.stop();
        } catch (Exception e) {
            getLogger().warning("[UnityLauncher] dailyEconomyTask.stop() failed: " + e.getMessage());
            e.printStackTrace();
        }

        // --- shutdown DB pool ---
        if (hikari != null) {
            try { hikari.close(); } catch (Throwable ignore) {}
            hikari = null;
        }

        try {
            if (upgradesManager != null) upgradesManager.stop();
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] upgradesManager.stop() failed: " + t.getMessage());
            t.printStackTrace();
        }

        instance = null;
        getLogger().info("UnityLauncher disabled.");

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
    }

    /* ===================== Small helpers ===================== */

    public String encodeLocation(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    public void addPlayerToWaitList(Player player) {
        if (player != null) awaitingCorrectCommand.add(player.getUniqueId());
    }

    public void removePlayerFromWaitList(Player player) {
        if (player != null) awaitingCorrectCommand.remove(player.getUniqueId());
    }

    public boolean isPlayerInWaitList(Player player) {
        return player != null && awaitingCorrectCommand.contains(player.getUniqueId());
    }

    public int getMaxBaseLength(Collection<String> values) {
        return values.stream().mapToInt(String::length).max().orElse(1);
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

    public ZonesEconomyConfig getZonesEconomyConfig() {
        return zonesEconomyConfig;
    }

    public CountryRegistryJdbc getCountryRegistry() {
        return countryRegistryJdbc;
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
                Bukkit.getLogger().severe("[UnityLauncher] db.properties: отсутствуют ключи db.url/db.user/db.password");
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
                getLogger().severe("[UnityLauncher] db.properties: отсутствуют db.url/db.user/db.password — пул не инициализирован.");
                return;
            }

            HikariConfig cfg = buildHikariConfig(url, user, pass);
            hikari = new HikariDataSource(cfg);
            getLogger().info("HikariCP инициализирован: " + cfg.getPoolName());
        } catch (Throwable t) {
            getLogger().severe("[UnityLauncher] Не удалось инициализировать HikariCP: " + t.getMessage());
            logDbException(t);
        }
    }

    private static @NotNull HikariConfig buildHikariConfig(String url, String user, String pass) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);

        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(8000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(10 * 60_000);
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
                Bukkit.getLogger().severe("[UnityLauncher] db.properties not found in resources!");
                throw new IllegalStateException("db.properties not found");
            }
            props.load(input);
        }
        DB_PROPS = props;
        return props;
    }

    private static void logDbException(Throwable t) {
        Bukkit.getLogger().severe("[UnityLauncher] Ошибка при подключении к БД: " + t);
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
        } catch (Throwable t) {
            getLogger().warning("[Auth] failed to load secrets.properties: " + t);
        }
    }

    private @NotNull Properties getProperties() throws IOException {
        java.io.File f = new java.io.File(getDataFolder(), "secrets.properties");
        if (!f.exists()) {
            f.getParentFile().mkdirs();
            try (var out = new PrintWriter(f, java.nio.charset.StandardCharsets.UTF_8)) {
                out.println("# UnityLauncher auth secrets");
                out.println("# auth.pepper.base64=       # <- заполни Base64-строкой");
                out.println("auth.iter=120000");
                out.println("auth.keyLen=256");
                out.println("auth.ttlMs=86400000");
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

}
