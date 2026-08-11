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

        // GH #6: scroll affordance matches the ATM signs (заголовок / ▴ /
        // <контекст> / ▾) instead of previewing the actual prev/next item
        // text on lines 1/3 — arrows only, shown solely when there's
        // something to scroll to in that direction.
        if (items.size() == 1) {
            sign.setLine(1, "");
            sign.setLine(2, ChatColor.GREEN + truncateToVisible(items.getFirst()));
            sign.setLine(3, "");
            highlighted = items.getFirst();
        } else {
            int centerIndex = Math.floorMod(offset + 1, items.size());
            String text = items.get(centerIndex);
            highlighted = text;

            sign.setLine(1, "▴");
            sign.setLine(2, ChatColor.GREEN + truncateToVisible(text));
            sign.setLine(3, "▾");
        }

        sign.update();
        return highlighted;
    }

    public String truncateToVisible(String text) {
        if (text == null) return "";
        return (text.length() > 15) ? text.substring(0, 15) : text;
    }
}
