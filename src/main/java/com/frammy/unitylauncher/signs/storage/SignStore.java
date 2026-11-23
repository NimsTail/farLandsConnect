package com.frammy.unitylauncher.signs.storage;

import com.frammy.unitylauncher.signs.features.shop.ItemData;
import com.frammy.unitylauncher.signs.SignCategory;
import com.frammy.unitylauncher.signs.SignVariables;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Container;
import org.bukkit.block.Chest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.*;

public final class SignStore {

    private final Map<Location, String[]> originalSignTexts = new HashMap<>();
    private final Map<Location, SignVariables> signsByLoc = new HashMap<>();

    // scroll/render state
    private final Map<Location, List<String>> signPages = new HashMap<>();
    private final Map<Location, List<ItemData>> signItemData = new HashMap<>();


    // shop: container -> source sign (O(1))
    private final Map<Location, Location> containerToSourceSign = new HashMap<>();

    // shop: source sign -> bound containers (для корректного rebinding / double chest)
    private final Map<Location, Set<Location>> sourceSignToContainers = new HashMap<>();

    public static Location keyLoc(Location loc) {
        if (loc == null || loc.getWorld() == null) return loc;
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public Map<Location, String[]> originalSignTexts() { return originalSignTexts; }
    public Map<Location, SignVariables> signs() { return signsByLoc; }
    public Map<Location, List<String>> signPages() { return signPages; }
    public Map<Location, List<ItemData>> signItemData() { return signItemData; }
    public Map<Location, Location> containerToSourceSign() { return containerToSourceSign; }
    public Map<Location, Set<Location>> sourceSignToContainers() { return sourceSignToContainers; }

    public SignVariables get(Location loc) { return signsByLoc.get(keyLoc(loc)); }
    public void put(Location loc, SignVariables vars) { signsByLoc.put(keyLoc(loc), vars); }
    public SignVariables remove(Location loc) {
        Location k = keyLoc(loc);
        signPages.remove(k);
        signItemData.remove(k);
        originalSignTexts.remove(k);
        return signsByLoc.remove(k);
    }

    public static String locKey(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public void bindContainer(Location containerLoc, Location signLoc) {
        containerLoc = keyLoc(containerLoc);
        signLoc = keyLoc(signLoc);

        containerToSourceSign.put(containerLoc, signLoc);
        sourceSignToContainers.computeIfAbsent(signLoc, k -> new HashSet<>()).add(containerLoc);
    }

    public void unbindContainer(Location containerLoc) {
        containerLoc = keyLoc(containerLoc);

        Location signLoc = containerToSourceSign.remove(containerLoc);
        if (signLoc == null) return;

        Set<Location> set = sourceSignToContainers.get(signLoc);
        if (set != null) {
            set.remove(containerLoc);
            if (set.isEmpty()) sourceSignToContainers.remove(signLoc);
        }
    }

    public void unbindAllForSourceSign(Location signLoc) {
        signLoc = keyLoc(signLoc);

        Set<Location> set = sourceSignToContainers.remove(signLoc);
        if (set == null || set.isEmpty()) return;

        for (Location c : set) {
            containerToSourceSign.remove(c);
        }
    }

    public Location sourceSignByContainer(Location containerLoc) {
        return containerToSourceSign.get(keyLoc(containerLoc));
    }

    public boolean isShopProtectedInventory(Inventory inv) {
        if (inv == null) return false;
        InventoryHolder h = inv.getHolder();

        if (h instanceof Container c) {
            return containerToSourceSign.containsKey(keyLoc(c.getLocation()));
        }

        if (h instanceof DoubleChest dc) {
            Chest left = (dc.getLeftSide() instanceof Chest cl) ? cl : null;
            Chest right = (dc.getRightSide() instanceof Chest cr) ? cr : null;

            if (left != null && containerToSourceSign.containsKey(keyLoc(left.getLocation()))) return true;
            return right != null && containerToSourceSign.containsKey(keyLoc(right.getLocation()));
        }

        return false;
    }

    public int countByCountry(SignCategory category, String countryCanonical, java.util.function.Function<SignVariables, String> signCountryCanonical) {
        if (countryCanonical == null || countryCanonical.isBlank()) return 0;
        int n = 0;
        for (SignVariables sv : signsByLoc.values()) {
            if (sv == null) continue;
            if (sv.getSignCategory() != category) continue;
            String pc = signCountryCanonical.apply(sv);
            if (countryCanonical.equals(pc)) n++;
        }
        return n;
    }

    public Location parseContainerLocation(SignVariables vars, World world) {
        if (vars == null || world == null) return null;
        if (vars.getSignText() == null || vars.getSignText().size() < 2) return null;

        String[] coords = vars.getSignText().get(1).split(" ");
        if (coords.length != 3) return null;

        try {
            int x = Integer.parseInt(coords[0]);
            int y = Integer.parseInt(coords[1]);
            int z = Integer.parseInt(coords[2]);
            return keyLoc(new Location(world, x, y, z));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void rebuildShopContainerBinds() {
        // 1) полностью сбрасываем старые бинды (они runtime-only и после рестарта могут стать мусором)
        containerToSourceSign.clear();
        sourceSignToContainers.clear();

        // 2) пересобираем бинды только из сохранённых SHOP_SOURCE табличек
        for (Map.Entry<Location, SignVariables> e : signsByLoc.entrySet()) {
            Location signLoc = keyLoc(e.getKey());
            SignVariables sv = e.getValue();
            if (signLoc == null || sv == null) continue;

            if (sv.getSignCategory() != SignCategory.SHOP_SOURCE) continue;

            World w = signLoc.getWorld();
            if (w == null) continue;

            Location containerLoc = parseContainerLocation(sv, w);
            if (containerLoc == null) continue;

            // 3) если контейнер уже забинден на другую табличку — решаем конфликт.
            //    Политика: "последняя запись побеждает" (стабильно и просто).
            Location old = containerToSourceSign.get(containerLoc);
            if (old != null && !old.equals(signLoc)) {
                // отцепим контейнер от старой таблички, чтобы не осталось хвостов в sourceSignToContainers
                unbindContainer(containerLoc);
            }

            bindContainer(containerLoc, signLoc);
        }

        // 4) доп. чистка: если вдруг signLoc больше не существует в signsByLoc — выбросить (на всякий случай)
        containerToSourceSign.values().removeIf(src -> src == null || !signsByLoc.containsKey(keyLoc(src)));
        for (Iterator<Map.Entry<Location, Set<Location>>> it = sourceSignToContainers.entrySet().iterator(); it.hasNext();) {
            var en = it.next();
            Location src = keyLoc(en.getKey());
            if (src == null || !signsByLoc.containsKey(src)) {
                it.remove();
                continue;
            }
            Set<Location> set = en.getValue();
            if (set == null) { it.remove(); continue; }
            set.removeIf(Objects::isNull);
            if (set.isEmpty()) it.remove();
        }
    }

    public void pruneMissingWorlds() {
        signsByLoc.keySet().removeIf(l -> l == null || l.getWorld() == null);
        originalSignTexts.keySet().removeIf(l -> l == null || l.getWorld() == null);
        signPages.keySet().removeIf(l -> l == null || l.getWorld() == null);
        signItemData.keySet().removeIf(l -> l == null || l.getWorld() == null);

        containerToSourceSign.keySet().removeIf(l -> l == null || l.getWorld() == null);
        containerToSourceSign.values().removeIf(l -> l == null || l.getWorld() == null);

        sourceSignToContainers.keySet().removeIf(l -> l == null || l.getWorld() == null);
        for (Iterator<Map.Entry<Location, Set<Location>>> it = sourceSignToContainers.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            Set<Location> set = e.getValue();
            if (set == null) { it.remove(); continue; }
            set.removeIf(l -> l == null || l.getWorld() == null);
            if (set.isEmpty()) it.remove();
        }
    }

}
