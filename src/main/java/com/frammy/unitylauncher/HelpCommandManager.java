package com.frammy.unitylauncher;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;

public class HelpCommandManager {

    // Публичный вложенный класс, чтобы его можно было возвращать из public-методов
        public record HelpCommand(String command, String description, String category) {

        public Component toComponent() {
                return Component.text(command)
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.suggestCommand(command)) // клик — вставить команду в чат
                        .hoverEvent(HoverEvent.showText(Component.text("Нажми, чтобы вставить в чат")))
                        .append(Component.text(": "))
                        .append(Component.text(description).color(NamedTextColor.WHITE));
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
            if (cmd.category().equalsIgnoreCase(category)) {
                filtered.add(cmd);
            }
        }
        return filtered;
    }
}
