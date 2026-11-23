package com.frammy.unitylauncher.signs.features.shop;

import org.bukkit.Location;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record ItemData(
        Location chestLocation,
        String materialKey,
        int dealQuantity,
        int totalQuantity,
        double dealPrice,
        // GH#35 (зачарования на авто-лотах, 2026-08-28) — slug'и в формате
        // сайтового каталога Enchantment ("unbreaking_3"), уже посчитанные
        // при сборке (см. ShopListUpdater.computeItemsForSourceSigns) —
        // отсортированы, пустой список для незачарованного варианта.
        // Никак не используется в самой ItemData (displayName/available
        // её игнорируют) — просто провозится до JSON-сериализации на сайт.
        List<String> enchantments,
        // Средний % оставшейся прочности по всем стакам этого варианта
        // (100 = новый, 0 = вот-вот сломается), null — материал без
        // прочности (блоки, еда и т.п.).
        Double durabilityPct
) {
    public ItemData {
        Objects.requireNonNull(chestLocation, "chestLocation");
        Objects.requireNonNull(materialKey, "materialKey");

        materialKey = materialKey.trim();
        if (materialKey.isEmpty()) throw new IllegalArgumentException("materialKey is blank");

        if (dealQuantity <= 0) throw new IllegalArgumentException("dealQuantity must be > 0");
        if (totalQuantity < 0) throw new IllegalArgumentException("totalQuantity must be >= 0");
        if (dealPrice < 0) throw new IllegalArgumentException("dealPrice must be >= 0");

        if (enchantments == null) enchantments = List.of();
        if (durabilityPct != null && (durabilityPct < 0 || durabilityPct > 100)) {
            throw new IllegalArgumentException("durabilityPct must be within 0..100");
        }
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
