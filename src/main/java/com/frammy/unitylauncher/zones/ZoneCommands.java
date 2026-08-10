package com.frammy.unitylauncher.zones;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class ZoneCommands {

    private final ZoneManager zones;

    public ZoneCommands(ZoneManager zones) {
        this.zones = zones;
    }

    public void handle(Player p, String[] args) {
        if (args == null || args.length < 1) {
            p.sendMessage(ChatColor.RED + "Использование: /ul zone <addcorner|removecorner|build|update|price|remove|confirmremove|cancelremove|addmember|removemember|transferowner>");
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {

            case "addcorner" -> {
                if (args.length != 2) {
                    p.sendMessage(ChatColor.RED + "Использование: /ul zone addcorner <zoneType>");
                    return;
                }

                ZoneType requested;
                try {
                    requested = ZoneType.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    p.sendMessage(ChatColor.RED + "Неизвестный тип зоны: " + ChatColor.YELLOW + args[1]);
                    p.sendMessage(ChatColor.GRAY + "Доступные типы: " + ChatColor.WHITE + Arrays.toString(ZoneType.values()));
                    return;
                }

                zones.addCornerCmd(p, requested);
            }

            case "removecorner" -> zones.removeCornerCmd(p);

            case "build" -> {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "Использование: /ul zone build <zoneType> [zoneName]");
                    return;
                }

                ZoneType requested;
                try {
                    requested = ZoneType.valueOf(args[1].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    p.sendMessage(ChatColor.RED + "Неизвестный тип зоны.");
                    return;
                }

                UUID id = p.getUniqueId();
                ZoneType pending = zones.getPendingZoneType(id);
                if (pending != null && pending != requested) {
                    p.sendMessage(ChatColor.RED + "Вы начали строить зону типа "
                            + ChatColor.GOLD + pending + ChatColor.RED +
                            ". Используйте тот же тип в /ul zone build или начните новую зону.");
                    return;
                }

                ZoneType t = (pending != null) ? pending : requested;

                boolean builtOk;
                if (t == ZoneType.COUNTRY) {
                    builtOk = zones.buildZoneCountryCmd(p);
                } else {
                    if (args.length < 3) {
                        p.sendMessage(ChatColor.RED + "Использование: /ul zone build <zoneType> <zoneName>");
                        return;
                    }
                    builtOk = zones.buildZoneCmd(p, t, args[2]);
                }

                if (builtOk) {
                    zones.clearPendingBuildState(id);
                }
            }

            case "update" -> {
                if (args.length < 2) {
                    p.sendMessage(ChatColor.RED + "Использование: /ul zone update <corners|name|color> <значение>");
                    return;
                }
                zones.updateZone(p, args[1].toLowerCase(Locale.ROOT), args.length > 2 ? args[2] : "");
            }

            case "price" -> zones.showPriceCmd(p);

            case "remove" -> zones.removeZoneCmd(p);

            case "confirmremove" -> zones.confirmRemoveZone(p);

            case "cancelremove" -> zones.cancelRemoveZone(p);

            case "addmember" -> {
                if (args.length != 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone addmember <ник>"); return; }
                zones.addMemberCmd(p, args[1]);
            }

            case "removemember" -> {
                if (args.length != 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone removemember <ник>"); return; }
                zones.removeMemberCmd(p, args[1]);
            }

            case "transferowner" -> {
                if (args.length != 2) { p.sendMessage(ChatColor.RED + "Использование: /ul zone transferowner <ник>"); return; }
                zones.transferOwnerCmd(p, args[1]);
            }

            default -> p.sendMessage(ChatColor.RED + "Неизвестная команда!");
        }
    }
}
