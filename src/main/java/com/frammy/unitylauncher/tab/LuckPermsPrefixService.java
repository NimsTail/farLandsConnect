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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ставит/снимает пользовательский prefix в LuckPerms, чтобы EssentialsX Chat видел его через Vault.
 * Мы помечаем свои Prefix-узлы метаданными source=unitylauncher и безопасно чистим только их.
 */
public final class LuckPermsPrefixService {

    private final Plugin plugin;
    private final Map<UUID, String> lastApplied = new ConcurrentHashMap<>();
    // GH #10: два вызова applyOrClear для одного игрока подряд (джойн-событие
    // + периодический таймер TabPrefixService, раз в 15с) раньше могли
    // выполниться параллельно на разных async-потоках — оба грузят User,
    // оба видят один и тот же СТАРЫЙ снимок нод, оба чистят+добавляют СВОЙ
    // новый узел в своей локальной копии и сохраняют. LuckPerms сохраняет
    // как diff относительно загруженного снимка, а не полной заменой — из-за
    // этого второй save применял diff "убрать старое (уже не было), добавить
    // новое" ПОВЕРХ уже сохранённого первым результата, и оба prefix-узла
    // выживали одновременно (см. luckperms_user_permissions в issue #10 —
    // 4 разных prefix.1000.&X на одном игроке). Цепочка per-UUID гарантирует,
    // что load→clear→add→save для одного игрока всегда идёт строго
    // последовательно, второй вызов стартует только после того, как первый
    // реально долетел до БД.
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
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
        // Быстрый выход по кешу ДО постановки в цепочку — если значение точно
        // не менялось, незачем даже занимать очередь. Если кеш устарел
        // (гонка), applyOnce ниже всё равно safe — просто лишний load/save.
        String prevCached = lastApplied.get(uuid);
        if ((prevCached == null && now == null) || (prevCached != null && prevCached.equals(now))) return;

        if (lp == null) {
            Bukkit.getLogger().warning(
                    "[UnityLauncher] LuckPermsPrefixService: lp == null, пропускаю applyOrClear для " + uuid
            );
            return;
        }

        chains.compute(uuid, (id, prevInChain) -> {
            CompletableFuture<Void> base = (prevInChain != null) ? prevInChain : CompletableFuture.completedFuture(null);
            return base
                    .thenRunAsync(() -> applyOnce(uuid, now), r -> Bukkit.getScheduler().runTaskAsynchronously(plugin, r))
                    .exceptionally(t -> {
                        Bukkit.getLogger().warning(
                                "[UnityLauncher] LuckPermsPrefixService chain error for " + uuid + ": " + t.getMessage()
                        );
                        return null;
                    });
        });
    }

    private void applyOnce(UUID uuid, String now) {
        try {
            User user = lp.getUserManager().loadUser(uuid).join();
            if (user == null) {
                Bukkit.getLogger().warning(
                        "[UnityLauncher] LuckPermsPrefixService: user == null для " + uuid
                );
                return;
            }

            // GH #10 round 4: the metadata tag (META_SOURCE_KEY) is a
            // NodeMetadataKey — LuckPerms keeps that as in-memory-only
            // bookkeeping on the Node instance, it does NOT round-trip
            // through storage. loadUser() above rebuilds every Node fresh
            // from the DB/YAML backend each call, so on any change AFTER
            // the very first one, every node here — including our own
            // previously-saved prefix — comes back with an EMPTY metadata
            // map. The clear() below matched nothing (not even our own old
            // node), so it never actually removed anything past the first
            // apply — every later change just piled a new PrefixNode on top
            // of the untouched old one, exactly "старый висит, новый
            // заливается сверху". PRIORITY is a real field of PrefixNode
            // itself (persisted, not metadata) and this service is the only
            // thing that ever sets a prefix at exactly this priority, so
            // matching on it survives the reload where metadata doesn't.
            user.data().clear(node -> (node instanceof PrefixNode pn) && pn.getPriority() == PRIORITY);

            // ставим новый, если есть
            if (now != null && !now.trim().isEmpty()) {
                String amp = now.replace('§', '&').trim();
                PrefixNode n = PrefixNode.builder(amp, PRIORITY)
                        .withMetadata(META_SOURCE_KEY, META_SOURCE_VAL)
                        .build();
                user.data().add(n);
            }

            // .join() — must complete (and the DB write with it) before this
            // chain link resolves and the next queued call is allowed to load.
            lp.getUserManager().saveUser(user).join();
            lastApplied.put(uuid, now);
        } catch (Exception t) {
            Bukkit.getLogger().warning(
                    "[UnityLauncher] LuckPermsPrefixService error: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage()
            );
            t.printStackTrace();
        }
    }

    /** Снять наш кастомный префикс. */
    public void clear(UUID uuid) {
        applyOrClear(uuid, null);
    }
}
