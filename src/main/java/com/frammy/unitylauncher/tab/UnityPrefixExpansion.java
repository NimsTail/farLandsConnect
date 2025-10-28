package com.frammy.unitylauncher.tab;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.zones.countryrelations.CountryRegistryJdbc;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class UnityPrefixExpansion extends PlaceholderExpansion {

    private final UnityLauncher plugin;

    public UnityPrefixExpansion(UnityLauncher plugin, CountryRegistryJdbc countries) {
        this.plugin = plugin;
    }

    @Override public @NotNull String getIdentifier() { return "unity"; }
    @Override public @NotNull String getAuthor() { return "UnityLauncher"; }
    @Override public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer p, @NotNull String params) {
        if (p == null) {
            return "";
        } else {
            p.getUniqueId();
        }

        // берём пару [prefix, suffix] из твоего кэша
        String[] ps = plugin.computeTabPrefixFromCache(p.getUniqueId());

        switch (params.toLowerCase()) {
            case "prefix": {
                String raw = safe(ps, 0);
                if (raw.isEmpty()) return "";
                return toMiniMessage(raw) + " "; // пробел после префикса
            }
            case "suffix": {
                String raw = safe(ps, 1);
                if (raw.isEmpty()) return "";
                return " " + toMiniMessage(raw); // пробел перед суффиксом
            }
            default:
                return "";
        }
    }

    private static String safe(String[] arr, int i) {
        if (arr == null || i < 0 || i >= arr.length) return "";
        String s = arr[i];
        return (s == null || s.isBlank()) ? "" : s;
    }

    /** "&aТекст"/"§aТекст" -> MiniMessage ("<green>Текст") */
    private static String toMiniMessage(String legacy) {
        String amp = legacy.replace('§', '&');
        Component comp = LegacyComponentSerializer.legacyAmpersand().deserialize(amp);
        return MiniMessage.miniMessage().serialize(comp);
    }
}
