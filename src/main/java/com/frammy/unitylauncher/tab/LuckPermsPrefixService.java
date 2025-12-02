package com.frammy.unitylauncher.tab;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.metadata.NodeMetadataKey;
import net.luckperms.api.node.types.PrefixNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ставит/снимает пользовательский prefix в LuckPerms, чтобы EssentialsX Chat видел его через Vault.
 * Мы помечаем свои Prefix-узлы метаданными source=unitylauncher и безопасно чистим только их.
 */
public final class LuckPermsPrefixService {

    private final Plugin plugin;
    private final Map<UUID, String> lastApplied = new ConcurrentHashMap<>();
    private static final String META_SOURCE_VAL = "unitylauncher";
    private static final int PRIORITY = 1000; // выше типичных групп, если нужно — измените
    private static final NodeMetadataKey<String> META_SOURCE_KEY =
            NodeMetadataKey.of("source", String.class); // ключ метаданных

    /** Кешируем ссылку на LuckPerms, чтобы не ловить исключения каждый раз. Может быть null. */
    private final LuckPerms lp;

    public LuckPermsPrefixService(Plugin plugin) {
        this.plugin = plugin;

        LuckPerms tmp = null;
        try {
            tmp = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            Bukkit.getLogger().warning(
                    "[UnityLauncher] LuckPermsPrefixService: LuckPerms API недоступен: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
        }
        this.lp = tmp;
    }

    /** Установить/обновить префикс. Если prefix == null или пустой — снимаем наш кастомный LP-префикс. */
    public void applyOrClear(UUID uuid, String now) {
        String prev = lastApplied.get(uuid);

        // если ничего не поменялось — не дёргаем LuckPerms лишний раз
        if ((prev == null && now == null) || (prev != null && prev.equals(now))) return;

        // Если LuckPerms вообще нет — не пытаемся ничего делать
        if (lp == null) {
            Bukkit.getLogger().warning(
                    "[UnityLauncher] LuckPermsPrefixService: lp == null, пропускаю applyOrClear для " + uuid
            );
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                User user = lp.getUserManager().loadUser(uuid).join();
                if (user == null) {
                    Bukkit.getLogger().warning(
                            "[UnityLauncher] LuckPermsPrefixService: user == null для " + uuid
                    );
                    return;
                }

                // убираем наши старые prefix-ноды
                user.data().clear(node ->
                        (node instanceof PrefixNode) &&
                                node.getMetadata(META_SOURCE_KEY).map(META_SOURCE_VAL::equals).orElse(false)
                );

                // ставим новый, если есть
                if (now != null && !now.trim().isEmpty()) {
                    String amp = now.replace('§', '&').trim();
                    PrefixNode n = PrefixNode.builder(amp, PRIORITY)
                            .withMetadata(META_SOURCE_KEY, META_SOURCE_VAL)
                            .build();
                    user.data().add(n);
                }

                lp.getUserManager().saveUser(user);
                lastApplied.put(uuid, now);
            } catch (Exception t) {
                Bukkit.getLogger().warning(
                        "[UnityLauncher] LuckPermsPrefixService error: "
                                + t.getClass().getSimpleName() + ": " + t.getMessage()
                );
                t.printStackTrace();
            }
        });
    }

    /** Снять наш кастомный префикс. */
    public void clear(UUID uuid) {
        applyOrClear(uuid, null);
    }
}
