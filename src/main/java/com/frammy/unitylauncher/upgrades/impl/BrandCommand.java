package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.NonNull;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * /brand [set <текст...> | clear | info]
 * <p>
 * Новая реальность:
 * - Пермишены берём из plugin.yml (unity.brand.1 + unity.brand.override)
 * - Глобально можно выключить через upgrades core.enabled (если UpgradesManager доступен)
 */
public record BrandCommand(UnityLauncher plugin) implements TabExecutor {

    private static final String PERM_USE = "unity.brand.1";
    private static final String PERM_OVERRIDE = "unity.brand.override";

    // PDC keys (инициализируем один раз от plugin, т.к. NamespacedKey требует instance)
    private static NamespacedKey KEY_BRAND;
    private static NamespacedKey KEY_TIME;
    private static NamespacedKey KEY_OWNER;

    // Формат времени (команды выполняются в main-thread, так что SimpleDateFormat ок)
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm dd.MM.yyyy");

    // Невидимые маркеры, которыми помечаем «наши» строки в lore
    private static final String MARK1 = "\u200B "; // custom text
    private static final String MARK2 = "\u3164 "; // "Сделано <ник>"
    private static final String MARK3 = "\u2064 "; // time

    // Лимиты, чтобы люди не делали lore на 300 строк
    private static final int MAX_CUSTOM_LEN = 64;

    public BrandCommand(UnityLauncher plugin) {
        this.plugin = plugin;
        initKeys(plugin);
    }

    private static void initKeys(UnityLauncher plugin) {
        if (KEY_BRAND != null) return;
        synchronized (BrandCommand.class) {
            if (KEY_BRAND != null) return;
            KEY_BRAND = new NamespacedKey(plugin, "brand.made_by");
            KEY_TIME = new NamespacedKey(plugin, "brand.time");
            KEY_OWNER = new NamespacedKey(plugin, "brand.owner");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String @NonNull [] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }

        // Глобальный выключатель через upgrades core.enabled (если UpgradesManager есть)
        try {
            var um = plugin.getUpgradesManager();
            if (um != null && um.config() != null && um.config().core() != null) {
                if (!um.config().core().enabled()) {
                    p.sendMessage(ChatColor.RED + "Система отключена администратором (core.enabled=false).");
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        if (!p.hasPermission(PERM_USE)) {
            p.sendMessage(ChatColor.RED + "Нет прав: " + ChatColor.YELLOW + PERM_USE);
            return true;
        }

        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            p.sendMessage(ChatColor.RED + "Возьми предмет в основную руку.");
            return true;
        }

        // /brand -> info (по умолчанию)
        if (args.length == 0) {
            showInfo(p, item);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "clear":
            case "remove":
            case "delete": {
                boolean adminBypass = p.isOp() || p.hasPermission(PERM_OVERRIDE);
                ClearResult res = clearBrand(p, item, adminBypass);

                switch (res) {
                    case CLEARED -> p.sendMessage(ChatColor.YELLOW + "Бренд снят.");
                    case ADMIN_CLEARED -> p.sendMessage(ChatColor.YELLOW + "Бренд снят (админ-override).");
                    case NO_BRAND -> p.sendMessage(ChatColor.GRAY + "На предмете не было бренда.");
                    case NOT_OWNER -> {
                        String owner = getBrandOwnerName(item);
                        if (owner == null) owner = "неизвестно";
                        p.sendMessage(ChatColor.RED + "Нельзя снять чужой бренд. Владелец: " + ChatColor.GOLD + owner);
                    }
                }
                return true;
            }

            case "info":
                showInfo(p, item);
                return true;

            case "set": {
                boolean adminBypass = p.isOp() || p.hasPermission(PERM_OVERRIDE);

                // запрет перезаписи чужого бренда
                if (!canModifyBrand(p, item, adminBypass)) {
                    String owner = getBrandOwnerName(item);
                    if (owner == null) owner = "неизвестно";
                    p.sendMessage(ChatColor.RED + "Нельзя перезаписать чужой бренд. Владелец: " + ChatColor.GOLD + owner);
                    p.sendMessage(ChatColor.GRAY + "Сначала сними бренд: " + ChatColor.WHITE + "/brand clear");
                    return true;
                }

                if (args.length == 1) {
                    applyBrand(item, p.getName());
                    p.sendMessage(ChatColor.GREEN + "Бренд добавлен: " + ChatColor.GOLD + p.getName());
                    return true;
                }

                String custom = Arrays.stream(args).skip(1).collect(Collectors.joining(" ")).trim();
                custom = sanitizeCustom(custom);

                if (custom.isEmpty()) {
                    applyBrand(item, p.getName());
                    p.sendMessage(ChatColor.GREEN + "Бренд добавлен: " + ChatColor.GOLD + p.getName());
                    return true;
                }

                applyBrandCustom(item, custom, p.getName());
                p.sendMessage(ChatColor.GREEN + "Бренд-метка установлена: " + ChatColor.GOLD + custom);
                return true;
            }

            default:
                p.sendMessage(ChatColor.GRAY + "Использование: "
                        + ChatColor.WHITE + "/" + label + ChatColor.GRAY + " | "
                        + ChatColor.WHITE + "/" + label + " set <текст>" + ChatColor.GRAY + " | "
                        + ChatColor.WHITE + "/" + label + " clear" + ChatColor.GRAY + " | "
                        + ChatColor.WHITE + "/" + label + " info");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String @NonNull [] args) {
        if (args.length == 1) {
            String pref = args[0].toLowerCase(Locale.ROOT);
            return Stream.of("set", "clear", "info")
                    .filter(s -> s.startsWith(pref))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ===== Branding logic

    private static boolean hasOurMark(String s) {
        if (s == null) return false;
        return s.contains(MARK1) || s.contains(MARK2) || s.contains(MARK3);
    }

    private static String brandLine(String who) {
        return MARK2 + ChatColor.GRAY + "Сделано " + ChatColor.GOLD + who;
    }

    private static String timeLine(long ts) {
        return MARK3 + ChatColor.DARK_GRAY + FMT.format(new Date(ts));
    }

    private static void applyBrand(ItemStack s, String who) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return;

        long now = System.currentTimeMillis();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_BRAND, PersistentDataType.STRING, who);
        pdc.set(KEY_TIME, PersistentDataType.LONG, now);
        pdc.set(KEY_OWNER, PersistentDataType.STRING, who);

        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        lore.removeIf(BrandCommand::hasOurMark);
        lore.add(brandLine(who));
        lore.add(timeLine(now));

        meta.setLore(lore);
        s.setItemMeta(meta);
    }

    private static void applyBrandCustom(ItemStack s, String text, String who) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return;

        long now = System.currentTimeMillis();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_BRAND, PersistentDataType.STRING, text); // кастом-текст
        pdc.set(KEY_TIME, PersistentDataType.LONG, now);
        pdc.set(KEY_OWNER, PersistentDataType.STRING, who);  // владелец

        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        lore.removeIf(BrandCommand::hasOurMark);
        lore.add(MARK1 + ChatColor.GRAY + text);
        lore.add(brandLine(who));
        lore.add(timeLine(now));

        meta.setLore(lore);
        s.setItemMeta(meta);
    }

    private enum ClearResult {CLEARED, ADMIN_CLEARED, NO_BRAND, NOT_OWNER}

    private static boolean canModifyBrand(Player actor, ItemStack s, boolean adminBypass) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return true; // нечего модифицировать

        String owner = getBrandOwner(meta);
        if (owner == null || owner.isBlank()) return true; // бренда нет/непонятно чей (старьё) — разрешаем set
        if (owner.equalsIgnoreCase(actor.getName())) return true; // свой — разрешаем
        return adminBypass; // чужой — только с override/op
    }

    /**
     * Удалить бренд, только если владелец совпадает с игроком, либо adminBypass=true.
     */
    private static ClearResult clearBrand(Player actor, ItemStack s, boolean adminBypass) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return ClearResult.NO_BRAND;

        String owner = getBrandOwner(meta);

        if (owner == null && !adminBypass) return ClearResult.NOT_OWNER;
        if (owner != null && !owner.equalsIgnoreCase(actor.getName()) && !adminBypass) return ClearResult.NOT_OWNER;

        boolean hadAny = false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(KEY_BRAND, PersistentDataType.STRING)) {
            pdc.remove(KEY_BRAND);
            hadAny = true;
        }
        if (pdc.has(KEY_TIME, PersistentDataType.LONG)) {
            pdc.remove(KEY_TIME);
            hadAny = true;
        }
        if (pdc.has(KEY_OWNER, PersistentDataType.STRING)) {
            pdc.remove(KEY_OWNER);
            hadAny = true;
        }

        if (meta.hasLore()) {
            List<String> lore = new ArrayList<>(Objects.requireNonNull(meta.getLore()));
            lore.removeIf(BrandCommand::hasOurMark);

            // страховка для старых предметов
            lore.removeIf(line -> {
                String plain = ChatColor.stripColor(line == null ? "" : line).trim();
                if (plain.isEmpty()) return false;
                boolean isRu = plain.startsWith("Сделано ");
                boolean isEn = plain.toLowerCase(Locale.ROOT).startsWith("made by ");
                boolean isTime = plain.matches("\\d{2}:\\d{2} \\d{2}\\.\\d{2}\\.\\d{4}");
                return isRu || isEn || isTime;
            });

            meta.setLore(lore.isEmpty() ? null : lore);
            hadAny = true;
        }

        if (!hadAny) return ClearResult.NO_BRAND;

        s.setItemMeta(meta);

        return (adminBypass && (owner == null || !owner.equalsIgnoreCase(actor.getName())))
                ? ClearResult.ADMIN_CLEARED
                : ClearResult.CLEARED;
    }

    /**
     * Имя владельца из PDC или из lore; null — если определить нельзя.
     */
    private static String getBrandOwner(ItemMeta meta) {
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        // 1) Современный путь — KEY_OWNER
        String owner = pdc.get(KEY_OWNER, PersistentDataType.STRING);
        if (owner != null && !owner.isBlank()) return owner;

        // 2) Обратная совместимость: вытащим из lore строку "Сделано <ник>"
        if (meta.hasLore()) {
            String fromLore = parseOwnerFromLore(meta.getLore());
            if (fromLore != null && !fromLore.isBlank()) return fromLore;
        }

        // 3) Очень старые предметы: иногда KEY_BRAND хранил ник
        String brand = pdc.get(KEY_BRAND, PersistentDataType.STRING);
        if (brand != null && !brand.isBlank()) {
            if (!containsMark(meta.getLore(), MARK1)) return brand;
        }

        return null;
    }

    private static String getBrandOwnerName(ItemStack s) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return null;
        return getBrandOwner(meta);
    }

    private static boolean containsMark(List<String> lore, String mark) {
        if (lore == null) return false;
        for (String line : lore) {
            if (line != null && line.contains(mark)) return true;
        }
        return false;
    }

    /**
     * Извлекаем ник из строки с MARK2: "…Сделано <ник>" или "Made by <nick>".
     */
    private static String parseOwnerFromLore(List<String> lore) {
        if (lore == null) return null;

        for (String line : lore) {
            if (line == null) continue;
            if (!line.contains(MARK2)) continue;

            String cleaned = ChatColor.stripColor(line.replace(MARK2, "")).trim();

            if (cleaned.startsWith("Сделано ")) {
                String after = cleaned.substring("Сделано ".length()).trim();
                if (!after.isEmpty()) return after;
            }

            String low = cleaned.toLowerCase(Locale.ROOT);
            if (low.startsWith("made by ")) {
                String after = cleaned.substring(8).trim();
                if (!after.isEmpty()) return after;
            }
        }

        return null;
    }

    private static void showInfo(Player p, ItemStack s) {
        if (s == null || s.getType().isAir()) {
            p.sendMessage(ChatColor.YELLOW + "В руке нет предмета.");
            return;
        }

        ItemMeta meta = s.getItemMeta();
        if (meta == null) {
            p.sendMessage(ChatColor.YELLOW + "Нет метаданных предмета.");
            return;
        }

        List<String> lore = meta.hasLore() ? meta.getLore() : null;
        List<String> parts = getStrings(lore);

        String owner = getBrandOwner(meta);
        if (owner != null && !owner.isBlank()) {
            parts.addFirst(ChatColor.GRAY + "Владелец: " + ChatColor.GOLD + owner);
        }

        if (parts.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Бренд не установлен.");
            return;
        }

        p.sendMessage(String.join(ChatColor.GRAY + " | ", parts));
    }

    private static @NotNull List<String> getStrings(List<String> lore) {
        List<String> parts = new ArrayList<>();
        if (lore == null || lore.isEmpty()) return parts;

        for (String line : lore) {
            if (line == null) continue;

            // MARK1 = кастомный текст
            if (line.contains(MARK1)) {
                String cleaned = line.replace(MARK1, "").trim();
                cleaned = ChatColor.stripColor(cleaned);
                if (!cleaned.isEmpty()) parts.add(ChatColor.GRAY + cleaned);
            }

            // MARK2 = "Сделано <ник>"
            if (line.contains(MARK2)) {
                String cleaned = line.replace(MARK2, "").trim();
                cleaned = ChatColor.stripColor(cleaned);
                if (!cleaned.isEmpty()) parts.add(ChatColor.GOLD + cleaned + ChatColor.GRAY);
            }

            // MARK3 = время
            if (line.contains(MARK3)) {
                String cleaned = line.replace(MARK3, "").trim();
                cleaned = ChatColor.stripColor(cleaned);
                if (!cleaned.isEmpty()) parts.add(ChatColor.GRAY + cleaned);
            }
        }

        return parts;
    }

    private static String sanitizeCustom(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // запрещаем переносы строк/табуляции
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        while (s.contains("  ")) s = s.replace("  ", " ");

        // режем по длине (по цветам тоже не даём разрастись)
        s = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s));
        if (s.length() > MAX_CUSTOM_LEN) s = s.substring(0, MAX_CUSTOM_LEN).trim();

        return s;
    }
}
