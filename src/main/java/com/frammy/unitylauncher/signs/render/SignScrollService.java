package com.frammy.unitylauncher.signs.render;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.signs.SignVariables;
import com.frammy.unitylauncher.signs.storage.SignStore;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SignScrollService {

    private final UnityLauncher plugin;
    private final SignStore store;

    private final Map<Location, BukkitTask> scrollingTasks = new HashMap<>();
    private final Map<Location, BukkitTask> resetTasks = new HashMap<>();
    private final Map<Location, Map<Integer, BukkitTask>> activeScrolls = new HashMap<>();

    public SignScrollService(UnityLauncher plugin, SignStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    public void pauseScrolling(Location location) {
        SignVariables v = store.get(location);
        if (v != null) v.setPaused(true);
    }

    public void resumeScrolling(Location location) {
        SignVariables v = store.get(location);
        if (v != null) v.setPaused(false);
    }

    public void stopScrollingTask(Location loc) {
        loc = SignStore.keyLoc(loc);
        BukkitTask task = scrollingTasks.remove(loc);
        if (task != null) task.cancel();
    }

    public void stopHorizontalScroll(Location signLocation, int lineIndex) {
        signLocation = SignStore.keyLoc(signLocation);
        Map<Integer, BukkitTask> tasks = activeScrolls.get(signLocation);
        if (tasks == null) return;
        BukkitTask task = tasks.remove(lineIndex);
        if (task != null) task.cancel();
        if (tasks.isEmpty()) activeScrolls.remove(signLocation);
    }

    public void startSignTextScroll(Sign sign, int lineIndex, String fullText, ChatColor color,
                                    int visibleWidth, int durationTicks, int intervalTicks, Runnable onComplete) {
        if (sign == null) return;

        String stripped = ChatColor.stripColor(fullText);
        Location loc = SignStore.keyLoc(sign.getLocation());

        activeScrolls.computeIfAbsent(loc, l -> new HashMap<>());
        BukkitTask oldTask = activeScrolls.get(loc).get(lineIndex);
        if (oldTask != null) oldTask.cancel();

        if (stripped.length() <= visibleWidth) {
            sign.setLine(lineIndex, color + stripped);
            sign.update();
            if (onComplete != null) onComplete.run();
            return;
        }

        BukkitTask newTask = new BukkitRunnable() {
            int tick = 0;
            boolean forward = true;

            @Override public void run() {
                BlockState st = loc.getBlock().getState();
                if (tick >= durationTicks || !(st instanceof Sign currentSign)) {
                    cancel();
                    Map<Integer, BukkitTask> m = activeScrolls.get(loc);
                    if (m != null) {
                        m.remove(lineIndex);
                        if (m.isEmpty()) activeScrolls.remove(loc);
                    }
                    if (onComplete != null) onComplete.run();
                    return;
                }

                int maxOffset = stripped.length() - visibleWidth;

                int offset = tick % (maxOffset + 1);
                if (!forward) offset = maxOffset - offset;

                String view = stripped.substring(offset, offset + visibleWidth);
                currentSign.setLine(lineIndex, color + view);
                currentSign.update();

                if (tick % (maxOffset + 1) == 0) forward = !forward;
                tick++;
            }

        }.runTaskTimer(plugin, 0L, intervalTicks);

        activeScrolls.get(loc).put(lineIndex, newTask);
    }

    public void makeSignScrollingLines(Location signLocation, Map<Integer, String> originalLines, int intervalTicks, int maxLength) {
        signLocation = SignStore.keyLoc(signLocation);
        BlockState st = signLocation.getBlock().getState();
        if (!(st instanceof Sign sign)) return;

        Map<Integer, String> scrollBuffers = new HashMap<>();
        for (Map.Entry<Integer, String> entry : originalLines.entrySet()) {
            int lineIndex = entry.getKey();
            String text = entry.getValue();
            if (text == null) text = "";

            if (text.length() <= maxLength) {
                sign.setLine(lineIndex, text);
            } else {
                String scrollingBuffer = (text + "   ").repeat(2);
                scrollBuffers.put(lineIndex, scrollingBuffer);
            }
        }
        sign.update();

        if (scrollBuffers.isEmpty()) return;

        AtomicInteger offset = new AtomicInteger(0);
        stopScrollingTask(signLocation);

        Location finalLoc = signLocation;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            SignVariables vars = store.get(finalLoc);
            if (vars == null) {
                stopScrollingTask(finalLoc);
                return;
            }
            if (vars.isPaused()) return;

            BlockState cur = finalLoc.getBlock().getState();
            if (!(cur instanceof Sign currentSign)) {
                stopScrollingTask(finalLoc);
                return;
            }

            boolean anyNearby = Bukkit.getOnlinePlayers().stream()
                    .anyMatch(p -> p.getWorld().equals(finalLoc.getWorld())
                            && p.getLocation().distanceSquared(finalLoc) <= 35 * 35);
            if (!anyNearby) return;

            int pos = offset.getAndUpdate(i -> i + 1);

            for (Map.Entry<Integer, String> entry : scrollBuffers.entrySet()) {
                int lineIndex = entry.getKey();
                String buffer = entry.getValue();
                int start = pos % buffer.length();

                StringBuilder display = new StringBuilder();
                for (int i = 0; i < maxLength; i++) {
                    display.append(buffer.charAt((start + i) % buffer.length()));
                }
                currentSign.setLine(lineIndex, display.toString());
            }

            currentSign.update();
        }, 0L, intervalTicks);

        scrollingTasks.put(signLocation, task);
    }

    public void scheduleSignReset(Location loc) {
        loc = SignStore.keyLoc(loc);
        BukkitTask prev = resetTasks.remove(loc);
        if (prev != null) prev.cancel();

        Location finalLoc = loc;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            SignVariables sv = store.get(finalLoc);
            if (sv == null) { resetTasks.remove(finalLoc); return; }

            if (sv.isPaused()) { scheduleSignReset(finalLoc); return; }

            BlockState st = finalLoc.getBlock().getState();
            if (!(st instanceof Sign sign)) { resetTasks.remove(finalLoc); return; }

            String[] lines = store.originalSignTexts().get(finalLoc);
            if (lines != null) {
                for (int i = 0; i < Math.min(4, lines.length); i++) sign.setLine(i, lines[i]);
                sign.update();
            }
            resetTasks.remove(finalLoc);
        }, 20L * 10L);

        resetTasks.put(loc, task);
    }

    public Map<Location, BukkitTask> scrollingTasks() { return scrollingTasks; }
    public Map<Location, BukkitTask> resetTasks() { return resetTasks; }
    public Map<Location, Map<Integer, BukkitTask>> activeScrolls() { return activeScrolls; }
}
