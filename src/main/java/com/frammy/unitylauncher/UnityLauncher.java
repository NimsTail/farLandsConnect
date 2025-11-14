package com.frammy.unitylauncher;

import com.frammy.unitylauncher.auth.AuthBossbarManager;
import com.frammy.unitylauncher.auth.AuthListener;
import com.frammy.unitylauncher.auth.AuthService;
import com.frammy.unitylauncher.auth.LoginRateLimiter;
import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ActivityWeights;
import com.frammy.unitylauncher.chunkactivity.ChunkActivityHeatmapExporter;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.tab.LuckPermsPrefixService;
import com.frammy.unitylauncher.tab.TabPrefixService;
import com.frammy.unitylauncher.upgrades.BrandCommand;
import com.frammy.unitylauncher.upgrades.UpgradesListener;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UnityLauncher extends JavaPlugin implements Listener {

    // === singleton ===
    private static UnityLauncher instance;
    public static UnityLauncher getInstance() { return instance; }

    // === managers / services ===
    private final Set<Player> awaitingCorrectCommand = new HashSet<>();
    public final List<String> commandCategories = new ArrayList<>();

    public MoneyManager moneyManager;
    private ZoneManager zoneManager;
    public ZoneActivityCalculations zoneActivityCalculations;
    private SignManager signManager;
    private ActivityTracker tracker;
    private WebSocketManager webSocketManager;
    private BlueMapIntegration blueMapIntegration;
    private LoginRateLimiter loginLimiter;

    public DiplomacyService diplomacy;
    public CountryRegistryJdbc countryRegistryJdbc;
    public CountryRelationshipDao countryRelationshipDao;

    public AuthService authService;
    public AuthListener authListener;
    public AuthBossbarManager authBossbars;

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
    public Set<Player> getAwaitingCorrectCommand() { return awaitingCorrectCommand; }
    public CountryRegistryJdbc getCountryRegistryJdbc() { return countryRegistryJdbc; }
    public AuthListener getAuthListener() { return authListener; }
    public AuthService getAuthService() { return authService; }
    public LoginRateLimiter getLoginLimiter() { return loginLimiter; }

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

        // --- базовые листенеры (сам плагин) ---
        Bukkit.getPluginManager().registerEvents(this, this);

        // --- money / balance accounting ---
        moneyManager = new MoneyManager(getDataFolder(), "unity_launcher");
        getServer().getPluginManager().registerEvents(moneyManager, this);

        // --- activity tracker (chunk activity sampling) ---
        tracker = new ActivityTracker(this);

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
                blueMapIntegration,
                UnityCommands.getInstance()
        );
        zoneManager.setSignManager(signManager);
        getServer().getPluginManager().registerEvents(signManager, this);

        // сразу поднимем signData.yml (это не тяжело, не зависит от BlueMap)
        try {
            signManager.loadSignData();
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] signManager.loadSignData() failed on enable: " + t.getMessage());
            t.printStackTrace();
        }

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

// ---------- Авторизация ----------
        commandCategories.add("Авторизация");
        helpManager.addCommand("/ul change", "Подсказка по смене пароля", "Авторизация");
        helpManager.addCommand("/ul change <старый> <новый>", "Сменить пароль аккаунта", "Авторизация");

// ---------- Финансы ----------
        commandCategories.add("Финансы");
        helpManager.addCommand("/ul balance", "Показать твой личный баланс", "Финансы");
        helpManager.addCommand("/ul zone price", "Посчитать дневную стоимость текущей зоны", "Финансы");

// ---------- Уведомления ----------
        commandCategories.add("Уведомления");
        helpManager.addCommand("/ul notifications", "Просмотр полученных уведомлений", "Уведомления");
        helpManager.addCommand("/ul notifications ON", "Включить уведомления", "Уведомления");
        helpManager.addCommand("/ul notifications OFF", "Выключить уведомления", "Уведомления");

// ---------- Страна ----------
        commandCategories.add("Страна");
        helpManager.addCommand("/ul country", "Информация о твоей стране", "Страна");

// ---------- Зоны ----------
        commandCategories.add("Зоны");
        helpManager.addCommand("/ul zone addcorner <тип>", "Добавить точку контура новой зоны", "Зоны");
        helpManager.addCommand("/ul zone removecorner", "Удалить последнюю точку контура", "Зоны");
        helpManager.addCommand("/ul zone build <тип> [название]", "Построить зону по выставленным точкам", "Зоны");
        helpManager.addCommand("/ul zone update corners +/-", "Расширить или сузить границы зоны", "Зоны");
        helpManager.addCommand("/ul zone update name <новое_имя>", "Переименовать зону", "Зоны");
        helpManager.addCommand("/ul zone update color R,G,B", "Изменить цвет зоны на карте", "Зоны");
        helpManager.addCommand("/ul zone remove", "Запросить удаление текущей зоны", "Зоны");
        helpManager.addCommand("/ul zone confirmremove", "Подтвердить удаление зоны", "Зоны");
        helpManager.addCommand("/ul zone cancelremove", "Отменить удаление зоны", "Зоны");

// ---------- Админ / отладка ----------
        commandCategories.add("Админ");
        helpManager.addCommand("/ul reload", "Перезагрузить конфиг, апгрейды и зоны", "Админ");
        helpManager.addCommand("/ul expo", "Экспортировать тепловую карту активности чанков в BlueMap", "Админ");
        helpManager.addCommand("/ul fsnap", "Принудительно сделать снимок активности и начислить биллинг зон", "Админ");
        helpManager.addCommand("/ul blist", "Показать список зон по очереди биллинга", "Админ");
        helpManager.addCommand("/ul fpslink <url>", "Отправить ссылку в подключённое приложение", "Админ");

        // --- diplomacy / international relations (COUNTRIES table) ---
        countryRelationshipDao = new CountryRelationshipDao();
        diplomacy = new DiplomacyService(countryRelationshipDao);
        Bukkit.getScheduler().runTaskAsynchronously(this, diplomacy::loadAll);

        // --- countries registry / who leads which country / what country owns a player ---
        countryRegistryJdbc = new CountryRegistryJdbc(this);

        // --- lazy BlueMap load (restore saved markers etc. after world is ready) ---
        new LazyBlueMapLoader(this).scheduleLazyLoad();

        // --- export heatmap layer for BlueMap after startup ---
        Bukkit.getScheduler().runTaskLater(this, () ->
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        var stats   = tracker.getChunkStatsMap();
                        ActivityWeights weights = new ActivityWeights();
                        Bukkit.getScheduler().runTask(this, () ->
                                ChunkActivityHeatmapExporter.exportHeatmapToBlueMapLayer(stats, "world", weights)
                        );
                    } catch (Throwable t) {
                        getLogger().severe("Ошибка экспорта тепловой карты: " + t.getMessage());
                        t.printStackTrace();
                    }
                }), 40L);

        // --- init DB pool early so DBConnect() uses hikari ---
        initDataSource();

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

        // --- main config + upgrades + zones ---
        saveDefaultConfig();
        UpgradesListener.registerAll(this);
        zoneManager.loadZonesFromConfig();
        getLogger().info("[UL] Zones loaded: " + zoneManager.getZones().size());

        // --- /brand команда ---
        BrandCommand brandCmd = new BrandCommand();
        Objects.requireNonNull(getCommand("brand"), "command 'brand' not found in plugin.yml")
                .setExecutor(brandCmd);
        Objects.requireNonNull(getCommand("brand"), "command 'brand' not found in plugin.yml")
                .setTabCompleter(brandCmd);

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

        // --- AUTH ---
        this.authService  = new AuthService();
        this.authBossbars = new AuthBossbarManager(this);
        this.authListener = new AuthListener(this, this.authService, this.authBossbars);
        this.loginLimiter = new LoginRateLimiter();
        getServer().getPluginManager().registerEvents(this.authListener, this);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> authService.preloadAllAuth());

        // --- server messages (join/quit/advancement phrases) ---
        ServerMessagesListener.init(this);

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

        // save sign data (shops etc.)
        try {
            if (signManager != null) {
                signManager.saveSignData();
            } else {
                getLogger().warning("[UnityLauncher] signManager is null on disable — skipping saveSignData()");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] saveSignData() failed: " + t.getMessage());
        }

        // save zones.yml
        try {
            if (zoneManager != null) {
                zoneManager.saveZonesToConfig();
            } else {
                getLogger().warning("[UnityLauncher] zoneManager is null on disable — skipping saveZonesToConfig()");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] saveZonesToConfig() failed: " + t.getMessage());
        }

        // persist chunk activity to disk
        try {
            if (tracker != null) {
                tracker.forceSampleNow();
                tracker.saveAllToDisk();
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] tracker save failed: " + t.getMessage());
        }

        // close websocket sessions
        try {
            if (webSocketManager != null) {
                webSocketManager.disconnectAll();
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] webSocketManager disconnect failed: " + t.getMessage());
        }

        // save diplomacy state
        try {
            if (diplomacy != null) {
                diplomacy.snapshot().keySet().forEach(diplomacy::save);
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] diplomacy save failed: " + t.getMessage());
        }

        // persist BlueMap markers
        try {
            if (blueMapIntegration != null) {
                // наборы, которыми ты уже пользуешься
                blueMapIntegration.saveBlueMapMarkers("zones_shop");
                blueMapIntegration.saveBlueMapMarkers("services");
                blueMapIntegration.saveBlueMapMarkers("shops");
                blueMapIntegration.saveBlueMapMarkers("chunk-activity");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] blueMapIntegration save failed: " + t.getMessage());
        }

        // shutdown DB pool
        if (hikari != null) {
            try { hikari.close(); } catch (Throwable ignore) {}
            hikari = null;
        }

        instance = null;
        getLogger().info("UnityLauncher disabled.");

        UpgradesListener.unregisterAll(this);
    }

    /* ===================== Player-related events ===================== */

    // make fps:// URLs clickable instead of sending them raw
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage();
        if (message.startsWith("fps://")) {
            event.setCancelled(true);

            TextComponent clickableMessage = new TextComponent("§a" + message);
            clickableMessage.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("§aОткрыть ссылку §7" + message)));
            clickableMessage.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ul fpslink " + message));

            event.getPlayer().spigot().sendMessage(clickableMessage);
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
    }

    // block chat if player is forced to finish /ul shop flow first
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (awaitingCorrectCommand.contains(player)) {
            player.sendMessage(ChatColor.RED + "Ты не можешь отправлять сообщения, пока не укажешь границы магазина. Используй: /ul shop addcorner");
            event.setCancelled(true);
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
        awaitingCorrectCommand.add(player);
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

    private void safeRegisterListener(String name, Listener listener) {
        if (listener == null) {
            getLogger().severe("[UL] Attempted to register null listener: " + name);
            return;
        }
        getServer().getPluginManager().registerEvents(listener, this);
        getLogger().info("[UL] Registered listener: " + name + " (" + listener.getClass().getName() + ")");
    }
}
