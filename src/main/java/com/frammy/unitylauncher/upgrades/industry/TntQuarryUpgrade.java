package com.frammy.unitylauncher.upgrades.industry;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * TNT Quarry — бонусный drop для руд (аналог Fortune) в зависимости от уровня апгрейда СТРАНЫ.
 * - Ванильный дроп НЕ трогаем — всё выпадает как обычно.
 * - Для поддерживаемых блоков добавляем дополнительный дроп с шансом (пер-блок, пер-предмет).
 * - Проверка апгрейда идёт по СТРАНЕ, на территории которой ПРОИЗОШЁЛ взрыв (LuckPerms Group API), оффлайн-независимо.
 * - Защищаем предметы вокруг эпицентра взрыва от разрушения на 3 сек.
 */
public class TntQuarryUpgrade implements Listener {

    private static final boolean DEBUG = false;

    private void dbg(String msg) {
        if (DEBUG) Bukkit.getLogger().info("[TntQuarry] " + msg);
    }

    private final Random random = new Random();

    /** Блоки, на которые действует «фортуна»-бонус */
    private static final Set<Material> FORTUNE_BLOCKS = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.NETHER_QUARTZ_ORE, Material.NETHER_GOLD_ORE
            // при желании добавь здесь и другие (например ANCIENT_DEBRIS)
    );

    // Шансы на доп. предмет ПО-КАЖДОМУ итему руды (уровень → шанс дублирования)
    // 1: 33%, 2: 50%, 3: 66%  (можешь настроить)
    private static final double BONUS_CHANCE_L1 = 0.33;
    private static final double BONUS_CHANCE_L2 = 0.50;
    private static final double BONUS_CHANCE_L3 = 0.66;

    @EventHandler
    public void onTntExplode(EntityExplodeEvent e) {
        Entity entity = e.getEntity();
        if (!(entity instanceof TNTPrimed)) return;

        // СТРАНА определяется строго по локации взрыва (тип зоны НЕ учитывается)
        int fortuneLevel = UpgradeCondition.getTieredGlobalUpgradeAt(
                e.getLocation(),
                "unity.tnt.quarry",
                3
        );
        if (fortuneLevel <= 0) {
            dbg("No TNT Quarry upgrade at " + e.getLocation());
            return;
        }
        dbg("TNT Quarry active L" + fortuneLevel + " at " + e.getLocation());

        // Защита уже лежащих предметов от разрушения на 3 сек.
        e.getLocation().getWorld().getNearbyEntities(e.getLocation(), 6, 6, 6,
                ent -> ent instanceof Item
        ).forEach(ent -> {
            Item item = (Item) ent;
            item.setInvulnerable(true);
            if (DEBUG) dbg("Protect item " + item.getItemStack().getType());
            Bukkit.getScheduler().runTaskLater(UnityLauncher.getInstance(), () -> {
                if (!item.isDead()) item.setInvulnerable(false);
            }, 60L);
        });

        // Ванильный дроп оставляем — ничего не отключаем
        // Бонус: для каждой РУДЫ — шанс дублирования каждого выпавшего предмета
        double bonusChance = switch (fortuneLevel) {
            case 3 -> BONUS_CHANCE_L3;
            case 2 -> BONUS_CHANCE_L2;
            default -> BONUS_CHANCE_L1;
        };

        // Обрабатываем КАЖДЫЙ блок отдельно, но не мешаем vanilla
        // Бонусный спавн делаем в следующий тик, чтобы ваниль успел отработать
        var blocks = List.copyOf(e.blockList());
        Bukkit.getScheduler().runTask(UnityLauncher.getInstance(), () -> {
            for (Block b : blocks) {
                Material type = b.getType();
                if (!FORTUNE_BLOCKS.contains(type)) continue;

                // получаем "ванильный" потенциальный дроп (как от кирки), чтобы понять, что дублировать
                Collection<ItemStack> vanillaLike = b.getDrops(new ItemStack(Material.DIAMOND_PICKAXE));
                if (vanillaLike.isEmpty()) continue;

                for (ItemStack base : vanillaLike) {
                    // Разыгрываем шанс дубля КАЖДЫЙ раз (по стеку)
                    if (random.nextDouble() < bonusChance) {
                        ItemStack extra = base.clone();
                        // Можно усилить: +1..(1+fortuneLevel) вместо всегда +1
                        // extra.setAmount(base.getAmount() * (1 + random.nextInt(fortuneLevel)));
                        // сейчас: просто +1 стек к тому, что обычно выпадает
                        // (для мелких дропов это и выглядит "как фортуна")
                        b.getWorld().dropItemNaturally(
                                b.getLocation().add(0.5, 0.5, 0.5),
                                extra
                        );
                        if (DEBUG) dbg("Bonus +" + extra.getAmount() + " " + extra.getType() + " for " + type);
                    }
                }
            }
        });
    }
}
