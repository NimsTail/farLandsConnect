package com.frammy.unitylauncher.upgrades.core;

import org.jetbrains.annotations.NotNull;

public record UpgradeKey(String id) {
    public UpgradeKey {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("UpgradeKey id is blank");
    }

    @Override public @NotNull String toString() { return id; }

    public static UpgradeKey of(String id) { return new UpgradeKey(id); }
}
