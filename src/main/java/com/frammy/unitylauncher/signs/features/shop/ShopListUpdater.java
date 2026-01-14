package com.frammy.unitylauncher.signs.features.shop;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignState;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.render.SignRenderer;
import com.frammy.unitylauncher.signs.storage.SignStore;
import com.frammy.unitylauncher.zones.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;

import java.util.*;
import java.util.function.Function;

public record ShopListUpdater(UnityLauncher plugin, ZoneManager zoneManager, SignStore store, SignRenderer renderer,
                              Function<Location, String> shopKeyFn) {

    public void updateAllRelatedShopListSigns(Location anyLocationInsideShop) {
        anyLocationInsideShop = SignStore.keyLoc(anyLocationInsideShop);

        String key = shopKeyFn.apply(anyLocationInsideShop);
        if (key == null) return;

        // Берём list/source из store по одному ключу магазина (без BlueMap)
        List<Location> shopListSigns = store.signs().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().getSignCategory() == SignCategory.SHOP_LIST)
                .map(Map.Entry::getKey)
                .filter(loc -> key.equals(shopKeyFn.apply(loc)))
                .toList();

        List<Location> sourceSignLocations = store.signs().entrySet().stream()
                .filter(e -> {
                    SignVariables v = e.getValue();
                    return v != null
                            && v.getSignCategory() == SignCategory.SHOP_SOURCE
                            && v.getSignState() == SignState.SHOP_DEFINED;
                })
                .map(Map.Entry::getKey)
                .filter(loc -> key.equals(shopKeyFn.apply(loc)))
                .toList();

        if (shopListSigns.isEmpty()) return;

        // Можно использовать твой уже готовый метод обновления по key
        updateShopListsByKey(key, new HashSet<>(shopListSigns), new HashSet<>(sourceSignLocations));
    }

    public void rebuildAllListsLater() {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Set<String> rebuilt = new HashSet<>();

            for (Map.Entry<Location, SignVariables> e : store.signs().entrySet()) {
                SignVariables v = e.getValue();
                if (v == null) continue;
                if (v.getSignCategory() != SignCategory.SHOP_LIST) continue;

                String key = shopKeyFn.apply(e.getKey());
                if (key == null) continue;
                if (!rebuilt.add(key)) continue; // уже пересобрали этот магазин

                try {
                    updateAllRelatedShopListSigns(e.getKey());
                } catch (Throwable ignored) {}
            }
        }, 20L * 5L);
    }

    public void updateShopListsByKey(String shopKey,
                                     Set<Location> listSigns,
                                     Set<Location> sourceSigns) {
        if (shopKey == null || listSigns == null || listSigns.isEmpty()) return;

        List<ItemData> allItems = new ArrayList<>();

        for (Location srcLoc : sourceSigns) {
            srcLoc = SignStore.keyLoc(srcLoc);

            SignVariables sv = store.get(srcLoc);
            if (sv == null) continue;
            if (sv.getSignCategory() != SignCategory.SHOP_SOURCE) continue;
            if (sv.getSignState() != SignState.SHOP_DEFINED) continue;

            List<String> t = sv.getSignText();
            if (t == null || t.size() < 4) continue;

            // --- 1) сундук из строки координат ---
            Location chestLoc = parseChestLocation(srcLoc, t.get(1));
            if (chestLoc == null) continue;

            // --- 2) qty/price из строк 2-3 ---
            Integer dealQty = parsePositiveInt(t.get(2));
            Double dealPrice = parsePositiveDouble(t.get(3));
            if (dealQty == null || dealQty <= 0) continue;
            if (dealPrice == null || dealPrice <= 0) continue;

            // --- 3) материал и наличие в сундуке ---
            var st = chestLoc.getBlock().getState();
            if (!(st instanceof org.bukkit.block.Container c)) continue;

            var inv = c.getInventory();
            org.bukkit.Material mat = null;
            int total = 0;

            for (var it : inv.getContents()) {
                if (it == null || it.getType().isAir()) continue;
                if (mat == null) mat = it.getType();
                if (it.getType() == mat) total += it.getAmount();
            }
            if (mat == null) continue;

            allItems.add(new ItemData(
                    SignStore.keyLoc(chestLoc),
                    mat.name(),
                    dealQty,
                    total,
                    dealPrice
            ));
        }

        // линии для таблички списка
        List<String> itemLines = allItems.stream().map(ItemData::displayName).toList();

        for (Location signLoc : listSigns) {
            signLoc = SignStore.keyLoc(signLoc);
            store.signPages().put(signLoc, itemLines);
            store.signItemData().put(signLoc, allItems);

            Block block = signLoc.getBlock();
            if (block.getState() instanceof Sign sign) {
                renderer.updateSignView(sign, itemLines, 0);
            }
        }
    }

    private static Location parseChestLocation(Location srcLoc, String raw) {
        if (srcLoc == null || srcLoc.getWorld() == null || raw == null) return null;
        String s = org.bukkit.ChatColor.stripColor(raw).trim();
        String[] p = s.split("\\s+");
        if (p.length < 3) return null;
        try {
            int x = Integer.parseInt(p[0]);
            int y = Integer.parseInt(p[1]);
            int z = Integer.parseInt(p[2]);
            return SignStore.keyLoc(new Location(srcLoc.getWorld(), x, y, z));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // такие же позитивные парсеры, как в ShopController (можно вынести в util)
    private static Integer parsePositiveInt(String s) {
        if (s == null) return null;
        s = org.bukkit.ChatColor.stripColor(s);
        var m = java.util.regex.Pattern.compile("-?\\d+").matcher(s);
        if (!m.find()) return null;
        try { return Math.abs(Integer.parseInt(m.group())); } catch (NumberFormatException e) { return null; }
    }

    private static Double parsePositiveDouble(String s) {
        if (s == null) return null;
        s = org.bukkit.ChatColor.stripColor(s);
        var m = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(s);
        if (!m.find()) return null;
        try { return Math.abs(Double.parseDouble(m.group().replace(',', '.'))); }
        catch (NumberFormatException e) { return null; }
    }

}
