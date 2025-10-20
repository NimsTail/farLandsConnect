package com.frammy.unitylauncher;

import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;
import com.frammy.unitylauncher.advs.achievements.*;
import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class AdvancementsManager implements Listener {

    private final JavaPlugin plugin;
    private AdvancementTab achievements;
    private static final String ROOT_BG = "textures/block/stone.png";

    public AdvancementsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        UltimateAdvancementAPI api = UltimateAdvancementAPI.getInstance(plugin);
        achievements = api.createAdvancementTab(AdvancementTabNamespaces.achievements_NAMESPACE);

        RootAdvancement ach0 = new RootAdvancement(
                achievements,
                "ach0",
                new FancyAdvancementDisplay(
                        Material.GRASS_BLOCK, "Начало",
                        AdvancementFrameType.TASK, true, true,
                        0f, 0f, "",
                        "Впервые зайти на сервер", ""
                ),
                ROOT_BG,
                1
        );

        Ach1 ach1 = new Ach1(ach0);
        Ach1_1 ach1_1 = new Ach1_1(ach0);
        Ach1_2 ach1_2 = new Ach1_2(ach0);
        Ach1_2_1 ach1_2_1 = new Ach1_2_1(ach0);
        Ach1_2_2 ach1_2_2 = new Ach1_2_2(ach0);
        Ach1_2_3 ach1_2_3 = new Ach1_2_3(ach0);
        Ach1_3 ach1_3 = new Ach1_3(ach0);
        Ach1_4 ach1_4 = new Ach1_4(ach0);
        Ach1_4_1 ach1_4_1 = new Ach1_4_1(ach0);
        Ach1_5 ach1_5 = new Ach1_5(ach0);
        Ach1_6 ach1_6 = new Ach1_6(ach0);
        Ach1_6_1 ach1_6_1 = new Ach1_6_1(ach0);
        Ach1_6_2 ach1_6_2 = new Ach1_6_2(ach0);
        Ach1_1_1 ach1_1_1 = new Ach1_1_1(ach0);
        Ach1_7 ach1_7 = new Ach1_7(ach0);
        Ach1_8 ach1_8 = new Ach1_8(ach0);
        Oak_sapling28 oak_sapling28 = new Oak_sapling28(ach0);
        Oak_sapling29 oak_sapling29 = new Oak_sapling29(ach0);
        Oak_sapling30 oak_sapling30 = new Oak_sapling30(ach0);
        Oak_sapling31 oak_sapling31 = new Oak_sapling31(ach0);
        Oak_sapling32 oak_sapling32 = new Oak_sapling32(ach0);
        Oak_sapling33 oak_sapling33 = new Oak_sapling33(ach0);
        Oak_sapling34 oak_sapling34 = new Oak_sapling34(ach0);
        Dried_kelp35 dried_kelp35 = new Dried_kelp35(ach0);
        Oak_sapling36 oak_sapling36 = new Oak_sapling36(ach0);
        Mossy_cobblestone37 mossy_cobblestone37 = new Mossy_cobblestone37(ach0);
        Oak_sapling38 oak_sapling38 = new Oak_sapling38(ach0);
        Oak_sapling39 oak_sapling39 = new Oak_sapling39(ach0);
        Oak_sapling40 oak_sapling40 = new Oak_sapling40(ach0);
        Oak_sapling41 oak_sapling41 = new Oak_sapling41(ach0);
        Oak_sapling42 oak_sapling42 = new Oak_sapling42(ach0);
        Oak_sapling43 oak_sapling43 = new Oak_sapling43(ach0);
        Oak_sapling44 oak_sapling44 = new Oak_sapling44(ach0);
        Oak_sapling45 oak_sapling45 = new Oak_sapling45(ach0);
        Raw_gold_block46 raw_gold_block46 = new Raw_gold_block46(ach0);
        Oak_sapling47 oak_sapling47 = new Oak_sapling47(ach0);
        Oak_sapling48 oak_sapling48 = new Oak_sapling48(ach0);
        Oak_sapling49 oak_sapling49 = new Oak_sapling49(ach0);
        Water_bucket50 water_bucket50 = new Water_bucket50(ach0);
        Tropical_fish51 tropical_fish51 = new Tropical_fish51(ach0);
        Oak_sapling52 oak_sapling52 = new Oak_sapling52(ach0);
        Oak_sapling53 oak_sapling53 = new Oak_sapling53(ach0);
        Tnt54 tnt54 = new Tnt54(ach0);
        Lodestone55 lodestone55 = new Lodestone55(ach0);
        Netherite_scrap56 netherite_scrap56 = new Netherite_scrap56(ach0);
        Gilded_blackstone57 gilded_blackstone57 = new Gilded_blackstone57(ach0);
        Shroomlight58 shroomlight58 = new Shroomlight58(ach0);
        Glowstone59 glowstone59 = new Glowstone59(ach0);
        Obsidian60 obsidian60 = new Obsidian60(ach0);
        Soul_sand61 soul_sand61 = new Soul_sand61(ach0);
        Feather62 feather62 = new Feather62(ach0);
        Trident63 trident63 = new Trident63(ach0);
        Diamond_sword64 diamond_sword64 = new Diamond_sword64(ach0);
        Snowball65 snowball65 = new Snowball65(ach0);
        Shield66 shield66 = new Shield66(ach0);
        Golden_helmet67 golden_helmet67 = new Golden_helmet67(ach0);
        Sculk68 sculk68 = new Sculk68(ach0);
        Oak_sapling69 oak_sapling69 = new Oak_sapling69(ach0);
        Oak_sapling70 oak_sapling70 = new Oak_sapling70(ach0);
        Oak_sapling71 oak_sapling71 = new Oak_sapling71(ach0);
        Oak_sapling72 oak_sapling72 = new Oak_sapling72(ach0);
        Verdant_froglight73 verdant_froglight73 = new Verdant_froglight73(ach0);
        Pearlescent_froglight74 pearlescent_froglight74 = new Pearlescent_froglight74(ach0);
        Oak_sapling77 oak_sapling77 = new Oak_sapling77(ach0);
        Oak_sapling78 oak_sapling78 = new Oak_sapling78(ach0);
        Diamond79 diamond79 = new Diamond79(ach0);
        Oak_sapling80 oak_sapling80 = new Oak_sapling80(ach0);
        Oak_sapling81 oak_sapling81 = new Oak_sapling81(ach0);
        Oak_sapling82 oak_sapling82 = new Oak_sapling82(ach0);
        Oak_sapling84 oak_sapling84 = new Oak_sapling84(ach0);
        Dragon_head85 dragon_head85 = new Dragon_head85(ach0);
        Oak_sapling86 oak_sapling86 = new Oak_sapling86(ach0);
        Oak_sapling87 oak_sapling87 = new Oak_sapling87(ach0);
        Oak_sapling88 oak_sapling88 = new Oak_sapling88(ach0);
        Oak_sapling89 oak_sapling89 = new Oak_sapling89(ach0);
        Oak_sapling90 oak_sapling90 = new Oak_sapling90(ach0);
        Oak_sapling91 oak_sapling91 = new Oak_sapling91(ach0);
        Oak_sapling92 oak_sapling92 = new Oak_sapling92(ach0);
        Oak_sapling93 oak_sapling93 = new Oak_sapling93(ach0);
        Oak_sapling94 oak_sapling94 = new Oak_sapling94(ach0);
        Oak_sapling95 oak_sapling95 = new Oak_sapling95(ach0);

        achievements.registerAdvancements(
                ach0, ach1, ach1_1, ach1_2, ach1_2_1, ach1_2_2, ach1_2_3,
                ach1_3, ach1_4, ach1_4_1, ach1_5, ach1_6, ach1_6_1, ach1_6_2,
                ach1_1_1, ach1_7, ach1_8, oak_sapling28, oak_sapling29, oak_sapling30,
                oak_sapling31, oak_sapling32, oak_sapling33, oak_sapling34, dried_kelp35,
                oak_sapling36, mossy_cobblestone37, oak_sapling38, oak_sapling39,
                oak_sapling40, oak_sapling41, oak_sapling42, oak_sapling43,
                oak_sapling44, oak_sapling45, raw_gold_block46, oak_sapling47,
                oak_sapling48, oak_sapling49, water_bucket50, tropical_fish51,
                oak_sapling52, oak_sapling53, tnt54, lodestone55, netherite_scrap56,
                gilded_blackstone57, shroomlight58, glowstone59, obsidian60,
                soul_sand61, feather62, trident63, diamond_sword64, snowball65,
                shield66, golden_helmet67, sculk68, oak_sapling69, oak_sapling70,
                oak_sapling71, oak_sapling72, verdant_froglight73,
                pearlescent_froglight74, oak_sapling77, oak_sapling78, diamond79,
                oak_sapling80, oak_sapling81, oak_sapling82, oak_sapling84,
                dragon_head85, oak_sapling86, oak_sapling87, oak_sapling88,
                oak_sapling89, oak_sapling90, oak_sapling91, oak_sapling92,
                oak_sapling93, oak_sapling94, oak_sapling95
        );

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    achievements.showTab(p);
                } catch (Throwable ex) {
                    plugin.getLogger().warning("[Advancements] showTab failed for " + p.getName() + ": " + ex.getMessage());
                }
            }
        });

        Bukkit.getPluginManager().registerEvents(this, plugin);

        plugin.getLogger().info("[Advancements] Таб '" +
                AdvancementTabNamespaces.achievements_NAMESPACE + "' инициализирован.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        try {
            achievements.showTab(e.getPlayer());
        } catch (Throwable ex) {
            plugin.getLogger().warning("[Advancements] showTab on join failed for " + e.getPlayer().getName() + ": " + ex.getMessage());
        }
    }
}
