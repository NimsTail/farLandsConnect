package com.frammy.unitylauncher.signs;

import com.frammy.unitylauncher.BlueMapIntegration;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.features.atm.AtmController;
import com.frammy.unitylauncher.signs.features.redstone.RedstoneController;
import com.frammy.unitylauncher.signs.features.shop.ShopController;
import com.frammy.unitylauncher.signs.features.shop.ShopListUpdater;
import com.frammy.unitylauncher.signs.features.trash.TrashController;
import com.frammy.unitylauncher.signs.markers.MarkerService;
import com.frammy.unitylauncher.signs.persistence.SignYamlRepository;
import com.frammy.unitylauncher.signs.render.SignRenderer;
import com.frammy.unitylauncher.signs.render.SignScrollService;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.geom.ZoneGeometry;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallHangingSign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.*;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class SignManager implements Listener {

    private final UnityLauncher plugin;
    private final ZoneManager zoneManager;
    private final BlueMapIntegration blueMapIntegration;
    private final SignStore store;
    private final AtmController atm;
    private final SignScrollService scroll;
    private final SignYamlRepository repo;
    private final TrashController trash;
    private final RedstoneController redstone;
    private final ShopListUpdater shopLists;
    private final ShopController shop;
    private final com.frammy.unitylauncher.signs.features.shop.AutoDebitService autoDebit;

    // Guards rebuildShopIndexAndListsNow() against running twice — once from
    // LazyBlueMapLoader's direct call (the real "zones are actually loaded"
    // signal) and once from the fallback poll below, in whichever order they
    // land. See rebuildShopIndexAndListsWhenZonesReady() for why the poll
    // exists at all instead of just always relying on the direct call.
    private volatile boolean shopIndexBuilt = false;
    private org.bukkit.scheduler.BukkitTask shopReadyPollTask;

    public SignManager(UnityLauncher plugin, File dataFolder, ZoneManager zoneManager, BlueMapIntegration blueMapIntegration) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.blueMapIntegration = blueMapIntegration;
        this.store = new SignStore();
        SignRenderer renderer = new SignRenderer();
        this.scroll = new SignScrollService(plugin, store);
        this.autoDebit = new com.frammy.unitylauncher.signs.features.shop.AutoDebitService(plugin, store, zoneManager);
        this.atm = new AtmController(plugin, blueMapIntegration, store, scroll, autoDebit);

        this.repo = new SignYamlRepository(plugin, dataFolder, SignManager::playerCountryCanonical);
        this.trash = new TrashController(plugin);
        this.redstone = new RedstoneController(plugin, store);

        MarkerService markers = new MarkerService(zoneManager);

        this.shopLists = new ShopListUpdater(plugin, zoneManager, store, renderer,
                (loc) -> shopKey(zoneManager, loc)
        );

        this.shop = new com.frammy.unitylauncher.signs.features.shop.ShopController(
                plugin, zoneManager, blueMapIntegration, markers, store, renderer, scroll, shopLists, this::indexShopSign
        );
    }

    public com.frammy.unitylauncher.signs.features.shop.AutoDebitService getAutoDebitService() {
        return autoDebit;
    }

    private static String shopKey(ZoneManager zm, Location loc) {
        if (zm == null || loc == null) return null;
        var z = zm.getShopZoneAt(loc);
        if (z == null) return null;
        var w = loc.getWorld();
        if (w == null) return null;
        String name = z.getName();
        if (name == null || name.isBlank()) return null;
        return w.getUID() + ":" + name;
    }

    private final Map<String, Set<Location>> shopToListSigns = new HashMap<>();
    private final Map<String, Set<Location>> shopToSourceSigns = new HashMap<>();

    private void indexShopSign(Location signLoc, SignVariables sv) {
        String key = shopKey(zoneManager, signLoc);
        if (key == null || sv == null) return;

        if (sv.getSignCategory() == SignCategory.SHOP_LIST) {
            shopToListSigns.computeIfAbsent(key, k -> new HashSet<>()).add(signLoc);
        } else if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            shopToSourceSigns.computeIfAbsent(key, k -> new HashSet<>()).add(signLoc);
        }
    }

    private void unindexShopSign(Location signLoc, SignVariables sv) {
        String key = shopKey(zoneManager, signLoc);
        if (key == null || sv == null) return;

        if (sv.getSignCategory() == SignCategory.SHOP_LIST) {
            Set<Location> s = shopToListSigns.get(key);
            if (s != null) { s.remove(signLoc); if (s.isEmpty()) shopToListSigns.remove(key); }
        } else if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            Set<Location> s = shopToSourceSigns.get(key);
            if (s != null) { s.remove(signLoc); if (s.isEmpty()) shopToSourceSigns.remove(key); }
        }
    }

    // ======= helpers (оставляем тут, чтобы не плодить util пока) =======

    // ВАЖНО: раньше тут было просто trim+lowercase БЕЗ транслитерации/резолва по ID — это была
    // третья, отдельная от UpgradeCondition схема канонизации страны, из-за которой лимиты
    // ATM/TRASH по правам могли смотреть не в ту LuckPerms-группу. Теперь делегируем в
    // UpgradeCondition, чтобы везде использовался один и тот же ключ (Countries.Id).
    private static String playerCountryCanonical(String playerName) {
        return com.frammy.unitylauncher.upgrades.UpgradeCondition.playerCountryCanonical(playerName);
    }

    /** Same as AtmController.countryDisplayName — canonical is Countries.Id (a numeric string), never show it directly (GH #6). */
    private static String countryDisplayName(String canonical) {
        if (canonical == null || canonical.isBlank()) return canonical;
        try {
            String name = com.frammy.unitylauncher.UnityLauncher.getInstance().countryRegistryJdbc.getCountryDisplayNameForCanonical(canonical);
            return name != null ? name : canonical;
        } catch (Throwable ignored) {
            return canonical;
        }
    }

    private static String signCountryCanonical(SignVariables sv) {
        if (sv == null) return null;

        // sv.getOwnerCountry() is ALREADY the canonical Countries.Id (every
        // call site that populates it — AtmController/TrashController/
        // ShopController — passes playerCountryCanonical(...)'s result
        // straight through, see the `pc` params above). Re-resolving it here
        // via resolveCountryGroupId() treated it as a raw country NAME
        // instead and looked it up by lowercased-name, which a numeric id
        // string never matches — so this always returned null, countByCountry
        // never matched any existing sign, `have` was permanently stuck at 0,
        // and the "X/Y" limit shown to players never advanced past 1/allowed
        // no matter how many ATM/TRASH signs were already placed for the
        // country (the limit itself was silently never enforced either).
        String c = sv.getOwnerCountry();
        if (c != null && !c.isBlank()) {
            return c;
        }

        String owner = sv.getOwnerName();
        return playerCountryCanonical(owner);
    }

    private static final String ATM_PERM_BASE = "unity.atm";
    private static final String TRASH_PERM_BASE = "unity.trash";
    private static final int SPECIAL_SIGNS_MAX_LEVEL_CAP = 200;

    private int allowedSpecialSignsByPerm(String countryCanonical, String permBase) {
        if (countryCanonical == null || countryCanonical.isBlank()) return 0;
        return countryMaxLevel(countryCanonical, permBase, SPECIAL_SIGNS_MAX_LEVEL_CAP);
    }

    private boolean requireCountryLeader(Player p) {
        if (p == null) return false;
        if (UnityLauncher.getInstance().countryRegistryJdbc.isCountryLeaderCached(p.getName())) return true;
        p.sendMessage(ChatColor.RED + "Создавать специальные таблички может только правитель страны.");
        return false;
    }

    private void stopAllScrollingTasks() {
        for (var t : scroll.scrollingTasks().values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        scroll.scrollingTasks().clear();

        for (var t : scroll.resetTasks().values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        scroll.resetTasks().clear();

        for (var m : scroll.activeScrolls().values()) {
            for (var t : m.values()) {
                try { t.cancel(); } catch (Throwable ignored) {}
            }
        }
        scroll.activeScrolls().clear();
    }

    private void restoreRuntimeStateAfterLoad() {
        // 1) восстановить скролл
        for (var e : store.signs().entrySet()) {
            Location loc = e.getKey();
            SignVariables sv = e.getValue();
            if (loc == null || sv == null) continue;
            if (loc.getWorld() == null) continue;

            BlockState st = loc.getBlock().getState();
            List<String> stWorld = null;
            if (st instanceof Sign s) {
                stWorld = Arrays.asList(s.getLines());
            }

            List<String> base = (stWorld != null && !stWorld.isEmpty()) ? stWorld : sv.getSignText();
            List<Integer> sl = sv.getScrollLines();
            if (sl != null && !sl.isEmpty() && base != null && !base.isEmpty()) {
                Map<Integer, String> m = new HashMap<>();
                for (int idx : sl) {
                    if (idx >= 0 && idx < base.size()) m.put(idx, base.get(idx));
                }
                if (!m.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> scroll.makeSignScrollingLines(loc, m, 6, 13));
            }

            // GH #21 point 1: SHOP_SOURCE signs no longer get their own POI
            // marker (redundant on top of the SHOP zone's own extrude
            // marker) — this restore-on-boot pass is what would otherwise
            // keep resurrecting markerIds saved on signs from before that
            // change on every server restart.
            String markerId = sv.getMarkerID();
            if (markerId != null && !markerId.isBlank() && sv.getSignCategory() == SignCategory.ATM) {
                blueMapIntegration.addBlueMapMarker(markerId, loc, "services", "Сервисы", "point_atm", null, null);
                // GH #21 п.3 (restore-on-boot regression) — addBlueMapMarker
                // above only sets the generic "ATM" label; without this the
                // real ID/country/fee popup (AtmController.onSignCreateATM)
                // silently reset to blank on every server restart.
                atm.applyMarkerDetail(loc, markerId, sv.getOwnerCountry(), sv.getAtmNumber());
            }
        }

        // 3) на всякий случай перестроить листы (ты это уже делаешь)
//        try { shopLists.rebuildAllListsLater(); } catch (Throwable ignored) {}
    }

    /** Массово перестроить все SHOP_LIST таблички (планировщик внутри ShopListUpdater). */
    public void rebuildAllShopListsLater() {
        try {
            shopLists.rebuildAllListsLater();
        } catch (Throwable t) {
            plugin.getLogger().warning("[Signs] rebuildAllShopListsLater failed: " + t.getMessage());
        }
    }

    // ======= persistence API =======

    public void saveSignData() {
        repo.save(store, false);
    }

    /**
     * The real "zones are actually loaded" signal — call this once
     * ZoneManager.loadZonesFromConfig() has genuinely returned (see
     * LazyBlueMapLoader, which is the only normal-startup caller). Safe to
     * call more than once: the first call wins, the rest are no-ops.
     *
     * Previously this whole rebuild only ever ran from a 20s poll
     * (rebuildShopIndexAndListsWhenZonesReady below) that started at
     * SignManager.enable() — i.e. right at the very start of onEnable(),
     * well before LazyBlueMapLoader's BlueMapAPI.onEnable()-gated call to
     * loadZonesFromConfig() had populated any zones at all. That poll's
     * "are zones ready?" check was itself indirect (does some existing SHOP
     * sign already resolve to a zone?), so on a slow/loaded boot — world
     * prep + BlueMap init eating into the same 20s window — it could run
     * out of tries before ever seeing zones loaded, and it never retried
     * again afterward: SHOP indices/lists just stayed empty for the whole
     * server session until someone ran /ul reload. This direct call removes
     * the guesswork for the normal path; the poll remains only as a
     * fallback (e.g. BlueMap disabled, so LazyBlueMapLoader never runs).
     */
    public void rebuildShopIndexAndListsNow() {
        if (shopIndexBuilt) return;
        shopIndexBuilt = true;
        if (shopReadyPollTask != null) {
            shopReadyPollTask.cancel();
            shopReadyPollTask = null;
        }

        shopToListSigns.clear();
        shopToSourceSigns.clear();
        for (var e : store.signs().entrySet()) {
            indexShopSign(e.getKey(), e.getValue());
        }

        repairAllShopSourcesFromWorld();

        try { shopLists.rebuildAllListsLater(); }
        catch (Throwable t) { plugin.getLogger().warning("[Signs] rebuildAllListsLater failed: " + t.getMessage()); }
    }

    /**
     * Fallback only — see rebuildShopIndexAndListsNow(). Polls for the same
     * "at least one SHOP sign resolves to a zone" heuristic in case nothing
     * ever calls the direct signal (BlueMap missing/disabled leaves
     * LazyBlueMapLoader.scheduleLazyLoad() a no-op). Keeps retrying at a
     * slower cadence after the first 20s instead of giving up forever, so a
     * merely-slow boot self-heals instead of needing a manual /ul reload.
     */
    private void rebuildShopIndexAndListsWhenZonesReady() {
        final int[] tries = {0};

        // The Consumer<BukkitTask> overload returns void (unlike the plain
        // Runnable one) — self-cancellation only works via the task handed
        // to the callback, so that's also how shopReadyPollTask gets set,
        // not from this call's own return value.
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            shopReadyPollTask = task;
            if (shopIndexBuilt) {
                task.cancel();
                return;
            }
            tries[0]++;

            boolean zonesReadyForShop = false;

            // Проверяем, что хотя бы одна SHOP табличка уже видит свою зону
            for (var e : store.signs().entrySet()) {
                var sv = e.getValue();
                if (sv == null) continue;
                if (sv.getSignCategory() != SignCategory.SHOP_SOURCE
                        && sv.getSignCategory() != SignCategory.SHOP_LIST) continue;

                Location loc = e.getKey();
                if (loc == null || loc.getWorld() == null) continue;

                if (zoneManager.getShopZoneAt(loc) != null) {
                    zonesReadyForShop = true;
                    break;
                }
            }

            if (zonesReadyForShop) {
                rebuildShopIndexAndListsNow();
                task.cancel();
                return;
            }

            if (tries[0] == 40) { // 40*10 тиков ≈ 20 секунд — раньше тут отменяли задачу насовсем
                plugin.getLogger().warning("[Signs] SHOP zones not ready after 20s — still retrying (was: giving up here for the rest of the session).");
            }
        }, 10L, 10L);
    }

    public void loadSignData() {
        repo.load(store, false, null); // <-- ВАЖНО: не rebuildAllListsLater тут

        // runtime бинды контейнер->source восстановим сразу (не зависит от зон)
        store.rebuildShopContainerBinds();

        // индексы shopTo* НЕ строим тут (зоны могут быть не готовы)
        shopToListSigns.clear();
        shopToSourceSigns.clear();

        // REDSTONE-таблички (v2, GH #2 round 2) больше не владеют никаким
        // блоком — они ищут уже существующий провод/рычаг рядом при каждом
        // клике, так что тут нечего восстанавливать на старте.
    }

    public SignStore store() { return store; }

    public TrashController getTrashController() {
        return trash;
    }

    public ShopController getShopController() {
        return shop;
    }

    /** Сообщить, что имя SHOP-зоны изменилось — обновить все связанные таблички магазина. */
    public void onShopZoneRenamed() {
        shopLists.rebuildAllListsLater();
    }

    /**
     * GH#34 (личный кабинет продавца, farlandsconnect infra/personal-upgrades-
     * catalog.md, 2026-08-27) — read-only снимок живого содержимого сундуков
     * одного SHOP-магазина по markerId, для ZoneRequestPoller's
     * "shop_inventory_sync". Переиспользует ТОТ ЖЕ расчёт
     * (ShopListUpdater.computeItemsForSourceSigns), что уже строит таблички
     * SHOP_LIST на каждой продаже/правке — никакого нового периодического
     * сканирования сундуков здесь не добавляется, это просто чтение уже
     * готового индекса shopToSourceSigns по требованию сайта.
     */
    public List<com.frammy.unitylauncher.signs.features.shop.ItemData> getShopItemsByMarkerId(String markerId) {
        if (markerId == null) return List.of();

        var zone = zoneManager.getAllZonesSnapshot().stream()
                .filter(z -> markerId.equals(z.getMarkerID()))
                .findFirst()
                .orElse(null);
        if (zone == null || zone.getType() != com.frammy.unitylauncher.zones.ZoneType.SHOP) return List.of();

        World world = zone.getWorld();
        String name = zone.getName();
        if (world == null || name == null || name.isBlank()) return List.of();

        String key = world.getUID() + ":" + name;
        Set<Location> sourceSigns = shopToSourceSigns.getOrDefault(key, Set.of());
        return shopLists.computeItemsForSourceSigns(sourceSigns);
    }

    private static final String SIGNS_BYPASS_PERM = "unity.signs.bypass";

    /** Локация блока-опоры для конкретной таблички в мире. */
    public static Location getSupportBlockOfSign(Block signBlock) {
        if (signBlock == null) return null;

        BlockData data = signBlock.getBlockData();

        // 1) Обычная настенная табличка
        switch (data) {
            case WallSign ws -> {
                BlockFace facing = ws.getFacing();               // куда "смотрит" табличка

                return signBlock.getRelative(facing.getOppositeFace()).getLocation(); // блок, к которому прикреплена

            }
            // 2) Свисающая со стены
            case WallHangingSign whs -> {
                BlockFace facing = whs.getFacing();
                return signBlock.getRelative(facing.getOppositeFace()).getLocation();
            }
            // 3) Свисающая с потолка
            case HangingSign ignored -> {
                return signBlock.getRelative(BlockFace.UP).getLocation();
            }
            default -> {
            }
        }

        // 4) Напольная табличка (столбик)
        // В 1.21 для standing sign нет Directional, поэтому просто блок снизу — это “опора”
        return signBlock.getRelative(BlockFace.DOWN).getLocation();
    }

    private boolean canBreakProtectedSupport(Player p, Location signLoc, SignVariables sv) {
        if (p == null) return false;
        if (p.isOp() || p.hasPermission(SIGNS_BYPASS_PERM)) return true;

        // ATM: сознательно без приватной защиты — кто угодно может сломать
        // опору. Ломать реальную табличку по-прежнему разрешено только
        // тем, кто может дойти до этого места физически.
        if (sv != null && sv.getSignCategory() == SignCategory.ATM) return true;

        // SHOP: истинный владелец — владелец зоны (ownerName может устареть)
        if (sv != null && (sv.getSignCategory() == SignCategory.SHOP_SOURCE || sv.getSignCategory() == SignCategory.SHOP_LIST)) {
            return zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), signLoc);
        }

        // Остальные: владелец таблички по ownerName
        String owner = (sv != null ? sv.getOwnerName() : null);
        return owner != null && owner.equalsIgnoreCase(p.getName());
    }

    // ======= events =======

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();

        // 0) защита опорных блоков табличек
        // Если этот блок держит любую сохранённую табличку — ломать может только хозяин таблички.
        Location broken = SignStore.keyLoc(b.getLocation());

        // Собираем таблички, которые физически стоят на этом блоке — нужны
        // и для проверки права ломать (ниже), и (баг-фикс, см. ниже) для
        // деактивации после, если ломать разрешено.
        List<Location> signsOnBrokenSupport = new ArrayList<>();

        // маленькая оптимизация: если мир null или store пуст — не бегаем
        if (broken.getWorld() != null && !store.signs().isEmpty()) {
            for (var entry : store.signs().entrySet()) {
                Location signLoc = entry.getKey();
                SignVariables sv = entry.getValue();
                if (signLoc == null || sv == null) continue;
                if (signLoc.getWorld() == null) continue;

                // мир/координаты сравниваем нормализовано
                Block signBlock = signLoc.getBlock();
                BlockState st = signBlock.getState();
                if (!(st instanceof Sign)) continue; // защищаем только реальные таблички в мире

                Location support = getSupportBlockOfSign(signBlock);
                if (support == null || support.getWorld() == null) continue;

                Location supportKey = SignStore.keyLoc(support);
                if (!supportKey.equals(broken)) continue; // это не опора этой таблички

                if (!canBreakProtectedSupport(p, signLoc, sv)) {
                    e.setCancelled(true);

                    // Человеческое сообщение
                    if (sv.getSignCategory() == SignCategory.SHOP_SOURCE || sv.getSignCategory() == SignCategory.SHOP_LIST) {
                        p.sendMessage(ChatColor.RED + "Этот блок держит табличку магазина. Ломать может только владелец SHOP-зоны.");
                    } else {
                        String owner = sv.getOwnerName();
                        p.sendMessage(ChatColor.RED + "Этот блок держит защищённую табличку. Ломать может только её хозяин"
                                + (owner != null && !owner.isBlank() ? (": " + ChatColor.YELLOW + owner) : "") + ChatColor.RED + ".");
                    }
                    return;
                }

                signsOnBrokenSupport.add(signLoc);
            }
        }

        // Разрешённый снос опоры (хозяин ломает свой же блок) и правда роняет
        // табличку физикой ванильного Minecraft — но физика не бросает
        // BlockBreakEvent на сам блок таблички, только на тот, что реально
        // сломал игрок. Раньше это означало, что деактивация ниже (п.523,
        // "if (!(e.getBlock().getState() instanceof Sign)) return") никогда
        // не срабатывала для этого случая: табличка визуально пропадала
        // (роняется предметом), а стор/BlueMap-маркер/апгрейды продолжали
        // считать её действующей вечно — репортнутый баг "разрушить блок
        // под АТМ, табличка падает, но сервер считает его активным".
        for (Location signLoc : signsOnBrokenSupport) {
            SignVariables sv = store.get(signLoc);
            if (sv != null) deactivateSignAt(signLoc, sv);
        }

        // 1) защита контейнеров магазина
        BlockState st = b.getState();
        if (st instanceof Container c) {
            Location chestLoc = SignStore.keyLoc(c.getLocation());
            Location src = store.sourceSignByContainer(chestLoc);

            // если это double chest, а ломают “вторую половину” — тоже проверим соседей
            if (src == null && b.getType() == Material.CHEST) {
                // проверим 4 стороны на случай второй половины (дёшево)
                for (BlockFace face : new BlockFace[]{
                        BlockFace.NORTH,
                        BlockFace.SOUTH,
                        BlockFace.WEST,
                        BlockFace.EAST
                }) {
                    Block nb = b.getRelative(face);
                    if (nb.getType() != Material.CHEST) continue;
                    BlockState nst = nb.getState();
                    if (!(nst instanceof Container nc)) continue;
                    Location nLoc = SignStore.keyLoc(nc.getLocation());
                    src = store.sourceSignByContainer(nLoc);
                    if (src != null) break;
                }
            }

            if (src != null && !zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), src)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Этот сундук связан с магазином. Ломать может только владелец SHOP-зоны.");
                return;
            }
        }

        if (!(e.getBlock().getState() instanceof Sign)) return;

        Location loc = SignStore.keyLoc(e.getBlock().getLocation());
        SignVariables sv = store.get(loc);
        if (sv == null) return;

        deactivateSignAt(loc, sv);
    }

    /**
     * Same cleanup a physical break already does (stop scroll, unbind shop
     * containers, unindex, redstone cleanup, drop the store entry, remove
     * the BlueMap marker) — factored out so it's callable without an actual
     * BlockBreakEvent. GH #11: a deleted/dissolved zone used to leave every
     * sign inside it fully intact and still "live" — a shop sign kept
     * accepting purchases against a chest/zone that no longer existed,
     * which is what the report called "всё посыпалось". The physical sign
     * block itself is left standing either way (same as a real break —
     * this class never touches the block's text on removal, only the
     * bookkeeping that makes it functional), just no longer tracked.
     */
    private void deactivateSignAt(Location loc, SignVariables sv) {
        scroll.stopScrollingTask(loc);
        if (sv.getScrollLines() != null) {
            for (int idx : sv.getScrollLines()) {
                scroll.stopHorizontalScroll(loc, idx);
            }
        }

        if (sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            store.unbindAllForSourceSign(loc);
        }

        unindexShopSign(loc, sv);

        if (sv.getSignCategory() == SignCategory.REDSTONE) {
            redstone.onSignRemoved(loc);
        }

        store.remove(loc);

        if (sv.getMarkerID() != null && !sv.getMarkerID().isBlank()) {
            try {
                World w = loc.getWorld();
                if (w != null) {
                    // GH #21 point 5: the marker is stored in its MarkerSet
                    // under a PREFIXED key ("atm_" + markerID — see
                    // AtmController.addBlueMapMarker/BlueMapIntegration's
                    // "point_atm" case), but this removal call was passing
                    // the bare markerID — a key that was never actually
                    // used, so the remove() was a silent no-op and the
                    // marker just stayed on the map forever after breaking
                    // the sign.
                    String markerKey = sv.getSignCategory() == SignCategory.ATM ? "atm_" + sv.getMarkerID() : sv.getMarkerID();
                    blueMapIntegration.removeBlueMapMarker(markerKey, w.getName(), "services");
                }
            } catch (Throwable ignored) {}
        }
    }

    /**
     * GH #11: called from ZoneManager right after a zone is actually removed
     * (deleted, or a country dissolved) — deactivates every sign whose
     * location falls inside any of that zone's shapes, same cleanup as a
     * physical break. Y range is intentionally wide (whole build-height) —
     * this only needs to match the zone's X/Z footprint, a sign's exact Y
     * inside that column is irrelevant.
     */
    public void deactivateSignsInZone(List<List<Location>> shapes) {
        if (shapes == null || shapes.isEmpty() || store.signs().isEmpty()) return;

        List<Location> toRemove = new ArrayList<>();
        for (Location loc : store.signs().keySet()) {
            if (loc == null || loc.getWorld() == null) continue;
            if (ZoneGeometry.pointInAnyShape(loc, shapes, -512, 512)) {
                toRemove.add(loc);
            }
        }
        for (Location loc : toRemove) {
            SignVariables sv = store.get(loc);
            if (sv != null) deactivateSignAt(loc, sv);
        }
    }

    private void repairAllShopSourcesFromWorld() {
        for (var e : store.signs().entrySet()) {
            Location loc = e.getKey();
            SignVariables sv = e.getValue();
            if (loc == null || sv == null) continue;
            if (sv.getSignCategory() != SignCategory.SHOP_SOURCE) continue;
            if (loc.getWorld() == null) continue;

            try {
                var st = loc.getBlock().getState();
                if (!(st instanceof Sign sign)) continue;

                String l2 = sign.getLine(2);
                String l3 = sign.getLine(3);

                Integer amount = parseIntLoose(l2);
                Double price = parseDoubleLoose(l3);

                // Если на табличке реально стоят цифры — считаем, что товар настроен
                if (amount != null && amount > 0 && price != null && price > 0) {
                    // Приводим к единому виду (как ты делаешь при подтверждении)
                    List<String> t = safe4(sv.getSignText());
                    String line2 = "Кол-во: " + ChatColor.YELLOW + amount;
                    String line3 = "Цена: " + ChatColor.GREEN + price;

                    sv.setSignText(Arrays.asList(t.get(0), t.get(1), line2, line3));
                    sv.setSignState(SignState.SHOP_DEFINED);

                    // опционально: поправим табличку в мире, если там “грязный” формат
                    sign.setLine(2, line2);
                    sign.setLine(3, line3);
                    sign.update();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static List<String> safe4(List<String> in) {
        ArrayList<String> out = new ArrayList<>(4);
        if (in != null) out.addAll(in);
        while (out.size() < 4) out.add("");
        if (out.size() > 4) out.subList(4, out.size()).clear();
        return out;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShopContainerAutoMove(InventoryMoveItemEvent e) {
        if (isShopLinkedInventory(e.getSource()) || isShopLinkedInventory(e.getDestination())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSignChange(SignChangeEvent e) {
        if (!(e.getBlock().getState() instanceof Sign sign)) return;

        Player p = e.getPlayer();
        Location loc = SignStore.keyLoc(sign.getLocation());

        // ===== EDIT existing ATM (через openSign — ввод суммы/цели, см. AtmController) =====
        SignVariables existingForEdit = store.get(loc);
        if (existingForEdit != null && existingForEdit.getSignCategory() == SignCategory.ATM) {
            atm.onSignEditSubmit(e, loc, existingForEdit);
            return;
        }

        // ===== EDIT existing SHOP_SOURCE (через openSign) =====
        SignVariables existing = store.get(loc);
        if (existing != null && existing.getSignCategory() == SignCategory.SHOP_SOURCE) {

            // только владелец SHOP-зоны может редактировать
            if (!zoneManager.isPlayerOwnerOfShopZoneAt(p.getName(), loc)) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Редактировать эту табличку может только владелец.");
                return;
            }

            // (опционально) синхронизируем ownerName, чтобы оно не устаревало
            if (existing.getOwnerName() == null || !existing.getOwnerName().equalsIgnoreCase(p.getName())) {
                existing.setOwnerName(p.getName());
            }

            // фиксируем 0-1 строки (чтобы не ломали заголовок и координаты)
            List<String> prev = existing.getSignText() != null ? existing.getSignText() : List.of("", "", "", "");
            String l0 = !prev.isEmpty() ? prev.get(0) : "";
            String l1 = prev.size() > 1 ? prev.get(1) : "";

            e.setLine(0, l0);
            e.setLine(1, l1);

            // берём новые 2-3 строки из события (игрок их изменил)
            String l2 = safeLine(e, 2);
            String l3 = safeLine(e, 3);

            existing.setSignText(Arrays.asList(l0, l1, l2, l3));

            // если меняли цену/кол-во — отправляем в режим "нужно подтвердить"
            existing.setSignState(SignState.SHOP_UNDEFINED);

            // подсказка
            Bukkit.getScheduler().runTask(plugin, () ->
                    p.sendMessage(ChatColor.GRAY + "Изменения сохранены. Теперь Shift+ЛКМ по табличке для подтверждения.")
            );

            return; // важно: НЕ идти дальше в логику создания табличек
        }

        // hanging запреты (как в легаси)
        boolean isHanging = e.getBlock().getType().toString().contains("HANGING");

        String line0raw = e.getLine(0);
        if (line0raw == null) line0raw = "";

        store.originalSignTexts().putIfAbsent(loc, new String[]{
                safeLine(e, 0),
                safeLine(e, 1),
                safeLine(e, 2),
                safeLine(e, 3)
        });

        // ===== SHOP =====
        if (line0raw.equalsIgnoreCase("shop") || line0raw.equalsIgnoreCase("магазин")) {

            // hanging запреты оставляем тут (как у тебя было)
            if (isHanging) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать как магазинные!");
                return;
            }

            shop.onSignCreateShop(e, SignManager::playerCountryCanonical);
            return;
        }

        // ===== ATM =====
        if (line0raw.equalsIgnoreCase("ATM")) {

            if (isHanging) {
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве банковского автомата!");
                e.setCancelled(true);
                return;
            }

            String pc = playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "ATM можно ставить только будучи в составе страны.");
                return;
            }

            if (!requireCountryLeader(p)) {
                e.setCancelled(true);
                return;
            }

            int have = store.countByCountry(SignCategory.ATM, pc, SignManager::signCountryCanonical);
            int allowed = allowedSpecialSignsByPerm(pc, ATM_PERM_BASE);

            atm.onSignCreateATM(e, pc, have, allowed);
            return;
        }

        // ===== TRASH =====
        if (line0raw.equalsIgnoreCase("TRASH")
                || line0raw.equalsIgnoreCase("МУСОР")
                || line0raw.equalsIgnoreCase("MUSOR")) {

            if (isHanging) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать в качестве мусорного приёмника!");
                return;
            }

            String pc = playerCountryCanonical(p.getName());
            if (pc == null || pc.isBlank()) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Таблички приёма мусора можно ставить только будучи в составе страны.");
                return;
            }

            if (!requireCountryLeader(p)) {
                e.setCancelled(true);
                return;
            }

            int have = store.countByCountry(SignCategory.TRASH_SELL, pc, SignManager::signCountryCanonical);
            int need = have + 1;
            int allowed = allowedSpecialSignsByPerm(pc, TRASH_PERM_BASE);

            if (allowed < need) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Нельзя поставить мусорку №" + need + " для страны [" + countryDisplayName(pc) + "]. "
                        + ChatColor.GRAY + "Нужно разрешение! (куплено: " + allowed + ").");
                return;
            }

            String title = "Мусорка [" + countryDisplayName(pc) + "]";
            sign.setLine(0, title);
            sign.setLine(1, "ПКМ с пустой");
            sign.setLine(2, "рукой, чтобы");
            sign.setLine(3, "сдать мусор");
            sign.update();

            Map<Integer, String> scrollLines = new HashMap<>();
            scrollLines.put(0, title);

            Bukkit.getScheduler().runTask(plugin, () ->
                    scroll.makeSignScrollingLines(loc, scrollLines, 6, 13)
            );

            store.put(loc, new SignVariables(
                    p.getName(),
                    pc,
                    List.of(title, sign.getLine(1), sign.getLine(2), sign.getLine(3)),
                    List.of(0),
                    false,
                    false,
                    SignCategory.TRASH_SELL,
                    SignState.SHOP_DEFINED,
                    null
            ));

            p.sendMessage(ChatColor.GREEN + "Табличка приёма мусора установлена. " + ChatColor.GRAY + "(" + need + "/" + allowed + ")");
            return;
        }

        // ===== REDSTONE (GitHub issue #2, minecraftServer repo) =====
        if (line0raw.equalsIgnoreCase("REDSTONE") || line0raw.equalsIgnoreCase("РЕДСТОУН")) {
            if (isHanging) {
                e.setCancelled(true);
                p.sendMessage(ChatColor.RED + "Свисающие таблички нельзя использовать как редстоун-табличку!");
                return;
            }
            redstone.onSignCreateRedstone(e);
        }
    }

    private static String safeLine(SignChangeEvent e, int i) {
        String s = e.getLine(i);
        return s == null ? "" : s;
    }

    private static Integer parseIntLoose(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        s = s.replaceAll("[^0-9\\-]", "");
        if (s.isBlank() || s.equals("-")) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { return null; }
    }

    private static Double parseDoubleLoose(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        s = s.replace(',', '.');
        s = s.replaceAll("[^0-9.\\-]", "");
        if (s.isBlank() || s.equals("-") || s.equals(".")) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) { return null; }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (!(e.getClickedBlock().getState() instanceof Sign sign)) return;

        Location loc = SignStore.keyLoc(e.getClickedBlock().getLocation());
        SignVariables sv = store.get(loc);
        if (sv == null) return;

        // TRASH: оставляем правило "только пустая рука"
        if (sv.getSignCategory() == SignCategory.TRASH_SELL) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                Player p = e.getPlayer();
                if (p.getInventory().getItemInMainHand().getType() != Material.AIR) {
                    p.sendMessage(ChatColor.RED + "Освободи основную руку, чтобы сдать мусор.");
                    e.setCancelled(true);
                    e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                    e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
                    return;
                }
                trash.handleTrashSell(p);
                e.setCancelled(true);
                e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
                e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            }
            return;
        }

        // ATM: пусть работает и с предметом в руке
        if (sv.getSignCategory() == SignCategory.ATM) {
            atm.onInteract(e, sv);
            return;
        }

        // REDSTONE: пусть работает и с предметом в руке (как ATM)
        if (sv.getSignCategory() == SignCategory.REDSTONE) {
            redstone.onInteract(e, sv, loc);
            return;
        }

        // SHOP
        if (sv.getSignCategory() == SignCategory.SHOP_LIST || sv.getSignCategory() == SignCategory.SHOP_SOURCE) {
            shop.onInteract(e);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        // Не-владельцам больше не запрещаем ОТКРЫВАТЬ магазинный сундук —
        // только смотреть содержимое разрешено всем. Трогать вещи не-владельцу
        // можно только если у него включено авто-списание (см.
        // AutoDebitService.onClick/onDrag — там же и решается, кто может
        // забрать товар и по какой цене; тут только просмотр).

        // дальше обычная логика магазина (привязка/апдейт листов)
        shop.onInventoryOpen(e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        try { shop.onInventoryClose(e); } catch (Throwable ignored) {}
        if (e.getPlayer() instanceof Player p) {
            try { autoDebit.finalizeUnpaid(p); } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        atm.onQuit(e.getPlayer());
        shop.onPlayerQuit(e.getPlayer());
        try { autoDebit.finalizeUnpaid(e.getPlayer()); } catch (Throwable ignored) {}
    }

    // Авто-списание с товаром в SHOP_SOURCE-сундуке не-владельцем — см.
    // AutoDebitService для полного разбора механики (клэмп при заборе,
    // списание при фактическом попадании в инвентарь игрока).
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShopChestClick(org.bukkit.event.inventory.InventoryClickEvent e) {
        autoDebit.onClick(e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShopChestDrag(org.bukkit.event.inventory.InventoryDragEvent e) {
        autoDebit.onDrag(e);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onShopChestDrop(org.bukkit.event.player.PlayerDropItemEvent e) {
        autoDebit.onDrop(e);
    }

    // ATM's browse sessions hijack the mouse wheel (see AtmController) — only
    // while a session is active for that player, and only enough to cancel
    // the hotbar-slot change; ignoreCancelled=false on purpose since a wheel
    // scroll isn't cancelled by anything else that would matter here.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemHeld(org.bukkit.event.player.PlayerItemHeldEvent e) {
        if (atm.onItemHeld(e.getPlayer(), e.getPreviousSlot(), e.getNewSlot())) {
            e.setCancelled(true);
            return;
        }
        // SHOP_LIST wheel-browse (see ShopController.onItemHeld) — same
        // hijack pattern as ATM, only while the player is actually looking
        // at a SHOP_LIST sign in overview mode.
        if (shop.onItemHeld(e.getPlayer(), e.getPreviousSlot(), e.getNewSlot())) {
            e.setCancelled(true);
        }
    }

    // REDSTONE-табличка держит свою силу вручную (см. RedstoneController — обычный
    // блок не умеет "просто" быть источником питания в Bukkit) — не отменяем
    // событие, только подменяем итоговое значение для наших эмиттеров.
    @EventHandler(ignoreCancelled = false)
    public void onBlockRedstone(BlockRedstoneEvent e) {
        redstone.onBlockRedstone(e);
    }

    public void enable() { onEnable(); }
    public void disable() { onDisable(); }

    private void onEnable() {
        loadSignData();
        store.pruneMissingWorlds();

        // скроллы/маркеры можно сразу
        restoreRuntimeStateAfterLoad();

        // А вот shop индексы + листы — только когда зоны готовы
        rebuildShopIndexAndListsWhenZonesReady();
    }

    private void onDisable() {
        // 14) перед сохранением подчистим мусорные записи
        store.pruneMissingWorlds();

        // 1) сохранить
        saveSignData();

        // 2) корректно остановить ВСЁ, что крутится
        stopAllScrollingTasks();
    }

    private boolean isShopLinkedInventory(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return false;

        // Самый надёжный путь (как с сейфами): location у контейнерного инвентаря
        Location loc = inv.getLocation();
        if (loc != null) {
            loc = SignStore.keyLoc(loc);
            if (store.sourceSignByContainer(loc) != null) return true;
        }

        // fallback по holder’у
        var holder = inv.getHolder();
        if (holder instanceof org.bukkit.block.Container c) {
            Location l = SignStore.keyLoc(c.getLocation());
            return store.sourceSignByContainer(l) != null;
        }
        if (holder instanceof org.bukkit.block.DoubleChest dc) {
            if (dc.getLeftSide() instanceof org.bukkit.block.Chest cl) {
                if (store.sourceSignByContainer(SignStore.keyLoc(cl.getLocation())) != null) return true;
            }
            if (dc.getRightSide() instanceof org.bukkit.block.Chest cr) {
                return store.sourceSignByContainer(SignStore.keyLoc(cr.getLocation())) != null;
            }
        }

        return false;
    }

}
