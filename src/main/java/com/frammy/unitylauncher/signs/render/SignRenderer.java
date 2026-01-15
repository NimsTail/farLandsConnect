package com.frammy.unitylauncher.signs.render;

import org.bukkit.ChatColor;
import org.bukkit.block.Sign;

import java.util.List;

public final class SignRenderer {

    public String updateSignView(Sign sign, List<String> items, int offset) {
        if (sign == null) return null;

        if (items == null || items.isEmpty()) {
            sign.setLine(1, "");
            sign.setLine(2, "");
            sign.setLine(3, "");
            sign.update();
            return null;
        }

        String highlighted = null;

        if (items.size() == 1) {
            sign.setLine(1, "");
            sign.setLine(2, ChatColor.GREEN + truncateToVisible(items.getFirst()));
            sign.setLine(3, "");
            highlighted = items.getFirst();
        } else if (items.size() == 2) {
            int upperIndex = offset % 2;
            int centerIndex = (offset + 1) % 2;

            sign.setLine(1, truncateToVisible(items.get(upperIndex)));
            sign.setLine(2, ChatColor.GREEN + truncateToVisible(items.get(centerIndex)));
            sign.setLine(3, "");
            highlighted = items.get(centerIndex);
        } else {
            for (int i = 0; i < 3; i++) {
                int index = (offset + i) % items.size();
                String text = items.get(index);

                if (i == 1) {
                    highlighted = text;
                    sign.setLine(i + 1, ChatColor.GREEN + truncateToVisible(text));
                } else {
                    sign.setLine(i + 1, truncateToVisible(text));
                }
            }
        }

        sign.update();
        return highlighted;
    }

    public String truncateToVisible(String text) {
        if (text == null) return "";
        return (text.length() > 15) ? text.substring(0, 15) : text;
    }
}
