package com.frammy.unitylauncher.upgrades.core;

import org.bukkit.event.Listener;
import org.jetbrains.annotations.Nullable;

public interface Upgrade {

    UpgradeKey key();

    UpgradeScope scope();

    /** Если апгрейд выключен конфигом — менеджер его не запускает */
    default boolean enabledByConfig(UpgradeContext ctx) { return true; }

    /** Тут апгрейд стартует: регает таски/кеши/инициализацию */
    void enable(UpgradeContext ctx);

    /** Тут апгрейд должен всё прибрать (cancel tasks, clear caches) */
    void disable();

    /** Если апгрейд хочет слушать события — вернёт listener, иначе null */
    default @Nullable Listener listener() { return null; }
}
