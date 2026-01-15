package com.frammy.unitylauncher.advs;
import com.frammy.unitylauncher.advs.achievements.Ach1;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.*;

public class BlockBreakAdvancementsListener implements Listener {

    private final Ach1 ach1;

    private final Map<UUID, Set<Material>> leafProgress = new HashMap<>();

    private static final Set<Material> LEAVES = Set.of(
            Material.OAK_LEAVES,
            Material.SPRUCE_LEAVES,
            Material.BIRCH_LEAVES,
            Material.JUNGLE_LEAVES,
            Material.ACACIA_LEAVES,
            Material.DARK_OAK_LEAVES,
            Material.AZALEA_LEAVES,
            Material.FLOWERING_AZALEA_LEAVES,
            Material.MANGROVE_LEAVES,
            Material.CHERRY_LEAVES
    );

    public BlockBreakAdvancementsListener(Ach1 ach1) {
        this.ach1 = ach1;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Material mat = e.getBlock().getType();

        if (!LEAVES.contains(mat)) return;

        // (опционально, но правильно) если уже получено — не трекаем
        // если в твоей версии есть такой метод:
        // if (ach1.isGranted(p)) return;

        Set<Material> set = leafProgress.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        set.add(mat);

        if (set.size() >= 10) {
            ach1.grant(p);              // ✅ выдаём достижение
            leafProgress.remove(p.getUniqueId()); // чистим прогресс
        }
    }
}