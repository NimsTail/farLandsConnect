package com.frammy.unitylauncher.upgrades.core;

import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

public final class TaskGroup {

    private final List<BukkitTask> tasks = new ArrayList<>();

    public BukkitTask add(BukkitTask task) {
        if (task != null) tasks.add(task);
        return task;
    }

    public void cancelAll() {
        for (BukkitTask t : tasks) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        tasks.clear();
    }

    public int size() { return tasks.size(); }
}
