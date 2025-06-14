package com.frammy.unitylauncher;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.zones.ZoneManager;
import de.bluecolored.bluemap.api.BlueMapAPI;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

public final class UnityLauncher extends JavaPlugin implements Listener {
    HashMap<String, Date> sessions = new HashMap<>();
    HashMap<String, Long> playTime = new HashMap<>();
    private static UnityLauncher instance;
    private final Set<Player> awaitingCorrectCommand = new HashSet<>();
    private FileConfiguration shopConfig;
    public ArrayList<String> commandCategories= new ArrayList<String>();
    public MoneyManager moneyManager;
    private ZoneManager zoneManager;
    private SignManager signManager;
    private WebSocketManager webSocketManager;
    private BlueMapIntegration blueMapIntegration;
    public Set<Player> getAwaitingCorrectCommand() {return awaitingCorrectCommand;}

    @Override
    public void onEnable() {
        //Инициализируем всякую туфту
        Bukkit.getPluginManager().registerEvents(this, this);
        moneyManager = new MoneyManager(getDataFolder(), "unity_launcher");
        getServer().getPluginManager().registerEvents(moneyManager, this);
        webSocketManager = new WebSocketManager(getLogger());

        this.blueMapIntegration = new BlueMapIntegration(this, getLogger(), getDataFolder());
        this.zoneManager = new ZoneManager(this, null, blueMapIntegration); // пока передаём null, позже установим SignManager
        this.signManager = new SignManager(this, getDataFolder(), zoneManager, blueMapIntegration, UnityCommands.getInstance());
        this.zoneManager.setSignManager(signManager);
        getServer().getPluginManager().registerEvents(signManager, this);
        // webSocketManager.connect();
        HelpCommandManager helpManager = new HelpCommandManager();
        Objects.requireNonNull(getCommand("unityLauncher")).setExecutor(new Unity(helpManager, webSocketManager));
        this.getCommand("unityLauncher").setTabCompleter(new CommandCompleter());
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.onEnable(api -> {
                System.out.println("Загружаем маркеры для BlueMap.");
                blueMapIntegration.initializeBlueMapMarkerStorage("shops");
                blueMapIntegration.loadBlueMapMarkers();
            });
        }

        //Загружаем данные yml
        signManager.loadSignData();

        //loadShopData();
        zoneManager.loadZoneData();
        zoneManager.loadZonesFromConfig();

        //Для help комманды
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

        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "signData.yml"));
        signManager.restoreScrollingSignsFromFile(config);
        instance = this;
    }
    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    @Override
    public void onDisable() {
        signManager.saveSignData();
        zoneManager.saveZonesToConfig();
        if (webSocketManager != null) {
            webSocketManager.disconnectAll();
        }

        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "UPDATE Users SET DayDealCode=0 WHERE 1;";
                Statement st = con.createStatement();
                st.executeUpdate(query);

                playTime.forEach((key, value) -> {
                    try {
                        String query2 = "SELECT Playtime FROM Users WHERE Name='" + key + "';";
                        Statement st2 = con.createStatement();
                        ResultSet rs2 = st2.executeQuery(query2);
                        long sqlTime = 0;
                        if (rs2.next())
                            sqlTime = rs2.getInt("Playtime");
                        else
                            Bukkit.getConsoleSender().sendMessage("No player " + key + " in database");
                        sqlTime += value;
                        String query3 = "UPDATE Users SET Playtime=" + sqlTime + " WHERE Name='" + key + "';";
                        Statement st3 = con.createStatement();
                        st3.executeUpdate(query3);
                    } catch (Exception e) {
                        onError("", e, null);
                    }
                });
            } catch (Exception ex) {
                onError("NotInBase", ex, null);
            }
        }

        blueMapIntegration.saveBlueMapMarkers("services");
        blueMapIntegration.saveBlueMapMarkers("shops");
        instance = null;
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
    }

    @Nullable
    public static Connection DBConnect() {
        try {
            Class.forName("com.mysql.jdbc.Driver");
            String url = "jdbc:mysql://mysql.apexhosting.gdn:3306/apexMC1473088";
            String username = "apexMC1473088";
            String password = "H#pXkkgG8SbaexeB6azGXMlm";
            return DriverManager.getConnection(url, username, password);
        } catch (Exception e) {
            onError("DBError", e, null);
            return null;
        }
    }

    public static void resetDayDealCode() {
        Connection con = DBConnect();
        if (con != null) {
            try {
                String query = "UPDATE Users SET DayDealCode=0 WHERE 1;";
                Statement st = con.createStatement();
                st.executeUpdate(query);
            } catch (Exception ex) {
                onError("NotInBase", ex, null);
            }
        }
    }

    public static void updatePlaytime(Map<String, Long> playTime) {
        Connection con = DBConnect();
        if (con != null) {
            playTime.forEach((key, value) -> {
                try {
                    String query2 = "SELECT Playtime FROM Users WHERE Name='" + key + "';";
                    Statement st2 = con.createStatement();
                    ResultSet rs2 = st2.executeQuery(query2);
                    long sqlTime = 0;
                    if (rs2.next()) {
                        sqlTime = rs2.getInt("Playtime");
                    } else {
                        Bukkit.getConsoleSender().sendMessage("No player " + key + " in database");
                    }
                    sqlTime += value;
                    String query3 = "UPDATE Users SET Playtime=" + sqlTime + " WHERE Name='" + key + "';";
                    Statement st3 = con.createStatement();
                    st3.executeUpdate(query3);
                } catch (Exception e) {
                    onError("", e, null);
                }
            });
        }
    }

    public static void onError(String reason, Exception e, Player p) {
        e.printStackTrace();
        switch (reason) {
            case "NotInBase":
                if (p != null) p.sendMessage(ChatColor.RED + "Вас не существует в базе!");
                break;
            case "SignErr":
                if (p != null) p.sendMessage(ChatColor.RED + "Ошибка при оплате по табличке!");
                break;
            case "DBError":
                if (p != null) p.sendMessage(ChatColor.RED + "Ошибка при соединении с базой!");
                break;
            default:
                break;
        }
    }
}
