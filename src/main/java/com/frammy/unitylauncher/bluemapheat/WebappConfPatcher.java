package com.frammy.unitylauncher.bluemapheat;

import org.jspecify.annotations.NonNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class WebappConfPatcher {

    private WebappConfPatcher() {}

    public static void patch(Path webappConf, int maxZoomDistance) {
        try {
            if (!Files.exists(webappConf)) return;

            List<String> lines = Files.readAllLines(webappConf, StandardCharsets.UTF_8);
            List<String> out = getList(maxZoomDistance, lines);

            Files.write(webappConf, out, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        } catch (Throwable ignored) {
            // молча — чтобы не валить сервер на странном формате конфига
        }
    }

    private static @NonNull List<String> getList(int maxZoomDistance, List<String> lines) {
        List<String> out = new ArrayList<>(lines.size() + 4);

        boolean hasMax = false;

        for (String line : lines) {
            String trim = line.trim();

            if (trim.startsWith("max-zoom-distance:")) {
                out.add("max-zoom-distance: " + maxZoomDistance);
                hasMax = true;
                continue;
            }

            out.add(line);
        }

        if (!hasMax) {
            out.add("");
            out.add("max-zoom-distance: " + maxZoomDistance);
        }
        return out;
    }
}
