package com.frammy.unitylauncher.signs.features.redstone;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignManager;
import com.frammy.unitylauncher.signs.SignState;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.storage.SignStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "REDSTONE" таблички — GitHub issue #2 (minecraftServer repo): клик снимает
 * заданную сумму денег с игрока и на короткое время выдаёт редстоун-сигнал
 * мощностью {@value #PULSE_LEVEL} с блока-опоры таблички.
 *
 * Как это физически устроено (и почему): Bukkit не даёт произвольному блоку
 * (камню, дереву и т.д.) просто "стать" источником питания — сила редстоуна
 * у обычных блоков не хранится, она всегда ВЫЧИСЛЯЕТСЯ движком из соседей. Мы
 * ставим настоящий REDSTONE_WIRE-блок сверху опоры (единственный ванильный
 * блок, чью мощность можно явно задать через {@link Levelled}) и держим его
 * силу вручную: сразу после клика — выставляем уровень через BlockData, а
 * дальше на КАЖДЫЙ следующий пересчёт этого блока (BlockRedstoneEvent —
 * фактически на каждое соседнее обновление) принудительно возвращаем нужное
 * значение через {@link BlockRedstoneEvent#setNewCurrent(int)}, иначе ванильная
 * физика тут же пересчитает провод обратно по своим соседям (то есть в 0) и
 * наш "источник" погаснет сам по себе на следующем тике.
 */
public final class RedstoneController {

    private final UnityLauncher plugin;
    private final SignStore store;

    /** Локация REDSTONE_WIRE-эмиттера -> сила, которую туда принудительно возвращаем при пересчёте. */
    private final Map<Location, Integer> forcedPower = new ConcurrentHashMap<>();

    private static final int PULSE_LEVEL = 2; // как попросили в issue — фиксированные 2 единицы
    private static final long PULSE_DURATION_TICKS = 20L; // 1с — не было указано явно, разумный дефолт для "кнопки"
    private static final long RETRIGGER_COOLDOWN_MS = 1000L; // анти-спам по двойным кликам на одной табличке

    private final Map<Location, Long> lastTriggeredAt = new ConcurrentHashMap<>();

    private static final Pattern DOUBLE_ANY = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    public RedstoneController(UnityLauncher plugin, SignStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    // ===== создание =====

    public void onSignCreateRedstone(SignChangeEvent e) {
        Player p = e.getPlayer();
        Location loc = SignStore.keyLoc(e.getBlock().getLocation());

        Double price = parsePositiveDouble(e.getLine(1));
        if (price == null) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Вторая строка должна быть положительной ценой активации (например: 50).");
            return;
        }

        if (!(e.getBlock().getState() instanceof Sign sign)) {
            e.setCancelled(true);
            return;
        }

        Location support = SignManager.getSupportBlockOfSign(e.getBlock());
        if (support == null || support.getWorld() == null) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Не удалось определить опорный блок таблички.");
            return;
        }

        Block emitterBlock = support.getBlock().getRelative(org.bukkit.block.BlockFace.UP);
        if (!emitterBlock.getType().isAir()) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Над опорным блоком таблички должно быть свободное место "
                    + ChatColor.GRAY + "(туда ставится редстоун-эмиттер).");
            return;
        }

        String title = "Redstone";
        String line1 = "Цена: " + ChatColor.GOLD + round2(price);
        e.setLine(0, title);
        e.setLine(1, line1);
        e.setLine(2, "ЛКМ -");
        e.setLine(3, "активировать");

        store.put(loc, new SignVariables(
                p.getName(),
                null,
                List.of(title, line1, "ЛКМ -", "активировать"),
                List.of(0),
                false,
                false,
                SignCategory.REDSTONE,
                SignState.SHOP_DEFINED,
                null
        ));

        claimEmitter(emitterBlock.getLocation());

        p.sendMessage(ChatColor.GREEN + "Редстоун-табличка установлена. "
                + ChatColor.GRAY + "Цена активации: " + round2(price) + " Ⓕ, сигнал силой " + PULSE_LEVEL
                + " на " + (PULSE_DURATION_TICKS / 20) + "с.");
    }

    // ===== клик =====

    public void onInteract(PlayerInteractEvent e, SignVariables sv, Location signLoc) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK) return;
        e.setCancelled(true);
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        Player p = e.getPlayer();

        Long last = lastTriggeredAt.get(signLoc);
        long now = System.currentTimeMillis();
        if (last != null && (now - last) < RETRIGGER_COOLDOWN_MS) return;

        Double price = priceOf(sv);
        if (price == null) {
            p.sendMessage(ChatColor.RED + "У этой таблички не задана цена.");
            return;
        }

        lastTriggeredAt.put(signLoc, now);

        plugin.moneyManager.tryWithdraw(p.getName(), price, ok -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!ok) {
                    p.sendMessage(ChatColor.RED + "Недостаточно средств. Нужно: " + ChatColor.YELLOW + round2(price) + " Ⓕ.");
                    return;
                }
                p.sendMessage(ChatColor.GREEN + "Списано: " + ChatColor.YELLOW + round2(price)
                        + ChatColor.GREEN + " Ⓕ. Сигнал активирован на " + (PULSE_DURATION_TICKS / 20) + "с.");
                pulse(signLoc);
            });
        });
    }

    private void pulse(Location signLoc) {
        Location emitter = emitterFor(signLoc);
        if (emitter == null) return;

        // На случай если эмиттер потерялся (перезагрузка/ручная застройка) — переставим.
        if (!(emitter.getBlock().getType() == Material.REDSTONE_WIRE)) {
            claimEmitter(emitter);
        }

        forcedPower.put(emitter, PULSE_LEVEL);
        applyForced(emitter);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            forcedPower.put(emitter, 0);
            applyForced(emitter);
        }, PULSE_DURATION_TICKS);
    }

    private void applyForced(Location emitterLoc) {
        Block b = emitterLoc.getBlock();
        if (b.getType() != Material.REDSTONE_WIRE) return; // кто-то снёс наш блок вручную — не лезем

        BlockData bd = b.getBlockData();
        if (!(bd instanceof Levelled lv)) return;

        int level = forcedPower.getOrDefault(emitterLoc, 0);
        lv.setLevel(level);
        b.setBlockData(lv, true); // true = physics update, тем самым триггерит BlockRedstoneEvent у соседей
    }

    /** Ставит REDSTONE_WIRE-эмиттер и регистрирует его как "наш" (принудительно удерживаемый). */
    private void claimEmitter(Location loc) {
        Block b = loc.getBlock();
        if (b.getType() != Material.REDSTONE_WIRE) {
            if (!b.getType().isAir()) {
                // Место заняли (постройка игрока, пока сервер был выключен,
                // и т.п.) — не сносим чужое, просто не регистрируем эмиттер.
                // Активация этой таблички не сработает, пока место не
                // освободится (следующий reclaimAllOnStartup её подхватит).
                return;
            }
            b.setType(Material.REDSTONE_WIRE, false);
        }
        forcedPower.put(loc, 0);
        applyForced(loc);
    }

    private void releaseEmitter(Location loc) {
        forcedPower.remove(loc);
        lastTriggeredAt.remove(loc);
        Block b = loc.getBlock();
        if (b.getType() == Material.REDSTONE_WIRE) {
            b.setType(Material.AIR, true);
        }
    }

    private Location emitterFor(Location signLoc) {
        Block signBlock = signLoc.getBlock();
        Location support = SignManager.getSupportBlockOfSign(signBlock);
        if (support == null || support.getWorld() == null) return null;
        return SignStore.keyLoc(support.getBlock().getRelative(org.bukkit.block.BlockFace.UP).getLocation());
    }

    /** Табличка сломана/убрана — освобождаем и убираем свой эмиттер. */
    public void onSignRemoved(Location signLoc) {
        Location emitter = emitterFor(signLoc);
        if (emitter != null) releaseEmitter(emitter);
    }

    /** Восстановление рантайм-состояния после старта сервера — для всех уже сохранённых REDSTONE-табличек. */
    public void reclaimAllOnStartup() {
        for (var entry : store.signs().entrySet()) {
            SignVariables sv = entry.getValue();
            if (sv == null || sv.getSignCategory() != SignCategory.REDSTONE) continue;
            Location emitter = emitterFor(entry.getKey());
            if (emitter != null) claimEmitter(emitter);
        }
    }

    // ===== редстоун-физика =====

    public void onBlockRedstone(BlockRedstoneEvent e) {
        Location loc = SignStore.keyLoc(e.getBlock().getLocation());
        Integer forced = forcedPower.get(loc);
        if (forced != null) e.setNewCurrent(forced);
    }

    // ===== utils =====

    private Double priceOf(SignVariables sv) {
        if (sv == null || sv.getSignText() == null || sv.getSignText().size() < 2) return null;
        return parsePositiveDouble(sv.getSignText().get(1));
    }

    private static Double parsePositiveDouble(String s) {
        if (s == null) return null;
        s = ChatColor.stripColor(s);
        Matcher m = DOUBLE_ANY.matcher(s);
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group().replace(',', '.'));
            return v > 0 ? v : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
