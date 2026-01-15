package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.FramesDao;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public abstract class FrameRewardAdvancement extends BaseAdvancement {

    protected abstract int getFrameId();

    protected FrameRewardAdvancement(
            String key,
            com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay display,
            com.fren_gor.ultimateAdvancementAPI.advancement.Advancement parent,
            int x
    ) {
        super(key, display, parent, x);
    }

    @Override
    public final void giveReward(Player player) {
        super.giveReward(player);

        int frameId = getFrameId();
        if (frameId <= 0) return;

        Bukkit.getScheduler().runTaskAsynchronously(
                UnityLauncher.getInstance(),
                () -> FramesDao.addUserToFrame(frameId, player.getName())
        );
    }
}
