package com.frammy.unitylauncher.upgrades;

import org.bukkit.entity.Player;

/** Базовый интерфейс апгрейда. */
public interface Upgrade {
    String getKey();          // ключ = permission LuckPerms (например unity.redstone.basic)
    String getDescription();  // описание
    void apply(Player player);// применение эффекта (кратковременный эффект или сообщение)
}