package com.frammy.unitylauncher.upgrades;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;


/**
 * Индустриальные апгрейды из твоей схемы (GraphML).
 * Здесь даны ключи, которые ты добавишь в Countries. Upgrades.
 * Внутри только безопасная логика (сообщения/краткие эффекты),
 * а "долгоживущая" логика реализуется в слушателях событий плагина.
 */

public final class IndustryUpgrades {

    /** Разблокирует базовый редстоун (рычаги/кнопки/поршни). */
    public static class RedstoneBasic implements Upgrade {
        @Override public String getKey() { return "unity.redstone.basic"; }
        @Override public String getDescription() { return """
                Базовый редстоун разблокирован:
                REDSTONE,
                REDSTONE_TORCH,
                REDSTONE_BLOCK,
                LEVER,
                NOTE_BLOCK,
                PISTON,
                STICKY_PISTON,
                DISPENSER,
                DROPPER)"""; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Апгрейд] Доступен базовый редстоун!");
        }
    }

    /** Разблокирует продвинутый редстоун (воронки/компараторы). */
    public static class RedstoneAdvanced implements Upgrade {
        @Override public String getKey() { return "unity.redstone.advanced"; }
        @Override public String getDescription() { return """
                Продвинутый редстоун разблокирован:
                REPEATER,
                COMPARATOR,
                OBSERVER,
                DAYLIGHT_DETECTOR,
                SCULK_SENSOR,
                CALIBRATED_SCULK_SENSOR,
                HOPPER,
                HOPPER_MINECART,
                POWERED_RAIL,
                DETECTOR_RAIL,
                ACTIVATOR_RAIL,
                TRIPWIRE_HOOK,
                TRIPWIRE"""; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Апгрейд] Доступен продвинутый редстоун!");
        }
    }

    //++
    /** Haste I по всей зоне (мы даём короткий эффект-индикатор игроку, постоянство — в зонах). */
    public static class HasteZone implements Upgrade {
        @Override public String getKey() { return "unity.zone.haste.basic"; }
        @Override public String getDescription() { return "Спешка I в пределах зоны государства"; }
        @Override public void apply(Player p) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 20 * 20, 0, true, false));
            p.sendMessage("§a[Апгрейд] В зоне страны действует Спешка I!");
        }
    }

    //++
    /** Haste II по всей зоне (мы даём короткий эффект-индикатор игроку, постоянство — в зонах). */
    public static class HasteZone2 implements Upgrade {
        @Override public String getKey() { return "unity.zone.haste.advanced"; }
        @Override public String getDescription() { return "Спешка II в пределах зоны государства"; }
        @Override public void apply(Player p) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 20 * 20, 1, true, false));
            p.sendMessage("§a[Апгрейд] В зоне страны действует Спешка I!");
        }
    }

    //++
    /** +15% к выплавке руд (шанс дополнительного слитка) — реализуется в FurnaceSmeltEvent. */
    public static class FurnaceOreBoost implements Upgrade {
        @Override public String getKey() { return "unity.furnace.ore_boost"; }
        @Override public String getDescription() { return "+15% шанс доп. выхода при плавке руд"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Апгрейд] Плавка руд улучшена (+15%)!");
        }
    }

    public static class TntQuarry1 implements Upgrade {
        @Override public String getKey() { return "unity.tnt.quarry.1"; }
        @Override public String getDescription() { return "TNT работает как Fortune I (x2)"; }
        @Override public void apply(Player p) { p.sendMessage("§a[Апгрейд] ТНТ не взрывает дроп и взрывается с Фортуной 1!"); }
    }

    public static class TntQuarry2 implements Upgrade {
        @Override public String getKey() { return "unity.tnt.quarry.2"; }
        @Override public String getDescription() { return "TNT работает как Fortune II (x3)"; }
        @Override public void apply(Player p) { p.sendMessage("§a[Апгрейд] ТНТ не взрывает дроп и взрывается с Фортуной 2!"); }
    }

    public static class TntQuarry3 implements Upgrade {
        @Override public String getKey() { return "unity.tnt.quarry.3"; }
        @Override public String getDescription() { return "TNT работает как Fortune III (x4)"; }
        @Override public void apply(Player p) { p.sendMessage("§a[Апгрейд] ТНТ не взрывает дроп и взрывается с Фортуной 3!"); }
    }


    /** Smart Hoppers I */
    public static class SmartHoppers1 implements Upgrade {
        @Override public String getKey() { return "unity.hopper.smart.1"; }
        @Override public String getDescription() { return "Воронки работают как в ваниле (8 тиков)."; }
        @Override public void apply(Player p) { p.sendMessage("§a[Апгрейд] Воронки ускорены до ванильной скорости!"); }
    }

    /** Smart Hoppers II */
    public static class SmartHoppers2 implements Upgrade {
        @Override public String getKey() { return "unity.hopper.smart.2"; }
        @Override public String getDescription() { return "Воронки переносят сразу целый стак каждые 8 тиков."; }
        @Override public void apply(Player p) { p.sendMessage("§a[Апгрейд] Воронки теперь перемещают целые стаки!"); }
    }

    /** Быстрая загрузка/разгрузка вагонеток-сундуков на «спец-блоке» (например, медный блок). */
    public static class FastMinecartIO implements Upgrade {
        @Override public String getKey() { return "unity.minecart.fast_io"; }
        @Override public String getDescription() { return "Быстрая разгрузка/загрузка вагонеток на спец-блоке"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Апгрейд] Вагонетки разгружаются быстрее на медных блоках!");
        }
    }

    /** Брендинг предметов «Made in <Country>» — ItemCraft/Move/Click (доп. lore). */
    public static class ItemBranding implements Upgrade {
        @Override public String getKey() { return "unity.item.branding"; }
        @Override public String getDescription() { return "Предметы получают метку происхождения (lore)"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Апгрейд] Товары из твоей зоны получают фирменную метку!");
        }
    }

    /** Печи возле лавы/магмы работают быстрее на 10%. */
    public static class LavaBoostedFurnaces implements Upgrade {
        @Override public String getKey() { return "unity.furnace.lava_boost"; }
        @Override public String getDescription() { return "Печи у лавы/магмы работают быстрее на 10%"; }
        @Override public void apply(Player p) {
            p.sendMessage("§a[Апгрейд] Печи рядом с лавой ускорены!");
        }
    }
}

