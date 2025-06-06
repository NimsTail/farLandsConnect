package com.frammy.unitylauncher;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class HelpCommandManager {

    // Класс для хранения информации о командах
    static class HelpCommand {
        String command;
        String description;
        String category;

        public HelpCommand(String command, String description, String category) {
            this.command = command;
            this.description = description;
            this.category = category;
        }

        public Component toComponent() {
            return Component.text(command + ": ")
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.suggestCommand(command)) // Кликабельность команды
                    .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы вставить в чат"))) // Всплывающее сообщение
                    .append(Component.text(": ")) // Разделитель
                    .append(Component.text(description) // Описание
                            .color(NamedTextColor.WHITE));
        }
    }

    // Список команд
    private final List<HelpCommand> commands = new ArrayList<>();

    // Добавление команды
    public void addCommand(String command, String description, String category) {
        commands.add(new HelpCommand(command, description, category));
    }

    // Получение списка команд по категории
    public List<HelpCommand> getCommandsByCategory(String category) {
        List<HelpCommand> filtered = new ArrayList<>();
        for (HelpCommand cmd : commands) {
            if (cmd.category.equalsIgnoreCase(category)) {
                filtered.add(cmd);
            }
        }
        return filtered;
    }

    // Отправка всех команд игроку
    public void sendHelp(Player player) {
        for (HelpCommand cmd : commands) {
            player.sendMessage(cmd.toComponent());
        }
    }
}
