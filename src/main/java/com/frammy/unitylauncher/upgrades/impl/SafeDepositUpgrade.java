package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.BankCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.frammy.unitylauncher.upgrades.UpgradeCondition.countryMaxLevel;

public final class SafeDepositUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("bank.safe_deposit");

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    private NamespacedKey KEY_SAFE_KEYHASH;
    private NamespacedKey KEY_SAFE_ID;
    private final Map<UUID, String> pendingCreateKeyHash = new ConcurrentHashMap<>();

    private record SafeMeta(String owner, String keyHash, String id) {}

    private final Map<Location, SafeMeta> safes = new ConcurrentHashMap<>();

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        BankCfg.SafeDepositCfg cfg = ctx.config().bank().safeDeposit();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        KEY_SAFE_KEYHASH = new NamespacedKey(plugin(), "safe.keyhash");
        KEY_SAFE_ID      = new NamespacedKey(plugin(), "safe.id");
        loadSafes();
    }

    @Override
    protected void onDisable() {
        saveSafes();
        safes.clear();
    }

    /** Внешний API: можно ли создать сейф (лимит + апгрейд страны) */
    public boolean canCreateSafe(Player player) {
        if (player == null) return false;

        var cfg = C().bank().safeDeposit();

        String pc = UpgradeCondition.playerCountryCanonical(player.getName());
        if (pc == null || pc.isBlank()) return false;

        if (countryMaxLevel(pc, cfg.permBase(), 1) < 1) return false;

        int current = countPlayerSafes(player.getName());
        return current < Math.max(0, cfg.maxPerPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCreateClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();

        UUID uid = p.getUniqueId();
        String kh = pendingCreateKeyHash.get(uid);
        if (kh == null) return; // не в режиме создания

        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CHEST) {
            p.sendMessage(ChatColor.RED + "Нужно кликнуть по обычному сундуку.");
            return;
        }

        // не даём открыть сундук в момент привязки
        e.setCancelled(true);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);

        Location loc = keyLoc(b.getLocation());

        ZoneInfo zone = UpgradeCondition.zoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.BANK) {
            p.sendMessage(ChatColor.RED + "Сейфы можно размещать только в банковских зонах!");
            return;
        }

        if (safes.containsKey(loc)) {
            p.sendMessage(ChatColor.RED + "Этот сундук уже является сейфом!");
            pendingCreateKeyHash.remove(uid);
            return;
        }

        String id = UUID.randomUUID().toString();
        safes.put(loc, new SafeMeta(p.getName(), kh, id));

        Chest chest = (Chest) b.getState();
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        pdc.set(KEY_SAFE_KEYHASH, PersistentDataType.STRING, kh);
        pdc.set(KEY_SAFE_ID, PersistentDataType.STRING, id);
        chest.update(true, false);

        pendingCreateKeyHash.remove(uid);

        p.sendMessage(ChatColor.GREEN + "Сейф создан! Откроется только предметом-ключом.");
        saveSafes();

        if (C().core().debug()) {
            plugin().getLogger().info("[Bank/SafeDeposit] created by " + p.getName() + " at " + loc);
        }
    }

    public void beginCreateSafe(Player p, ItemStack keyItem) {
        if (p == null) return;

        if (!canCreateSafe(p)) {
            p.sendMessage(ChatColor.RED + "У вас нет прав на создание сейфа или достигнут лимит!");
            return;
        }

        if (keyItem == null || keyItem.getType().isAir()) {
            p.sendMessage(ChatColor.RED + "Нужен предмет-ключ в руке для создания сейфа.");
            return;
        }

        String kh = keyHash(keyItem);
        if (kh.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Не удалось создать ключ предмета (ошибка сериализации).");
            return;
        }

        pendingCreateKeyHash.put(p.getUniqueId(), kh);
        p.sendMessage(ChatColor.YELLOW + "Режим создания сейфа активирован. ПКМ по сундуку в BANK-зоне.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pendingCreateKeyHash.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler
    public void onKick(PlayerKickEvent e) {
        pendingCreateKeyHash.remove(e.getPlayer().getUniqueId());
    }

    /** Внешний API: попытка создать сейф из блока (сундук) */
    public boolean tryCreateSafe(Player player, Block block, ItemStack keyItem) {
        if (player == null || block == null) return false;
        if (block.getType() != Material.CHEST) return false;

        var cfg = C().bank().safeDeposit();

        Location loc = keyLoc(block.getLocation());

        ZoneInfo zone = UpgradeCondition.zoneAt(loc);
        if (zone == null || zone.getType() != ZoneType.BANK) {
            player.sendMessage(ChatColor.RED + "Сейфы можно размещать только в банковских зонах!");
            return false;
        }

        if (!canCreateSafe(player)) {
            player.sendMessage(ChatColor.RED + "У вас нет прав на создание сейфа или достигнут лимит!");
            return false;
        }

        if (safes.containsKey(loc)) {
            player.sendMessage(ChatColor.RED + "Этот сундук уже является сейфом!");
            return false;
        }
        if (keyItem == null || keyItem.getType().isAir()) {
            player.sendMessage(ChatColor.RED + "Нужен предмет-ключ в руке для создания сейфа.");
            return false;
        }

        String kh = keyHash(keyItem);
        if (kh.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Не удалось создать ключ предмета (ошибка сериализации).");
            return false;
        }

        String id = UUID.randomUUID().toString();
        safes.put(loc, new SafeMeta(player.getName(), kh, id));

        Chest chest = (Chest) block.getState();
        PersistentDataContainer pdc = chest.getPersistentDataContainer();
        pdc.set(KEY_SAFE_KEYHASH, PersistentDataType.STRING, kh);
        pdc.set(KEY_SAFE_ID, PersistentDataType.STRING, id);
        chest.update();

        player.sendMessage(ChatColor.GREEN + "Сейф создан! Откроется только предметом-ключом.");
        saveSafes();

        if (C().core().debug()) {
            plugin().getLogger().info("[Bank/SafeDeposit] created by " + player.getName() + " at " + loc);
        }
        return true;
    }

    /** Внешний API: это сейф? */
    public boolean isSafe(Location location) {
        return safes.containsKey(keyLoc(location));
    }

    public String getSafeKeyHash(Location location) {
        SafeMeta m = safes.get(keyLoc(location));
        return (m == null) ? null : m.keyHash();
    }

    /** Внешний API: проверка ключа */
    public boolean hasValidKey(Player p, Location safeLoc) {
        if (p == null) return false;

        SafeMeta m = safes.get(keyLoc(safeLoc));
        if (m == null) return false;

        String expect = m.keyHash();

        var main = p.getInventory().getItemInMainHand();
        if (keyHash(main).equals(expect)) return true;

        var off = p.getInventory().getItemInOffHand();
        return keyHash(off).equals(expect);
    }

    /** Внешний API: безопасный snapshot для других апгрейдов */
    public Map<Location, String> safesSnapshot() {
        Map<Location, String> out = new java.util.HashMap<>();
        for (var e : safes.entrySet()) out.put(e.getKey(), e.getValue().keyHash());
        return java.util.Map.copyOf(out);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;

        Inventory inv = e.getInventory();

        // Самый надежный путь: location у контейнерного инвентаря
        Location loc = inv.getLocation();
        if (loc == null) return;

        loc = keyLoc(loc);

        SafeMeta meta = safes.get(loc);
        if (meta == null) return; // не сейф

        if (p.isOp()) return;

        if (!hasValidKey(p, loc)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Это сейф. Нужен правильный предмет-ключ в руке.");
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1.0f, 1.0f);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeBreak(BlockBreakEvent e) {
        Location loc = keyLoc(e.getBlock().getLocation());
        SafeMeta meta = safes.get(loc);
        if (meta == null) return;

        Player p = e.getPlayer();
        if (!p.isOp() && !hasValidKey(p, loc)) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Сейф можно сломать только с правильным предметом-ключом.");
            return;
        }

        safes.remove(loc);
        p.sendMessage(ChatColor.YELLOW + "Сейф удалён.");
        saveSafes();

        if (C().core().debug()) {
            plugin().getLogger().info("[Bank/SafeDeposit] removed by " + p.getName() + " at " + loc);
        }
    }

    private int countPlayerSafes(String playerName) {
        if (playerName == null) return 0;
        String pn = playerName.trim().toLowerCase(Locale.ROOT);

        int n = 0;
        for (SafeMeta m : safes.values()) {
            if (m == null) continue;
            String o = m.owner();
            if (o != null && o.trim().toLowerCase(Locale.ROOT).equals(pn)) n++;
        }
        return n;
    }

    private Location keyLoc(Location loc) {
        if (loc == null || loc.getWorld() == null) return loc;
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private File safesFile() {
        return new File(plugin().getDataFolder(), "bank_safes.yml");
    }

    private void loadSafes() {
        File f = safesFile();
        if (!f.exists()) return;

        try {
            YamlConfiguration yc = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection sec = yc.getConfigurationSection("safes");
            if (sec == null) return;

            int loaded = 0;

            for (String key : sec.getKeys(false)) {
                String world = sec.getString(key + ".world");
                int x = sec.getInt(key + ".x");
                int y = sec.getInt(key + ".y");
                int z = sec.getInt(key + ".z");

                String keyHash = sec.getString(key + ".keyHash");
                String owner   = sec.getString(key + ".owner"); // может быть null в старых сейвах
                String id      = sec.getString(key + ".id");    // может быть null в старых сейвах

                if (world == null || keyHash == null) continue;

                World w = Bukkit.getWorld(world);
                if (w == null) continue;

                Location loc = keyLoc(new Location(w, x, y, z));

                // миграция: если нет id — генерим
                if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
                if (owner == null) owner = ""; // старые сейфы будут "без владельца", но лимит их не засчитает никому

                safes.put(loc, new SafeMeta(owner, keyHash, id));
                loaded++;

                // синхронизируем PDC, чтобы сундук сам “помнил”, что он сейф
                Block b = loc.getBlock();
                if (b.getState() instanceof Chest chest) {
                    PersistentDataContainer pdc = chest.getPersistentDataContainer();
                    pdc.set(KEY_SAFE_KEYHASH, PersistentDataType.STRING, keyHash);
                    pdc.set(KEY_SAFE_ID, PersistentDataType.STRING, id);
                    chest.update();
                }
            }

            plugin().getLogger().info("[Bank/SafeDeposit] Loaded " + loaded + " safes");
        } catch (Exception e) {
            plugin().getLogger().severe("[Bank/SafeDeposit] Failed to load safes: " + e.getMessage());
        }
    }

    private void saveSafes() {
        File f = safesFile();
        YamlConfiguration yc = new YamlConfiguration();

        int i = 0;
        for (Map.Entry<Location, SafeMeta> e : safes.entrySet()) {
            Location loc = e.getKey();
            SafeMeta m = e.getValue();
            if (loc == null || loc.getWorld() == null || m == null) continue;

            String key = "safes.safe_" + (i++);
            yc.set(key + ".world", loc.getWorld().getName());
            yc.set(key + ".x", loc.getBlockX());
            yc.set(key + ".y", loc.getBlockY());
            yc.set(key + ".z", loc.getBlockZ());
            yc.set(key + ".keyHash", m.keyHash());
            yc.set(key + ".owner", m.owner());
            yc.set(key + ".id", m.id());
        }

        try {
            yc.save(f);
        } catch (Exception e) {
            plugin().getLogger().severe("[Bank/SafeDeposit] Failed to save safes: " + e.getMessage());
        }
    }

    private boolean isSafeInventory(Inventory inv) {
        if (inv == null) return false;

        // САМЫЙ НАДЁЖНЫЙ путь: блоковые инвентари знают свою локацию
        Location loc = inv.getLocation();
        if (loc != null && isSafe(loc)) return true;

        // Фоллбек: старый holder-based (оставим на всякий случай)
        InventoryHolder h = inv.getHolder();

        if (h instanceof Chest c) {
            return isSafe(c.getLocation());
        }

        if (h instanceof DoubleChest dc) {
            // DoubleChest#getLocation() обычно есть, но на всякий оставим половины
            Location dl = dc.getLocation();
            if (isSafe(dl)) return true;

            InventoryHolder left = dc.getLeftSide();
            InventoryHolder right = dc.getRightSide();

            if (left instanceof Chest cl && isSafe(cl.getLocation())) return true;
            return right instanceof Chest cr && isSafe(cr.getLocation());
        }

        return false;
    }

    private boolean isSafeBlock(Block b) {
        if (b == null) return false;
        if (b.getType() != Material.CHEST) return false;
        return isSafe(b.getLocation());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeAutoMove(InventoryMoveItemEvent e) {
        // Любое движение предметов ИЗ или В сейф запрещено
        if (isSafeInventory(e.getSource()) || isSafeInventory(e.getDestination())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isSafeBlock);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onSafeBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isSafeBlock);
    }

    private String keyHash(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";

        try {
            // Нормализуем amount, чтобы ключ не ломался из-за стаков
            ItemStack one = item.clone();
            one.setAmount(1);

            byte[] bytes = serializeItem(one);
            if (bytes.length == 0) return "";

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] serializeItem(ItemStack item) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(bos)) {
            oos.writeObject(item);
            oos.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

}
