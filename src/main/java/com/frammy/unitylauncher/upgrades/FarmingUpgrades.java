package com.frammy.unitylauncher.upgrades;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Сельхоз/производственные апгрейды (привязка к ванили).
 * Долгоживущая логика реализуется в твоих Listener'ах:
 *  - FurnaceBurnEvent / FurnaceSmeltEvent
 *  - BrewEvent
 *  - BlockGrowEvent / BlockFertilizeEvent
 *  - EntityBreedEvent
 *  - PlayerInteractEvent (farmland)
 */
public final class FarmingUpgrades {

    /** "Готовка/топливо": Bamboo как топливо горит дольше (x1.5) в зоне. */
    public static class CookingBambooFuel implements Upgrade {
        @Override public String getKey() { return "unity.cookingBambooFuel"; }
        @Override public String getDescription() { return "Бамбук как топливо эффективнее (x1.5) в зоне"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Аграрный] Бамбук горит дольше в печах твоей страны!");
            // Реал: FurnaceBurnEvent — увеличить burnTime, если fuel == BAMBOO и печь в зоне страны
        }
    }

    /** Варка зелий быстрее: -25% времени. */
    public static class FasterBrewing implements Upgrade {
        @Override public String getKey() { return "unity.potions"; }
        @Override public String getDescription() { return "Варка зелий идёт быстрее (~-25% времени)"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Аграрный] Зельеварение ускорено в твоей стране!");
            // Реал: перехват BrewEvent / внутреннего тика стойки и сокращение таймера
        }
    }

    /** Шанс сохранить ингредиент при варке (например, адский нарост/пыль светокамня). */
    public static class BrewingIngredientSave implements Upgrade {
        @Override public String getKey() { return "unity.farming.brewing_save"; }
        @Override public String getDescription() { return "Варка: шанс не потратить один ингредиент"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Аграрный] Иногда ингредиент при варке не расходуется!");
            // Реал: BrewEvent — по шансу возвращаем/не списываем соответствующий ингредиент
        }
    }

    /** Теплица: рост при пониженном свете под стеклянной крышей. */
    public static class GreenhouseLowLight implements Upgrade {
        @Override public String getKey() { return "unity.farming.greenhouse"; }
        @Override public String getDescription() { return "Теплица: рост культур при низком свете под стеклянной крышей"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Аграрный] Теплица заработала: посевы растут и при слабом свете!");
            // Реал: BlockGrowEvent — если над грядкой сплошная 'стеклянная крыша', разрешать/форсить рост
        }
    }

    /** Размножение животных: -20..30% к кулдауну в зоне. */
    public static class BreedingCooldown implements Upgrade {
        @Override public String getKey() { return "unity.farming.breeding"; }
        @Override public String getDescription() { return "Животные размножаются чаще (понижен кулдаун)"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Аграрный] Скот в твоей зоне размножается чаще!");
            // Реал: EntityBreedEvent / метаданные сущностей с меньшим cooldown
        }
    }

    /** Пчёлы рядом с грядкой: шанс ускоренного роста. */
    public static class BeehiveGrowth implements Upgrade {
        @Override public String getKey() { return "unity.farming.bee_growth"; }
        @Override public String getDescription() { return "Если рядом улей — выше шанс роста культур"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Аграрный] Пчёлы помогают: посевы растут быстрее!");
            // Реал: BlockGrowEvent — проверка ближайшего Beehive/Bee nest в радиусе
        }
    }

    /** Игроки не мнут farmland прыжками. */
    public static class NoFarmlandTrample implements Upgrade {
        @Override public String getKey() { return "unity.farming.no_trample"; }
        @Override public String getDescription() { return "Ферма защищена: прыжки не мнут грядки"; }
        @Override public void apply(Player p) {
            // Небольшой визуальный индикатор, чтобы игрок понял что апгрейд активен
            p.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, 20 * 3, 0, true, false));
            p.sendMessage("§a[Аграрный] Грядки больше не мнутся от прыжков!");
            // Реал: отменять trample в соответствующем событии (изменение блока farmland -> dirt)
        }
    }
}
