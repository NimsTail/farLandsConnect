package com.frammy.unitylauncher.signs.features.shop;

import org.bukkit.Location;

import java.util.Locale;
import java.util.Objects;

public record ItemData(
        Location chestLocation,
        String materialKey,
        int dealQuantity,
        int totalQuantity,
        double dealPrice
) {
    public ItemData {
        Objects.requireNonNull(chestLocation, "chestLocation");
        Objects.requireNonNull(materialKey, "materialKey");

        materialKey = materialKey.trim();
        if (materialKey.isEmpty()) throw new IllegalArgumentException("materialKey is blank");

        if (dealQuantity <= 0) throw new IllegalArgumentException("dealQuantity must be > 0");
        if (totalQuantity < 0) throw new IllegalArgumentException("totalQuantity must be >= 0");
        if (dealPrice < 0) throw new IllegalArgumentException("dealPrice must be >= 0");
    }

    public boolean available() {
        return totalQuantity >= dealQuantity && dealPrice > 0;
    }

    public String displayName() {
        String[] parts = materialKey.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(p.charAt(0)).append(p.substring(1).toLowerCase(Locale.ROOT)).append(' ');
        }
        return sb.toString().trim();
    }
}
