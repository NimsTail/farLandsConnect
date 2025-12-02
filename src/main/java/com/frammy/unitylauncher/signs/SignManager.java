package com.frammy.unitylauncher.signs;

import com.flowpowered.math.vector.Vector2d;
import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.UnityCommands;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.chunkactivity.ZonesEconomyConfig;
import com.frammy.unitylauncher.upgrades.UpgradesConfig;
import com.frammy.unitylauncher.upgrades.UpgradesListener;
import com.frammy.unitylauncher.zones.ZoneManager;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

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
    private final Map<Location, Map<Integer, BukkitTask>> activeScrolls = new HashMap<>();
    Map<Location, List<ItemData>> signItemData = new HashMap<>();
    private final ZoneManager zoneManager;
    private final BlueMapIntegration blueMapIntegration;
    private final Map<Location, Location> containerToSourceSign = new HashMap<>();
    private static UpgradesConfig C;

    public SignManager(UnityLauncher unityLauncher, File dataFolder, ZoneManager zoneManager, BlueMapIntegration blueMapIntegration, UnityCommands unityCommands) {
        this.unityLauncher = unityLauncher;
        this.dataFolder = dataFolder;
        this.zoneManager = zoneManager;
        this.blueMapIntegration = blueMapIntegration;

        if (C == null) C = UpgradesConfig.load(unityLauncher);
    }

    private final File dataFolder;
    public File getDataFolder() {
        return dataFolder;
    }
    public UnityLauncher getPlugin() {
        return unityLauncher;
    }
    private File signsFile() {
        return new File(getDataFolder(), "signs.yml"); // либо твой фактический путь
    }

    // === ATM квоты ===
    private final Map<String, Integer> atmExtraCache = new HashMap<>(); // countryCanonical -> extra
    private long atmExtraCacheLoadedAt = 0L;

    // === МУСОРКИ: лимит табличек на страну ===
    // Пока фиксированная константа. При желании можно вынести в UpgradesConfig / zones-economy.
    private static final int TRASH_SIGN_LIMIT_PER_COUNTRY = 5;

    private int countExistingTrashForCountry(String countryCanonical) {
        if (countryCanonical == null || countryCanonical.isBlank()) return 0;
        int n = 0;
        for (Map.Entry<Location, SignVariables> e : genericSignList.entrySet()) {
            SignVariables sv = e.getValue();
            if (sv.getSignCategory() != SignCategory.TRASH_SELL) continue;
            String owner = sv.getOwnerName();
            String pc = com.frammy.unitylauncher.upgrades.UpgradeCondition.playerCountryCanonical(owner);
            if (countryCanonical.equals(pc)) n++;
        }
        return n;
    }


    private int getBaseAtmLimitForCountry(String countryCanonical) {
        return countryMaxLevel(countryCanonical, C.atmPerm, 40);
    }

    private void reloadAtmExtraIfNeeded() {
        long now = System.currentTimeMillis();
        if ((now - atmExtraCacheLoadedAt) < 10_000L) return; // анти-спам: не чаще раз в 10 сек

        atmExtraCache.clear();
        File f = new File(getDataFolder(), C.atmFile);
        if (!f.exists()) { atmExtraCacheLoadedAt = now; return; }

        try {
            org.bukkit.configuration.file.YamlConfiguration yc =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            org.bukkit.configuration.ConfigurationSection sec = yc.getConfigurationSection("countries");
            if (sec != null) {
                for (String k : sec.getKeys(false)) {
                    int extra = sec.getInt(k, 0);
                    atmExtraCache.put(k.toLowerCase(java.util.Locale.ROOT), Math.max(0, extra));
                }
            }
        } catch (Throwable t) {
            getPlugin().getLogger().severe("[SignManager] Ошибка чтения " + f.getName() + ": " + t.getMessage());
        }
        atmExtraCacheLoadedAt = now;
    }

    private int getAllowedAtm(String countryCanonical) {
        reloadAtmExtraIfNeeded();
        int base = getBaseAtmLimitForCountry(countryCanonical);
        int extra = atmExtraCache.getOrDefault(countryCanonical == null ? "" : countryCanonical.toLowerCase(java.util.Locale.ROOT), 0);
        return Math.max(0, base + extra);
    }

    private int countExistingAtmForCountry(String countryCanonical) {
        if (countryCanonical == null || countryCanonical.isBlank()) return 0;
        int n = 0;
        for (Map.Entry<Location, SignVariables> e : genericSignList.entrySet()) {
            SignVariables sv = e.getValue();
            if (sv.getSignCategory() != SignCategory.ATM) continue;
            String owner = sv.getOwnerName();
            String pc = com.frammy.unitylauncher.upgrades.UpgradeCondition.playerCountryCanonical(owner);
            if (countryCanonical.equals(pc)) n++;
        }
        return n;
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
            if (genericSignList.containsKey(sign.getLocation())) {
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
                    sendPrefixed(p, C.errNotOwner);
                    return;
                }
            }

            if (Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("shop") || Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("магазин")) {
                ExtrudeMarker marker = isSignWithinMarker(sign.getLocation(), "zones_shop");
                if (marker == null) {
                    if (!zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), sign.getLocation())) {
                        e.setCancelled(true);
                        p.sendMessage(ChatColor.RED + "Магазинные таблички можно ставить только в вашей SHOP-зоне.");
                        return;
                    }
                } else {
                    String label = marker.getLabel();
                    //  if (zoneManager.getZoneOwner("shop", marker.get)) {}
                    String line0 = "Торговая точка [ " + label + " ]";
                    e.setLine(0, line0);
                    Map<Integer, String> linesToScroll = new HashMap<>();
                    linesToScroll.put(0, line0);

                    switch (e.getLine(1)) {
                        case "source":
                        case "источник":
                            Block nearestStorage = findNearestContainer(sign.getLocation(), p);
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
                            Map<Integer, String> linesToScroll1 = new HashMap<>();
                            linesToScroll1.put(0, line0);
                            makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll1, 6, 13);

                            playerScrollIndex.put(p.getUniqueId(), 0);

                            SignVariables listVars = new SignVariables(
                                    p.getName(),
                                    Arrays.asList(line0, "...", "Загрузка", "..."),
                                    List.of(0),
                                    false,
                                    false,
                                    SignCategory.SHOP_LIST,
                                    SignState.SHOP_DEFINED,
                                    null
                            );
                            genericSignList.put(sign.getLocation(), listVars);

                            // ⏳ ОТЛОЖЕННО обновляем список товаров, чтобы успела сохраниться табличка
                            Bukkit.getScheduler().runTask(UnityLauncher.getInstance(), () -> updateAllRelatedShopListSigns(sign.getLocation()));

                            sendPrefixed(p, C.msgSignUpdatedAll);
                            break;

                        case "help":
                        case "помощь":
                            p.sendMessage(ChatColor.GREEN + "Для показа помощи");
                            break;
                        case null:
                            break;
                        default:
                            p.sendMessage(ChatColor.RED + "Отсутствуют параметры на 2-ой строке таблички.");
                            break;
                    }
                }
            }
            if (Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("ATM")) {
                // Свисающие таблички нельзя — оставляем твою проверку выше
                if (e.getBlock().getType().toString().contains("HANGING")) {
                    p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве банковского автомата!");
                    return;
                }

                // Кто владелец страны?
                String pc = com.frammy.unitylauncher.upgrades.UpgradeCondition.playerCountryCanonical(p.getName());
                if (pc == null || pc.isBlank()) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "ATM можно ставить только будучи в составе страны.");
                    return;
                }

                // Квота
                int allowed = getAllowedAtm(pc);
                int have = countExistingAtmForCountry(pc);
                if (have >= allowed) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Достигнут лимит ATM для страны [" + pc + "]: " + have + "/" + allowed
                            + ChatColor.GRAY + ". Купите расширение или повысите уровень.");
                    return;
                }
                Bukkit.getLogger().info("[ATM] pc=" + pc
                        + " allowed=" + getAllowedAtm(pc)
                        + " have=" + countExistingAtmForCountry(pc)
                        + " base=" + C.atmPerm);

                // Создаём ATM
                UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
                    if (data == null) {
                        new BukkitRunnable(){ @Override public void run(){
                            p.sendMessage(ChatColor.RED + "Данные не найдены.");
                        }}.runTask(UnityLauncher.getInstance());
                        return;
                    }
                    new BukkitRunnable() {
                        @Override public void run() {
                            String line0 = "ATM [" + data.countryName + "]";
                            Map<Integer, String> linesToScroll = new HashMap<>();
                            linesToScroll.put(0, line0);

                            sign.setLine(0, line0);
                            sign.setLine(1, "Коснитесь,");
                            sign.setLine(2, "чтобы начать");
                            sign.setLine(3, "");
                            sign.update();

                            makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll, 6, 13);

                            sendPrefixed(p, C.msgSignBankCreated + ChatColor.GRAY + " (" + (have + 1) + "/" + allowed + ")");

                            String markerID = "marker_" + UUID.randomUUID();
                            SignVariables vars = new SignVariables(
                                    p.getName(),
                                    Arrays.asList(line0, "Коснитесь,", "чтобы начать", ""),
                                    List.of(0),
                                    false,
                                    false,
                                    SignCategory.ATM,
                                    SignState.ATM_MENU,
                                    markerID
                            );
                            genericSignList.put(sign.getLocation(), vars);
                            blueMapIntegration.addBlueMapMarker(markerID, sign.getLocation(), "services", "Сервисы", "point_atm", null, p);
                        }
                    }.runTask(UnityLauncher.getInstance());
                });
            }
            if (Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("TRASH")
                    || Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("МУСОР")
                    || Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("MUSOR")) {

                // Свисающие таблички тоже запрещаем
                if (e.getBlock().getType().toString().contains("HANGING")) {
                    p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве мусорного приёмника!");
                    return;
                }

                // Должен быть в стране
                String pc = com.frammy.unitylauncher.upgrades.UpgradeCondition.playerCountryCanonical(p.getName());
                if (pc == null || pc.isBlank()) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Таблички приёма мусора можно ставить только будучи в составе страны.");
                    return;
                }

                // Лимит табличек на страну
                int have = countExistingTrashForCountry(pc);
                if (have >= TRASH_SIGN_LIMIT_PER_COUNTRY) {
                    e.setCancelled(true);
                    p.sendMessage(ChatColor.RED + "Достигнут лимит мусорных табличек для страны [" + pc + "]: "
                            + have + "/" + TRASH_SIGN_LIMIT_PER_COUNTRY);
                    return;
                }

                // Создаём табличку
                String line0 = "Мусорка [" + pc + "]";
                sign.setLine(0, line0);
                sign.setLine(1, "ПКМ с пустой");
                sign.setLine(2, "рукой, чтобы");
                sign.setLine(3, "сдать мусор");
                sign.update();

                Map<Integer, String> linesToScroll = new HashMap<>();
                linesToScroll.put(0, line0);
                makeSignScrollingLines(e.getBlock().getLocation(), linesToScroll, 6, 13);

                SignVariables vars = new SignVariables(
                        p.getName(),
                        Arrays.asList(line0, sign.getLine(1), sign.getLine(2), sign.getLine(3)),
                        List.of(0),
                        false,
                        false,
                        SignCategory.TRASH_SELL,
                        SignState.SHOP_DEFINED,
                        null
                );
                genericSignList.put(sign.getLocation(), vars);

                p.sendMessage(ChatColor.GREEN + "Табличка приёма мусора установлена. "
                        + ChatColor.GRAY + "(" + (have + 1) + "/" + TRASH_SIGN_LIMIT_PER_COUNTRY + ")");
            }

        } else {
            if (Objects.requireNonNull(e.getLine(0)).equalsIgnoreCase("ATM")) {
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве банковского автомата!");
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory inventory = event.getInventory();

        if (!(inventory.getHolder() instanceof Container container)) return;

        Block block = container.getBlock();
        Location containerLocation = block.getLocation();

        for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
            SignVariables vars = entry.getValue();
            if (vars.getSignCategory() != SignCategory.SHOP_SOURCE) continue;

            Location storedLoc = parseContainerLocation(vars, block.getWorld());
            if (storedLoc != null && storedLoc.equals(containerLocation)) {
                updateAllRelatedShopListSigns(storedLoc);
            }
        }
    }

    public void updateAllRelatedShopListSigns(Location containerLocation) {
        ExtrudeMarker marker = isSignWithinMarker(containerLocation, "zones_shop");
        if (marker == null) return;

        List<Location> shopListSigns = genericSignList.entrySet().stream()
                .filter(e -> e.getValue().getSignCategory() == SignCategory.SHOP_LIST)
                .filter(e -> {
                    ExtrudeMarker m = isSignWithinMarker(e.getKey(), "zones_shop");
                    return m != null && m.getLabel().equals(marker.getLabel());
                })
                .map(Map.Entry::getKey)
                .toList();

        List<Location> sourceSignLocations = genericSignList.entrySet().stream()
                .filter(e -> e.getValue().getSignCategory() == SignCategory.SHOP_SOURCE && e.getValue().getSignState() == SignState.SHOP_DEFINED)
                .filter(e -> {
                    ExtrudeMarker m = isSignWithinMarker(e.getKey(), "zones_shop");
                    return m != null && m.getLabel().equals(marker.getLabel());
                })
                .map(Map.Entry::getKey)
                .toList();

        Set<Block> containers = new HashSet<>();
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

        Map<Location, List<ItemData>> summary = zoneManager.getItemSummaryFromContainers(
                new ArrayList<>(containers),
                sourceSignLocations
        );

        // Объединяем всё в один общий список
        List<ItemData> allItems = summary.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // Назначаем один и тот же список на каждую SHOP_LIST табличку
        for (Location signLoc : shopListSigns) {
            if (allItems.isEmpty()) continue;

            List<String> itemLines = allItems.stream()
                    .map(e -> Arrays.stream(e.name.split("_"))
                            .map(w -> w.charAt(0) + w.substring(1).toLowerCase())
                            .collect(Collectors.joining(" ")))
                    .collect(Collectors.toList());

            signPages.put(signLoc, itemLines);
            signItemData.put(signLoc, allItems);

            Block block = signLoc.getBlock();
            if (block.getState() instanceof Sign sign) {
                updateSignView(sign, itemLines, 0); // Показываем первые 3 строки
            }
        }
    }

    private Location parseContainerLocation(SignVariables vars, World world) {
        if (vars.getSignText().size() < 2) return null;
        String[] coords = vars.getSignText().get(1).split(" ");
        if (coords.length != 3) return null;

        try {
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int z = Integer.parseInt(coords[2]);
            return new Location(world, x, y, z);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();

        if (b == null || !(b.getState() instanceof Sign sign)) return;

        Location loc = sign.getLocation();

        // Только наши таблички
        SignVariables sv0 = genericSignList.get(loc);
        if (sv0 == null) return;

        // === МУСОРНЫЕ ТАБЛИЧКИ (TRASH_SELL) ===
        if (sv0.getSignCategory() == SignCategory.TRASH_SELL) {
            // Нас интересует только ПКМ по табличке
            if (action == Action.RIGHT_CLICK_BLOCK) {
                // Требуем пустую основную руку, чтобы не пересекаться с редактированием/боем
                if (p.getInventory().getItemInMainHand().getType() != Material.AIR) {
                    p.sendMessage(ChatColor.RED + "Освободи основную руку, чтобы сдать мусор.");
                    e.setCancelled(true);
                    return;
                }

                handleTrashSell(p, sign);
                e.setCancelled(true);
            }
            // Никакая другая логика (SHOP/ATM) сюда не должна падать
            return;
        }

        // Магазинные таблички редактирует ТОЛЬКО владелец
        if (sv0.getSignCategory() == SignCategory.SHOP_SOURCE || sv0.getSignCategory() == SignCategory.SHOP_LIST) {
            // редактирование/привязка только владельцу
            boolean isOwner = sv0.getOwnerName().equalsIgnoreCase(p.getName());

            // SHIFT+ПКМ — выбрать сундук
            if (action == Action.RIGHT_CLICK_BLOCK && p.isSneaking()) {
                if (!isOwner) { sendPrefixed(p, C.errNotOwner); e.setCancelled(true); return; }
                if (sv0.getSignCategory() == SignCategory.SHOP_SOURCE) {
                    // включаем режим выбора сундука
                    signSelectionMap.put(p, b);
                    p.sendMessage(ChatColor.YELLOW + "Открой нужное хранилище для привязки.");
                    e.setCancelled(true);
                    return;
                }
            }

            // ПКМ без шифта — редактирование 2 и 3 строки владельцем
            if (action == Action.RIGHT_CLICK_BLOCK && !p.isSneaking()) {
                if (!isOwner) { sendPrefixed(p, C.errNotOwner); e.setCancelled(true); return; }
                // просто ставим паузу прокрутки — как у тебя, редактировать игрок будет руками строки 2 и 3
                pauseScrolling(loc);
                return;
            }
        }


        if (e.getAction() == Action.LEFT_CLICK_BLOCK && b.getState() instanceof Sign) {
            SignVariables signVariables = genericSignList.get(sign.getLocation());

            if (signVariables == null) return;
            if (e.getPlayer().getInventory().getItemInMainHand().getType() != Material.AIR) return;

            if (signVariables.getSignCategory() == SignCategory.SHOP_LIST) {
                if (!signPages.containsKey(loc)) return;

                List<String> items = signPages.get(loc);
                List<ItemData> dataList = signItemData.get(loc);

                if (items == null || items.isEmpty() || dataList == null || dataList.isEmpty()) return;

                int index = playerScrollIndex.getOrDefault(p.getUniqueId(), 0);

                int n = Math.min(items.size(), dataList.size());

                int selected = (index + 1) % n;
                if (selected >= n) selected = n - 1;
                if (selected < 0) selected = 0;

                String selectedItemName = ChatColor.stripColor(items.get(selected));
                ItemData selectedItem   = dataList.get(selected);

                p.sendMessage(ChatColor.YELLOW + "=======" + ChatColor.GOLD + " [Магазин] " + ChatColor.YELLOW + "=======\n" +
                        "\n" + ChatColor.GREEN + "Предмет: " + ChatColor.RESET + selectedItemName +
                        "\n" + ChatColor.GREEN + "Доступное количество: " + ChatColor.RESET + selectedItem.overallQuantity + " шт." +
                        "\n" + ChatColor.GREEN + "Количество одной сделки: " + ChatColor.RESET + selectedItem.quantity + " шт." +
                        "\n" + ChatColor.GREEN + "Цена одной сделки: " + ChatColor.RESET + selectedItem.price + " Ⓕ" +
                        "\n" + ChatColor.GREEN + "Координаты сундука: " + ChatColor.RESET + formatLocation(selectedItem.chestLocation));
            }

            if (genericSignList.get(loc).getOwnerName().equalsIgnoreCase(p.getName())) {
                if (signVariables.getSignState() == SignState.SHOP_UNDEFINED) {
                    if (p.isSneaking()) {
                        if (!sign.getLine(2).isEmpty() && !sign.getLine(3).isEmpty()) {
                            if (getContainerLocation(sign) == null) {
                                sendPrefixed(p, C.errInvalidFormat);
                                return;
                            }
                            double price;
                            int amount;
                            try {
                                amount = Integer.parseInt(ChatColor.stripColor(sign.getLine(2)));
                                price = Double.parseDouble(ChatColor.stripColor(sign.getLine(3)));

                            } catch (NumberFormatException exc) {
                                sendPrefixed(p, C.errInvalidFormat);
                                sign.setLine(2, "<Количество>");
                                sign.setLine(3, "<Цена>");
                                sign.update();
                                return;
                            }

                            List<String> signTexts = genericSignList.get(sign.getLocation()).getSignText();
                            String line3 = "Цена: " + ChatColor.GREEN + price;
                            String line2 = "Кол-во: " + ChatColor.YELLOW + amount;

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
            }
            if (signVariables.getSignState() == SignState.SHOP_DEFINED && signVariables.getSignCategory().equals(SignCategory.SHOP_SOURCE)) {
                if (p.isSneaking()) {
                    // Владелец может вернуть в режим редактирования — оставляем твою логику
                    if (genericSignList.get(loc).getOwnerName().equalsIgnoreCase(p.getName())) {
                        List<String> text = signVariables.getSignText();
                        String line2 = text.get(2).replace("Кол-во: " + ChatColor.YELLOW, ChatColor.RESET + "");
                        String line3 = text.get(3).replace("Цена: " + ChatColor.GREEN, ChatColor.RESET + "");
                        signVariables.setSignText(Arrays.asList(text.get(0), text.get(1), line2, line3));
                        sign.setLine(2, line2);
                        sign.setLine(3, line3);
                        sign.update();
                        p.sendMessage(ChatColor.GRAY + "Табличка переключена в режим редактирования.");
                        genericSignList.get(loc).setSignState(SignState.SHOP_UNDEFINED);
                    }
                    return;
                }

                String seller = signVariables.getOwnerName();
                // нормализуем имя (снимем цвета и пробелы, берём "официальный" ник, если возможно)
                try {
                    String clean = org.bukkit.ChatColor.stripColor(seller).trim();
                    java.util.UUID suid = Bukkit.getOfflinePlayer(clean).getUniqueId();
                    String exact = Bukkit.getOfflinePlayer(suid).getName();
                    if (exact != null) seller = exact;
                } catch (Throwable ignore) {}
                if (seller.equalsIgnoreCase(p.getName())) return; // сам у себя — не покупаем

                double price = Double.parseDouble(signVariables.getSignText().get(3).replace("Цена: " + ChatColor.GREEN, ""));
                int quantity = Integer.parseInt(signVariables.getSignText().get(2).replace("Кол-во: " + ChatColor.YELLOW, ""));

                // Определяем способ оплаты: наличка или онлайн
                ItemStack hand = p.getInventory().getItemInMainHand();
                final boolean payWithCash = unityLauncher.moneyManager.isMoneyItem(hand);

                p.sendMessage(ChatColor.GRAY + "Обработка транзакции...");

                // Фон: достаём деньги покупателя и инфу продавца
                String finalSeller = seller;
                String finalSeller1 = seller;
                String finalSeller2 = seller;

                new BukkitRunnable() {
                    @Override public void run() {
                        List<String> keys = List.of("money");

                        double buyerMoney = 0.0;
                        if (!payWithCash) {
                            // 1) баланс покупателя (для онлайн-платежа)
                            Map<String, Object> buyerMap = UnityCommands.getInstance()
                                    .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), keys);
                            buyerMoney = buyerMap.get("money") instanceof Number ? ((Number) buyerMap.get("money")).doubleValue() : 0.0;

                            if (buyerMoney < price) {
                                double finalBuyerMoney = buyerMoney;
                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.RED + "Недостаточно средств. Баланс: " + ChatColor.YELLOW + finalBuyerMoney + ChatColor.RED + " Ⓕ.");
                                }}.runTask(UnityLauncher.getInstance());
                                return;
                            }
                        }

                        // 2) баланс продавца (может не существовать → 0.0)
                        Map<String, Object> sellerMap = UnityCommands.getInstance()
                                .getJsonFieldValues("Users", "GeneralData", "Name", finalSeller2, keys);
                        double sellerMoney = sellerMap.get("money") instanceof Number ? ((Number) sellerMap.get("money")).doubleValue() : 0.0;

                        final double buyerMoneyFinal  = buyerMoney;
                        final double sellerMoneyFinal = sellerMoney;

                        // 3) Дальше — основной поток: предмет/контейнер + операция с наличкой
                        new BukkitRunnable(){
                            @Override public void run() {
                                try {
                                    // Если оплата наличкой — сперва пробуем списать кэш
                                    if (payWithCash) {
                                        boolean ok = unityLauncher.moneyManager.spendCash(p, price);
                                        if (!ok) {
                                            p.sendMessage(ChatColor.RED + "Недостаточно наличных для покупки.");
                                            return;
                                        }
                                    }

                                    Location chestLoc = getContainerLocation((Sign) b.getState());
                                    if (chestLoc == null) { p.sendMessage(ChatColor.RED + "Хранилище не привязано."); return; }
                                    Block cb = chestLoc.getBlock();
                                    if (!(cb.getState() instanceof Container container)) {
                                        p.sendMessage(ChatColor.RED + "Хранилище повреждено."); return;
                                    }
                                    int slot = getFirstOccupiedSlot(container.getInventory());
                                    if (slot == -1) { p.sendMessage(ChatColor.RED + "Контейнер пуст."); return; }

                                    ItemStack stack = container.getInventory().getItem(slot);
                                    if (stack == null || stack.getType().isAir() || stack.getAmount() < quantity) {
                                        p.sendMessage(ChatColor.RED + "Недостаточно товара в слоте."); return;
                                    }

                                    ItemStack toGive = stack.clone(); toGive.setAmount(quantity);
                                    HashMap<Integer, ItemStack> leftovers = p.getInventory().addItem(toGive);
                                    if (!leftovers.isEmpty()) { p.sendMessage(ChatColor.RED + "Нет места в инвентаре."); return; }

                                    int newAmt = stack.getAmount() - quantity;
                                    if (newAmt <= 0) container.getInventory().setItem(slot, null);
                                    else {
                                        ItemStack newStack = stack.clone(); newStack.setAmount(newAmt);
                                        container.getInventory().setItem(slot, newStack);
                                    }

                                    String itemName = org.apache.commons.lang3.text.WordUtils.capitalizeFully(
                                            toGive.getType().name().toLowerCase().replace("_", " ")
                                    );

                                    String methodName = payWithCash ? "наличными" : "онлайн";
                                    p.sendMessage(ChatColor.GREEN + "Покупка успешна (" + methodName + "): " + itemName + " ×" + quantity
                                            + ChatColor.GRAY + " (за " + ChatColor.YELLOW + price + " Ⓕ" + ChatColor.GRAY + ")");

                                    // 4) Обновляем деньги — в фоне
                                    new BukkitRunnable(){ @Override public void run(){
                                        UnityCommands uc = UnityCommands.getInstance();

                                        Map<String, Object> updSeller = new HashMap<>();
                                        updSeller.put("money", round2(sellerMoneyFinal + price));
                                        uc.mergeAndUpdatePlayerData(finalSeller2, "GeneralData", updSeller);

                                        // Онлайн-платёж: списываем со счёта покупателя.
                                        // При оплате наличкой баланс покупателя онлайн НЕ трогаем.
                                        if (!payWithCash) {
                                            Map<String, Object> updBuyer = new HashMap<>();
                                            updBuyer.put("money", round2(buyerMoneyFinal - price));
                                            uc.mergeAndUpdatePlayerData(p.getName(), "GeneralData", updBuyer);
                                        }

                                        // лог заказа
                                        uc.createOrder(finalSeller2, p.getName(), itemName, price, quantity, sign.getLocation(),
                                                (stack.hasItemMeta() ? stack.getItemMeta().getEnchants() : java.util.Map.of()));
                                    }}.runTaskAsynchronously(UnityLauncher.getInstance());

                                    // 5) Обновим списки
                                    updateAllRelatedShopListSigns(chestLoc);

                                } catch (Throwable ex) {
                                    p.sendMessage(ChatColor.RED + "Ошибка транзакции: " + ex.getMessage());
                                    ex.printStackTrace();
                                }
                            }
                        }.runTask(UnityLauncher.getInstance());
                    }
                }.runTaskAsynchronously(UnityLauncher.getInstance());

            }
        }

        SignVariables vars = genericSignList.get(loc);
        SignState state = (vars != null) ? vars.getSignState() : SignState.ATM_MENU;
        if (vars != null && vars.getSignState() != null) {
            state = vars.getSignState();
        }
        // RIGHT_CLICK → Пауза прокрутки
        if (action == Action.RIGHT_CLICK_BLOCK) {
            pauseScrolling(loc);
            return;
        }

        if (action != Action.LEFT_CLICK_BLOCK) return;

        // Если табличка в режиме "Коснитесь, чтобы начать"
        if (ChatColor.stripColor(sign.getLine(1)).equals("Коснитесь,") && genericSignList.containsKey(loc)) {
            p.getItemInHand();
            if (p.getItemInHand().getType() == Material.AIR) {
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

    private String formatLocation(Location loc) {
        return String.format("X: %d Y: %d Z: %d", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public int getFirstOccupiedSlot(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && item.getType() != Material.AIR) {
                return i;
            }
        }
        return -1; // если нет ни одного занятого слота
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent e) {
        Inventory inv = e.getInventory();
        if (!(inv.getHolder() instanceof Container container)) return;

        final Location containerLoc = container.getBlock().getLocation();
        final HumanEntity he = e.getPlayer();
        final Player p = (he instanceof Player) ? (Player) he : null;
        final String opener = (p != null) ? p.getName() : null;

        // === DEBUG-флаг из конфига (если есть)
        final boolean DEBUG = (C != null && C.DEBUG);

        // === 1) РЕЖИМ ПРИВЯЗКИ (включён ранее Shift+ПКМ по SHOP_SOURCE)
        if (p != null && signSelectionMap.containsKey(p)) {
            Block signBlock = signSelectionMap.get(p);

            // Если блока уже нет или это не табличка — снимем режим
            if (!(signBlock != null && signBlock.getState() instanceof Sign sign)) {
                signSelectionMap.remove(p);
            } else {
                // СТАЛО: одна и та же SHOP-зона + владелец этой SHOP-зоны = владелец таблички
                if (!zoneManager.isSameShopArea(sign.getLocation(), containerLoc)) {
                    p.sendMessage(ChatColor.RED + "Хранилище должно быть в той же зоне магазина, что и табличка.");
                    e.setCancelled(true);
                    return;
                }
                if (!zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), sign.getLocation())) {
                    p.sendMessage(ChatColor.RED + "Привязка доступна только владельцу этой SHOP-зоны.");
                    e.setCancelled(true);
                    return;
                }

                // Привязка координат контейнера ко 2-й строке таблички
                SignVariables vars = genericSignList.get(sign.getLocation());
                if (vars != null && vars.getSignCategory() == SignCategory.SHOP_SOURCE) {
                    List<String> text = new ArrayList<>(vars.getSignText() == null ? List.of() : vars.getSignText());
                    while (text.size() < 4) text.add("");
                    String coords = containerLoc.getBlockX() + " " + containerLoc.getBlockY() + " " + containerLoc.getBlockZ();
                    text.set(1, coords);
                    vars.setSignText(text);

                    sign.setLine(1, coords);
                    sign.update();

                    containerToSourceSign.entrySet().stream()
                            .filter(en -> en.getValue().equals(sign.getLocation()))
                            .map(Map.Entry::getKey).findFirst().ifPresent(containerToSourceSign::remove);

                    containerToSourceSign.put(containerLoc, sign.getLocation());

                    p.sendMessage(ChatColor.GREEN + "Привязано хранилище: " + formatLocation(containerLoc));

                    // Автообновление связанных SHOP_LIST в этой же зоне
                    try {
                        updateAllRelatedShopListSigns(containerLoc);
                    } catch (Throwable t) {
                        if (DEBUG) Bukkit.getLogger().warning("[SignManager] updateAllRelatedShopListSigns error: " + t.getMessage());
                    }

                    // Владелец может сразу работать с контейнером — событие не отменяем.
                    signSelectionMap.remove(p);
                } else {
                    // На всякий случай: режим очистим
                    signSelectionMap.remove(p);
                }
            }
        }

        // === 2) ДОСТУП К ПРИВЯЗАННОМУ КОНТЕЙНЕРУ — только владелец SHOP_SOURCE
        // Линейный проход по нашим табличкам (нормально, их немного; при желании можно проиндексировать)
        for (Map.Entry<Location, SignVariables> entry : genericSignList.entrySet()) {
            SignVariables sv = entry.getValue();
            if (sv == null || sv.getSignCategory() != SignCategory.SHOP_SOURCE) continue;

            Location stored = parseContainerLocation(sv, containerLoc.getWorld());
            if (stored == null) continue;

            // Нашли ровно тот контейнер, который привязан к табличке
            if (stored.equals(containerLoc)) {
                // Не владелец — не пускаем
                if (!sv.getOwnerName().equalsIgnoreCase(opener)) {
                    e.setCancelled(true);
                    if (he instanceof Player pp) {
                        pp.sendMessage(ChatColor.RED + "Этот сундук связан с магазином. Открывать может только владелец.");
                    }
                } else {
                    // Владелец открыл — мягко обновим списки (без отмены события)
                    try {
                        updateAllRelatedShopListSigns(stored);
                    } catch (Throwable t) {
                        if (DEBUG) Bukkit.getLogger().warning("[SignManager] updateAllRelatedShopListSigns error: " + t.getMessage());
                    }
                }
                return; // контейнер идентифицирован — дальше искать не нужно
            }
        }

        // === 3) Контейнер не привязан ни к одной нашей SHOP_SOURCE — ничего не делаем
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        final Block brokenBlock = event.getBlock();
        final Player player = event.getPlayer();

        // === 0) DEBUG флаг (необязательно)
        final boolean DEBUG = (C != null && C.DEBUG);

        // Если блок — в SHOP-зоне, где игрок НЕ владелец — запрещаем ломать таблички магазина и привязанные контейнеры
        if (zoneManager.getShopZoneAt(brokenBlock.getLocation()) != null
                && !zoneManager.isPlayerOwnerOfShopZoneAt(player.getName(), brokenBlock.getLocation())) {

            // 3.1.1: если сам блок — контейнер, и он привязан к SHOP_SOURCE — запрет
            if (brokenBlock.getState() instanceof org.bukkit.block.Container) {
                Location signLoc = containerToSourceSign.get(brokenBlock.getLocation());
                if (signLoc != null) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "Контейнер привязан к магазину. Ломать может только владелец SHOP-зоны.");
                    return;
                }
            }

            // 3.1.2: если есть прикреплённые к этому блоку наши магазинные таблички — запрет
            for (BlockFace f : new BlockFace[]{ BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN }) {
                Block nb = brokenBlock.getRelative(f);
                if (!(nb.getState() instanceof Sign)) continue;

                SignVariables sv = genericSignList.get(nb.getLocation());
                if (sv == null) continue;
                if (sv.getSignCategory() != SignCategory.SHOP_SOURCE
                        && sv.getSignCategory() != SignCategory.SHOP_LIST) {
                    continue;
                }

                // ВАЖНО: запрещаем только если табличка реально ПРИКРЕПЛЕНА к этому блоку
                if (!isAttachedToBlock(nb, brokenBlock)) continue;

                event.setCancelled(true);
                player.sendMessage(ChatColor.RED + "В магазине ломать может только владелец SHOP-зоны.");
                return;
            }

        }

        // === 1) Если ломают саму табличку (включая hanging)
        if (brokenBlock.getState() instanceof Sign) {
            Location signLoc = brokenBlock.getLocation();
            SignVariables sv = genericSignList.get(signLoc);
            if (sv != null && (sv.getSignCategory() == SignCategory.SHOP_SOURCE || sv.getSignCategory() == SignCategory.SHOP_LIST)) {
                if (!sv.getOwnerName().equalsIgnoreCase(player.getName())) {
                    player.sendMessage(ChatColor.RED + "Это магазинная табличка другого игрока (" + sv.getOwnerName() + "). Ломать нельзя.");
                    event.setCancelled(true);
                    return;
                }
                // Владелец ломает — снимем маркер, индексы и т.д.
                String markerId = sv.getMarkerID();
                if (markerId != null) {
                    blueMapIntegration.removeBlueMapMarker(markerId, signLoc.getWorld().getName(), "services");
                }
                Location stored = parseContainerLocation(sv, signLoc.getWorld());
                if (stored != null) containerToSourceSign.remove(stored);
                genericSignList.remove(signLoc);
                stopScrollingTask(signLoc);
                signPages.remove(signLoc);
                try {
                    UUID uid = Bukkit.getOfflinePlayer(sv.getOwnerName()).getUniqueId();
                    playerScrollIndex.remove(uid);
                } catch (Throwable ignore) {}
                return;
            }
        }

        // === 2) Если ломают НЕ табличку. Проверяем соседние 6 блоков на наличие табличек,
        // прикреплённых к ЭТОМУ блоку (вместо сканирования ВСЕГО genericSignList)
        final BlockFace[] faces = { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN };
        boolean hadAttachedSign = false;

        for (BlockFace f : faces) {
            final Block nb = brokenBlock.getRelative(f);
            if (!(nb.getState() instanceof Sign neighborSign)) continue;

            final Location signLoc = nb.getLocation();
            final SignVariables sv = genericSignList.get(signLoc);
            if (sv == null) continue; // не наша

            if (!isAttachedToBlock(nb, brokenBlock)) continue; // табличка не висит на этом блоке

            // Право на удаление
            if (!sv.getOwnerName().equalsIgnoreCase(player.getName())) {
                player.sendMessage(ChatColor.RED + "Вы не можете сломать эту табличку, так как её установил другой игрок.");
                event.setCancelled(true);
                return;
            }

            // BlueMap
            final String markerId = sv.getMarkerID();
            if (markerId != null) {
                blueMapIntegration.removeBlueMapMarker(markerId, signLoc.getWorld().getName(), "services");
            }

            // Если это SHOP_SOURCE — снимем привязку контейнера из индекса
            Location stored = parseContainerLocation(sv, signLoc.getWorld());
            if (stored != null) {
                containerToSourceSign.remove(stored);
            }

            // Чистим структуры
            genericSignList.remove(signLoc);
            stopScrollingTask(signLoc);
            signPages.remove(signLoc);
            UUID uid = null;
            try { uid = Bukkit.getOfflinePlayer(sv.getOwnerName()).getUniqueId(); } catch (Throwable ignore) {}
            if (uid != null) playerScrollIndex.remove(uid);
        }

        // === 3) Если ломают КОНТЕЙНЕР: найдём его в индексе за O(1)
        if (brokenBlock.getState() instanceof Container) {
            Location signLoc = containerToSourceSign.remove(brokenBlock.getLocation());
            if (signLoc != null) {
                Block signBlock = signLoc.getBlock();
                if (signBlock.getState() instanceof Sign srcSign) {
                    SignVariables sv = genericSignList.get(signLoc);
                    if (sv != null && sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
                        sv.setSignState(SignState.SHOP_UNDEFINED);
                        List<String> text = new ArrayList<>(sv.getSignText() == null ? List.of("", "", "", "") : sv.getSignText());
                        while (text.size() < 4) text.add("");

                        String line2 = text.get(2).replace("Кол-во: " + ChatColor.YELLOW, ChatColor.RESET + "");
                        String line3 = text.get(3).replace("Цена: "    + ChatColor.GREEN, ChatColor.RESET + "");
                        sv.setSignText(Arrays.asList(text.get(0), ChatColor.RED + "Разрушено", line2, line3));

                        srcSign.setLine(1, ChatColor.RED + "Разрушено");
                        srcSign.setLine(2, line2);
                        srcSign.setLine(3, line3);
                        srcSign.update();
                    }
                }
            }
        }
    }

    public Location getContainerLocation(Sign sign) {

        String[] coords = sign.getLine(1).split(" ");

        if (coords.length != 3) return null;

        try {
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int z = Integer.parseInt(coords[2]);
            Block containerBlock = sign.getLocation().getWorld().getBlockAt(x, y, z);
            return containerBlock.getLocation();
        } catch (NumberFormatException ignored) {}
        return null;
    }

    @EventHandler
    public void onScroll(PlayerItemHeldEvent e) {
        Player player = e.getPlayer();

        // Проверим, смотрит ли игрок на табличку
        Block target = player.getTargetBlockExact(4); // до 6 блоков — можно увеличить
        if (target == null || !(target.getState() instanceof Sign sign)) return;

        Location loc = target.getLocation();

        List<String> items = signPages.get(loc);
        if (items == null) return;

        List<String> allStrings = new ArrayList<>();
        for (List<String> list : signPages.values()) {
            allStrings.addAll(list);
        }

        if (allStrings.size() < 3) {
            int toAdd = 3 - allStrings.size();
            for (int i = 0; i < toAdd; i++) {
                signPages.get(loc).add("  "); // добавляем пустые строки
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
        stopHorizontalScroll(loc, 2);
        String selectedText = updateSignView(sign, items, newIndex);
        if (!genericSignList.containsKey(loc)) return; // запись могли удалить в процессе

        // Если выбранный текст длиннее 15, запускаем прокрутку
        if (selectedText != null && ChatColor.stripColor(selectedText).length() > 15) {
            pauseScrolling(loc);
            startSignTextScroll(sign, 2, selectedText, ChatColor.GREEN, 15, 216, 6, () -> resumeScrolling(loc)); // строка 2 — третья строка
        }
        scheduleSignReset(sign.getLocation());
        e.setCancelled(true);
    }

    @EventHandler
    public  void onPlayerLeave(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (unityLauncher.getAwaitingCorrectCommand().contains(p)) {
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
                        p.sendMessage(ChatColor.GRAY + "Обрабатываем операцию..");
                        unityLauncher.moneyManager.giveMoney(p, amount);
                    }
                    case "admin", "админ" ->
                            p.sendMessage(ChatColor.YELLOW + "Слушай, а ловко ты это придумал. Я даже сначала и не понял.");
                    default ->
                            p.sendMessage(ChatColor.RED + "Необходимо указать счёт, с которого будут сняты деньги - 'Страна' или 'Игрок'.");
                }
                genericSignList.get(loc).setSignState(SignState.ATM_ACTION_READY);
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
                    case "страна", "государство", "стр", "ст", "country" -> {
                        p.sendMessage(ChatColor.GRAY + "Обрабатываем операцию..");
                        unityLauncher.moneyManager.takeMoney(p, amount, true);
                    }
                    case "игрок", "я", "мой счёт", "me", "игр", "иг" -> {
                        p.sendMessage(ChatColor.GRAY + "Обрабатываем операцию..");
                        unityLauncher.moneyManager.takeMoney(p, amount, false);
                    }
                    default ->
                            p.sendMessage(ChatColor.RED + "Необходимо указать счёт, с которого будут сняты деньги - 'Страна' или 'Игрок'.");
                }
                genericSignList.get(loc).setSignState(SignState.ATM_ACTION_READY);
            });
        });
        actions.put("Перевод игроку", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Никнейм>");
            sign.setLine(3, "<Сумма>");
            sign.update();
            genericSignList.get(loc).setSignState(SignState.ATM_ACTION_READY);

            signClickActions.put(sign.getLocation(), () -> {
                Sign updatedSign = (Sign) sign.getBlock().getState();
                String targetName = ChatColor.stripColor(updatedSign.getLine(2)).trim();
                double amount;

                try {
                    amount = Double.parseDouble(ChatColor.stripColor(updatedSign.getLine(3)).replace(',', '.'));
                } catch (NumberFormatException ex) {
                    p.sendMessage(ChatColor.RED + "Введите корректную сумму.");
                    return;
                }

                if (targetName.isEmpty() || amount <= 0) {
                    p.sendMessage(ChatColor.RED + "Укажи ник и сумму > 0.");
                    return;
                }
                if (targetName.equalsIgnoreCase(p.getName())) {
                    p.sendMessage(ChatColor.RED + "Нельзя перевести самому себе.");
                    return;
                }

                p.sendMessage(ChatColor.GRAY + "Проверяем данные и выполняем перевод...");

                new BukkitRunnable() {
                    @Override public void run() {
                        List<String> keys = List.of("money");
                        Map<String, Object> senderMap = UnityCommands.getInstance()
                                .getJsonFieldValues("Users", "GeneralData", "Name", p.getName(), keys);
                        Double senderMoney = senderMap.get("money") instanceof Number ? ((Number) senderMap.get("money")).doubleValue() : null;

                        // 2) Ищем получателя
                        UnityCommands.getInstance().getPlayerInfo(targetName, targetData -> {
                            if (senderMoney == null) {
                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.RED + "Не удалось получить твой баланс.");
                                }}.runTask(UnityLauncher.getInstance());
                                return;
                            }
                            if (targetData == null) {
                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.RED + "Игрок '" + targetName + "' не найден.");
                                }}.runTask(UnityLauncher.getInstance());
                                return;
                            }
                            if (senderMoney < amount) {
                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.RED + "Недостаточно средств. Доступно: " + ChatColor.YELLOW + senderMoney + ChatColor.RED + " Ⓕ.");
                                }}.runTask(UnityLauncher.getInstance());
                                return;
                            }

                            // 3) Атомично (для нас) применяем обе стороны
                            new BukkitRunnable(){ @Override public void run(){
                                UnityCommands uc = UnityCommands.getInstance();

                                Map<String, Object> updSender = new HashMap<>();
                                updSender.put("money", round2(senderMoney - amount));
                                uc.mergeAndUpdatePlayerData(p.getName(), "GeneralData", updSender);

                                Map<String, Object> updTarget = new HashMap<>();
                                updTarget.put("money", round2(targetData.money + amount));
                                uc.mergeAndUpdatePlayerData(targetName, "GeneralData", updTarget);

                                new BukkitRunnable(){ @Override public void run(){
                                    p.sendMessage(ChatColor.GREEN + "Перевод выполнен: " + ChatColor.YELLOW + amount + " Ⓕ"
                                            + ChatColor.GREEN + " → " + ChatColor.RESET + targetName);
                                }}.runTask(UnityLauncher.getInstance());
                            }}.runTaskAsynchronously(UnityLauncher.getInstance());
                        });
                    }
                }.runTaskAsynchronously(UnityLauncher.getInstance());

                genericSignList.get(loc).setSignState(SignState.ATM_ACTION_READY);
            });
        });

        actions.put("Перевод стране", () -> {
            sign.setLine(1, "Укажите данные:");
            sign.setLine(2, "<Сумма>");
            sign.setLine(3, " ");
            sign.update();
        });
        actions.put("Информация", () -> p.sendMessage(ChatColor.YELLOW + "=======[ ATM ]=======\n" +
                ChatColor.GREEN + "Принадлежит: " + ChatColor.RESET + genericSignList.get(loc).getSignText().getFirst().replace("ATM [", "").replace("]", "") + "\n" +
                ChatColor.GREEN + "Установлен: " + ChatColor.RESET + genericSignList.get(loc).getOwnerName() + "\n" +
                ChatColor.GREEN + "Коммиссионная плата для других банков: " + ChatColor.RESET + " "));

        playerScrollIndex.clear();

        // Первый раз отображаем

        if (block.getState() instanceof Sign) {
            updateSignView((Sign) block.getState(), options, 0);
        }
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // Обновление таблички
    private String updateSignView(Sign sign, List<String> items, int offset) {
        if (items == null || items.isEmpty()) {
            // Очищаем табличку
            sign.setLine(1, "");
            sign.setLine(2, "");
            sign.setLine(3, "");
            sign.update();
            return null;
        }

        String highlighted = null;

        if (items.size() == 1) {
            // Одна строка — по центру
            sign.setLine(1, "");
            sign.setLine(2, ChatColor.GREEN + truncateToVisible(items.getFirst()));
            sign.setLine(3, "");
            highlighted = items.getFirst();
        } else if (items.size() == 2) {
            // Две строки — верх и центр, центральная скроллится, offset 0 или 1
            int upperIndex = offset % 2;
            int centerIndex = (offset + 1) % 2;

            sign.setLine(1, truncateToVisible(items.get(upperIndex)));
            sign.setLine(2, ChatColor.GREEN + truncateToVisible(items.get(centerIndex)));
            sign.setLine(3, "");
            highlighted = items.get(centerIndex);
        } else {
            // Три и более — обычная прокрутка
            for (int i = 0; i < 3; i++) {
                int index = (offset + i) % items.size();
                String text = items.get(index);

                if (i == 1) {
                    highlighted = text;
                    sign.setLine(i + 1, ChatColor.GREEN + truncateToVisible(text));
                } else {
                    sign.setLine(i + 1, truncateToVisible(text));
                }
            }
        }
        sign.update();
        return highlighted;
    }

    private String truncateToVisible(String text) {return (text.length() > 15) ? text.substring(0, 15) : text;}

    public void startSignTextScroll(Sign sign, int lineIndex, String fullText, ChatColor color, int visibleWidth, int durationTicks, int intervalTicks, Runnable onComplete) {
        String stripped = ChatColor.stripColor(fullText);
        Location loc = sign.getLocation();

        // Остановка предыдущей задачи (если была)
        activeScrolls.computeIfAbsent(loc, l -> new HashMap<>());
        BukkitTask oldTask = activeScrolls.get(loc).get(lineIndex);
        if (oldTask != null) oldTask.cancel();

        // Если прокручивать нечего — просто отобразить
        if (stripped.length() <= visibleWidth) {
            sign.setLine(lineIndex, color + stripped);
            sign.update();
            if (onComplete != null) onComplete.run();
            return;
        }

        BukkitTask newTask = new BukkitRunnable() {
            int tick = 0;
            boolean forward = true;

            @Override
            public void run() {
                if (tick >= durationTicks || !sign.getLocation().getBlock().getState().equals(sign)) {
                    cancel();
                    activeScrolls.get(loc).remove(lineIndex);
                    if (onComplete != null) onComplete.run();
                    return;
                }

                int maxOffset = stripped.length() - visibleWidth;
                int offset = (tick / intervalTicks) % (maxOffset + 1);
                if (!forward) offset = maxOffset - offset;

                String view = stripped.substring(offset, offset + visibleWidth);
                sign.setLine(lineIndex, color + view);
                sign.update();

                if ((tick / intervalTicks) % (maxOffset + 1) == 0) {
                    forward = !forward;
                }
                tick += intervalTicks;
            }
        }.runTaskTimer(unityLauncher, 0L, intervalTicks);
        activeScrolls.get(loc).put(lineIndex, newTask);
    }

    public void stopHorizontalScroll(Location signLocation, int lineIndex) {
        Map<Integer, BukkitTask> tasks = activeScrolls.get(signLocation);
        if (tasks == null) return;
        BukkitTask task = tasks.remove(lineIndex);
        if (task != null) task.cancel();
        if (tasks.isEmpty()) activeScrolls.remove(signLocation);
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
            shopConfig.set(path + ".isConfigurable", vars.isConfigurable());
            shopConfig.set(path + ".isPaused", vars.isPaused());
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

            assert worldName != null;
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

            String stateStr = section.getString("state");
            if (stateStr != null && !stateStr.isBlank()) {
                try {
                    state = SignState.valueOf(stateStr);
                } catch (IllegalArgumentException ignored) {
                    // упадём в дефолт ниже
                }
            }

            if (state == null) {
                // разумный дефолт по типу таблички
                if (category == SignCategory.ATM) {
                    state = SignState.ATM_MENU;
                } else if (category == SignCategory.SHOP_SOURCE) {
                    state = SignState.SHOP_UNDEFINED;
                } else {
                    state = SignState.SHOP_DEFINED;
                }
            }

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
            SignVariables vars = new SignVariables(owner, text, scrollLines, isConfigurable, isPaused, category, state, markerID);
            genericSignList.put(loc, vars);

            if (!scrollLines.isEmpty()) {
                Map<Integer, String> scrollMap = new HashMap<>();
                for (int index : scrollLines) {
                    if (index >= 0 && index < text.size()) {
                        scrollMap.put(index, text.get(index));
                    }
                }
                if (!scrollMap.isEmpty()) {
                    makeSignScrollingLines(loc, scrollMap, 8, 13);
                }
            }

            if (category == SignCategory.SHOP_LIST) {
                Bukkit.getScheduler().runTaskLater(unityLauncher, () -> updateAllRelatedShopListSigns(loc), 20L * 5);
            }
            if (category == SignCategory.SHOP_SOURCE) {
                Location stored = parseContainerLocation(vars, world);
                if (stored != null) containerToSourceSign.put(stored, loc);
            }

        }
    }

    public void pauseScrolling(Location location) {
        SignVariables v = genericSignList.get(location);
        if (v != null) v.setPaused(true);
    }
    public void resumeScrolling(Location location) {
        SignVariables v = genericSignList.get(location);
        if (v != null) v.setPaused(false);
    }

    public void makeSignScrollingLines(Location signLocation, Map<Integer, String> originalLines, int intervalTicks, int maxLength) {
        Block block = signLocation.getBlock();
        if (!(block.getState() instanceof Sign sign)) return;

        Map<Integer, String> scrollBuffers = new HashMap<>();
        for (Map.Entry<Integer, String> entry : originalLines.entrySet()) {
            int lineIndex = entry.getKey();
            String text = entry.getValue();

            if (text.length() <= maxLength) {
                sign.setLine(lineIndex, text);
            } else {
                String scrollingBuffer = (text + "   ").repeat(2);
                scrollBuffers.put(lineIndex, scrollingBuffer);
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
            // === ВСТАВИТЬ САМЫМ ПЕРВЫМ ===
            SignVariables vars = genericSignList.get(signLocation);
            if (vars == null) {
                stopScrollingTask(signLocation); // аккуратно снимает задачу, если есть
                return;
            }
            if (vars.isPaused()) {
                return; // пауза — просто пропускаем тик
            }
            // === КОНЕЦ ВСТАВКИ ===

            // Табличка ещё существует?
            BlockState state = signLocation.getBlock().getState();
            if (!(state instanceof Sign currentSign)) {
                stopScrollingTask(signLocation);
                return;
            }

            boolean anyNearby = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(player -> player.getWorld().equals(signLocation.getWorld())
                            && player.getLocation().distanceSquared(signLocation) <= 35 * 35);
            if (!anyNearby) return;

            int baseLength = Math.max(1, unityLauncher.getMaxBaseLength(originalLines.values()));
            int pos = offset.getAndUpdate(i -> (i + 1) % baseLength);

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

    public void stopScrollingTask(Location loc) {
        BukkitTask task = scrollingTasks.remove(loc);
        if (task != null) {
            task.cancel();
        }
    }

    public void scheduleSignReset(Location loc) {
        // Отменим предыдущую задачу, если была
        BukkitTask prev = resetTasks.remove(loc);
        if (prev != null) prev.cancel();

        BukkitTask task = Bukkit.getScheduler().runTaskLater(unityLauncher, () -> {
            // Если запись уже исчезла — ничего не делаем
            SignVariables sv = genericSignList.get(loc);
            if (sv == null) { resetTasks.remove(loc); return; }

            // Если сейчас на паузе — перепланируем
            if (sv.isPaused()) { scheduleSignReset(loc); return; }

            BlockState st = loc.getBlock().getState();
            if (!(st instanceof Sign sign)) { resetTasks.remove(loc); return; }

            String[] lines = originalSignTexts.get(loc);
            if (lines != null) {
                for (int i = 0; i < Math.min(4, lines.length); i++) {
                    sign.setLine(i, lines[i]);
                }
                sign.update();
            }
            resetTasks.remove(loc);
        }, 20 * 10L); // 10 секунд

        resetTasks.put(loc, task);
    }

    private Block findNearestContainer(Location origin, Player p) {
        World world = origin.getWorld();
        Block nearest = null;
        double minDistanceSquared = Double.MAX_VALUE;
        boolean badZoneFound = false;

        // Маркер зоны самой таблички
        ExtrudeMarker signMarker = isSignWithinMarker(origin, "zones_shop");

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    Block block = world.getBlockAt(origin.clone().add(x, y, z));
                    if (!(block.getState() instanceof Container)) continue;

                    double dist2 = origin.distanceSquared(block.getLocation());
                    ExtrudeMarker chestMarker = isSignWithinMarker(block.getLocation(), "zones_shop");

                    boolean sameZone = (signMarker != null && chestMarker != null
                            && Objects.equals(signMarker.getLabel(), chestMarker.getLabel()));
                    if (!sameZone) { badZoneFound = true; continue; }

                    // НОВОЕ: владелец SHOP-зоны должен совпадать с игроком
                    if (!isShopMarkerOwnedBy(chestMarker, p.getName(), world)) {
                        badZoneFound = true;
                        continue;
                    }

                    if (dist2 < minDistanceSquared) {
                        minDistanceSquared = dist2;
                        nearest = block;
                    }
                }
            }
        }
        if (nearest == null && badZoneFound) {
            p.sendMessage(ChatColor.RED + "Хранилище должно находиться в твоей зоне магазина.");
        }
        return nearest;
    }

    // Возвращает блок, на котором держится табличка (стена / пол / потолок / цепь)
    private Block getSignSupportBlock(Block signBlock) {
        BlockState state = signBlock.getState();
        if (!(state instanceof Sign)) return null;

        Material type = signBlock.getType();
        BlockData data = signBlock.getBlockData();
        String name = type.name();

        // Настенные таблички и настенные свисающие таблички
        if (data instanceof Directional directional &&
                (name.endsWith("WALL_SIGN") || name.endsWith("WALL_HANGING_SIGN"))) {

            BlockFace facing = directional.getFacing();        // куда смотрит
            BlockFace attached = facing.getOppositeFace();     // к чему прикреплена
            return signBlock.getRelative(attached);
        }

        // Обычные стоячие таблички — стоят на блоке снизу
        if (name.endsWith("_SIGN") && !name.contains("WALL") && !name.contains("HANGING")) {
            return signBlock.getRelative(BlockFace.DOWN);
        }

        // Свисающие (цепь к потолку)
        if (name.endsWith("HANGING_SIGN") && !name.contains("WALL")) {
            return signBlock.getRelative(BlockFace.UP);
        }

        // Фоллбек: если есть направление — используем его, иначе считаем что стоит на блоке снизу
        if (data instanceof Directional dir) {
            return signBlock.getRelative(dir.getFacing().getOppositeFace());
        }

        return signBlock.getRelative(BlockFace.DOWN);
    }

    private boolean isAttachedToBlock(Block signBlock, Block possibleSupportingBlock) {
        Block support = getSignSupportBlock(signBlock);
        return support != null && support.equals(possibleSupportingBlock);
    }


    public ExtrudeMarker isSignWithinMarker(Location signLocation, String setName) {
        boolean debug = false;
        try {
            debug = UpgradesListener.class.getDeclaredField("DEBUG").getBoolean(null);
        } catch (Throwable ignored) {}

        Optional<BlueMapAPI> apiOpt = BlueMapAPI.getInstance();
        if (apiOpt.isEmpty()) {
            if (debug) Bukkit.getLogger().info("[SignManager] BlueMapAPI не инициализирован.");
            return null;
        }
        BlueMapAPI api = apiOpt.get();

        Optional<BlueMapMap> mapOpt = api.getMap(signLocation.getWorld().getName());
        if (mapOpt.isEmpty()) {
            if (debug) Bukkit.getLogger().info("[SignManager] Карта не найдена для мира " + signLocation.getWorld().getName());
            return null;
        }
        BlueMapMap map = mapOpt.get();

        MarkerSet set = map.getMarkerSets().get(setName);
        if (set == null) {
            if (debug) Bukkit.getLogger().info("[SignManager] MarkerSet с ID " + setName + " не найден.");
            return null;
        }

        if (debug) Bukkit.getLogger().info("[SignManager] MarkerSet '" + setName + "' содержит " + set.getMarkers().size() + " маркеров.");

        Vector2d sign2D = new Vector2d(signLocation.getX(), signLocation.getZ());
        double y = signLocation.getY();

        for (Marker marker : set.getMarkers().values()) {
            if (!(marker instanceof ExtrudeMarker extrude)) continue;

            Shape shape = extrude.getShape();
            double minY = extrude.getShapeMinY();
            double maxY = extrude.getShapeMaxY();
            boolean insidePolygon = zoneManager.isPointInsidePolygon(sign2D, Collections.singletonList(shape.getPoints()));
            boolean insideHeight = y >= minY && y <= maxY;

            if (debug) {
                Bukkit.getLogger().info(String.format(
                        "[SignManager DEBUG] Проверяем маркер '%s': poly=%s, y=%.2f ∈ [%.2f..%.2f]? %s",
                        extrude.getLabel(),
                        insidePolygon, y, minY, maxY, insideHeight
                ));
            }

            if (insidePolygon && insideHeight) {
                if (debug) Bukkit.getLogger().info("[SignManager DEBUG] Табличка попала внутрь маркера '" + extrude.getLabel() + "'");
                return extrude;
            }
        }

        if (debug) Bukkit.getLogger().info("[SignManager DEBUG] Табличка не попала ни в один маркер в наборе '" + setName + "'");
        return null;
    }


    /** Применяет DTO батчами. Мы обновляем ТОЛЬКО существующие записи, чтобы не гадать конструктор. */
    public void applySignsDTOBatched(List<SignDTO> dto, int perTick) {
        if (dto == null || dto.isEmpty()) return;
        final int BATCH = Math.max(50, perTick);

        unityLauncher.getLogger().info("[SignManager] Применяем таблички батчами: " + dto.size() + " шт., " + BATCH + "/тик");

        AtomicInteger idx = new AtomicInteger(0);
        new org.bukkit.scheduler.BukkitRunnable() {
            @Override public void run() {
                int start = idx.get();
                int end = Math.min(start + BATCH, dto.size());

                for (int i = start; i < end; i++) {
                    SignDTO s = dto.get(i);
                    org.bukkit.World w = Bukkit.getWorld(s.world());
                    if (w == null) continue;
                    Location loc = new Location(w, s.x(), s.y(), s.z());

                    // Если запись существует — обновим поля; иначе пропустим (не знаем твой конструктор)
                    SignVariables vars = genericSignList.get(loc);
                    if (vars != null) {
                        if (s.category() != null) vars.setSignCategory(s.category());
                        if (s.ownerName() != null) vars.setOwnerName(s.ownerName());
                        // label — если у тебя есть соответствующее поле/метод — примени тут
                    }
                }

                idx.set(end);
                if (end >= dto.size()) {
                    cancel();
                    unityLauncher.getLogger().info("[SignManager] Применение DTO завершено: " + dto.size());
                }
            }
        }.runTaskTimer(unityLauncher, 1L, 1L);
    }

    /** Асинхронно читает YAML и строит DTO, не трогая Bukkit/World/Location. */
    public CompletableFuture<List<SignDTO>> loadSignsDTOAsync() {
        return CompletableFuture.supplyAsync(() -> {
            File f = signsFile();
            if (!f.exists()) {
                unityLauncher.getLogger().warning("[SignManager] signs.yml не найден: " + f.getAbsolutePath());
                return List.of();
            }
            try (FileInputStream in = new FileInputStream(f)) {
                // SnakeYAML 2.x: нужен LoaderOptions
                Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
                Object root = yaml.load(in);

                List<SignDTO> out = new ArrayList<>();

                if (root instanceof Map<?, ?> map) {
                    Object signs = map.containsKey("signs") ? map.get("signs") : map.get("data");
                    if (signs instanceof Iterable<?> list) {
                        for (Object o : list) parseOneDTO(o, out);
                    } else {
                        // возможно, карта id -> entry
                        for (Object v : map.values()) parseOneDTO(v, out);
                    }
                } else if (root instanceof Iterable<?> list) {
                    for (Object o : list) parseOneDTO(o, out);
                }

                unityLauncher.getLogger().info("[SignManager] Загружено DTO табличек: " + out.size());
                return out;
            } catch (Exception e) {
                unityLauncher.getLogger().severe("[SignManager] Ошибка чтения signs.yml: " + e.getMessage());
                e.printStackTrace();
                return List.of();
            }
        });
    }

    private void parseOneDTO(Object node, List<SignDTO> out) {
        if (!(node instanceof Map<?, ?> m)) return;

        String world = asString(m.get("world"));
        Integer x = asInt(m.get("x"));
        Integer y = asInt(m.get("y"));
        Integer z = asInt(m.get("z"));
        if (world == null || x == null || y == null || z == null) return;

        String catStr = asString(m.containsKey("category") ? m.get("category") : m.get("type"));

        com.frammy.unitylauncher.signs.SignCategory defaultCat;
        try {
            defaultCat = com.frammy.unitylauncher.signs.SignCategory.valueOf("OTHER");
        } catch (Exception ex) {
            com.frammy.unitylauncher.signs.SignCategory[] vals = com.frammy.unitylauncher.signs.SignCategory.values();
            defaultCat = vals.length > 0 ? vals[0] : null;
        }

        com.frammy.unitylauncher.signs.SignCategory cat;
        try {
            cat = (catStr == null) ? defaultCat :
                    com.frammy.unitylauncher.signs.SignCategory.valueOf(catStr.toUpperCase());
        } catch (IllegalArgumentException iae) {
            cat = defaultCat;
        }

        String owner = asString(m.get("owner"));
        Object labelObj = m.containsKey("label") ? m.get("label") : m.get("name");
        String label = asString(labelObj);

        out.add(new SignDTO(world, x, y, z, cat, owner, label));
    }

    private String asString(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private String prefix() { return (C != null ? C.signsPrefix : ""); }

    private void sendPrefixed(Player p, String msg) {
        if (msg == null || msg.isEmpty()) return;
        p.sendMessage(prefix() + msg);
    }

    private void sendPrefixed(Player p, String msg, double cost) {
        if (msg == null) return;
        String s = msg.replace("%cost%", String.valueOf(cost));
        p.sendMessage(prefix() + s);
    }

    // === OWNERSHIP CHECK: принадлежит ли этот SHOP-маркер игроку? ===
    private boolean isShopMarkerOwnedBy(de.bluecolored.bluemap.api.markers.ExtrudeMarker marker, String playerName, World world) {
        if (marker == null || playerName == null || world == null || zoneManager == null) return false;
        final String label = marker.getLabel(); // имя зоны на карте == ZoneInfo.getName()
        if (label == null || label.isBlank()) return false;

        // Находим зону типа SHOP с таким именем в этом мире и сверяем владельца.
        return zoneManager.getAllZonesSnapshot().stream()
                .filter(z -> z.getType() == com.frammy.unitylauncher.zones.ZoneType.SHOP)
                .filter(z -> z.getName() != null && z.getName().equals(label))
                .filter(z -> z.getWorld() != null && z.getWorld().getUID().equals(world.getUID()))
                .anyMatch(z -> z.getOwner() != null && z.getOwner().equalsIgnoreCase(playerName));
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        protectShopBlocksFromExplosion(e.blockList());
    }

    @org.bukkit.event.EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        protectShopBlocksFromExplosion(e.blockList());
    }

    private void protectShopBlocksFromExplosion(java.util.List<Block> blocks) {
        // Удаляем из списка на разрушение: (а) наши магазинные таблички; (б) привязанные контейнеры
        blocks.removeIf(b -> {
            // Если блок в SHOP-зоне — проверяем ниже
            if (zoneManager.getShopZoneAt(b.getLocation()) == null) return false;

            // (а) табличка из нашего списка и это SHOP_* табличка
            if (b.getState() instanceof Sign) {
                SignVariables sv = genericSignList.get(b.getLocation());
                if (sv != null && (sv.getSignCategory() == SignCategory.SHOP_SOURCE || sv.getSignCategory() == SignCategory.SHOP_LIST)) {
                    return true; // защитить
                }
            }

            // (б) контейнер, привязанный к SHOP_SOURCE
            if (b.getState() instanceof org.bukkit.block.Container) {
                return containerToSourceSign.containsKey(b.getLocation()); // защитить
            }
            return false;
        });
    }

    /**
     * Продажа мусора по тарифам из zones-economy.yml (economy.trashSell.*).
     *
     * Настройки:
     *  - enabled
     *  - minStackSize
     *  - prices (Material -> цена за 1 шт.)
     *  - blacklist (Material, которые никогда не продаём)
     *
     * dailyLimitPerPlayer / globalDailyLimit здесь сознательно не трогаю —
     * для них нужна отдельная система учёта за сутки.
     */
    private void handleTrashSell(Player p, Sign sign) {
        ZonesEconomyConfig.TrashSell ts = ZonesEconomyConfig.get().trashSell;

        if (!ts.enabled) {
            p.sendMessage(ChatColor.RED + "Продажа мусора временно отключена на сервере.");
            return;
        }

        Inventory inv = p.getInventory();
        ItemStack[] contents = inv.getStorageContents();

        double totalReward = 0.0;
        int totalItems = 0;

        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType().isAir()) continue;

            Material type = stack.getType();

            // Чёрный список — вообще не трогаем
            if (ts.blacklist.contains(type)) continue;

            Double pricePerOne = ts.prices.get(type);
            if (pricePerOne == null || pricePerOne <= 0.0) continue;

            int amount = stack.getAmount();
            if (amount <= 0) continue;

            // Минимальный размер стака для продажи
            if (amount < ts.minStackSize) continue;

            totalReward += pricePerOne * amount;
            totalItems += amount;

            // Забираем весь стак
            inv.setItem(slot, null);
        }

        totalReward = round2(totalReward);

        if (totalReward <= 0.0 || totalItems <= 0) {
            p.sendMessage(ChatColor.YELLOW + "В инвентаре нет предметов, которые принимаются как мусор.");
            return;
        }

        double finalTotalReward = totalReward;
        int finalTotalItems = totalItems;

        p.sendMessage(ChatColor.GRAY + "Сдаём мусор...");

        UnityCommands.getInstance().getPlayerInfo(p.getName(), data -> {
            if (data == null) {
                new BukkitRunnable(){ @Override public void run(){
                    p.sendMessage(ChatColor.RED + "Не удалось получить твои данные. Сообщи администрации.");
                }}.runTask(UnityLauncher.getInstance());
                return;
            }

            new BukkitRunnable(){ @Override public void run(){
                Map<String, Object> updates = new HashMap<>();
                updates.put("money", round2(data.money + finalTotalReward));
                UnityCommands.getInstance().mergeAndUpdatePlayerData(p.getName(), "GeneralData", updates);

                new BukkitRunnable(){ @Override public void run(){
                    p.sendMessage(ChatColor.GREEN + "Сдано мусора: " + ChatColor.YELLOW + finalTotalItems + ChatColor.GREEN + " шт.");
                    p.sendMessage(ChatColor.GREEN + "Зачислено: " + ChatColor.YELLOW + finalTotalReward + ChatColor.GREEN + " Ⓕ.");
                }}.runTask(UnityLauncher.getInstance());
            }}.runTaskAsynchronously(UnityLauncher.getInstance());
        });
    }
}