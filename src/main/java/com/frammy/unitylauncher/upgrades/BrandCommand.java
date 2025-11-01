package com.frammy.unitylauncher.upgrades;

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

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BrandCommand implements TabExecutor {

    // Ключи для PDC
    private static final NamespacedKey KEY_BRAND =
            new NamespacedKey(UnityLauncher.getInstance(), "brand.made_by");
    private static final NamespacedKey KEY_TIME =
            new NamespacedKey(UnityLauncher.getInstance(), "brand.time");

    // Формат времени для второй строки
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm dd.MM.yyyy");

    // ==== Маркеры: невидимые символы, которыми помечаем «наши» строки
    private static final String MARK1 = "\u200B"; // text
    private static final String MARK2 = "\u3164"; // made
    private static final String MARK3 = "\u2064"; // time

    private static boolean hasOurMark(String s) {
        if (s == null) return false;
        return s.contains(MARK1) || s.contains(MARK2)|| s.contains(MARK3);
    }

    // Возвращаем строку бренда ДЛЯ ОТОБРАЖЕНИЯ, но с невидимым маркером в начале.
// Маркер не видно игроку, но он останется в тексте и мы потом легко найдём эти строки.
    private static String brandLine(String who) {
        return MARK2 + ChatColor.GRAY + "Сделано " + ChatColor.GOLD + who;
    }

    private static String timeLine(long ts) {
        return MARK3 + ChatColor.DARK_GRAY + FMT.format(new Date(ts));
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }
        if (!p.hasPermission("unity.brand")) {
            p.sendMessage(ChatColor.RED + "Нет прав: unity.brand");
            return true;
        }

        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            p.sendMessage(ChatColor.RED + "Возьми предмет в основную руку.");
            return true;
        }

        // Подкоманды: clear / info / set <текст...>
        if (args.length == 0) {
            // По умолчанию: "Сделано <ник>" + дата
            applyBrand(item, p.getName());
            p.sendMessage(ChatColor.GREEN + "Бренд добавлен: " + ChatColor.GOLD + p.getName());
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "clear":
            case "remove":
            case "delete":
                if (clearBrand(item)) {
                    p.sendMessage(ChatColor.YELLOW + "Бренд снят.");
                } else {
                    p.sendMessage(ChatColor.GRAY + "На предмете не было бренда.");
                }
                return true;

            case "info":
                showInfo(p, item);
                return true;

            case "set":
                if (args.length == 1) {
                    // /brand set -> как и по умолчанию
                    applyBrand(item, p.getName());
                    p.sendMessage(ChatColor.GREEN + "Бренд добавлен: " + ChatColor.GOLD + p.getName());
                } else {
                    // /brand set <свой-текст>
                    String custom = Arrays.stream(args).skip(1).collect(Collectors.joining(" "));
                    applyBrandCustom(item, custom, p.getName());
                    p.sendMessage(ChatColor.GREEN + "Бренд-метка установлена: " + ChatColor.GOLD + custom);
                }
                return true;

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
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return Stream.of("set", "clear", "info")
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ====== Логика брендинга

    private static void applyBrand(ItemStack s, String who) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        pdc.set(KEY_BRAND, PersistentDataType.STRING, who);
        pdc.set(KEY_TIME,  PersistentDataType.LONG,   now);

        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        // 1) убираем все наши прежние строки
        lore.removeIf(BrandCommand::hasOurMark);
        // 2) добавляем свежие
        lore.add(brandLine(who));
        lore.add(timeLine(now));

        meta.setLore(lore);
        s.setItemMeta(meta);
    }

    private static void applyBrandCustom(ItemStack s, String text , String who) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        long now = System.currentTimeMillis();
        pdc.set(KEY_BRAND, PersistentDataType.STRING, text);
        pdc.set(KEY_TIME,  PersistentDataType.LONG,   now);

        List<String> lore = meta.hasLore() ? new ArrayList<>(Objects.requireNonNull(meta.getLore())) : new ArrayList<>();
        lore.removeIf(BrandCommand::hasOurMark);
        lore.add(MARK1 + ChatColor.GRAY + text);
        lore.add(brandLine(who));
        lore.add(timeLine(now));

        meta.setLore(lore);
        s.setItemMeta(meta);
    }


    private static boolean clearBrand(ItemStack s) {
        ItemMeta meta = s.getItemMeta();
        if (meta == null) return false;
        boolean changed = false;

        // Чистим PDC
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (pdc.has(KEY_BRAND, PersistentDataType.STRING)) { pdc.remove(KEY_BRAND); changed = true; }
        if (pdc.has(KEY_TIME,  PersistentDataType.LONG))   { pdc.remove(KEY_TIME);  changed = true; }

        // Чистим lore
        if (meta.hasLore()) {
            List<String> lore = new ArrayList<>(Objects.requireNonNull(meta.getLore()));

            // 1) убрать все строки с нашими невидимыми маркерами
            lore.removeIf(BrandCommand::hasOurMark);

            // 2) страховка: убрать строки «Сделано ...»/«Made by ...» и наши тайм-строки,
            // даже если по какой-то причине маркера нет
            lore.removeIf(line -> {
                String plain = ChatColor.stripColor(line == null ? "" : line).trim();
                if (plain.isEmpty()) return false;
                boolean isRu = plain.startsWith("Сделано ");
                boolean isEn = plain.toLowerCase(Locale.ROOT).startsWith("made by ");
                boolean isTime = plain.matches("\\d{2}:\\d{2} \\d{2}\\.\\d{2}\\.\\d{4}");
                return isRu || isEn || isTime;
            });

            // Если пусто — делаем предмет «ванильным»
            meta.setLore(lore.isEmpty() ? null : lore);
            changed = true;
        }

        if (changed) s.setItemMeta(meta);
        return changed;
    }


// ===== хелперы =====

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

        // lore может быть null → treat as empty
        List<String> lore = meta.hasLore() ? meta.getLore() : null;
        List<String> parts = getStrings(lore);

        if (parts.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Бренд не установлен.");
            return;
        }

        // пример: "меч бога | Сделано frammy | 12:44 28.10.2025"
        p.sendMessage(String.join(ChatColor.GRAY + " | ", parts));
    }

    private static @NotNull List<String> getStrings(List<String> lore) {
        List<String> parts = new ArrayList<>();
        if (lore == null || lore.isEmpty()) {
            return parts;
        }

        for (String line : lore) {
            if (line == null) continue;

            // MARK1 = кастомный текст
            if (line.contains(MARK1)) {
                // убираем маркер и сохраняем в части
                String cleaned = line.replace(MARK1, "").trim();
                if (!cleaned.isEmpty()) {
                    parts.add(ChatColor.GRAY + cleaned);
                }
            }

            // MARK2 = "Сделано <ник>"
            if (line.contains(MARK2)) {
                String cleaned = line.replace(MARK2, "").trim();
                if (!cleaned.isEmpty()) {
                    parts.add(ChatColor.GOLD + cleaned + ChatColor.GRAY);
                }
            }

            // MARK3 = время
            if (line.contains(MARK3)) {
                String cleaned = line.replace(MARK3, "").trim();
                if (!cleaned.isEmpty()) {
                    parts.add(ChatColor.GRAY + cleaned);
                }
            }
        }

        return parts;
    }

}
