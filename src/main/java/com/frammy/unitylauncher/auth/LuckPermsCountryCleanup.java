package com.frammy.unitylauncher.auth;

import com.frammy.unitylauncher.UnityLauncher;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.metadata.NodeMetadataKey;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * One-time admin cleanup for GH #10 — "стары group.* узлы на игроках, это
 * легаси до миграции или всё ещё активно создаются?" turned out to be: some
 * (country_21/24 at the time of asking) are real, live memberships, the rest
 * is pre-migration cruft (old PHP-era name-based groups like "group.bentley")
 * that was never stripped when a player actually left/switched countries —
 * nothing in the current codebase adds a per-player country group
 * inheritance node at all (UpgradeGrantPoller only ever touches the GROUP's
 * own nodes, not a player's), so this can only be a one-time data fix, not
 * an ongoing bug to patch in the write path.
 *
 * `/ul luckperms cleanup dry` reports what would change, `/ul luckperms
 * cleanup apply` actually does it. Per the explicit call in that issue:
 * never touches group.default, only ever removes OTHER group.* inheritance
 * that doesn't match the player's real current country (per
 * CountryRegistryJdbc — the plugin's own MySQL Countries.Players, not the
 * site's separate Postgres membership). Also clears our own duplicate
 * prefix.1000.&X nodes left over from the race LuckPermsPrefixService had
 * before this same pass (see that class) — harmless, the next periodic
 * reapply (or this player's next join) puts back exactly one correct one.
 */
public final class LuckPermsCountryCleanup {

    private LuckPermsCountryCleanup() {}

    private static final NodeMetadataKey<String> META_SOURCE_KEY = NodeMetadataKey.of("source", String.class);
    private static final String META_SOURCE_VAL = "unitylauncher";

    /**
     * GH #10 round 3 — strips EVERY country_* inheritance node and our own
     * tagged prefix nodes from one player, unconditionally (unlike the batch
     * dry/apply pass above, which only removes what doesn't match their
     * CURRENT country). Used by CountryMemberLeaveRequestPoller right after
     * the site tells us this specific player just left/was kicked from
     * their country — at that point they have no country at all, so "keep
     * the correct one" doesn't apply. Returns true if anything changed.
     */
    public static boolean stripPlayerCountryState(LuckPerms lp, UUID uuid) {
        User user;
        try {
            user = lp.getUserManager().loadUser(uuid).join();
        } catch (Exception e) {
            return false;
        }
        if (user == null) return false;

        List<Node> toRemove = new ArrayList<>();
        for (Node node : user.data().toCollection()) {
            if (node instanceof InheritanceNode inh && inh.getGroupName().toLowerCase().startsWith("country_")) {
                toRemove.add(node);
            } else if (node instanceof PrefixNode
                    && node.getMetadata(META_SOURCE_KEY).map(META_SOURCE_VAL::equals).orElse(false)) {
                toRemove.add(node);
            }
        }
        if (toRemove.isEmpty()) return false;

        for (Node n : toRemove) user.data().remove(n);
        lp.getUserManager().saveUser(user).join();
        return true;
    }

    public record Report(int playersScanned, int playersChanged, int staleGroupsRemoved, int stalePrefixesRemoved) {}

    public static void run(CommandSender sender, boolean apply) {
        LuckPerms lp;
        try {
            lp = LuckPermsProvider.get();
        } catch (Throwable t) {
            sender.sendMessage("§cLuckPerms недоступен: " + t.getMessage());
            return;
        }
        Logger log = UnityLauncher.getInstance().getLogger();

        lp.getUserManager().getUniqueUsers().thenAccept(uuids -> {
            Report report = process(lp, uuids, apply);
            Bukkit.getScheduler().runTask(UnityLauncher.getInstance(), () -> {
                sender.sendMessage((apply ? "§aПрименено. " : "§eПробный прогон (ничего не менялось). ")
                        + "Игроков просмотрено: " + report.playersScanned()
                        + ", изменено бы/изменено: " + report.playersChanged()
                        + ", лишних group.*: " + report.staleGroupsRemoved()
                        + ", дублей префикса: " + report.stalePrefixesRemoved());
            });
        }).exceptionally(t -> {
            log.warning("[LuckPermsCountryCleanup] failed: " + t);
            Bukkit.getScheduler().runTask(UnityLauncher.getInstance(), () -> sender.sendMessage("§cОшибка: " + t.getMessage()));
            return null;
        });
    }

    private static Report process(LuckPerms lp, Set<UUID> uuids, boolean apply) {
        int scanned = 0, changed = 0, groupsRemoved = 0, prefixesRemoved = 0;

        for (UUID uuid : uuids) {
            scanned++;
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String name = op.getName();
            if (name == null) continue; // LuckPerms knows a UUID Bukkit has never seen a name for — nothing to resolve against

            String correctGroup = correctGroupFor(name);

            User user;
            try {
                user = lp.getUserManager().loadUser(uuid).join();
            } catch (Exception e) {
                continue;
            }
            if (user == null) continue;

            List<Node> toRemove = new ArrayList<>();
            for (Node node : user.data().toCollection()) {
                if (node instanceof InheritanceNode inh) {
                    String groupName = inh.getGroupName();
                    boolean isDefault = groupName.equalsIgnoreCase("default");
                    boolean isCorrect = correctGroup != null && groupName.equalsIgnoreCase(correctGroup);
                    if (!isDefault && !isCorrect) {
                        toRemove.add(node);
                    }
                } else if (node instanceof PrefixNode
                        && node.getMetadata(META_SOURCE_KEY).map(META_SOURCE_VAL::equals).orElse(false)) {
                    // Duplicates from the pre-fix race — keep none here, the
                    // next legit apply (periodic reapply / next join) adds
                    // back exactly one.
                    toRemove.add(node);
                }
            }

            if (toRemove.isEmpty()) continue;
            changed++;
            for (Node n : toRemove) {
                if (n instanceof InheritanceNode) groupsRemoved++;
                else prefixesRemoved++;
            }

            if (apply) {
                for (Node n : toRemove) user.data().remove(n);
                lp.getUserManager().saveUser(user).join();
            }
        }

        return new Report(scanned, changed, groupsRemoved, prefixesRemoved);
    }

    /** Player's real current country group ("country_<Id>"), or null if they're not in one. */
    private static String correctGroupFor(String playerName) {
        String country = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(playerName);
        if (country == null || country.isBlank()) return null;
        Integer id = UnityLauncher.getInstance().countryRegistryJdbc.getCountryId(country);
        return id != null ? "country_" + id : null;
    }
}
