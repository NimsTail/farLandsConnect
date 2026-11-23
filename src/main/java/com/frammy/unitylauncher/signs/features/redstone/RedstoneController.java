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
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Powerable;
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
 * мощностью {@value #PULSE_LEVEL}.
 *
 * v2 (по фидбеку в issue #2, второй раунд): первая версия сама СТАВИЛА
 * REDSTONE_WIRE-блок над опорой и держала его силу вручную. Это оказалось
 * неудачной идеей по двум причинам:
 * 1) баг — {@code b.getBlockData()} для REDSTONE_WIRE это {@link AnaloguePowerable}
 *    (getPower/setPower), а не {@link org.bukkit.block.data.Levelled}, которым
 *    код пытался его кастовать — каст всегда падал в null-ветку, applyForced
 *    тихо не делал вообще ничего, деньги снимались, сигнал никогда не появлялся.
 * 2) эксплойт — блок ставился в обход обычной валидации размещения (в том
 *    числе туда, куда игрок вручную поставить не смог бы), а "переставляется
 *    автоматически, если игрок его сломал" на практике означало бесконечный
 *    фарм редстоуновой пыли (сломал → плагин тут же восстановил на следующем
 *    клике/рестарте → сломал снова).
 *
 * Теперь ничего не ставится вообще: при клике ищется уже существующий
 * REDSTONE_WIRE, рычаг или повторитель среди 6 соседей — то, что игрок
 * построил сам как часть своей схемы, — и его сила/состояние форсируется на
 * время импульса. Если рядом ничего такого нет, деньги не снимаются вовсе
 * (проверка идёт ДО списания).
 *
 * v3 (issue #2, третий раунд): три отдельных бага/пробела —
 * 1) провод не гас обратно — "выключение" просто снимало запись из карты и
 *    звало BlockState.update(), которое ничего не пересчитывает само по
 *    себе, просто пересохраняет уже стоящее значение (PULSE_LEVEL). Теперь
 *    выключение форсирует 0 тем же способом, каким включение форсирует
 *    PULSE_LEVEL.
 * 2) искали цель только вокруг ОПОРНОГО блока — для настенной таблички это
 *    обычно там, где игрок и строит схему, но для напольной игрок скорее
 *    строит рядом с самой табличкой (на её высоте), не блоком под ней.
 *    Теперь проверяются соседи и таблички, и опоры.
 * 3) добавлена поддержка повторителя (Repeater — тоже Powerable, включается
 *    точно так же, как рычаг).
 */
public final class RedstoneController {

    private final UnityLauncher plugin;
    private final SignStore store;

    /** Локация REDSTONE_WIRE, чью силу мы сейчас удерживаем -> нужное значение. Только для проводов, которые СУЩЕСТВОВАЛИ сами по себе — мы их не создаём. */
    private final Map<Location, Integer> forcedPower = new ConcurrentHashMap<>();

    private static final int PULSE_LEVEL = 2; // как попросили в issue — фиксированные 2 единицы
    private static final long PULSE_DURATION_TICKS = 20L; // 1с — не было указано явно, разумный дефолт для "кнопки"
    private static final long RETRIGGER_COOLDOWN_MS = 1000L; // анти-спам по двойным кликам на одной табличке

    private final Map<Location, Long> lastTriggeredAt = new ConcurrentHashMap<>();

    private static final Pattern DOUBLE_ANY = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    private static final BlockFace[] ADJACENT = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

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

        if (findTarget(e.getBlock(), support.getBlock()) == null) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Рядом с табличкой или её опорным блоком нет редстоун-провода/рычага/повторителя "
                    + ChatColor.GRAY + "— сначала построй свою схему вплотную, потом ставь табличку.");
            return;
        }

        // GH #2: "ЛКМ - активировать" removed per the user's own call once
        // the sign actually worked — self-explanatory, didn't need spelling
        // out on the sign itself.
        String title = "Redstone";
        String line1 = "Цена: " + ChatColor.GOLD + round2(price);
        e.setLine(0, title);
        e.setLine(1, line1);
        e.setLine(2, "");
        e.setLine(3, "");

        store.put(loc, new SignVariables(
                p.getName(),
                null,
                List.of(title, line1, "", ""),
                List.of(0),
                false,
                false,
                SignCategory.REDSTONE,
                SignState.SHOP_DEFINED,
                null
        ));

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

        Location support = SignManager.getSupportBlockOfSign(signLoc.getBlock());
        Target target = findTarget(signLoc.getBlock(), support != null ? support.getBlock() : null);
        if (target == null) {
            p.sendMessage(ChatColor.RED + "Рядом с табличкой больше нет провода/рычага/повторителя — деньги не списаны.");
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
                pulse(target);
            });
        });
    }

    private void pulse(Target target) {
        switch (target.type()) {
            case WIRE -> {
                forcedPower.put(target.loc(), PULSE_LEVEL);
                applyForcedWire(target.loc());
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    // Explicitly force it back to 0 — the previous version just
                    // removed the map entry and called BlockState.update(), which
                    // re-saves whatever level was ALREADY on the block (still
                    // PULSE_LEVEL, nothing had actually changed it) instead of
                    // recomputing anything. Nothing then prompts a fresh
                    // BlockRedstoneEvent on its own, so the wire visibly never
                    // turned back off. Setting power to 0 the same way we set it
                    // to PULSE_LEVEL is what actually turns it off.
                    forcedPower.put(target.loc(), 0);
                    applyForcedWire(target.loc());
                    forcedPower.remove(target.loc());
                }, PULSE_DURATION_TICKS);
            }
            case POWERABLE -> {
                setPowered(target.loc(), true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> setPowered(target.loc(), false), PULSE_DURATION_TICKS);
            }
        }
    }

    private void applyForcedWire(Location wireLoc) {
        Block b = wireLoc.getBlock();
        if (b.getType() != Material.REDSTONE_WIRE) return; // провод сломан/убран за это время — не лезем

        BlockData bd = b.getBlockData();
        if (!(bd instanceof AnaloguePowerable ap)) return;

        ap.setPower(forcedPower.getOrDefault(wireLoc, 0));
        b.setBlockData(ap, true); // true = physics update, тем самым триггерит BlockRedstoneEvent у соседей
    }

    /** Рычаг или повторитель — оба Powerable, оба включаются/выключаются одинаково через setPowered. */
    private void setPowered(Location loc, boolean powered) {
        Block b = loc.getBlock();
        if (!(b.getBlockData() instanceof Powerable pw)) return; // сломан/убран за это время
        pw.setPowered(powered);
        b.setBlockData(pw, true);
    }

    private enum TargetType { WIRE, POWERABLE }

    private record Target(Location loc, TargetType type) {}

    /**
     * Первый существующий REDSTONE_WIRE, рычаг или повторитель среди 6
     * соседей опорного блока таблички — ничего не создаёт. Также проверяет
     * соседей самой таблички (не только опоры): для настенной таблички
     * игрок обычно строит схему у опорного блока, но для напольной —
     * скорее рядом с самой табличкой (на той же высоте), не блоком под ней.
     */
    private Target findTarget(Block signBlock, Block support) {
        Target fromSign = signBlock != null ? findTargetAround(signBlock) : null;
        if (fromSign != null) return fromSign;
        return support != null ? findTargetAround(support) : null;
    }

    private Target findTargetAround(Block center) {
        for (BlockFace face : ADJACENT) {
            Block b = center.getRelative(face);
            if (b.getType() == Material.REDSTONE_WIRE) {
                return new Target(SignStore.keyLoc(b.getLocation()), TargetType.WIRE);
            }
            if ((b.getType() == Material.LEVER || b.getType() == Material.REPEATER) && b.getBlockData() instanceof Powerable) {
                return new Target(SignStore.keyLoc(b.getLocation()), TargetType.POWERABLE);
            }
        }
        return null;
    }

    /** Табличка сломана/убрана — принудительное удержание провода (если было активно) снимаем. */
    public void onSignRemoved(Location signLoc) {
        lastTriggeredAt.remove(signLoc);
        // Сам провод/рычаг — не наш блок, мы его не создавали и не удаляем при сносе таблички.
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
