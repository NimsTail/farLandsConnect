package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.FramesDao;
import com.fren_gor.ultimateAdvancementAPI.advancement.BaseAdvancement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public abstract class FrameRewardAdvancement extends BaseAdvancement {

    /** Single-frame achievements only need to override this. */
    protected int getFrameId() {
        return 0;
    }

    /**
     * Override instead of {@link #getFrameId()} for an achievement that grants
     * more than one frame at once (e.g. a whole tier of a chain resolving into
     * a single trigger). Defaults to wrapping {@link #getFrameId()}.
     */
    protected int[] getFrameIds() {
        int single = getFrameId();
        return single > 0 ? new int[] { single } : new int[0];
    }

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

        int[] frameIds = getFrameIds();
        if (frameIds.length == 0) return;

        Bukkit.getScheduler().runTaskAsynchronously(
                UnityLauncher.getInstance(),
                () -> {
                    for (int frameId : frameIds) {
                        FramesDao.addUserToFrame(frameId, player.getName());
                    }
                }
        );
    }
}
