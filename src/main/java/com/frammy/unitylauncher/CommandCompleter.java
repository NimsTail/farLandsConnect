package com.frammy.unitylauncher;

import com.frammy.unitylauncher.zones.ZoneManager;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class CommandCompleter implements TabCompleter {

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {

        List<String> completions = new ArrayList<>();
        ZoneManager zoneManager = UnityLauncher.getInstance().getZoneManager();

        // первый аргумент — список подкоманд /ul <...>
        if (args.length == 1) {
            // auth
            completions.add("login");
            completions.add("reg");
            completions.add("change");         // смена пароля

            // экономика/инфо
            completions.add("balance");
            completions.add("country");

            // зоны / дипломатия
            completions.add("zone");
            completions.add("relations");

            // легаси/заглушки (пока команды существуют в UnityCommands)
            completions.add("notifications");
            completions.add("countrybalance");
            completions.add("daydeal");

        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "zone" -> {
                    completions.add("build");
                    completions.add("addcorner");
                    completions.add("removecorner");
                    completions.add("update");
                    completions.add("remove");
                    completions.add("price");
                }
                case "top" -> {
                    completions.add("Playtime");
                    completions.add("Balance");
                    completions.add("Events");
                }
                case "notifications" -> {
                    completions.add("on");
                    completions.add("off");
                }
                case "group" -> {
                    completions.add("list");
                    completions.add("set");
                    completions.add("prefix");
                }
                case "countrybalance" -> {
                    completions.add("add");
                    completions.add("withdraw");
                }
                // login/reg/change — сюда подсказки не нужны, там идёт пароль/аргумент
            }

        } else if (args.length == 3) {
            switch (args[1].toLowerCase()) {
                case "update" -> {
                    completions.add("corners");
                    completions.add("name");
                    completions.add("color");
                }
                case "build", "addcorner" -> {
                    if (zoneManager != null) {
                        for (ZoneType zoneType : zoneManager.zoneLimits.keySet()) {
                            completions.add(zoneType.toString().toUpperCase());
                        }
                    }
                }
            }

        } else if (args.length == 4) {
            if (args[2].equalsIgnoreCase("corners")) {
                completions.add("+");
                completions.add("-");
            }
        }

        return completions;
    }
}
