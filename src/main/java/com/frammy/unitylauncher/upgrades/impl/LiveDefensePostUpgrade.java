package com.frammy.unitylauncher.upgrades.impl;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.UpgradeCondition;
import com.frammy.unitylauncher.upgrades.config.types.MilitaryCfg;
import com.frammy.unitylauncher.upgrades.core.BaseUpgrade;
import com.frammy.unitylauncher.upgrades.core.UpgradeContext;
import com.frammy.unitylauncher.upgrades.core.UpgradeKey;
import com.frammy.unitylauncher.upgrades.core.UpgradeScope;
import com.frammy.unitylauncher.zones.ZoneInfo;
import com.frammy.unitylauncher.zones.ZoneType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

// infra/military-diplomacy-design.md GH#24 идея 1 "Живой оборонительный
// пост" — в отличие от DefensePatrolUpgrade (постоянный патруль), этот
// работает по триггеру: враг ТОЛЬКО ЧТО пересёк границу объекта (переход
// "не было рядом" -> "стал рядом" на предыдущем/текущем тике, не факт
// нахождения каждый тик) — спавнит одну волну и уходит на кулдаун.
//
// GH#30 — доработка по фидбеку (раунд 1):
//  1. Дроп/опыт уже были отключены с самого начала (onDeath ниже).
//  2. Мобы волны получают каждый свою случайную точку внутри реальной
//     территории объекта (rejection sampling по AABB + contains2D).
//  3. И триггер, и таргетинг заспавненных мобов требуют реальной войны.
//  4. "Интенсивность" волны зависит от числа онлайн-защитников страны.
//
// GH#30 (раунд 2) — по фидбеку "мобы дают дроп и опыт" (пункт 2, при том что
// onDeath их явно чистит) и "накидай пачки мобов сам":
//  5. Состав волны — теперь настоящий выбор страны (см. 3_militaryLiveDefensePack
//     choices в seed-данных): страна выбирает конкретную "пачку" мобов,
//     разблокированную уровнем узла; без выбора — старый фоллбек (1 моб по
//     биому). Наборы — на моё усмотрение по примеру, который дал пользователь
//     в фидбеке (зомби/скелеты на 1 уровне, усиленные варианты на 2-3).
//  6. onDeath поднят до EventPriority.HIGHEST — если другой плагин слушает
//     EntityDeathEvent на более раннем приоритете и что-то добавляет в
//     drops/exp уже ПОСЛЕ нашей чистки, это шло бы вразрез с "без дропа/
//     опыта"; на HIGHEST мы почти наверняка последние. Код самой очистки не
//     менялся — он был технически верным с самого начала, так что если баг
//     воспроизводился именно на СВЕЖЕМ джарнике (не предыдущем, ещё не
//     учитывающем фиксы этой сессии), тут может быть именно конфликт с
//     другим плагином/приоритетом, а не логическая ошибка в самой очистке.
//  7. Мобы поста теперь именованы (видимый CustomName) — видно, что это
//     защита объекта, не случайный моб.
public final class LiveDefensePostUpgrade extends BaseUpgrade implements Listener {

    private static final UpgradeKey KEY = UpgradeKey.of("military.live_defense");
    private static final String META_KEY = "unityLiveDefensePost";
    private static final String MOB_NAME = "§c⚔ Оборона поста";

    // GH#30 п.4 — черновые числа: при 0 онлайн-защитников волна получает
    // +1 моба сверху; каждый онлайн-защитник после первого снимает по
    // одному мобу с волны, но не ниже MIN_WAVE_SIZE.
    private static final int MIN_WAVE_SIZE = 1;

    // GH#30 п.2 — сколько раз пробовать случайную точку внутри AABB, прежде
    // чем сдаться и упасть на center() (полигон может занимать малую долю
    // своего же bounding box — L-образные/вытянутые территории).
    private static final int RANDOM_POINT_ATTEMPTS = 12;

    // GH#30 (Улучшения п.2) "Время между спавном" — независимый
    // параметрический апгрейд, множитель на cfg.cooldownTicks().
    private static final String COOLDOWN_PERM_BASE = "unity.military.live_defense_cooldown";
    private static final double[] COOLDOWN_MULTIPLIER = {1.0, 0.75, 0.55};

    // GH#30 (раунд 2, Улучшения п.1) "Состав волны" — permission-выбор, тот
    // же приём, что и у CrossbowUpgrade.EFFECT_PRESETS (см. её javadoc).
    // Наборы подобраны по примеру из фидбека: 1 уровень — простые
    // зомби/скелеты, 2 — усиленные варианты, 3 — ведьмы/разбойники.
    private record MobSpec(EntityType type, int count, boolean baby, double armorChance) {}
    private record Pack(String permission, List<MobSpec> mobs) {}
    private static final List<Pack> PACKS = List.of(
            new Pack("unity.military.live_defense_pack.zombie_pack", List.of(new MobSpec(EntityType.ZOMBIE, 3, false, 0.0))),
            new Pack("unity.military.live_defense_pack.skeleton_small", List.of(new MobSpec(EntityType.SKELETON, 2, false, 0.0))),
            new Pack("unity.military.live_defense_pack.skeleton_large", List.of(new MobSpec(EntityType.SKELETON, 4, false, 0.0))),
            new Pack("unity.military.live_defense_pack.zombie_armored", List.of(new MobSpec(EntityType.ZOMBIE, 3, false, 0.5))),
            new Pack("unity.military.live_defense_pack.witch_duo", List.of(
                    new MobSpec(EntityType.WITCH, 2, false, 0.0), new MobSpec(EntityType.ZOMBIE, 2, false, 0.5))),
            new Pack("unity.military.live_defense_pack.raid_mix", List.of(
                    new MobSpec(EntityType.WITCH, 1, false, 0.0), new MobSpec(EntityType.PILLAGER, 2, false, 0.0), new MobSpec(EntityType.ZOMBIE, 3, true, 0.0)))
    );

    @Override public UpgradeKey key() { return KEY; }
    @Override public UpgradeScope scope() { return UpgradeScope.COUNTRY; }
    @Override public Listener listener() { return this; }

    // markerId -> кто из врагов уже засчитан "внутри" на прошлый тик — переход
    // false->true (не было в сете, стал рядом) это и есть "пересечение границы".
    private final Map<String, Set<String>> nearbyEnemiesByZone = new ConcurrentHashMap<>();
    private final Map<String, Long> lastTriggerByZone = new ConcurrentHashMap<>();
    private BukkitTask task;

    @Override
    public boolean enabledByConfig(UpgradeContext ctx) {
        MilitaryCfg.LiveDefenseCfg cfg = ctx.config().military().liveDefense();
        return cfg != null && cfg.enabled();
    }

    @Override
    protected void onEnable() {
        var cfg = C().military().liveDefense();
        long period = Math.max(20L, cfg.periodTicks());

        task = Bukkit.getScheduler().runTaskTimer(plugin(), () -> tick(cfg), period, period);

        if (C().core().debug()) {
            plugin().getLogger().info("[Military/LiveDefensePost] started period=" + period);
        }
    }

    private void tick(MilitaryCfg.LiveDefenseCfg cfg) {
        var subtypeService = UnityLauncher.getInstance().militaryDefenseSubtypeService;

        for (ZoneInfo z : zones().getAllZonesSnapshot()) {
            if (z.getType() != ZoneType.MILITARY) continue;
            // GH#24 (фидбек 2026-08-14 п.1/4) — раньше запускался на ЛЮБОЙ
            // DEFENSE-зоне страны, купившей этот тип, разом со всеми
            // остальными; теперь только на той, что реально вкачана именно
            // в LIVE_DEFENSE (см. MilitaryDefenseSubtypeService).
            if (!subtypeService.isActiveAs(z, com.frammy.unitylauncher.military.MilitaryDefenseSubtype.LIVE_DEFENSE)) continue;

            String canonicalCountry = UpgradeCondition.zoneCountryCanonical(z);
            if (canonicalCountry == null) continue; // объект без страны — быть не должно, но на всякий случай

            Location center = z.getCenter();
            if (center == null || center.getWorld() == null) continue;

            String countryName = z.getCountryName();
            String markerId = z.getMarkerID();
            // GH#30 п.3 — "враг" теперь требует реальной войны, не просто
            // "не свой гражданин" — вне войны пост никого не засекает и не
            // спавнит волну.
            Set<String> nearbyNow = ConcurrentHashMap.newKeySet();
            for (Player p : center.getWorld().getPlayers()) {
                String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
                if (playerCountry == null || playerCountry.equalsIgnoreCase(countryName)) continue; // не враг
                if (!UnityLauncher.getInstance().warStatusCache.isAtWar(countryName, playerCountry)) continue; // не воюем — не враг
                if (p.getLocation().distance(center) > cfg.detectRadius()) continue;
                nearbyNow.add(p.getName());
            }

            Set<String> nearbyBefore = nearbyEnemiesByZone.getOrDefault(markerId, Set.of());
            boolean justCrossed = nearbyNow.stream().anyMatch(name -> !nearbyBefore.contains(name));
            nearbyEnemiesByZone.put(markerId, nearbyNow);

            if (!justCrossed) continue;
            long now = System.currentTimeMillis();
            Long lastTrigger = lastTriggerByZone.get(markerId);
            int cooldownLevel = UpgradeCondition.countryMaxLevel(canonicalCountry, COOLDOWN_PERM_BASE, 2);
            long cooldownMs = Math.round(cfg.cooldownTicks() * 50L * COOLDOWN_MULTIPLIER[cooldownLevel]);
            if (lastTrigger != null && now - lastTrigger < cooldownMs) continue;

            lastTriggerByZone.put(markerId, now);
            spawnWave(z, center, markerId, countryName, canonicalCountry);
        }
    }

    private void spawnWave(ZoneInfo z, Location center, String markerId, String countryName, String canonicalCountry) {
        List<MobSpec> mobs = resolveActivePack(canonicalCountry);
        if (mobs == null) {
            // Ничего не выбрано (узел не куплен вообще, или куплен но выбор
            // ещё не сделан) — старый фоллбек: 1 моб по биому.
            mobs = List.of(new MobSpec(DefensePatrolBiomeMob.forBiome(center), 1, false, 0.0));
        }

        // Разворачиваем спецификацию пачки в плоский список отдельных мобов —
        // дальше интенсивность по онлайну применяется к нему целиком, не
        // заботясь о том, откуда список взялся (пачка или фоллбек).
        List<MobSpec> flatUnits = new ArrayList<>();
        for (MobSpec spec : mobs) {
            for (int i = 0; i < spec.count(); i++) flatUnits.add(new MobSpec(spec.type(), 1, spec.baby(), spec.armorChance()));
        }

        // GH#30 п.4 — интенсивность волны обратно пропорциональна числу
        // онлайн-защитников: некому защищаться -> пост усиливается; чем
        // больше живых граждан на связи, тем меньше буст (но не ниже
        // MIN_WAVE_SIZE, чтобы пост не пропадал вовсе).
        int ownersOnline = (int) Bukkit.getOnlinePlayers().stream()
                .filter(p -> countryName.equalsIgnoreCase(UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName())))
                .count();
        if (ownersOnline == 0) {
            flatUnits.add(flatUnits.get(0)); // никто не защищается — усиление волны на 1 моба
        } else if (ownersOnline > 1) {
            int toRemove = Math.min(flatUnits.size() - MIN_WAVE_SIZE, ownersOnline - 1);
            for (int i = 0; i < toRemove; i++) flatUnits.remove(flatUnits.size() - 1);
        }

        for (MobSpec spec : flatUnits) {
            // GH#30 п.2 — своя случайная точка внутри территории объекта
            // для каждого моба волны, не общий center на всех.
            Location spawnLoc = randomPointIn(z, center);
            Entity e = spawnLoc.getWorld().spawnEntity(spawnLoc, spec.type());
            if (!(e instanceof LivingEntity mob)) continue;

            mob.setMetadata(META_KEY, new FixedMetadataValue(plugin(), markerId));
            mob.setRemoveWhenFarAway(true);
            // GH#30 (раунд 2, п.3) — видимое имя, чтобы отличать от случайных мобов.
            mob.setCustomName(MOB_NAME);
            mob.setCustomNameVisible(true);

            if (spec.baby() && mob instanceof Zombie zombie) zombie.setBaby(true);
            if (spec.armorChance() > 0 && ThreadLocalRandom.current().nextDouble() < spec.armorChance()) {
                equipIronArmor(mob);
            }
            if (spec.type() == EntityType.PILLAGER) {
                EntityEquipment eq = mob.getEquipment();
                if (eq != null) eq.setItemInMainHand(new ItemStack(Material.CROSSBOW));
            }
        }
    }

    /** Какая "пачка" сейчас выбрана страной — первая, чья permission-нода реально выдана (см. класс-javadoc). Null, если ничего не выбрано/не куплено. */
    private List<MobSpec> resolveActivePack(String canonicalCountry) {
        if (canonicalCountry == null) return null;
        for (Pack pack : PACKS) {
            if (UpgradeCondition.countryHasNode(canonicalCountry, pack.permission())) return pack.mobs();
        }
        return null;
    }

    private void equipIronArmor(LivingEntity mob) {
        EntityEquipment eq = mob.getEquipment();
        if (eq == null) return;
        eq.setHelmet(new ItemStack(Material.IRON_HELMET));
        eq.setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        eq.setLeggings(new ItemStack(Material.IRON_LEGGINGS));
        eq.setBoots(new ItemStack(Material.IRON_BOOTS));
    }

    /** Случайная точка внутри реальной территории зоны (rejection sampling по AABB), с фоллбеком на center при неудаче/маленьком полигоне. */
    private Location randomPointIn(ZoneInfo z, Location fallbackCenter) {
        World w = z.getWorld();
        if (w == null) return fallbackCenter;

        BoundingBox bb = z.getBoundingBoxXZ();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++) {
            double x = bb.getMinX() + rnd.nextDouble() * (bb.getMaxX() - bb.getMinX());
            double zc = bb.getMinZ() + rnd.nextDouble() * (bb.getMaxZ() - bb.getMinZ());
            int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(zc));
            Location candidate = new Location(w, x, y, zc);
            if (z.contains2D(candidate)) return candidate;
        }
        return fallbackCenter;
    }

    /** Без дропа/опыта — это защита объекта, не источник фарма (GH#24). HIGHEST — см. класс-javadoc п.6. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (e.getEntity().getMetadata(META_KEY).isEmpty()) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
    }

    // GH#30 п.3 — мобы поста атакуют только игроков страны, с которой у
    // владельца объекта реально идёт война (не просто "не свой").
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        List<org.bukkit.metadata.MetadataValue> meta = e.getEntity().getMetadata(META_KEY);
        if (meta.isEmpty()) return;

        String markerId = meta.get(0).asString();
        String guardCountry = zones().getAllZonesSnapshot().stream()
                .filter(z -> markerId.equals(z.getMarkerID()))
                .findFirst()
                .map(ZoneInfo::getCountryName)
                .orElse(null);
        if (guardCountry == null) return;

        String playerCountry = UnityLauncher.getInstance().countryRegistryJdbc.getCountryOfPlayer(p.getName());
        boolean isEnemyAtWar = playerCountry != null
                && !playerCountry.equalsIgnoreCase(guardCountry)
                && UnityLauncher.getInstance().warStatusCache.isAtWar(guardCountry, playerCountry);
        if (!isEnemyAtWar) {
            e.setCancelled(true);
        }
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        nearbyEnemiesByZone.clear();
        lastTriggerByZone.clear();
    }

    /** Тот же биомный выбор, что у DefensePatrolUpgrade (§14.2) — вынесено сюда, чтобы не тянуть зависимость между двумя upgrade-классами. */
    private static final class DefensePatrolBiomeMob {
        static EntityType forBiome(Location loc) {
            String name = loc.getBlock().getBiome().name();
            if (name.contains("DESERT")) return EntityType.HUSK;
            if (name.contains("SNOW") || name.contains("FROZEN") || name.contains("ICE")) return EntityType.STRAY;
            return Math.random() < 0.5 ? EntityType.ZOMBIE : EntityType.SKELETON;
        }
    }
}
