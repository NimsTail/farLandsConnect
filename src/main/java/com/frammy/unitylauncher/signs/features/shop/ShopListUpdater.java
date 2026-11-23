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
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

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

        List<ItemData> allItems = computeItemsForSourceSigns(sourceSigns);

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

    /**
     * Собственно расчёт ItemData по набору SHOP_SOURCE-табличек одного
     * магазина — вынесено из updateShopListsByKey, чтобы GH#34's
     * "shop_inventory_sync" (см. ZoneRequestPoller/SignManager) мог
     * переиспользовать РОВНО тот же расчёт, что уже строит таблички
     * SHOP_LIST, вместо второго отдельного сканирования тех же сундуков.
     */
    public List<ItemData> computeItemsForSourceSigns(Set<Location> sourceSigns) {
        List<ItemData> allItems = new ArrayList<>();
        if (sourceSigns == null) return allItems;

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

            // --- 3) материалы и наличие каждого в сундуке ---
            // "Кол-во:/Цена:" на табличке — единая ставка за единицу для
            // ВСЕГО сундука (см. AutoDebitService, class javadoc), не
            // привязка к одному материалу — так что несколько разных
            // товаров в одном сундуке это нормально: каждый попадает в
            // список отдельной позицией по одной и той же ставке. Раньше
            // тут брался только "первый попавшийся" материал, и любой
            // другой тип в том же сундуке был не в списке и без цены — то
            // есть фактически бесплатным.
            var st = chestLoc.getBlock().getState();
            if (!(st instanceof org.bukkit.block.Container c)) continue;

            // GH#35 (зачарования + шкала прочности, 2026-08-28) — раньше
            // группировали чисто по Material, из-за чего два зачарованных
            // варианта одного материала (или зачарованный + обычный)
            // схлопывались в одну позицию с потерянными зачарованиями.
            // Теперь ключ — материал + сигнатура зачарований (пустая для
            // незачарованного), прочность не часть ключа (непрерывное
            // значение), а агрегат — средний % по всем стакам варианта.
            Map<VariantKey, VariantAgg> totalsByVariant = new LinkedHashMap<>();
            for (var it : c.getInventory().getContents()) {
                if (it == null || it.getType().isAir()) continue;

                List<String> slugs = enchantSlugsOf(it);
                String sig = String.join(",", slugs);
                VariantKey key = new VariantKey(it.getType(), sig);
                VariantAgg agg = totalsByVariant.computeIfAbsent(key, k -> new VariantAgg(slugs));
                agg.count += it.getAmount();

                Double dur = durabilityPctOf(it);
                if (dur != null) {
                    agg.durabilitySum += dur * it.getAmount();
                    agg.durabilityCount += it.getAmount();
                }
            }

            for (var entry : totalsByVariant.entrySet()) {
                VariantAgg agg = entry.getValue();
                Double avgDurability = agg.durabilityCount > 0 ? agg.durabilitySum / agg.durabilityCount : null;
                allItems.add(new ItemData(
                        SignStore.keyLoc(chestLoc),
                        entry.getKey().material().name(),
                        dealQty,
                        agg.count,
                        dealPrice,
                        agg.enchantSlugs,
                        avgDurability
                ));
            }
        }

        return allItems;
    }

    private record VariantKey(Material material, String enchantSig) {}

    private static final class VariantAgg {
        final List<String> enchantSlugs;
        int count;
        double durabilitySum;
        int durabilityCount;

        VariantAgg(List<String> enchantSlugs) {
            this.enchantSlugs = enchantSlugs;
        }
    }

    /**
     * Slug'и зачарований предмета в формате сайтового каталога Enchantment
     * ("unbreaking_3" — Bukkit-ключ + уровень, "mending" — без уровня для
     * зачарований с maxLevel=1, см. backend/prisma/seed-data/enchantments.json).
     * Зачарованные книги хранят чары отдельно (EnchantmentStorageMeta), а не
     * в обычной ItemStack.getEnchantments() — оба случая нужны, продают и то,
     * и другое. Отсортировано — сигнатура не зависит от порядка перечисления.
     */
    private static List<String> enchantSlugsOf(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        Map<Enchantment, Integer> map = (meta instanceof EnchantmentStorageMeta esm)
                ? esm.getStoredEnchants()
                : stack.getEnchantments();
        if (map.isEmpty()) return List.of();

        List<String> slugs = new ArrayList<>(map.size());
        for (var e : map.entrySet()) {
            String base = e.getKey().getKey().getKey(); // уже snake_case, совпадает с id в каталоге
            slugs.add(e.getKey().getMaxLevel() <= 1 ? base : base + "_" + e.getValue());
        }
        Collections.sort(slugs);
        return slugs;
    }

    /**
     * % оставшейся прочности (100 = новый, 0 = вот-вот сломается), null —
     * материал без прочности вовсе (блоки, еда и т.п.) — отличать от "100%
     * у ровно нового предмета" важно, иначе шкала на сайте рисовалась бы
     * там, где ей вообще не место (см. Listing.durabilityPct в схеме сайта).
     */
    private static Double durabilityPctOf(ItemStack stack) {
        short maxDurability = stack.getType().getMaxDurability();
        if (maxDurability <= 0) return null;

        ItemMeta meta = stack.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return null;

        double pct = 100.0 * (maxDurability - dmg.getDamage()) / maxDurability;
        return Math.max(0, Math.min(100, pct));
    }

    /**
     * GH#35 (мгновенный синк, 2026-08-28) — то же самое, что делает
     * updateAllRelatedShopListSigns внутри, но БЕЗ требования на существующую
     * SHOP_LIST-табличку (тот метод молча ничего не делает, если её нет —
     * see раннего return выше) и БЕЗ побочного эффекта записи в игровые
     * таблички — просто расчёт данных, для пуша на сайт
     * (ShopController.onInventoryClose -> FarLandsApiClient.pushShopInventory).
     */
    public List<ItemData> computeItemsForShop(Location anyLocationInsideShop) {
        anyLocationInsideShop = SignStore.keyLoc(anyLocationInsideShop);
        String key = shopKeyFn.apply(anyLocationInsideShop);
        if (key == null) return List.of();

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

        return computeItemsForSourceSigns(new HashSet<>(sourceSignLocations));
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
