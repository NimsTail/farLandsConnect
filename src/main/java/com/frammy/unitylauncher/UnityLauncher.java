package com.frammy.unitylauncher;

import com.frammy.unitylauncher.chunkactivity.ActivityTracker;
import com.frammy.unitylauncher.chunkactivity.ChunkActivityHeatmapExporter;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.zones.ZoneActivityCalculations;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.upgrades.UpgradesListener;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import com.frammy.unitylauncher.zones.countryrelations.CountryRelationshipDao;
import com.frammy.unitylauncher.zones.countryrelations.DiplomacyService;
import de.bluecolored.bluemap.api.BlueMapAPI;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UnityLauncher extends JavaPlugin implements Listener {
    private static UnityLauncher instance;
    private final Set<Player> awaitingCorrectCommand = new HashSet<>();
    public ArrayList<String> commandCategories= new ArrayList<>();
    public MoneyManager moneyManager;
    private ZoneManager zoneManager;
    public ZoneActivityCalculations zoneActivityCalculations;
    private SignManager signManager;
    private ActivityTracker tracker;
    private WebSocketManager webSocketManager;
    private BlueMapIntegration blueMapIntegration;
    public DiplomacyService diplomacy;
    public CountryRegistryJdbc countryRegistryJdbc;
    public Set<Player> getAwaitingCorrectCommand() {return awaitingCorrectCommand;}

    // ★ добавлено: кэш для db.properties и разовая инициализация драйвера
    private static volatile Properties DB_PROPS;                 // кэш пропертей
    private static final AtomicBoolean DRIVER_LOADED = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        AdvancementsManager advManager = new AdvancementsManager(this);
        advManager.init();

        Bukkit.getPluginManager().registerEvents(this, this);
        moneyManager = new MoneyManager(getDataFolder(), "unity_launcher");
        getServer().getPluginManager().registerEvents(moneyManager, this);
        webSocketManager = new WebSocketManager(getLogger());
        tracker = new ActivityTracker(this);
        this.blueMapIntegration = new BlueMapIntegration(this, getLogger(), getDataFolder());
        this.zoneManager = new ZoneManager(this, null, blueMapIntegration, tracker); // пока передаём null, позже установим SignManager
        this.signManager = new SignManager(this, getDataFolder(), zoneManager, blueMapIntegration, UnityCommands.getInstance());
        this.zoneManager.setSignManager(signManager);
        this.zoneActivityCalculations = new ZoneActivityCalculations(zoneManager);
        zoneActivityCalculations.startZoneBillingScheduler();

        CountryRelationshipDao dao = new CountryRelationshipDao();
        diplomacy = new DiplomacyService(dao);
        diplomacy.loadAll();
        countryRegistryJdbc = new CountryRegistryJdbc();
        countryRegistryJdbc.start(this);
        Bukkit.getOnlinePlayers().forEach(p -> countryRegistryJdbc.ensureScheduledRefresh(p.getName()));
        Bukkit.getScheduler().runTaskAsynchronously(this, diplomacy::loadAll);

        getServer().getPluginManager().registerEvents(signManager, this);
        HelpCommandManager helpManager = new HelpCommandManager();
        Objects.requireNonNull(getCommand("unityLauncher")).setExecutor(new Unity(helpManager, webSocketManager, tracker, zoneManager));
        Objects.requireNonNull(this.getCommand("unityLauncher")).setTabCompleter(new CommandCompleter());

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

        // Загрузка табличек и зон ТОЛЬКО после инициализации BlueMap
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.onEnable(api -> {
                getLogger().info("Загружаем маркеры для BlueMap.");
                blueMapIntegration.initializeBlueMapMarkerStorage("zones_shop");
                blueMapIntegration.loadBlueMapMarkers();

                Bukkit.getScheduler().runTask(this, () -> {
                    signManager.loadSignData();
                    zoneManager.loadZoneData();
                    zoneManager.loadZonesFromConfig();

                    // Обновляем все SHOP_LIST таблички через 5 секунд
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        for (Map.Entry<Location, SignVariables> entry : signManager.genericSignList.entrySet()) {
                            if (entry.getValue().getSignCategory() == SignCategory.SHOP_LIST) {
                                signManager.updateAllRelatedShopListSigns(entry.getKey());
                            }
                        }
                    }, 20L * 5);
                });
            });
        } else {
            getLogger().warning("BlueMap не включён. Таблички и зоны не будут инициализированы!");
        }
        instance = this;

        Bukkit.getScheduler().runTaskLater(this, () -> ChunkActivityHeatmapExporter.exportHeatmapToBlueMapLayer(
                tracker.getChunkStatsMap(),
                "world",
                tracker.getWeights()
        ), 40L);

        // Регистрация всех апгрейдов
        getLogger().info("UnityLauncher enabled!");
        Bukkit.getPluginManager().registerEvents(new UpgradesListener(), this);
        Bukkit.getPluginManager().registerEvents(new UpgradesListener.SmartHopperListener(this), this);
        // слушатели апгрейдов
        UpgradesListener.registerAll(this);
        Objects.requireNonNull(getCommand("brand")).setExecutor(new com.frammy.unitylauncher.upgrades.BrandCommand());

        // ★ добавлено: асинхронный пинг БД после старта (чтобы поймать проблемы сразу)
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Connection c = DBConnect()) {
                if (c == null) {
                    Bukkit.getLogger().severe("[UnityLauncher] DB ping: нет подключения (DBConnect() вернул null)");
                } else {
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                        if (rs.next()) {
                            Bukkit.getLogger().info("[UnityLauncher] DB ping: OK");
                        } else {
                            Bukkit.getLogger().warning("[UnityLauncher] DB ping: нет результата SELECT 1");
                        }
                    }
                }
            } catch (Throwable t) {
                Bukkit.getLogger().severe("[UnityLauncher] DB ping: ошибка — " + t.getMessage());
                logDbException(t);
            }
        });
    }

    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    @Override
    public void onDisable() {
        try {
            if (countryRegistryJdbc != null) countryRegistryJdbc.stop();
        } catch (Throwable ignore) {}
        try {
            if (this.signManager != null) {
                this.signManager.saveSignData();
            } else {
                getLogger().warning("[UnityLauncher] signManager is null on disable — skipping saveSignData()");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] saveSignData() failed: " + t.getMessage());
        }

        try {
            if (this.zoneManager != null) {
                this.zoneManager.saveZonesToConfig();
            } else {
                getLogger().warning("[UnityLauncher] zoneManager is null on disable — skipping saveZonesToConfig()");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] saveZonesToConfig() failed: " + t.getMessage());
        }

        try {
            if (tracker != null) {
                tracker.forceSampleNow();
                tracker.saveAllToDisk();
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] tracker save failed: " + t.getMessage());
        }

        try {
            if (webSocketManager != null) {
                webSocketManager.disconnectAll();
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] webSocketManager disconnect failed: " + t.getMessage());
        }

        try {
            diplomacy.snapshot().keySet().forEach(diplomacy::save);
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] diplomacy save failed: " + t.getMessage());
        }

        try {
            if (blueMapIntegration != null) {
                blueMapIntegration.saveBlueMapMarkers("services");
                blueMapIntegration.saveBlueMapMarkers("shops");
                blueMapIntegration.saveBlueMapMarkers("chunk-activity");
            }
        } catch (Throwable t) {
            getLogger().warning("[UnityLauncher] blueMapIntegration save failed: " + t.getMessage());
        }

        instance = null;
        getLogger().info("UnityLauncher disabled.");
    }

    public static UnityLauncher getInstance() {
        return instance;
    }

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

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // вызываем только при смене блока (уменьшит нагрузку и дребезг)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        zoneManager.checkPlayerZone(event.getPlayer());
    }

    public String encodeLocation(Location loc) {
        return loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
    }

    public void addPlayerToWaitList(Player player) {
        awaitingCorrectCommand.add(player);
    }

    // Обработчик событий для перехвата сообщений в чате
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        // Если игрок в списке ожидания, блокируем его сообщение
        if (awaitingCorrectCommand.contains(player)) {
            player.sendMessage(ChatColor.RED + "Ты не можешь отправлять сообщения, пока не укажешь границы магазина. Используй: /ul shop addcorner");
            event.setCancelled(true); // Блокируем сообщение
        }
    }

    public int getMaxBaseLength(Collection<String> values) {
        return values.stream().mapToInt(String::length).max().orElse(1);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        webSocketManager.connectPlayer(e.getPlayer().getName());
        countryRegistryJdbc.ensureScheduledRefresh(e.getPlayer().getName());
    }

    /* ===================== БАЗА ДАННЫХ ===================== */

    // ★ изменено: DBConnect с кэшем пропертей и разовой загрузкой драйвера
    @Nullable
    public static Connection DBConnect() {
        try {
            Properties props = loadDbProps(); // может бросить исключение, логируем ниже
            String url = props.getProperty("db.url");
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

    // ★ добавлено: кэш загрузки db.properties из ресурсов
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

    // ★ добавлено: безопасные асинхронные версии ранее закомментированных методов

//    /** Асинхронно сбрасывает DayDealCode всем пользователям. */
//    public static void resetDayDealCodeAsync() {
//        Bukkit.getScheduler().runTaskAsynchronously(getInstance(), () -> {
//            String sql = "UPDATE Users SET DayDealCode = 0";
//            try (Connection con = DBConnect()) {
//                if (con == null) {
//                    Bukkit.getLogger().warning("[UnityLauncher] resetDayDealCodeAsync: DBConnect() == null");
//                    return;
//                }
//                try (PreparedStatement ps = con.prepareStatement(sql)) {
//                    int updated = ps.executeUpdate();
//                    Bukkit.getLogger().info("[UnityLauncher] resetDayDealCodeAsync: updated rows = " + updated);
//                }
//            } catch (Throwable t) {
//                Bukkit.getLogger().severe("[UnityLauncher] resetDayDealCodeAsync failed: " + t.getMessage());
//                logDbException(t);
//            }
//        });
//    }
//
//    /**
//     * Асинхронно обновляет Playtime накопительно.
//     * playTime: имя игрока -> добавляемые секунды/тиков (в твоих единицах).
//     */
//    public static void updatePlaytimeAsync(Map<String, Long> playTime) {
//        if (playTime == null || playTime.isEmpty()) return;
//
//        Bukkit.getScheduler().runTaskAsynchronously(getInstance(), () -> {
//            String selectSql = "SELECT Playtime FROM Users WHERE Name = ? LIMIT 1";
//            String updateSql = "UPDATE Users SET Playtime = ? WHERE Name = ?";
//
//            try (Connection con = DBConnect()) {
//                if (con == null) {
//                    Bukkit.getLogger().warning("[UnityLauncher] updatePlaytimeAsync: DBConnect() == null");
//                    return;
//                }
//                con.setAutoCommit(false);
//
//                try (PreparedStatement psSel = con.prepareStatement(selectSql);
//                     PreparedStatement psUpd = con.prepareStatement(updateSql)) {
//
//                    for (Map.Entry<String, Long> e : playTime.entrySet()) {
//                        String name = e.getKey();
//                        long delta = e.getValue() == null ? 0L : e.getValue();
//
//                        long current = 0L;
//                        psSel.clearParameters();
//                        psSel.setString(1, name);
//                        try (ResultSet rs = psSel.executeQuery()) {
//                            if (rs.next()) current = rs.getLong("Playtime");
//                        }
//
//                        long newValue = current + Math.max(0L, delta);
//                        psUpd.clearParameters();
//                        psUpd.setLong(1, newValue);
//                        psUpd.setString(2, name);
//                        psUpd.addBatch();
//                    }
//
//                    psUpd.executeBatch();
//                    con.commit();
//                } catch (Throwable t) {
//                    try { con.rollback(); } catch (Throwable ignore) {}
//                    throw t;
//                } finally {
//                    try { con.setAutoCommit(true); } catch (Throwable ignore) {}
//                }
//            } catch (Throwable t) {
//                Bukkit.getLogger().severe("[UnityLauncher] updatePlaytimeAsync failed: " + t.getMessage());
//                logDbException(t);
//            }
//        });
//    }

    /* ===================== Ошибки/уведомления ===================== */

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
