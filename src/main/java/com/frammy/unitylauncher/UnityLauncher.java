package com.frammy.unitylauncher;

import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ChunkActivityHeatmapExporter;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.tab.LuckPermsPrefixService;
import com.frammy.unitylauncher.tab.TabDatabaseSync;
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

    public DiplomacyService diplomacy;
    public CountryRegistryJdbc countryRegistryJdbc;
    public CountryRelationshipDao countryRelationshipDao;

    // DB pool
    private HikariDataSource hikari;

    // expose some stuff
    public BlueMapIntegration getBlueMapIntegration() { return blueMapIntegration; }
    public SignManager getSignManager() { return signManager; }
    public ZoneManager getZoneManager() { return zoneManager; }
    public ZoneActivityCalculations getZoneActivityCalculations() { return zoneActivityCalculations; }
    public DataSource getDataSource() { return hikari; }
    public Set<Player> getAwaitingCorrectCommand() { return awaitingCorrectCommand; }

    // cached db.properties + driver status
    private static volatile Properties DB_PROPS;
    private static final AtomicBoolean DRIVER_LOADED = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        instance = this;

        // --- register self as listener ---
        Bukkit.getPluginManager().registerEvents(this, this);

        // --- money / balance accounting ---
        moneyManager = new MoneyManager(getDataFolder(), "unity_launcher");
        getServer().getPluginManager().registerEvents(moneyManager, this);

        // --- activity tracker (chunk activity sampling) ---
        tracker = new ActivityTracker(this);

        // --- websocket manager for the external launcher bridge ---
        // uses constructor (Plugin plugin, Logger logger, String wsUrl) indirectly via convenience ctor
        webSocketManager = new WebSocketManager(getLogger());

        // --- BlueMap markers / heatmap layer integration ---
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

        // --- activity-based billing & overlap multipliers for zones ---
        zoneActivityCalculations = new ZoneActivityCalculations(zoneManager);

        // --- command registration & /ul help wiring ---
        HelpCommandManager helpManager = new HelpCommandManager();
        Objects.requireNonNull(getCommand("unityLauncher"))
                .setExecutor(new Unity(helpManager, webSocketManager, tracker, zoneManager));
        Objects.requireNonNull(getCommand("unityLauncher"))
                .setTabCompleter(new CommandCompleter());

        commandCategories.add("Авторизация");
        commandCategories.add("Финансы");
        commandCategories.add("Уведомления");
        commandCategories.add("Страна");

        helpManager.addCommand("/ul rcode", "Получение кода регистрации", "Авторизация");
        helpManager.addCommand("/ul balance", "Показывает твой баланс", "Финансы");
        helpManager.addCommand("/ul top", "Показывает ТОП игроков по заданной категории", "Финансы");
        helpManager.addCommand("/ul change", "Смена пароля", "Авторизация");
        helpManager.addCommand("/ul notifications", "Просмотр полученных уведомлений", "Уведомления");
        helpManager.addCommand("/ul notifications ON/OFF", "Включение/выключение уведомлений", "Уведомления");
        helpManager.addCommand("/ul country", "Информация о твоей стране", "Страна");
        helpManager.addCommand("/ul pay ИГРОК СУММА", "Отправление средств игроку", "Финансы");
        helpManager.addCommand("/ul cb ADD/WITHDRAW СУММА", "Управление балансом страны. Снять деньги может только глава государства", "Страна");
        helpManager.addCommand("/ul daydeal КОД", "Завершить ежедневный квест", "Финансы");
        helpManager.addCommand("/ul group LIST/SET/PREFIX", "Настраивает группы прав для государства", "Страна");
        helpManager.addCommand("/ul shop create НАЗВАНИЕ", "Создание торговой точки", "Финансы");

        // --- diplomacy / international relations (COUNTRIES table) ---
        countryRelationshipDao = new CountryRelationshipDao();
        diplomacy = new DiplomacyService(countryRelationshipDao);
        Bukkit.getScheduler().runTaskAsynchronously(this, diplomacy::loadAll);

        // --- countries registry / who leads which country / what country owns a player ---
        // new CountryRegistryJdbc is lightweight (sync lookups from Countries table)
        countryRegistryJdbc = new CountryRegistryJdbc(this);

        // --- lazy BlueMap load (restore saved markers etc. after world is ready) ---
        new LazyBlueMapLoader(this).scheduleLazyLoad();

        // --- export heatmap layer for BlueMap after startup ---
        Bukkit.getScheduler().runTaskLater(this, () ->
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    try {
                        var stats   = tracker.getChunkStatsMap();
                        var weights = tracker.getWeights();
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

        // применить префиксы всем уже онлайн после старта
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

        zoneManager.loadZonesFromConfig();
        getLogger().info("[UL] Zones loaded: " + zoneManager.getZones().size());

        // --- upgrades listener (chunk upgrades / ATM / etc.) ---
        UpgradesListener.registerAll(this);

        // РЕГИСТРАЦИЯ КОМАНДЫ /brand
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

        getLogger().info("UnityLauncher enabled!");
    }

    private @NotNull TabPrefixService getTabPrefixService() {
        TabDatabaseSync tabDb = new TabDatabaseSync(getDataSource(), "main", "world", this);
        TabPrefixService tabPrefixService =
                new TabPrefixService(this, this::computeTabPrefixFromCache, tabDb);
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
            diplomacy.snapshot().keySet().forEach(diplomacy::save);
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] diplomacy save failed: " + t.getMessage());
        }

        // persist BlueMap markers
        try {
            if (blueMapIntegration != null) {
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
     *
     * Логика сейчас минималистичная:
     * - узнаём ник по UUID;
     * - спрашиваем страну у countryRegistryJdbc.getCountryOfPlayer(name) (синхронно по кэшу/БД Countries);
     * - если страна известна → §7[<Страна>] §r;
     * - иначе null.
     *
     * suffix пока не используется.
     */
    public String[] computeTabPrefixFromCache(UUID uuid) {
        try {
            if (countryRegistryJdbc == null) {
                getLogger().warning("[TAB] countryRegistryJdbc == null");
                return new String[]{null, null};
            }

            // ник игрока
            OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
            String name = off.getName();
            if (name == null || name.isBlank()) {
                return new String[]{null, null};
            }

            // берём чисто префикс роли (например "§o§d❉Президент")
            String rolePrefixRaw = countryRegistryJdbc.getPlayerRolePrefix(name);

            String finalPrefix = null;
            if (rolePrefixRaw != null && !rolePrefixRaw.isBlank()) {
                // нормализуем & -> § на всякий случай
                finalPrefix = rolePrefixRaw.replace('&', '§').trim() + " ";
            }

            // возвращаем {prefix, suffix}, суффикс нам не нужен
            return new String[]{finalPrefix, null};

        } catch (Throwable t) {
            getLogger().warning("[TAB] Exception while computing prefix: " + t.getMessage());
            t.printStackTrace();
            return new String[]{null, null};
        }
    }

    /* ===================== DATABASE ===================== */

    /**
     * Глобальная точка подключения к БД.
     * 1) если Hikari уже инициализирован — берём connect из пула;
     * 2) иначе fallback на DriverManager (db.properties).
     */
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

    /** кэшированная загрузка db.properties из resources */
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
}
