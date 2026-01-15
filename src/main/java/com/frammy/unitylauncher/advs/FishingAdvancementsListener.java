package com.frammy.unitylauncher.advs;
import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.advs.achievements.Ach1_2;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class FishingAdvancementsListener implements Listener {

    private final UnityLauncher plugin;
    private final Ach1_2 ach1_2;

    private final NamespacedKey countKey;
    private final NamespacedKey doneKey;

    private static final int REQUIRED = 50;

    public FishingAdvancementsListener(UnityLauncher plugin, Ach1_2 ach1_2) {
        this.plugin = plugin;
        this.ach1_2 = ach1_2;
        this.countKey = new NamespacedKey(plugin, "ach1_2_fish_items");
        this.doneKey  = new NamespacedKey(plugin, "ach1_2_done");
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player p = e.getPlayer();

        // Проверяем, что пойман именно предмет
        if (!(e.getCaught() instanceof Item)) return;

        PersistentDataContainer pdc = p.getPersistentDataContainer();

        // Уже выполнено — не считаем
        if (pdc.has(doneKey, PersistentDataType.BYTE)) return;

        int count = pdc.getOrDefault(countKey, PersistentDataType.INTEGER, 0);
        count++;

        pdc.set(countKey, PersistentDataType.INTEGER, count);

        if (count >= REQUIRED) {
            ach1_2.grant(p); // ✅ выдаём достижение → вызовется giveReward()
            pdc.set(doneKey, PersistentDataType.BYTE, (byte) 1);
        }
    }
}

