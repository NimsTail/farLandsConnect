package com.frammy.unitylauncher;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.gson.MarkerGson;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Shape;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.lang.reflect.Array;
import java.sql.*;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public final class UnityLauncher extends JavaPlugin implements Listener {
    HashMap<String, Date> sessions = new HashMap<>();
    HashMap<String, Long> playTime = new HashMap<>();
    public Map<Player, List<Location>> markerPoints = new HashMap<>();
    private static UnityLauncher instance;
    public final Set<Player> awaitingCorrectCommand = new HashSet<>();
    private int currentID = 0;
    private FileConfiguration shopConfig;
    public ArrayList<String> commandCategories= new ArrayList<String>();
    private MoneyManager moneyManager;
    private ZoneManager zoneManager;
    private WebSocketManager webSocketManager;

   // private Map<Location, String> configuringSigns = new HashMap<>();
    private final Map<Location, BukkitTask> scrollingTasks = new HashMap<>();
   // private final Set<Location> pausedSigns = new HashSet<>();
    private final Map<UUID, Integer> playerScrollIndex = new HashMap<>();
    private final Map<Location, List<String>> signPages = new HashMap<>();
    private final Map<String, Runnable> actions = new HashMap<>();
    private final ChatColor highlightColor = ChatColor.GREEN;
    private final Map<Location, Runnable> signClickActions = new HashMap<>();
    private final Map<Player, Block> signSelectionMap = new HashMap<>();

    // UUID + Location → последний момент взаимодействия
    private final Map<Location, BukkitTask> resetTasks = new HashMap<>();

    // Загрузка стартовых текстов табличек из signs.yml (если ещё не хранится)
    private final Map<Location, String[]> originalSignTexts = new HashMap<>();
   // private final Map<Location, SignState> signStates = new HashMap<>();
    public Map<Location, SignVariables> genericSignList = new HashMap<>();
    enum SignState {
        MENU,         // скроллим список
        ACTION_READY  // ждём клик для выполнения действия
    }
    enum SignCategory {
        ATM,
        SHOP_SOURCE,
        SHOP_SELLER,
        SHOP_INFO,
        SHOP_LIST,
        SHOP_HELP
    }
    public class SignVariables {
        private boolean isConfigurable;
        private boolean isPaused;
        private List<Integer> scrollLines;
        private List<String> signText;
        private String ownerName;
        private SignState state;
        private SignCategory category;
        private String markerID;

        public SignVariables(String ownerName, List<String> signText, List<Integer> scrollLines, boolean isConfigurable, boolean isPaused, SignCategory category, SignState state, String markerID) {
            this.isConfigurable = isConfigurable;
            this.isPaused = isPaused;
            this.signText = signText;
            this.ownerName = ownerName;
            this.scrollLines = scrollLines;
            this.category = category;
            this.state = state;
            this.markerID = markerID;
        }

        public boolean getConfigurtable() {
            return isConfigurable;
        }
        public boolean getPaused() {
            return isPaused;
        }

        public List<String> getSignText() {
            return signText;
        }

        public List<Integer> getScrollLines() {
            return scrollLines;
        }
        public SignCategory getSignCategory() {
            return category;
        }
        public SignState getSignState() {
            return state;
        }
        public String getOwnerName() {
            return ownerName;
        }
        public String getMarkerID() {
            return markerID;
        }


        public void setConfigurtable(boolean isConfigurable) {
            this.isConfigurable = isConfigurable;
        }
        public void setPaused(boolean isPaused) {
            this.isPaused = isPaused;
        }

        public void setSignText(List<String> signText) {
            this.signText = signText;
        }

        public void setScrollLines(List<Integer> scrollLines) {
            this.scrollLines = scrollLines;
        }
        public void setSignCategory(SignCategory category) {
            this.category = category;
        }
        public void setSignState(SignState state) {
            this.state = state;
        }
        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
        }
        public void setMarkerID(String markerID) {
            this.markerID = markerID;
        }

    }


    @Override
    public void onEnable() {
        //Инициализируем всякую туфту
        Bukkit.getPluginManager().registerEvents(this, this);
        moneyManager = new MoneyManager(getDataFolder(), "unity_launcher");
        getServer().getPluginManager().registerEvents(moneyManager, this);
        webSocketManager = new WebSocketManager(getLogger());
        // webSocketManager.connect();
        HelpCommandManager helpManager = new HelpCommandManager();
        zoneManager = new ZoneManager(this, getDataFolder());
        Objects.requireNonNull(getCommand("unityLauncher")).setExecutor(new Unity(helpManager, webSocketManager));
        this.getCommand("unityLauncher").setTabCompleter(new CommandCompleter());
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.onEnable(api -> {
                System.out.println("Загружаем маркеры для BlueMap.");
                loadBlueMapMarkers();
            });
        }

        //Загружаем данные yml
        loadSignData();
        //loadShopData();
        loadZoneData();

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

        FileConfiguration config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "signs.yml"));
        restoreScrollingSignsFromFile(config);
        instance = this;
    }
    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    @Override
    public void onDisable() {
        saveSignData();
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
        saveBlueMapMarkers("services");
        saveBlueMapMarkers("shops");
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

    public void saveSignData() {
        File shopFile = new File(getDataFolder(), "signData.yml");
        YamlConfiguration shopConfig = new YamlConfiguration();

        for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
            Location loc = entry.getKey();
            SignVariables vars = entry.getValue();

            String path = "signs." + encodeLocation(loc); // Уникальный путь по координатам

            shopConfig.set(path + ".text", vars.getSignText());
            shopConfig.set(path + ".scrollLines", vars.getScrollLines());
            shopConfig.set(path + ".isConfigurable", vars.getConfigurtable());
            shopConfig.set(path + ".isPaused", vars.getPaused());
            shopConfig.set(path + ".owner", vars.getOwnerName());
            shopConfig.set(path + ".category", vars.getSignCategory().toString());
            shopConfig.set(path + ".state", vars.getSignState().toString());
            shopConfig.set(path + ".markerID", vars.getMarkerID());

            shopConfig.set(path + ".location.world", loc.getWorld().getName());
            shopConfig.set(path + ".location.x", loc.getBlockX());
            shopConfig.set(path + ".location.y", loc.getBlockY());
            shopConfig.set(path + ".location.z", loc.getBlockZ());
        }

        try {
            shopConfig.save(shopFile);
            Bukkit.getLogger().info("Все таблички успешно сохранены в signData.yml");
        } catch (IOException e) {
            Bukkit.getLogger().severe("Ошибка при сохранении табличек: " + e.getMessage());
            e.printStackTrace();
        }
    }
    private String encodeLocation(Location loc) {
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
    public static double calculateSurfaceArea(List<Location> points) {
        if (points.size() < 3) {
            // Если точек меньше трех, многоугольник не существует
            return 0;
        }

        double area = 0;
        int n = points.size();

        // Применяем формулу площади многоугольника (формула "Шу")
        for (int i = 0; i < n; i++) {
            Location current = points.get(i);
            Location next = points.get((i + 1) % n); // Следующая точка, для замыкания контура

            area += current.getX() * next.getZ() - current.getZ() * next.getX();
        }

        return Math.round(Math.abs(area / 2.0) * 100.0) / 100.0; // Площадь должна быть положительной
    }

    public void scheduleSignReset(Location loc) {
        // Отменим предыдущую задачу, если была
        if (resetTasks.containsKey(loc)) {
            resetTasks.get(loc).cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            // Если сброс сейчас в паузе — повторно планируем задачу
            if (genericSignList.get(loc).isPaused) {
                scheduleSignReset(loc); // запускаем таймер заново
                return;
            }

            Block block = loc.getBlock();
            if (!(block.getState() instanceof Sign)) return;

            Sign sign = (Sign) block.getState();
            String[] lines = originalSignTexts.get(loc);
            if (lines == null) return;

            for (int i = 0; i < Math.min(4, lines.length); i++) {
                sign.setLine(i, lines[i]);
            }
            sign.update();

            resetTasks.remove(loc); // удаляем завершённую задачу
        }, 20 * 10L); // 10 секунд

        resetTasks.put(loc, task);
    }
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        Player p = (Player) e.getPlayer();

        if (signSelectionMap.containsKey(p)) {
            Block signBlock = signSelectionMap.get(p);
            Location containerLoc = ((InventoryHolder) e.getInventory().getHolder()).getInventory().getLocation();

            if (containerLoc == null) {
                p.sendMessage(ChatColor.RED + "Ошибка: не удалось получить координаты хранилища.");
                return;
            }

            Sign sign = (Sign) signBlock.getState();
            sign.setLine(1, containerLoc.getBlockX() + " " + containerLoc.getBlockY() + " " + containerLoc.getBlockZ());
            sign.update();

            p.sendMessage(ChatColor.GREEN + "Новое хранилище выбрано: " +
                    containerLoc.getBlockX() + " " + containerLoc.getBlockY() + " " + containerLoc.getBlockZ());
            signSelectionMap.remove(p);
        }
    }
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();

        if (b == null || !(b.getState() instanceof Sign)) return;

        Sign sign = (Sign) b.getState();
        Location loc = sign.getLocation();

        if (e.getAction() == Action.LEFT_CLICK_BLOCK && b.getState() instanceof Sign) {
            if (genericSignList.get(sign.getLocation()) == null) return;
            if (!genericSignList.get(sign.getLocation()).getConfigurtable()) return;
            if (p.isSneaking()) {
                if (!sign.getLine(2).isEmpty() && !sign.getLine(3).isEmpty()) {
                    double amount, price;
                    try {
                       amount = Double.parseDouble(sign.getLine(3));
                       price = Double.parseDouble(sign.getLine(2));

                    }
                    catch (NumberFormatException exc) {
                        p.sendMessage(ChatColor.RED + "3 и 4 строки должны быть десятичными числами.");
                        sign.setLine(2, "");
                        sign.setLine(3, "");
                        return;
                    }
                    List<String> signTexts = genericSignList.get(sign.getLocation()).getSignText();
                    genericSignList.get(sign.getLocation()).setSignText(Arrays.asList(signTexts.get(0), signTexts.get(1), String.valueOf(price), String.valueOf(amount)));
                   // saveZoneAndShopData(p,null, genericSignList.get(sign.getLocation()).getSignText(), sign.getLocation());
                    p.sendMessage(ChatColor.GREEN + "Табличка товара подтверждена и теперь функционирует.");


                }

                return;
            }
            String secondLine = ChatColor.stripColor(sign.getLine(1)).toLowerCase();


            if (genericSignList.get(sign.getLocation()).getSignCategory() == SignCategory.SHOP_SOURCE) {
                if (!secondLine.isEmpty()) {
                    signSelectionMap.put(p, b); // добавляем игрока в режим выбора
                    p.sendMessage(ChatColor.YELLOW + "Теперь открой нужное хранилище, чтобы выбрать его.");
                    e.setCancelled(true); // предотвращаем случайный удар по табличке
                }
            }
        }

        SignVariables vars = genericSignList.get(loc);
        SignState state = (vars != null) ? vars.getSignState() : SignState.MENU;
        // RIGHT_CLICK → Пауза прокрутки
        if (action == Action.RIGHT_CLICK_BLOCK) {
            pauseScrolling(loc);
            return;
        }

        if (action != Action.LEFT_CLICK_BLOCK) return;

        // Если табличка в режиме "Коснитесь, чтобы начать"
        if (sign.getLine(1).equals("Коснитесь,") && genericSignList.containsKey(loc)) {
            if (p.getItemInHand() == null || p.getItemInHand().getType() == Material.AIR) {
                e.setCancelled(true);
                setupSign(loc, sign, p);
                genericSignList.get(loc).setSignState(SignState.MENU);
                scheduleSignReset(loc);
                return;
            }
        }

        // ===== В режиме MENU (скроллим) =====
        if (state == SignState.MENU) {
            List<String> items = signPages.get(loc);
            if (items == null || items.size() <= 3) return;

            int scrollIndex = playerScrollIndex.getOrDefault(p.getUniqueId(), 0);

            if (p.isSneaking()) {
                // ЛКМ + Shift → сброс
                scrollIndex = 0;
                playerScrollIndex.put(p.getUniqueId(), 0);
                p.sendMessage(ChatColor.GRAY + "Возврат к списку.");
                updateSignView(sign, items, 0);
            } else {
                // ЛКМ по средней строке → выбор
                int selectedIndex = scrollIndex + 1;
                if (selectedIndex < items.size()) {
                    String key = items.get(selectedIndex);
                    SignVariables svars = genericSignList.get(loc);
                    if (svars == null || svars.getSignState() != SignState.ACTION_READY) {
                        if (actions.containsKey(key)) {
                            actions.get(key).run(); // <-- Заменит табличку и установит ACTION_READY
                            p.sendMessage(ChatColor.GRAY + "Вы выбрали: " + key);
                        }
                    }
                }
            }
            scheduleSignReset(loc);
            return;
        }

        // ===== В режиме ACTION_READY =====
        if (state == SignState.ACTION_READY) {
            Runnable signAction = signClickActions.remove(loc);
            if (signAction != null) {
                e.setCancelled(true);
                signAction.run();
            }
            // Возврат к обычному режиму
            genericSignList.get(loc).setSignState(SignState.MENU);
            scheduleSignReset(loc);
        }


    }
    @EventHandler
    public void onScroll(PlayerItemHeldEvent e) {
        Player player = e.getPlayer();

        // Проверим, смотрит ли игрок на табличку
        Block target = player.getTargetBlockExact(4); // до 6 блоков — можно увеличить
        if (target == null || !(target.getState() instanceof Sign)) return;

        Location loc = target.getLocation();
        Sign sign = (Sign) target.getState();

        List<String> items = signPages.get(loc);
        if (items == null || items.size() <= 3) return;

        int current = playerScrollIndex.getOrDefault(player.getUniqueId(), 0);

        // Определим направление прокрутки
        int fromSlot = e.getPreviousSlot();
        int toSlot = e.getNewSlot();
        boolean scrollDown = (toSlot - fromSlot + 9) % 9 <= 4; // учитываем wraparound hotbar (0 → 8 и наоборот)

        int newIndex = scrollDown ? current + 1 : current - 1;

        // Зацикливание
        if (newIndex < 0) newIndex = items.size() - 1;
        if (newIndex >= items.size()) newIndex = 0;

        playerScrollIndex.put(player.getUniqueId(), newIndex);

        // Показываем срез из 3 элементов, начиная с newIndex
        updateSignView(sign, items, newIndex);
        scheduleSignReset(sign.getLocation());
        e.setCancelled(true);
    }

    // Обновление таблички
    private void updateSignView(Sign sign, List<String> items, int offset) {
        for (int i = 0; i < 3; i++) {
            int itemIndex = (offset + i) % items.size(); // зацикливание
            String text = items.get(itemIndex);
            if (i == 1) {
                sign.setLine(i + 1, highlightColor + text); // строка 2 выделена
            } else {
                sign.setLine(i + 1, ChatColor.RESET + text);
            }
        }
        sign.update();
    }

    // Пример инициализации
    public void setupSign(Location loc, Sign sign, Player p) {
        List<String> options = Arrays.asList(".","Снятие наличных", "Взнос наличных", "Перевод игроку", "Перевод стране", "Информация");
        signPages.put(loc, options);
        Block block = loc.getBlock();

        if (!originalSignTexts.containsKey(loc)) {
            originalSignTexts.put(loc, sign.getLines());
        }
        actions.put("Снятие наличных", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Источник>");
            sign.setLine(3, "<Сумма>");
            sign.update();
            genericSignList.get(loc).setSignState(SignState.ACTION_READY);
            signClickActions.put(sign.getLocation(), () -> {
                Sign updatedSign = (Sign) sign.getBlock().getState();
                double amount;
                try {
                    amount = Double.parseDouble(updatedSign.getLine(3));
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Введите корректную сумму.");
                    return; // прерываем выполнение, если ввод некорректный
                }

                switch (updatedSign.getLine(2).toLowerCase()) {
                    case "страна":
                    case "государство":
                    case "стр":
                    case "ст":
                    case "с":
                    case "country":
                        p.sendMessage(ChatColor.GRAY + "С счёта государства было снято " + amount + "F.");

                        break;
                    case "игрок":
                    case "я":
                    case "мой счёт":
                    case "me":
                    case "игр":
                    case "иг":
                        p.sendMessage(ChatColor.GRAY + "С твоего счёта было снято " + amount + "F.");
                        moneyManager.giveMoney(p, amount);
                        break;
                    case "admin":
                    case "админ":
                        p.sendMessage(ChatColor.YELLOW + "Слушай, а ловко ты это придумал. Я даже в начале не понял.");
                    break;
                    default:
                        p.sendMessage(ChatColor.RED + "Необходимо указать счёт, с которого будут сняты деньги - 'Страна' или 'Игрок'.");
                        break;

                }
            });
        });
        actions.put("Взнос наличных", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Получатель>");
            sign.setLine(3, "<Сумма>");
            sign.update();
            genericSignList.get(loc).setSignState(SignState.ACTION_READY);
            signClickActions.put(sign.getLocation(), () -> {
                Sign updatedSign = (Sign) sign.getBlock().getState();
                double amount;
                try {
                    amount = Double.parseDouble(updatedSign.getLine(3));
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Введите корректную сумму.");
                    return; // прерываем выполнение, если ввод некорректный
                }

                switch (updatedSign.getLine(2).toLowerCase()) {
                    case "страна":
                    case "государство":
                    case "стр":
                    case "ст":
                    case "с":
                    case "country":
                        p.sendMessage(ChatColor.GRAY + "На счёт государства было взнесено " + amount + "F.");
                        moneyManager.takeMoney(p, amount);
                        break;
                    case "игрок":
                    case "я":
                    case "мой счёт":
                    case "me":
                    case "игр":
                    case "иг":
                        p.sendMessage(ChatColor.GRAY + "На твой счёт было взнесено " + amount + "F.");
                        moneyManager.takeMoney(p, amount);
                        break;
                    default:
                        p.sendMessage(ChatColor.RED + "Необходимо указать счёт, с которого будут сняты деньги - 'Страна' или 'Игрок'.");
                        break;

                }
            });
        });
        actions.put("Перевод игроку", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Никнейм>");
            sign.setLine(3, "<Сумма>");
            sign.update();
        });
        actions.put("Перевод стране", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Сумма>");
            sign.setLine(3, " ");
            sign.update();
        });
        actions.put("Информация", () -> {
            sign.setLine(1, "Ком. плата: ");
            sign.setLine(2, " ");
            sign.setLine(3, " ");
            sign.update();
        });

        playerScrollIndex.clear();

        // Первый раз отображаем

        if (block.getState() instanceof Sign) {
            updateSignView((Sign) block.getState(), options, 0);
        }
    }
           //    String amountLine = sign.getLine(3);
             //  if (amountLine == null || amountLine.isEmpty()) {
             //      p.sendMessage(ChatColor.RED + "Сумма на табличке не указана!");
             //      return;
             //  }
              /* double amount;
               try {
                   amount = Double.parseDouble(amountLine);
               } catch (NumberFormatException ex) {
                   p.sendMessage(ChatColor.RED + "Некорректная сумма на табличке!");
                   return;
               }*/
               /*String operation = sign.getLine(2);
               if (operation == null || operation.isEmpty()) {
                   p.sendMessage(ChatColor.RED + "Тип операции на табличке не указан!");
                   return;
               }
               switch (operation.toLowerCase()) {
                   case ":withdraw":
                   case ":снятие":
                   case ":wd":
                   case ":>":
                       // Снятие денег
                       moneyManager.giveMoney(p, amount);
                       p.sendMessage(ChatColor.GREEN + "Вы сняли " + amount + " монет!");
                       break;

                   case ":deposit":
                   case ":dp":
                   case ":депозит":
                   case ":<":
                       // Депозит денег
                       moneyManager.takeMoney(p, amount);
                       break;

                   case ":$":
                       // Проверка строки для пользовательской команды
                       if (!sign.getLine(3).isEmpty() && !sign.getLine(1).isEmpty()) {
                           double customFee;
                           try {
                               customFee = Double.parseDouble(sign.getLine(1).replace(",", "."));
                           } catch (NumberFormatException ex) {
                               customFee = -1.0; // Устанавливаем значение по умолчанию
                           }

                           UnityCommands.getInstance().pay(p, sign.getLine(2).replace("::",""), amount, customFee);
                       } else {
                           p.sendMessage(ChatColor.RED + "Строки получателя (3) и суммы (4) не могут быть пустыми!");
                       }
                       break;
               }*/

   @EventHandler
   public  void onPlayerLeave(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if(awaitingCorrectCommand.contains(p.getName())) {
            UnityCommands.getInstance().setShops(p,UnityCommands.getInstance().getShops(p) + 1);
            awaitingCorrectCommand.remove(p);
        }

   }
    public void restoreScrollingSignsFromFile(FileConfiguration config) {
        ConfigurationSection section = config.getConfigurationSection("");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection signSection = section.getConfigurationSection(key);
            if (signSection == null) continue;

            Location loc = signSection.getLocation("location");
            if (loc == null) continue;

            Block block = loc.getBlock();
            if (!(block.getState() instanceof Sign)) continue;

            Sign sign = (Sign) block.getState();

            // Укажи здесь нужные строки для скроллинга, например: 0 строка
            int[] scrollLines = new int[] {0}; // или можешь извлекать это из YAML если хочешь
            Map<Integer, String> originalLines = new HashMap<>();

            for (int lineIndex : scrollLines) {
                if (lineIndex >= 0 && lineIndex <= 3) {
                    String rawLine = ChatColor.stripColor(sign.getLine(lineIndex));
                    originalLines.put(lineIndex, rawLine);
                }
            }

            // Запускаем скроллинг
            makeSignScrollingLines(loc, originalLines, 8, 13); // интервал и длина строки
        }
    }
    public void makeSignScrollingLines(Location signLocation, Map<Integer, String> originalLines, int intervalTicks, int maxLength) {
        Block block = signLocation.getBlock();
        if (!(block.getState() instanceof Sign)) return;

        // Удвоим текст, чтобы можно было безопасно прокручивать
        Map<Integer, String> scrollBuffers = new HashMap<>();
        for (Map.Entry<Integer, String> entry : originalLines.entrySet()) {
            String base = entry.getValue() + "   "; // Добавим пробелы
            scrollBuffers.put(entry.getKey(), base + base);
        }

        AtomicInteger offset = new AtomicInteger(0);

        // Остановим предыдущую анимацию для этой таблички
        if (scrollingTasks.containsKey(signLocation)) {
            scrollingTasks.get(signLocation).cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!(signLocation.getBlock().getState() instanceof Sign)) {
                scrollingTasks.remove(signLocation);
                genericSignList.get(signLocation).setPaused(false);

                return;
            }

            if (genericSignList.get(signLocation).isPaused) {
                return; // ⏸ Табличка в паузе
            }

            boolean anyNearby = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(player -> player.getWorld().equals(signLocation.getWorld())
                            && player.getLocation().distanceSquared(signLocation) <= 35 * 35);
            if (!anyNearby) return;

            Sign currentSign = (Sign) signLocation.getBlock().getState();
            int pos = offset.getAndUpdate(i -> (i + 1) % getMaxBaseLength(originalLines.values()));

            for (Map.Entry<Integer, String> entry : scrollBuffers.entrySet()) {
                int lineIndex = entry.getKey();
                String buffer = entry.getValue();

                StringBuilder displayBuilder = new StringBuilder();
                for (int i = 0; i < maxLength; i++) {
                    displayBuilder.append(buffer.charAt((pos + i) % buffer.length()));
                }
                currentSign.setLine(lineIndex, displayBuilder.toString());
            }

            currentSign.update();
        }, 0L, intervalTicks);

        scrollingTasks.put(signLocation, task);
    }
    private int getMaxBaseLength(Collection<String> values) {
        return values.stream().mapToInt(String::length).max().orElse(1);
    }
    public void pauseScrolling(Location location) {
        genericSignList.get(location).setPaused(true);
    }
    public void resumeScrolling(Location location) {
        genericSignList.get(location).setPaused(false);
    }
    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        Sign sign = (Sign) e.getBlock().getState();
        Player p = e.getPlayer();
        String[] oldLines = sign.getLines();
        String[] newLines = e.getLines();

        if (!e.getBlock().getType().toString().contains("HANGING")) {
            if (!genericSignList.containsKey(sign.getLocation())) return;

            if (genericSignList.get(sign.getLocation()) != null) {
                resumeScrolling(sign.getLocation());
            }

          //  if (e.getLine(0).equalsIgnoreCase(":wd") || e.getLine(0).equalsIgnoreCase(":withdraw") || e.getLine(0).equalsIgnoreCase(":снятие")) {
         //       p.sendMessage(ChatColor.GRAY + "Банкомат переключён на снятие наличных.");
         //   }
            if (e.getLine(0).equalsIgnoreCase("shop") || e.getLine(0).equalsIgnoreCase("магазин")) {
                String label = isSignWithinMarker(sign.getLocation());
                if (label.isEmpty()) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Табличку о продаже можно ставить только в пределах магазина!");
                } else {
                    File shopFile = new File(getDataFolder().getParentFile(), "UnityLauncher/signData.yml");
                    this.shopConfig = YamlConfiguration.loadConfiguration(shopFile);
                    ConfigurationSection shopSection = shopConfig.getConfigurationSection("shops." + label);
                    if (!shopSection.getString("owner").equalsIgnoreCase(p.getName())) {
                        e.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "Ты не являешься владельцем этого магазина!");
                    }

                    String line0 = "Торговая точка [ " + label + " ]";

                    e.setLine(0, line0);
                    Map<Integer, String> linesToScroll = new HashMap<>();
                    linesToScroll.put(0, line0);


                    switch (e.getLine(1)) {
                        case "source":
                        case "источник":
                           // configuringSigns.put(sign.getLocation(), "source");
                            Block nearestStorage = findNearestContainer(sign.getLocation(), 5);
                            if (nearestStorage != null) {
                                Location loc = nearestStorage.getLocation();
                                String line1 = loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
                                e.setLine(1, line1);
                             //   linesToScroll.put(1, line1);
                                makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll, 6, 13);
                                e.setLine(2, "<Количество>");
                                e.setLine(3, "<Цена>");
                                p.sendMessage(ChatColor.GRAY + "Координаты источника установлены.\n" +
                                        "Чтобы выбрать другое хранилище — кликните ЛКМ по табличке, затем откройте нужное хранилище.");//
                                genericSignList.put(sign.getLocation(), new SignVariables(p.getName(), Arrays.asList(line0, line1, "<Количество>", "<Цена>"), List.of(0), true, false, SignCategory.SHOP_SOURCE, null, null));
                            } else {
                                e.setCancelled(true);
                                p.sendMessage(ChatColor.RED + "Поблизости не найдено ни одного хранилища!");
                            }
                            break;
                        case "seller":
                        case "продавец":
                            p.sendMessage(ChatColor.GREEN + "Для показа информации о продавце");
                            break;
                        case "info":
                        case "инфо":
                        case "информация":
                            p.sendMessage(ChatColor.GREEN + "Для показа информации о магазине");
                            break;
                        case "list":
                        case "список":
                            p.sendMessage(ChatColor.GREEN + "Для показа доступных товаров в магазине, цены и количества");
                            break;
                        case "help":
                        case "помощь":
                            p.sendMessage(ChatColor.GREEN + "Для показа помощи");
                            break;
                        default:
                            p.sendMessage(ChatColor.RED + "Отсутствуют параметры на 2-ой строке таблички.");
                            break;

                    }


                }
            }

            if (genericSignList.containsKey(sign.getLocation())) {

                if (!oldLines[0].equals(newLines[0])) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Изменение первой строки невозможно. "  + ChatColor.GRAY + "\nДля изменения цели таблички сломайте её и установите с новыми параметрами.");
                }
                if (!genericSignList.get(sign.getLocation()).getOwnerName().equals(p.getName())) {
                    // Если никнейм игрока не совпадает с тем, кто установил табличку
                    if (!oldLines[0].equals(newLines[0]) || !oldLines[1].equals(newLines[1])) {
                        e.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "Вы не можете изменять эту табличку, так как её установил другой игрок.");
                        return;
                    }
                }

                // Проверка изменения второй строки таблички "ATM"
               /* if (oldLines[0].equalsIgnoreCase("ATM") && !oldLines[1].equals(newLines[1])) {
                    // Если строка изменилась, проверяем права игрока
                    if (!hasPermissionContaining(p, "0")) {
                        // Если у игрока нет права на изменение второй строки, отменяем событие
                        e.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "У вас нет прав на изменение второй строки ATM.");
                        return;
                    }
                }

                // Выполняем логику изменения, если ID таблички найден и права совпадают
                if (!e.getLine(0).equalsIgnoreCase("ATM")) {

                    if (hasPermissionContaining(p, "0")) {
                        // Удаляем табличку из atmSignData, если она больше не является "ATM"
                        atmSignData.remove(existingID);
                        saveSignData();
                        // Удаляем маркер с карты BlueMap
                        removeBlueMapMarker(existingID);
                        scrollingTasks.remove(e.getBlock().getLocation());
                        p.sendMessage("АТМ убран.");
                    } else {
                        // Табличка обновлена и остается "ATM", можно обновить данные
                        e.setLine(0, "ATM");
                        p.sendMessage("АТМ обновлен.");

                        // Обновляем данные о группе (если необходимо)
                        String group = p.getName(); // permission.getPrimaryGroup(p);
                        atmSignData.put(existingID, new ATMData(sign.getLocation(), group, e.getLines()));
                        saveSignData();
                    }
                }*/
            } else if (e.getLine(0).equalsIgnoreCase("ATM")) {
                if (hasPermissionContaining(p, "0")) {
                    String line0 = "ATM [" + UnityCommands.getInstance().getPlayerInfo(p).countryName + "]";

                    e.setLine(0, line0);
                    Map<Integer, String> linesToScroll = new HashMap<>();
                    linesToScroll.put(0, line0);

                    makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll, 6, 13);
                    // Если табличка не найдена и это новая табличка с "ATM"

                    e.setLine(1, "Коснитесь,");
                    e.setLine(2, "чтобы начать");
                    p.sendMessage("АТМ установлен.");
                    String markerID = "marker_" + UUID.randomUUID();

                    //atmSignData.put(currentID, new ATMData(sign.getLocation(), p.getName(), e.getLines()));
                    genericSignList.put(sign.getLocation(), new SignVariables(p.getName(), Arrays.asList(line0, "Коснитесь,", "чтобы начать", ""), List.of(0), false, false, SignCategory.ATM, null, markerID));
                   // saveSignData();
                    // Добавляем маркер на карту BlueMap
                    addBlueMapMarker(markerID, sign.getLocation(), "services", "Сервисы", "point", null, p);
                }
            }
        } else {
            if (e.getLine(0).equalsIgnoreCase("ATM")) {
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве банковского автомата!");
            }
        }
    }
    public boolean hasPlayerShopBrand(String shopNaming, String owner) {
        File shopFile = new File(getDataFolder().getParentFile(), "UnityLauncher/signData.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(shopFile);

        ConfigurationSection brandSection = config.getConfigurationSection("shops." + shopNaming);
        if (brandSection == null) return false;

        for (String shopId : brandSection.getKeys(false)) {
            ConfigurationSection shopSection = brandSection.getConfigurationSection(shopId);
            if (shopSection != null && owner.equalsIgnoreCase(shopSection.getString("owner"))) {
                return true;
            }
        }

        return false;
    }
    private Block findNearestContainer(Location origin, int radius) {
        World world = origin.getWorld();
        Block nearest = null;
        double minDistanceSquared = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block block = world.getBlockAt(origin.clone().add(x, y, z));
                    if (block.getState() instanceof Container) {
                        double distanceSquared = origin.distanceSquared(block.getLocation());
                        if (distanceSquared < minDistanceSquared) {
                            minDistanceSquared = distanceSquared;
                            nearest = block;
                        }
                    }
                }
            }
        }

        return nearest;
    }
    private String isSignWithinMarker(Location signLocation) {
        System.out.println("[DEBUG] Проверка таблички на маркеры: " + signLocation);

        Optional<BlueMapAPI> apiOptional = BlueMapAPI.getInstance();
        if (apiOptional.isPresent()) {
            BlueMapAPI api = apiOptional.get();
            System.out.println("[DEBUG] BlueMapAPI получен");

            Optional<BlueMapMap> mapOptional = api.getMap(signLocation.getWorld().getName());
            if (mapOptional.isPresent()) {
                BlueMapMap map = mapOptional.get();
                System.out.println("[DEBUG] Карта найдена: " + signLocation.getWorld().getName());

                MarkerSet markerSet = map.getMarkerSets().get("zones_shop");
                if (markerSet != null) {
                    System.out.println("[DEBUG] Найден MarkerSet с ID 'zones_shops'. Кол-во маркеров: " + markerSet.getMarkers().size());

                    for (Marker marker : markerSet.getMarkers().values()) {
                        if (marker instanceof ExtrudeMarker) {
                            ExtrudeMarker extrudeMarker = (ExtrudeMarker) marker;
                            Shape baseShape = extrudeMarker.getShape();
                            double minHeight = extrudeMarker.getShapeMinY();
                            double maxHeight = extrudeMarker.getShapeMaxY();
                            String label = extrudeMarker.getLabel();

                            System.out.println("[DEBUG] Проверка ExtrudeMarker '" + label + "'");
                            System.out.println(" - Высота: " + minHeight + " до " + maxHeight);
                            System.out.println(" - Форма: " + baseShape.getPoints().length + " точек");

                            Vector2d signPos2D = new Vector2d(signLocation.getX(), signLocation.getZ());
                            double y = signLocation.getY();

                            boolean insidePolygon = isPointInsidePolygon(signPos2D, Arrays.asList(baseShape.getPoints()));
                            boolean insideHeight = y >= minHeight && y <= maxHeight;

                            System.out.println(" - Позиция таблички 2D: " + signPos2D + " (Y: " + y + ")");
                            System.out.println(" - Внутри полигона? " + insidePolygon);
                            System.out.println(" - В пределах высоты? " + insideHeight);

                            if (insidePolygon && insideHeight) {
                                System.out.println("[DEBUG] Табличка попала внутрь маркера: " + label);
                                return label;
                            }
                        }
                    }

                    System.out.println("[DEBUG] Табличка не попала ни в один маркер в MarkerSet 'shops'");
                } else {
                    System.out.println("[DEBUG] MarkerSet с ID 'shops' не найден.");
                }
            } else {
                System.out.println("[DEBUG] Карта не найдена для мира: " + signLocation.getWorld().getName());
            }
        } else {
            System.out.println("[DEBUG] BlueMapAPI не инициализирован!");
        }

        return "";
    }
    private boolean isPointInsidePolygon(Vector2d point, List<Vector2d> polygon) {
        boolean result = false;
        int j = polygon.size() - 1;
        for (int i = 0; i < polygon.size(); i++) {
            if ((polygon.get(i).getY() > point.getY()) != (polygon.get(j).getY() > point.getY()) &&
                    (point.getX() < (polygon.get(j).getX() - polygon.get(i).getX()) * (point.getY() - polygon.get(i).getY()) / (polygon.get(j).getY() - polygon.get(i).getY()) + polygon.get(i).getX())) {
                result = !result;
            }
            j = i;
        }
        return result;
    }

    public boolean hasPermissionContaining(Player player, String permission) {
        for (PermissionAttachmentInfo permInfo : player.getEffectivePermissions()) {
            if (permInfo.getPermission().contains(permission)) {
                return true; // Игрок имеет право, содержащее "head"
            }
        }
        return false; // Игрок не имеет права, содержащего "head"
    }

    private boolean isAttachedToBlock(Block signBlock, Block possibleSupportingBlock) {
        if (!(signBlock.getState() instanceof Sign)) return false;

        // Для обычных табличек проверяем прикрепленный блок с помощью метода getAttachedFace
        try {
            org.bukkit.material.Sign signMaterial = (org.bukkit.material.Sign) signBlock.getState().getData();
            Block attachedBlock = signBlock.getRelative(signMaterial.getAttachedFace());
            return attachedBlock.equals(possibleSupportingBlock);
        } catch (ClassCastException e) {
            // Если это не обычная табличка, например Hanging Sign или другой тип, не обрабатываем
            return false;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e){
        webSocketManager.connectPlayer(e.getPlayer().getName());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block brokenBlock = event.getBlock();
        Player player = event.getPlayer();

        // Проверка на случай, если сам блок является табличкой или hanging sign
        if (brokenBlock.getType().toString().contains("SIGN")) {
            if (brokenBlock.getState() instanceof Sign) {
                Sign sign = (Sign) brokenBlock.getState();
                if (sign.getLine(0).equalsIgnoreCase("ATM")) {
                    for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
                        if (entry.getKey().equals(sign.getLocation())) {
                            // Проверяем, совпадает ли ник игрока с ником, который установил табличку
                            if (!entry.getValue().getOwnerName().equals(player.getName())) {
                                player.sendMessage(ChatColor.RED + "Вы не можете сломать эту табличку, так как её установил другой игрок.");
                                event.setCancelled(true);
                                return;
                            }
                            removeBlueMapMarker(Integer.parseInt(genericSignList.get(sign.getLocation()).markerID));
                            genericSignList.remove(sign.getLocation());
                            break;
                        }
                    }
                }
            }
        } else {
            // Проверка соседних блоков на наличие табличек (включая hanging signs), которые зависят от этого блока
            for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
                Location signLocation = entry.getKey();
                Block signBlock = signLocation.getBlock();

                // Проверяем, прикреплена ли табличка (обычная или hanging) к разрушенному блоку
                if (isAttachedToBlock(signBlock, brokenBlock)) {
                    // Проверяем, совпадает ли ник игрока с ником, который установил табличку
                    if (!entry.getValue().getOwnerName().equals(player.getName())) {
                        player.sendMessage(ChatColor.RED + "Вы не можете сломать эту табличку, так как её установил другой игрок.");
                        event.setCancelled(true);
                        return;
                    }
                    //atmSignData.remove(idToRemove);
                    removeBlueMapMarker(Integer.parseInt(genericSignList.get(signLocation).markerID));
                    genericSignList.remove(signLocation);
                   // saveSignData();
                    break;
                }
            }
        }
    }

    public void addBlueMapMarker(String id, Location location, String setID, String setLabel, String markerType, List<Vector3d> extrudePoints, Player p) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                blueMapAPI.getMap(location.getWorld().getName()).ifPresent(map -> {
                    // Проверяем наличие MarkerSet
                    Map<String, MarkerSet> markerSets = map.getMarkerSets();
                    if (markerSets != null) {
                        MarkerSet markerSet = markerSets.computeIfAbsent(setID, k -> new MarkerSet(setLabel));
                        if (markerSet != null && markerSet.getMarkers() != null) {
                            switch (markerType) {
                                case "extrude":
                                    // Преобразуем extrudePoints в Vector2d для новой фигуры
                                    List<Vector2d> basePoints = extrudePoints.stream()
                                            .map(loc -> new Vector2d(loc.getX(), loc.getZ()))
                                            .collect(Collectors.toList());
                                    Shape newShape = new Shape(basePoints);

                                    // Проверяем пересечение с существующими ExtrudeMarker
                                    for (Marker existingMarker : markerSet.getMarkers().values()) {
                                        if (existingMarker instanceof ExtrudeMarker) {
                                            ExtrudeMarker extrudeMarker = (ExtrudeMarker) existingMarker;
                                            if (shapesIntersect(newShape, extrudeMarker.getShape())) {
                                                p.sendMessage(ChatColor.RED + "На этой территории уже создан другой магазин.");
                                                Bukkit.getLogger().warning("Маркер пересекается с существующим: " + extrudeMarker.getLabel());
                                                return; // Прекращаем добавление нового маркера
                                            } else {
                                                //saveShopData(p, id);
                                                p.sendMessage(ChatColor.GREEN + "Торговая точка успешно создана!");
                                                markerPoints.clear();
                                                awaitingCorrectCommand.remove(p);
                                            }
                                        }
                                    }

                                    // Добавляем новый маркер, если пересечений нет
                                    ExtrudeMarker extrudeMarker = new ExtrudeMarker(id, newShape, 42, 152);
                                    extrudeMarker.setLabel(id);
                                    markerSet.getMarkers().put(id, extrudeMarker);
                                    break;

                                case "point":
                                    // Создаём POI-маркер
                                    Vector3d position = new Vector3d(location.getX() + 0.5, location.getY(), location.getZ() + 0.5);
                                    POIMarker marker = new POIMarker("atm_" + id, position);
                                    marker.setLabel("ATM");
                                    marker.setIcon("assets/atm.png", 8, 8);
                                    markerSet.getMarkers().put(String.valueOf(id), marker);
                                    break;

                                default:
                                    Bukkit.getLogger().warning("Unknown markerType: " + markerType);
                                    break;
                            }
                        } else {
                            Bukkit.getLogger().warning("MarkerSet or Markers map is null for setID: " + setID);
                        }
                    } else {
                        Bukkit.getLogger().warning("MarkerSets is null for map: " + location.getWorld().getName());
                    }
                });
            });
        }
    }
    private boolean shapesIntersect(Shape shape1, Shape shape2) {
        GeometryFactory geometryFactory = new GeometryFactory();

        // Преобразуем точки в массив координат и замыкаем линию
        Coordinate[] coords1 = ensureClosed(Arrays.asList(shape1.getPoints()));
        Coordinate[] coords2 = ensureClosed(Arrays.asList(shape2.getPoints()));

        // Создаем полигоны
        Polygon polygon1 = geometryFactory.createPolygon(coords1);
        Polygon polygon2 = geometryFactory.createPolygon(coords2);

        // Проверяем пересечение
        return polygon1.intersects(polygon2);
    }

    /**
     * Проверяет, что линии замкнуты, и добавляет первый элемент в конец массива, если необходимо.
     */
    private Coordinate[] ensureClosed(List<Vector2d> points) {
        List<Coordinate> coordinates = points.stream()
                .map(point -> new Coordinate(point.getX(), point.getY())) // Замените на ваши реальные поля
                .collect(Collectors.toList());

        // Если первый и последний координаты не совпадают, добавляем замыкающую точку
        if (!coordinates.get(0).equals(coordinates.get(coordinates.size() - 1))) {
            coordinates.add(new Coordinate(coordinates.get(0)));
        }

        return coordinates.toArray(new Coordinate[0]);
    }

    private void removeBlueMapMarker(int id) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                blueMapAPI.getMap("world").ifPresent(map -> {
                    MarkerSet markerSet = map.getMarkerSets().get("services");
                    if (markerSet != null) {
                        markerSet.getMarkers()
                                .remove("atm_" + id);
                    }
                });
            });
        }
    }

    public void saveBlueMapMarkers(String setID) {
        if (Bukkit.getPluginManager().isPluginEnabled("BlueMap")) {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                // Перебираем все доступные карты (мира)
                blueMapAPI.getMaps().forEach(map -> {
                    MarkerSet markerSet = map.getMarkerSets().get(setID);

                    if (markerSet != null) {
                        // Определяем путь к файлу маркеров
                        File markerFile = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/" + setID + ".json");

                        // Проверка существования файла; если не существует - создаем
                        if (!markerFile.exists()) {
                            try {
                                markerFile.getParentFile().mkdirs(); // Создаем родительскую директорию, если необходимо
                                markerFile.createNewFile(); // Создаем файл
                            } catch (IOException e) {
                                getLogger().severe("Ошибка при создании файла маркеров: " + markerFile.getAbsolutePath());
                                e.printStackTrace();
                                return; // Остановить выполнение, если не удалось создать файл
                            }
                        }

                        // Сохранение маркеров в файл
                        try (FileWriter writer = new FileWriter(markerFile)) {
                            MarkerGson.INSTANCE.toJson(markerSet, writer);
                        } catch (IOException ex) {
                            getLogger().severe("Ошибка при сохранении маркеров BlueMap.");
                            ex.printStackTrace();
                        }
                    }
                });
            });
        }
    }
    private void loadBlueMapMarkers() {
            BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                // Перебираем все доступные карты (мира)
                blueMapAPI.getMaps().forEach(map -> {
                    // Указываем путь к папке, где хранятся маркеры
                    File markerDirectory = new File(getDataFolder().getParentFile(), "BMMarker/customData/" + map.getId() + "/");
                    if (markerDirectory.exists() && markerDirectory.isDirectory()) {
                        // Перебираем все файлы в директории
                        File[] markerFiles = markerDirectory.listFiles((dir, name) -> name.endsWith(".json"));

                        if (markerFiles != null) {
                            for (File markerFile : markerFiles) {
                                try (FileReader reader = new FileReader(markerFile)) {
                                    MarkerSet markerSet = MarkerGson.INSTANCE.fromJson(reader, MarkerSet.class);
                                    // Используем имя файла (без расширения) в качестве ключа для MarkerSet
                                    String markerSetName = markerFile.getName().replace(".json", "");
                                    map.getMarkerSets().put(markerSetName, markerSet);
                                } catch (IOException ex) {
                                    getLogger().severe("Ошибка при загрузке маркеров BlueMap из файла: " + markerFile.getName());
                                    ex.printStackTrace();
                                }
                            }
                        }
                    } else {
                        getLogger().warning("Папка с маркерами не найдена или не является директорией: " + markerDirectory.getAbsolutePath());
                    }
                });
            });

    }
    public void loadSignData() {
        File shopFile = new File(getDataFolder(), "signData.yml");
        if (!shopFile.exists()) return;

        YamlConfiguration shopConfig = YamlConfiguration.loadConfiguration(shopFile);
        ConfigurationSection signsSection = shopConfig.getConfigurationSection("signs");
        if (signsSection == null) return;

        for (String key : signsSection.getKeys(false)) {
            ConfigurationSection section = signsSection.getConfigurationSection(key);
            if (section == null) continue;

            // Восстановление локации
            String worldName = section.getString("location.world");
            int x = section.getInt("location.x");
            int y = section.getInt("location.y");
            int z = section.getInt("location.z");

            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            Location loc = new Location(world, x, y, z);

            // Восстановление переменных
            List<String> text = section.getStringList("text");
            List<Integer> scrollLines = section.getIntegerList("scrollLines");
            boolean isConfigurable = section.getBoolean("isConfigurable");
            boolean isPaused = section.getBoolean("isPaused");
            String owner = section.getString("owner");
            String markerID = section.getString("markerID");

            SignCategory category = null;
            SignState state = null;

            try {
                category = SignCategory.valueOf(section.getString("category", "SHOP_INFO"));
            } catch (IllegalArgumentException ignored) {}

            try {
                state = SignState.valueOf(section.getString("state", "MENU"));
            } catch (IllegalArgumentException ignored) {}

            // Обновляем блок на табличку, если это возможно
            Block block = loc.getBlock();
            if (block.getType().toString().contains("SIGN")) {
                Sign sign = (Sign) block.getState();
                for (int i = 0; i < Math.min(4, text.size()); i++) {
                    sign.setLine(i, text.get(i));
                }
                sign.update();
            }

            // Добавляем в мапу
            genericSignList.put(loc, new SignVariables(owner, text, scrollLines, isConfigurable, isPaused, category, state, markerID));
        }

        Bukkit.getLogger().info("Таблички успешно загружены из signData.yml");
    }
    public void loadZoneData() {
        File zoneFile = new File(getDataFolder(), "zones.yml");
        if (zoneFile.exists()) {
            YamlConfiguration zoneConfig = YamlConfiguration.loadConfiguration(zoneFile);

            for (String typeKey : zoneConfig.getKeys(false)) { // "hospital", "shop" и т.д.
                ConfigurationSection typeSection = zoneConfig.getConfigurationSection(typeKey);
                if (typeSection == null) continue;

                for (String playerName : typeSection.getKeys(false)) {
                    ConfigurationSection playerSection = typeSection.getConfigurationSection(playerName);
                    if (playerSection == null) continue;

                    for (String zoneID : playerSection.getKeys(false)) {
                        ConfigurationSection zoneSection = playerSection.getConfigurationSection(zoneID);
                        if (zoneSection == null) continue;

                        String zoneName = zoneSection.getString("name");
                        String markerID = zoneSection.getString("marker_ID");

                        List<Location> corners = new ArrayList<>();
                        List<Map<?, ?>> rawCorners = zoneSection.getMapList("corners");
                        for (Map<?, ?> cornerMap : rawCorners) {
                            try {
                                String worldName = (String) cornerMap.get("world");
                                double x = (double) cornerMap.get("x");
                                double y = (double) cornerMap.get("y");
                                double z = (double) cornerMap.get("z");

                                World world = Bukkit.getWorld(worldName);
                                if (world == null) continue;

                                Location cornerLoc = new Location(world, x, y, z);
                                corners.add(cornerLoc);
                            } catch (Exception e) {
                                Bukkit.getLogger().warning("Ошибка при загрузке угла зоны: " + e.getMessage());
                            }
                        }
                        List<Vector2d> corners2D = corners.stream()
                                .map(cornerLoc -> new Vector2d(cornerLoc.getX(), cornerLoc.getZ()))
                                .collect(Collectors.toList());
                        for (Location loc : genericSignList.keySet()) {
                            Vector2d point = new Vector2d(loc.getX(), loc.getZ());
                            if (isPointInsidePolygon(point, corners2D)) {
                                genericSignList.get(loc).setOwnerName(playerName);
                            }
                        }

                        // Пример: логгирование загрузки
                        Bukkit.getLogger().info("Загружена зона: " + typeKey + " / " + playerName + " → " + zoneID + " (" + zoneName + ")");
                    }
                }
            }
        }
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
  /*  private static class ATMData {
        private final Location location;
        private final String group;
        private final String[] lines;

        public ATMData(Location location, String group, String[] lines) {
            this.location = location;
            this.group = group;
            this.lines = lines;
        }

        public Location getLocation() {
            return location;
        }

        public String getGroup() {
            return group;
        }

        public String[] getLines() { return lines; }
    }
    */

}
