package com.frammy.unitylauncher.signs;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.ZoneManager;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class SignManager implements Listener {
    private final UnityLauncher unityLauncher;
    private final Map<Location, String[]> originalSignTexts = new HashMap<>();
    public Map<Location, SignVariables> genericSignList = new HashMap<>();
    public final Map<Location, BukkitTask> scrollingTasks = new HashMap<>();
    private final Map<UUID, Integer> playerScrollIndex = new HashMap<>();
    private final Map<Location, List<String>> signPages = new HashMap<>();
    private final Map<String, Runnable> actions = new HashMap<>();
    private final Map<Location, Runnable> signClickActions = new HashMap<>();
    private final Map<Player, Block> signSelectionMap = new HashMap<>();
    private final Map<Location, BukkitTask> resetTasks = new HashMap<>();
    private final ZoneManager zoneManager;

    private final UnityCommands unityCommands;
    private final BlueMapIntegration blueMapIntegration;

    public SignManager(UnityLauncher unityLauncher, File dataFolder, ZoneManager zoneManager, BlueMapIntegration blueMapIntegration, UnityCommands unityCommands) {
        this.unityLauncher = unityLauncher;
        this.dataFolder = dataFolder;
        this.zoneManager = zoneManager;
        this.blueMapIntegration = blueMapIntegration;
        this.unityCommands = unityCommands;
    }

    private final File dataFolder;
    public File getDataFolder() {
        return dataFolder;
    }
    public UnityLauncher getPlugin() {
        return unityLauncher;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        Sign sign = (Sign) e.getBlock().getState();
        Player p = e.getPlayer();
        String[] oldLines = sign.getLines();
        String[] newLines = e.getLines();

        if (genericSignList.containsKey(sign.getLocation())) {
            if (genericSignList.get(sign.getLocation()).getSignState() == SignState.SHOP_DEFINED && genericSignList.get(sign.getLocation()).getSignCategory().equals(SignCategory.SHOP_SOURCE)) {
                p.sendMessage(ChatColor.RED + "Для редактирования таблички присядь и нажми ЛКМ.");
                e.setCancelled(true);
                resumeScrolling(sign.getLocation());
                return;
            }
        }

        if (!e.getBlock().getType().toString().contains("HANGING")) {
            if (genericSignList.get(sign.getLocation()) != null) {
                resumeScrolling(sign.getLocation());
            }
            if (genericSignList.containsKey(sign.getLocation())) {
                if (genericSignList.get(sign.getLocation()).getOwnerName().equals(p.getName())) {
                    if (!oldLines[0].equals(newLines[0])) {
                        p.sendMessage(ChatColor.RED + "Изменение первой строки невозможно. "  + ChatColor.GRAY + "\nДля изменения цели таблички сломайте её и установите с новыми параметрами.");
                        e.setCancelled(true);
                        return;
                    }
                } else {
                    p.sendMessage(ChatColor.RED + "Ты не являешься владельцем этого магазина!");
                    return;
                }
            }

            if (e.getLine(0).equalsIgnoreCase("shop") || e.getLine(0).equalsIgnoreCase("магазин")) {
                ExtrudeMarker marker = isSignWithinMarker(sign.getLocation(), "zones_shop");
                String label = marker.getLabel();
                if (label.isEmpty()) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Табличку о продаже можно ставить только в пределах магазина!");
                } else {
                    //  if (zoneManager.getZoneOwner("shop", marker.get)) {}
                    String line0 = "Торговая точка [ " + label + " ]";
                    e.setLine(0, line0);
                    Map<Integer, String> linesToScroll = new HashMap<>();
                    linesToScroll.put(0, line0);

                    switch (e.getLine(1)) {
                        case "source":
                        case "источник":
                            Block nearestStorage = findNearestContainer(sign.getLocation(), 5, p);
                            makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll, 6, 13);
                            if (nearestStorage != null) {
                                Location loc = nearestStorage.getLocation();
                                String line1 = loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ();
                                e.setLine(1, line1);
                                e.setLine(2, "<Количество>");
                                e.setLine(3, "<Цена>");
                                p.sendMessage(ChatColor.GRAY + "Координаты источника установлены.\n" +
                                        "Чтобы выбрать другое хранилище — кликните ЛКМ по табличке, затем откройте нужное хранилище.");//
                                genericSignList.put(sign.getLocation(), new SignVariables(p.getName(), Arrays.asList(line0, line1, "<Количество>", "<Цена>"), List.of(0), true, false, SignCategory.SHOP_SOURCE, SignState.SHOP_UNDEFINED, null));
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
                            if (marker != null) {
                                Map<Integer, String> linesToScroll1 = new HashMap<>();
                                linesToScroll1.put(0, line0);
                                makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll1, 6, 13);

                                List<Location> sourceSignLocations = genericSignList.entrySet().stream()
                                        .filter(en -> en.getValue().getSignCategory() == SignCategory.SHOP_SOURCE)
                                        .filter(en -> isSignWithinMarker(en.getKey(), "zones_shop").equals(marker))
                                        .map(Map.Entry::getKey)
                                        .toList();

                                List<Block> containers = new ArrayList<>();
                                for (Location loc : sourceSignLocations) {
                                    SignVariables sourceVars = genericSignList.get(loc);
                                    if (sourceVars == null || sourceVars.getSignText().size() < 2) continue;

                                    String[] coords = sourceVars.getSignText().get(1).split(" ");
                                    if (coords.length != 3) continue;

                                    try {
                                        int x = Integer.parseInt(coords[0]);
                                        int y = Integer.parseInt(coords[1]);
                                        int z = Integer.parseInt(coords[2]);
                                        Block containerBlock = loc.getWorld().getBlockAt(x, y, z);
                                        if (containerBlock.getState() instanceof Container) {
                                            containers.add(containerBlock);
                                        }
                                    } catch (NumberFormatException ignored) {}
                                }

                             /*   Map<String, Integer> summary = zoneManager.getItemSummaryFromContainers(containers);
                                List<String> itemLines = summary.entrySet().stream()
                                        .map(ent -> Arrays.stream(ent.getKey().split("_"))
                                                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase())
                                                .collect(Collectors.joining(" ")) + ": " + ent.getValue())
                                        .toList();

                                signPages.put(sign.getLocation(), itemLines);*/
                                //updateAllRelatedShopListSigns(containers.get(0).getLocation());
                                playerScrollIndex.put(p.getUniqueId(), 0);

                                genericSignList.put(sign.getLocation(), new SignVariables(
                                        p.getName(),
                                        Arrays.asList(line0, "<Загрузка>", "<...", "...>"),
                                        List.of(0),
                                        false,
                                        false,
                                        SignCategory.SHOP_LIST,
                                        SignState.SHOP_DEFINED,
                                        null
                                ));

                                p.sendMessage(ChatColor.GREEN + "Список товаров обновлён. Используйте колёсико мыши для прокрутки.");
                            } else {
                                p.sendMessage(ChatColor.RED + "Вы должны находиться в зоне магазина.");
                            }
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
            if (e.getLine(0).equalsIgnoreCase("ATM")) {
                if (unityCommands.hasPermissionContaining(p, "0")) {
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
                    blueMapIntegration.addBlueMapMarker(markerID, sign.getLocation(), "services", "Сервисы", "point_atm", null, p);
                } else {
                    p.sendMessage(ChatColor.RED  +"Недостаточно прав.");
                }
            }
        } else {
            if (e.getLine(0).equalsIgnoreCase("ATM")) {
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве банковского автомата!");
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();

        // Проверяем, что закрыт именно контейнер
        if (!(inventory.getHolder() instanceof Container container)) return;

        Block block = ((Container) inventory.getHolder()).getBlock();
        Location containerLocation = block.getLocation();

        // Находим все таблички, привязанные к этому контейнеру
        for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
            SignVariables vars = entry.getValue();

            if (vars.getSignCategory() != SignCategory.SHOP_SOURCE) continue;
            if (vars.getSignText().size() < 2) continue;

            String[] coords = vars.getSignText().get(1).split(" ");
            if (coords.length != 3) continue;

            try {
                int x = Integer.parseInt(coords[0]);
                int y = Integer.parseInt(coords[1]);
                int z = Integer.parseInt(coords[2]);
                Location storedLoc = new Location(block.getWorld(), x, y, z);

                if (storedLoc.equals(containerLocation)) {
                    updateAllRelatedShopListSigns(storedLoc);
                }
            } catch (NumberFormatException ignored) {}
        }
    }
    private void updateAllRelatedShopListSigns(Location containerLocation) {
        ExtrudeMarker marker = isSignWithinMarker(containerLocation, "zones_shop");
        if (marker == null) return;

        // Найти все таблички типа SHOP_LIST в этой зоне
        for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
            Location signLoc = entry.getKey();
            SignVariables vars = entry.getValue();

            if (vars.getSignCategory() != SignCategory.SHOP_LIST) continue;
            if (!isSignWithinMarker(signLoc, "zones_shop").equals(marker)) continue;

            // Найти все source таблички этой зоны
            List<Location> sourceSignLocations = genericSignList.entrySet().stream()
                    .filter(e -> e.getValue().getSignCategory() == SignCategory.SHOP_SOURCE)
                    .filter(e -> isSignWithinMarker(e.getKey(), "zones_shop").equals(marker))
                    .map(Map.Entry::getKey).toList();

            List<Block> containers = new ArrayList<>();
            for (Location loc : sourceSignLocations) {
                SignVariables sourceVars = genericSignList.get(loc);
                if (sourceVars == null || sourceVars.getSignText().size() < 2) continue;

                String[] coords = sourceVars.getSignText().get(1).split(" ");
                if (coords.length != 3) continue;

                try {
                    int x = Integer.parseInt(coords[0]);
                    int y = Integer.parseInt(coords[1]);
                    int z = Integer.parseInt(coords[2]);
                    Block block = loc.getWorld().getBlockAt(x, y, z);
                    if (block.getState() instanceof Container) {
                        containers.add(block);
                    }
                } catch (NumberFormatException ignored) {}
            }

            Map<String, Integer> summary = zoneManager.getItemSummaryFromContainers(containers);
            List<String> itemLines = summary.entrySet().stream()
                    .map(e -> Arrays.stream(e.getKey().split("_"))
                            .map(w -> w.charAt(0) + w.substring(1).toLowerCase())
                            .collect(Collectors.joining(" ")) + ": " + e.getValue())
                    .collect(Collectors.toList());


            signPages.put(signLoc, itemLines);
            playerScrollIndex.put(Bukkit.getOfflinePlayer(vars.getOwnerName()).getUniqueId(), 0);


            Block block = signLoc.getBlock();
            if (block.getState() instanceof Sign sign) {
                updateSignView(sign, itemLines, 0);
            }
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
            SignVariables signVariables = genericSignList.get(sign.getLocation());

            if (signVariables == null) return;

            //
            //Some purchase action here
            //

            if (!genericSignList.get(loc).getOwnerName().equalsIgnoreCase(p.getName())) return;

            if (signVariables.getSignState() == SignState.SHOP_UNDEFINED) {
                if (p.isSneaking()) {
                    if (!sign.getLine(2).isEmpty() && !sign.getLine(3).isEmpty()) {
                        double price;
                        int amount;
                        try {
                            amount = Integer.parseInt(ChatColor.stripColor(sign.getLine(2)));
                            price = Double.parseDouble(ChatColor.stripColor(sign.getLine(3)));

                        } catch (NumberFormatException exc) {
                            p.sendMessage(ChatColor.RED + "3 и 4 строки должны быть числами.");
                            sign.setLine(2, "<Количество>");
                            sign.setLine(3, "<Цена>");
                            sign.update();
                            return;
                        }
                        List<String> signTexts = genericSignList.get(sign.getLocation()).getSignText();
                        String line3 = "Цена: " + ChatColor.GREEN + String.valueOf(price);
                        String line2 = "Кол-во: " + ChatColor.YELLOW + String.valueOf(amount);

                        signVariables.setSignText(Arrays.asList(signTexts.get(0), signTexts.get(1), line2, line3));
                        sign.setLine(2, line2);
                        sign.setLine(3, line3);
                        sign.update();
                        signVariables.setSignState(SignState.SHOP_DEFINED);
                        if (genericSignList.get(sign.getLocation()).getMarkerID() == null) {
                            String markerID = "marker_" + UUID.randomUUID();
                            signVariables.setMarkerID(markerID);
                            blueMapIntegration.addBlueMapMarker(markerID, sign.getLocation(), "services", "Сервисы", "point_shop", null, p);
                        }
                        p.sendMessage(ChatColor.GREEN + "Табличка товара подтверждена.");
                    }
                    return;
                }
                String secondLine = ChatColor.stripColor(sign.getLine(1)).toLowerCase();

                if (signVariables.getSignCategory() == SignCategory.SHOP_SOURCE) {
                    if (!secondLine.isEmpty()) {
                        signSelectionMap.put(p, b); // добавляем игрока в режим выбора
                        p.sendMessage(ChatColor.YELLOW + "Теперь открой нужное хранилище, чтобы выбрать его.");
                        e.setCancelled(true); // предотвращаем случайный удар по табличке
                    }
                }
            }
            if (signVariables.getSignState() == SignState.SHOP_DEFINED && signVariables.getSignCategory().equals(SignCategory.SHOP_SOURCE)) {
                if (p.isSneaking()) {
                    List<String> text = signVariables.getSignText();
                    String line2 = text.get(2).replace("Кол-во: " + ChatColor.YELLOW , ChatColor.RESET + "");
                    String line3 = text.get(3).replace( "Цена: " + ChatColor.GREEN, ChatColor.RESET + "");
                    signVariables.setSignText(Arrays.asList(text.get(0), text.get(1), line2, line3));
                    sign.setLine(2, line2);
                    sign.setLine(3, line3);
                    sign.update();

                    p.sendMessage(ChatColor.GRAY + "Табличка переключена в режим редактирования.");
                    genericSignList.get(loc).setSignState(SignState.SHOP_UNDEFINED);
                }
            }
        }

        SignVariables vars = genericSignList.get(loc);
        SignState state = (vars != null) ? vars.getSignState() : SignState.ATM_MENU;
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
                genericSignList.get(loc).setSignState(SignState.ATM_MENU);
                scheduleSignReset(loc);
                return;
            }
        }

        // ===== В режиме MENU (скроллим) =====
        if (state == SignState.ATM_MENU) {
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
                    if (svars == null || svars.getSignState() != SignState.ATM_ACTION_READY) {
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
        if (state == SignState.ATM_ACTION_READY) {
            Runnable signAction = signClickActions.remove(loc);
            if (signAction != null) {
                e.setCancelled(true);
                signAction.run();
            }
            // Возврат к обычному режиму
            genericSignList.get(loc).setSignState(SignState.ATM_MENU);
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
        if (items == null || items.size() == 3) return;

        List<String> allStrings = new ArrayList<>();
        for (List<String> list : signPages.values()) {
            allStrings.addAll(list);
        }

        if (allStrings.size() < 3) {
            System.out.println("Точно меньше или равно 3");
            int toAdd = 3 - allStrings.size();
            for (int i = 0; i < toAdd; i++) {
                System.out.println("Отображаем пустую строку " + i);
                signPages.get(loc).add(" "); // добавляем пустые строки
            }
        }

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
    public void onBlockBreak(BlockBreakEvent event) {
        Block brokenBlock = event.getBlock();
        Player player = event.getPlayer();

        // Проверка на случай, если сам блок является табличкой или hanging sign
        if (brokenBlock.getType().toString().contains("SIGN")) {
            if (brokenBlock.getState() instanceof Sign) {
                Sign sign = (Sign) brokenBlock.getState();
                Location loc = sign.getLocation();
                if (genericSignList.containsKey(loc)) {
                    if (!genericSignList.get(loc).getOwnerName().equals(player.getName())) {
                        player.sendMessage(ChatColor.RED + "Вы не можете сломать эту табличку, так как её установил другой игрок.");
                        event.setCancelled(true);
                    } else {
                        blueMapIntegration.removeBlueMapMarker(genericSignList.get(loc).getMarkerID(), loc.getWorld().getName(), "services");
                        genericSignList.remove(loc);
                        stopScrollingTask(loc);
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
                    blueMapIntegration.removeBlueMapMarker(genericSignList.get(signLocation).getMarkerID(), signLocation.getWorld().getName(), "services");
                    genericSignList.remove(signLocation);
                    stopScrollingTask(signLocation);
                    // saveSignData();
                    break;
                }
            }
        }
    }

    @EventHandler
    public  void onPlayerLeave(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if(unityLauncher.getAwaitingCorrectCommand().contains(p.getName())) {
            UnityCommands.getInstance().setShops(p,UnityCommands.getInstance().getShops(p) + 1);
            unityLauncher.getAwaitingCorrectCommand().remove(p);
        }
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
            genericSignList.get(loc).setSignState(SignState.ATM_ACTION_READY);
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
                    case "страна", "государство", "стр", "ст", "с", "country" ->
                            p.sendMessage(ChatColor.GRAY + "С счёта государства было снято " + amount + "F.");
                    case "игрок", "я", "мой счёт", "me", "игр", "иг" -> {
                        p.sendMessage(ChatColor.GRAY + "С твоего счёта было снято " + amount + "F.");
                        unityLauncher.moneyManager.giveMoney(p, amount);
                    }
                    case "admin", "админ" ->
                            p.sendMessage(ChatColor.YELLOW + "Слушай, а ловко ты это придумал. Я даже в начале не понял.");
                    default ->
                            p.sendMessage(ChatColor.RED + "Необходимо указать счёт, с которого будут сняты деньги - 'Страна' или 'Игрок'.");
                }
            });
        });
        actions.put("Взнос наличных", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Получатель>");
            sign.setLine(3, "<Сумма>");
            sign.update();
            genericSignList.get(loc).setSignState(SignState.ATM_ACTION_READY);
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
                    case "страна", "государство", "стр", "ст", "с", "country" -> {
                        p.sendMessage(ChatColor.GRAY + "На счёт государства было взнесено " + amount + "F.");
                        unityLauncher.moneyManager.takeMoney(p, amount);
                    }
                    case "игрок", "я", "мой счёт", "me", "игр", "иг" -> {
                        p.sendMessage(ChatColor.GRAY + "На твой счёт было взнесено " + amount + "F.");
                        unityLauncher.moneyManager.takeMoney(p, amount);
                    }
                    default ->
                            p.sendMessage(ChatColor.RED + "Необходимо указать счёт, с которого будут сняты деньги - 'Страна' или 'Игрок'.");
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

    // Обновление таблички
    private void updateSignView(Sign sign, List<String> items, int offset) {
        if (items == null || items.isEmpty()) return;
        for (int i = 0; i < 3; i++) {
            int itemIndex = (offset + i) % items.size(); // зацикливание
            String text = items.get(itemIndex);
            if (text.length() > 15) text = text.substring(0, 15);
            sign.setLine(i + 1, text);
        }
        sign.update();
    }

    public void saveSignData() {
        File shopFile = new File(getDataFolder(), "signData.yml");
        YamlConfiguration shopConfig = new YamlConfiguration();

        for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
            Location loc = entry.getKey();
            SignVariables vars = entry.getValue();

            String path = "signs." + unityLauncher.encodeLocation(loc); // Уникальный путь по координатам

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

    public void pauseScrolling(Location location) {
        genericSignList.get(location).setPaused(true);
    }

    public void resumeScrolling(Location location) {
        genericSignList.get(location).setPaused(false);
    }

    public void makeSignScrollingLines(Location signLocation, Map<Integer, String> originalLines, int intervalTicks, int maxLength) {
        Block block = signLocation.getBlock();
        if (!(block.getState() instanceof Sign)) return;

        Sign sign = (Sign) block.getState();
        Map<Integer, String> scrollBuffers = new HashMap<>();
        for (Map.Entry<Integer, String> entry : originalLines.entrySet()) {
            int lineIndex = entry.getKey();
            String text = entry.getValue();

            if (text.length() <= maxLength) {
                sign.setLine(lineIndex, text);
            } else {
                String base = text + "   ";
                scrollBuffers.put(lineIndex, base + base);
            }
        }
        sign.update();

        // Если нет строк, которые нужно скроллить — выходим
        if (scrollBuffers.isEmpty()) return;

        AtomicInteger offset = new AtomicInteger(0);

        // Остановим предыдущую анимацию для этой таблички
        if (scrollingTasks.containsKey(signLocation)) {
            stopScrollingTask(signLocation);
        }

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(unityLauncher, () -> {
            if (!(signLocation.getBlock().getState() instanceof Sign)) {
                scrollingTasks.remove(signLocation);
                genericSignList.get(signLocation).setPaused(false);
                return;
            }

            if (genericSignList.get(signLocation).getPaused()) {
                return; // ⏸ Табличка в паузе
            }

            boolean anyNearby = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(player -> player.getWorld().equals(signLocation.getWorld())
                            && player.getLocation().distanceSquared(signLocation) <= 35 * 35);
            if (!anyNearby) return;

            Sign currentSign = (Sign) signLocation.getBlock().getState();
            int pos = offset.getAndUpdate(i -> (i + 1) % unityLauncher.getMaxBaseLength(originalLines.values()));

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

    public void restoreScrollingSignsFromFile(FileConfiguration config) {
        ConfigurationSection signsSection = config.getConfigurationSection("signs");
        if (signsSection == null) return;

        for (String key : signsSection.getKeys(false)) {
            ConfigurationSection signSection = signsSection.getConfigurationSection(key);
            if (signSection == null) continue;

            // Получаем координаты
            String worldName = signSection.getString("location.world");
            int x = signSection.getInt("location.x");
            int y = signSection.getInt("location.y");
            int z = signSection.getInt("location.z");

            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            Location loc = new Location(world, x, y, z);
            Block block = loc.getBlock();
            if (!(block.getState() instanceof Sign)) continue;

            Sign sign = (Sign) block.getState();

            // Получаем индексы строк, которые нужно скроллить
            List<Integer> scrollLines = signSection.getIntegerList("scrollLines");
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
    public void stopScrollingTask(Location loc) {
        BukkitTask task = scrollingTasks.remove(loc);
        if (task != null) {
            task.cancel();
        }
    }

    public void scheduleSignReset(Location loc) {
        // Отменим предыдущую задачу, если была
        if (resetTasks.containsKey(loc)) {
            resetTasks.get(loc).cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(unityLauncher, () -> {
            // Если сброс сейчас в паузе — повторно планируем задачу
            if (genericSignList.get(loc).getPaused()) {
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

    private Block findNearestContainer(Location origin, int radius, Player p) {
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
                            if (isSignWithinMarker(origin, "zones_shop") != null) {
                                minDistanceSquared = distanceSquared;
                                nearest = block;
                            } else {
                                p.sendMessage(ChatColor.RED + "Хранилище должно находится внутри зоны торговой точки.");
                            }

                        }
                    }
                }
            }
        }
        return nearest;
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

    public ExtrudeMarker isSignWithinMarker(Location signLocation, String setName) {
        // System.out.println("[DEBUG] Проверка таблички на маркеры: " + signLocation);

        Optional<BlueMapAPI> apiOptional = BlueMapAPI.getInstance();
        if (apiOptional.isPresent()) {
            BlueMapAPI api = apiOptional.get();
            //  System.out.println("[DEBUG] BlueMapAPI получен");

            Optional<BlueMapMap> mapOptional = api.getMap(signLocation.getWorld().getName());
            if (mapOptional.isPresent()) {
                BlueMapMap map = mapOptional.get();
                // System.out.println("[DEBUG] Карта найдена: " + signLocation.getWorld().getName());

                MarkerSet markerSet = map.getMarkerSets().get(setName);
                if (markerSet != null) {
                     System.out.println("[DEBUG] Найден MarkerSet с ID" + setName + ". Кол-во маркеров: " + markerSet.getMarkers().size());

                    for (Marker marker : markerSet.getMarkers().values()) {
                        if (marker instanceof ExtrudeMarker) {
                            ExtrudeMarker extrudeMarker = (ExtrudeMarker) marker;
                            Shape baseShape = extrudeMarker.getShape();
                            double minHeight = extrudeMarker.getShapeMinY();
                            double maxHeight = extrudeMarker.getShapeMaxY();
                            String label = extrudeMarker.getLabel();

                            // System.out.println("[DEBUG] Проверка ExtrudeMarker '" + label + "'");
                            // System.out.println(" - Высота: " + minHeight + " до " + maxHeight);
                            // System.out.println(" - Форма: " + baseShape.getPoints().length + " точек");

                            Vector2d signPos2D = new Vector2d(signLocation.getX(), signLocation.getZ());
                            double y = signLocation.getY();

                            boolean insidePolygon = zoneManager.isPointInsidePolygon(signPos2D, Arrays.asList(baseShape.getPoints()));
                            boolean insideHeight = y >= minHeight && y <= maxHeight;

                            //System.out.println(" - Позиция таблички 2D: " + signPos2D + " (Y: " + y + ")");
                            // System.out.println(" - Внутри полигона? " + insidePolygon);
                            //System.out.println(" - В пределах высоты? " + insideHeight);

                            if (insidePolygon && insideHeight) {
                                System.out.println("[DEBUG] Табличка попала внутрь маркера: " + label);
                                return extrudeMarker;
                            }
                        }
                    }

                    System.out.println("[DEBUG] Табличка не попала ни в один маркер в MarkerSet" + setName);
                } else {
                    System.out.println("[DEBUG] MarkerSet с ID" + setName + "не найден.");
                }
            } else {
                System.out.println("[DEBUG] Карта не найдена для мира: " + signLocation.getWorld().getName());
            }
        } else {
            System.out.println("[DEBUG] BlueMapAPI не инициализирован!");
        }
        return null;
    }
}