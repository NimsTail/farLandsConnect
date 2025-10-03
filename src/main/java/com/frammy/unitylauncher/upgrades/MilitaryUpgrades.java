package com.frammy.unitylauncher.upgrades;

import org.bukkit.entity.Player;

/**
 * Военно-спец апгрейды/доступы к предметам/механикам.
 * Долгоживущая логика — в соответствующих Listener'ах:
 *  - использование маяков/элитр/трезубцев/скалка/фаер-чарджа
 */
public final class MilitaryUpgrades {

    /** Доступ к маяку (разрешение ставить/активировать) */
    public static class BeaconAccess implements Upgrade {
        @Override public String getKey() { return "unity.beacon"; }
        @Override public String getDescription() { return "Разрешено ставить и настраивать маяки в зоне"; }
        @Override public void apply(Player p) {
            p.sendMessage("§b[Военный] Маяки разрешены для твоей страны!");
            // Реал: BlockPlace/Interact — разрешать/обрабатывать beacon в зоне государства
        }
    }

    /** Тотем бессмертия — разрешён к использованию */
    public static class TotemOfUndying implements Upgrade {
        @Override public String getKey() { return "unity.totem_of_undying"; }
        @Override public String getDescription() { return "Тотем бессмертия разрешён к использованию"; }
        @Override public void apply(Player p) {
            p.sendMessage("§b[Военный] Тотем бессмертия доступен!");
            // Реал: EntityResurrectEvent — не блокировать срабатывание тотема у жителей страны
        }
    }

    /** Элитры — разрешено использовать (можно с анти-абуз правилами) */
    public static class ElytraAccess implements Upgrade {
        @Override public String getKey() { return "unity.elytra"; }
        @Override public String getDescription() { return "Элитры разрешены к использованию"; }
        @Override public void apply(Player p) {
            p.sendMessage("§b[Военный] Элитры разрешены в твоей стране!");
            // Реал: EntityToggleGlideEvent/Move — не запрещать планирование в зоне
        }
    }

    /** Трезубец — разрешено использовать/бросать/чинить. */
    public static class TridentAccess implements Upgrade {
        @Override public String getKey() { return "unity.trident"; }
        @Override public String getDescription() { return "Трезубцы разрешены к использованию"; }
        @Override public void apply(Player p) {
            p.sendMessage("§b[Военный] Трезубцы разрешены!");
            // Реал: ProjectileLaunchEvent/Interact — не блокировать использование трезубца
        }
    }

    /** Скалк/датчики — разрешено использовать. */
    public static class SculkAccess implements Upgrade {
        @Override public String getKey() { return "unity.sculk"; }
        @Override public String getDescription() { return "Использование скалка/сенсоров разрешено"; }
        @Override public void apply(Player p) {
            p.sendMessage("§b[Военный] Скалк-технологии разрешены!");
            // Реал: BlockPlace/Interact — не блокировать скалк/скалк-сенсоры в зоне
        }
    }

    /** Fire Charge — разрешено поджигать/использовать. */
    public static class FireChargeAccess implements Upgrade {
        @Override public String getKey() { return "unity.fire_charge"; }
        @Override public String getDescription() { return "Огненные заряды разрешены к использованию"; }
        @Override public void apply(Player p) {
            p.sendMessage("§b[Военный] Огненные заряды разрешены!");
            // Реал: PlayerInteractEvent/ProjectileLaunchEvent — не блокировать использование fire charge
        }
    }
}
