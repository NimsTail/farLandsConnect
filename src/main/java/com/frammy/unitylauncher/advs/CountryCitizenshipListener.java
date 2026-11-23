package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.advs.achievements.Ach1_6;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.node.NodeAddEvent;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * "Стать гражданином любой из стран" (Ach1_6, выдаёт разом Гранит/Диорит/
 * Андезит — см. infra/frames-catalog.md §3 п.2) — hooks LuckPerms directly
 * instead of any one specific site-sync code path, since country membership
 * can be granted multiple ways (join, accept invite, ...) and all of them
 * ultimately show up as the player inheriting a "country_<id>" LuckPerms
 * group (см. infra/game-integration-architecture.md,
 * ZoneQuotaService#getCountryGroup).
 */
public final class CountryCitizenshipListener {

    private final Ach1_6 ach1_6;

    public CountryCitizenshipListener(Ach1_6 ach1_6) {
        this.ach1_6 = ach1_6;
    }

    public void register() {
        LuckPerms lp;
        try {
            lp = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            Bukkit.getLogger().warning("[Advancements] LuckPerms API недоступен, Ach1_6 не будет выдаваться: " + e.getMessage());
            return;
        }

        lp.getEventBus().subscribe(NodeAddEvent.class, event -> {
            if (!(event.getTarget() instanceof User user)) return;
            if (!(event.getNode() instanceof InheritanceNode node)) return;
            if (!node.getGroupName().startsWith("country_")) return;

            Player player = Bukkit.getPlayer(user.getUniqueId());
            if (player != null) {
                ach1_6.grant(player);
            }
        });
    }
}
