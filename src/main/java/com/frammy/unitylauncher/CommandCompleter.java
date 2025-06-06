package com.frammy.unitylauncher;

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
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        // Проверяем количество аргументов
        if (args.length == 1) {
            // Предлагаем команды на первом уровне аргументов
            completions.add("rcode");
            completions.add("balance");
            completions.add("notifications");
            completions.add("countrybalance");
            completions.add("daydeal");
            completions.add("group");
            completions.add("change");
            completions.add("top");
            completions.add("country");
            completions.add("zone");

        } else if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "zone":
                    completions.add("build");
                    completions.add("addcorner");
                    completions.add("removecorner");
                    completions.add("update");
                    break;
                case "top":
                    completions.add("Playtime");
                    completions.add("Balance");
                    completions.add("Events");
                    break;
                case "notifications":
                    completions.add("on");
                    completions.add("off");
                    break;
                case "group":
                    completions.add("list");
                    completions.add("set");
                    completions.add("prefix");
                    break;
                case "countrybalance":
                    completions.add("add");
                    completions.add("withdraw");
                    break;

            }
        }
        else if (args.length == 3) {
            switch (args[1].toLowerCase()) {

                case "update":
                    completions.add("corners");
                    completions.add("name");
                    break;
                case "build":
                    break;
            }
        }
        else if (args.length == 4) {
            switch (args[2].toLowerCase()) {
                case "corners":
                    completions.add("+");
                    completions.add("-");
                    break;
            }
        }
        return completions;
    }
}
