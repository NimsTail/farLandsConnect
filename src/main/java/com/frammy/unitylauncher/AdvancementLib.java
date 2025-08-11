package com.frammy.unitylauncher;

import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;
import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class AdvancementLib extends JavaPlugin {

    public static UltimateAdvancementAPI api;
    public AdvancementTab achievements;

    public void initializeTabs() {
        api = UltimateAdvancementAPI.getInstance(this);
        achievements = api.createAdvancementTab(AdvancementTabNamespaces.achievements_NAMESPACE);
        RootAdvancement ach0 = new RootAdvancement(achievements, "ach0", new FancyAdvancementDisplay(Material.GRASS_BLOCK, "Начало", AdvancementFrameType.TASK, true, true, 0f, 0f ,"", "Впервые зайти на сервер", ""),"textures/block/magenta_terracotta.png",1);
        Ach1 ach1 = new Ach1(ach0);
        Ach1.1 ach1.1 = new Ach1.1(ach0);
        Ach1.2 ach1.2 = new Ach1.2(ach0);
        Ach1.2.1 ach1.2.1 = new Ach1.2.1(ach1.1);
        Ach1.2.2 ach1.2.2 = new Ach1.2.2(ach1.1);
        Ach1.2.3 ach1.2.3 = new Ach1.2.3(ach1.1);
        Ach1.3 ach1.3 = new Ach1.3(ach0);
        Ach1.4 ach1.4 = new Ach1.4(ach0);
        Ach1.4.1 ach1.4.1 = new Ach1.4.1(ach1.3);
        Ach1.5 ach1.5 = new Ach1.5(ach0);
        Ach1.6 ach1.6 = new Ach1.6(ach0);
        Ach1.6.1 ach1.6.1 = new Ach1.6.1(ach1.5);
        Ach1.6.2 ach1.6.2 = new Ach1.6.2(ach1.5);
        Ach1.1.1 ach1.1.1 = new Ach1.1.1(ach1);
        Ach1.7 ach1.7 = new Ach1.7(ach1.6);
        Ach1.8 ach1.8 = new Ach1.8(ach1.7);
        Oak_sapling28 oak_sapling28 = new Oak_sapling28(ach1.2);
        Oak_sapling29 oak_sapling29 = new Oak_sapling29(ach1.2);
        Oak_sapling30 oak_sapling30 = new Oak_sapling30(ach1);
        Oak_sapling31 oak_sapling31 = new Oak_sapling31(ach1);
        Oak_sapling32 oak_sapling32 = new Oak_sapling32(ach1.8);
        Oak_sapling33 oak_sapling33 = new Oak_sapling33(ach1.8);
        Oak_sapling34 oak_sapling34 = new Oak_sapling34(ach1.8);
        Dried_kelp35 dried_kelp35 = new Dried_kelp35(ach1.8);
        Oak_sapling36 oak_sapling36 = new Oak_sapling36(ach1.6);
        Mossy_cobblestone37 mossy_cobblestone37 = new Mossy_cobblestone37(oak_sapling32);
        Oak_sapling38 oak_sapling38 = new Oak_sapling38(ach1.5);
        Oak_sapling39 oak_sapling39 = new Oak_sapling39(ach1.5);
        Oak_sapling40 oak_sapling40 = new Oak_sapling40(ach1.5);
        Oak_sapling41 oak_sapling41 = new Oak_sapling41(ach1.5);
        Oak_sapling42 oak_sapling42 = new Oak_sapling42(ach1.3);
        Oak_sapling43 oak_sapling43 = new Oak_sapling43(ach1.5);
        Oak_sapling44 oak_sapling44 = new Oak_sapling44(ach1.5);
        Oak_sapling45 oak_sapling45 = new Oak_sapling45(mossy_cobblestone37);
        Raw_gold_block46 raw_gold_block46 = new Raw_gold_block46(mossy_cobblestone37);
        Oak_sapling47 oak_sapling47 = new Oak_sapling47(mossy_cobblestone37);
        Oak_sapling48 oak_sapling48 = new Oak_sapling48(ach1.7);
        Oak_sapling49 oak_sapling49 = new Oak_sapling49(ach1.4);
        Water_bucket50 water_bucket50 = new Water_bucket50(ach1.4);
        Tropical_fish51 tropical_fish51 = new Tropical_fish51(ach1.4);
        Oak_sapling52 oak_sapling52 = new Oak_sapling52(oak_sapling45);
        Oak_sapling53 oak_sapling53 = new Oak_sapling53(oak_sapling52);
        Tnt54 tnt54 = new Tnt54(oak_sapling52);
        Lodestone55 lodestone55 = new Lodestone55(oak_sapling53);
        Netherite_scrap56 netherite_scrap56 = new Netherite_scrap56(oak_sapling53);
        Gilded_blackstone57 gilded_blackstone57 = new Gilded_blackstone57(oak_sapling53);
        Shroomlight58 shroomlight58 = new Shroomlight58(oak_sapling53);
        Glowstone59 glowstone59 = new Glowstone59(oak_sapling53);
        Obsidian60 obsidian60 = new Obsidian60(oak_sapling53);
        Soul_sand61 soul_sand61 = new Soul_sand61(lodestone55);
        Feather62 feather62 = new Feather62(lodestone55);
        Trident63 trident63 = new Trident63(lodestone55);
        Diamond_sword64 diamond_sword64 = new Diamond_sword64(lodestone55);
        Snowball65 snowball65 = new Snowball65(lodestone55);
        Shield66 shield66 = new Shield66(lodestone55);
        Golden_helmet67 golden_helmet67 = new Golden_helmet67(lodestone55);
        Sculk68 sculk68 = new Sculk68(soul_sand61);
        Oak_sapling69 oak_sapling69 = new Oak_sapling69(soul_sand61);
        Oak_sapling70 oak_sapling70 = new Oak_sapling70(sculk68);
        Oak_sapling71 oak_sapling71 = new Oak_sapling71(oak_sapling70);
        Oak_sapling72 oak_sapling72 = new Oak_sapling72(oak_sapling70);
        Verdant_froglight73 verdant_froglight73 = new Verdant_froglight73(oak_sapling71);
        Pearlescent_froglight74 pearlescent_froglight74 = new Pearlescent_froglight74(oak_sapling71);
        Oak_sapling77 oak_sapling77 = new Oak_sapling77(oak_sapling71);
        Oak_sapling78 oak_sapling78 = new Oak_sapling78(verdant_froglight73);
        Diamond79 diamond79 = new Diamond79(verdant_froglight73);
        Oak_sapling80 oak_sapling80 = new Oak_sapling80(verdant_froglight73);
        Oak_sapling81 oak_sapling81 = new Oak_sapling81(verdant_froglight73);
        Oak_sapling82 oak_sapling82 = new Oak_sapling82(verdant_froglight73);
        Oak_sapling84 oak_sapling84 = new Oak_sapling84(oak_sapling78);
        Dragon_head85 dragon_head85 = new Dragon_head85(oak_sapling78);
        Oak_sapling86 oak_sapling86 = new Oak_sapling86(oak_sapling78);
        Oak_sapling87 oak_sapling87 = new Oak_sapling87(oak_sapling84);
        Oak_sapling88 oak_sapling88 = new Oak_sapling88(oak_sapling84);
        Oak_sapling89 oak_sapling89 = new Oak_sapling89(oak_sapling87);
        Oak_sapling90 oak_sapling90 = new Oak_sapling90(oak_sapling87);
        Oak_sapling91 oak_sapling91 = new Oak_sapling91(ach0);
        Oak_sapling92 oak_sapling92 = new Oak_sapling92(ach0);
        Oak_sapling93 oak_sapling93 = new Oak_sapling93(ach0);
        Oak_sapling94 oak_sapling94 = new Oak_sapling94(ach0);
        Oak_sapling95 oak_sapling95 = new Oak_sapling95(oak_sapling53);
        achievements.registerAdvancements(ach0 ,ach1 ,ach1.1 ,ach1.2 ,ach1.2.1 ,ach1.2.2 ,ach1.2.3 ,ach1.3 ,ach1.4 ,ach1.4.1 ,ach1.5 ,ach1.6 ,ach1.6.1 ,ach1.6.2 ,ach1.1.1 ,ach1.7 ,ach1.8 ,oak_sapling28 ,oak_sapling29 ,oak_sapling30 ,oak_sapling31 ,oak_sapling32 ,oak_sapling33 ,oak_sapling34 ,dried_kelp35 ,oak_sapling36 ,mossy_cobblestone37 ,oak_sapling38 ,oak_sapling39 ,oak_sapling40 ,oak_sapling41 ,oak_sapling42 ,oak_sapling43 ,oak_sapling44 ,oak_sapling45 ,raw_gold_block46 ,oak_sapling47 ,oak_sapling48 ,oak_sapling49 ,water_bucket50 ,tropical_fish51 ,oak_sapling52 ,oak_sapling53 ,tnt54 ,lodestone55 ,netherite_scrap56 ,gilded_blackstone57 ,shroomlight58 ,glowstone59 ,obsidian60 ,soul_sand61 ,feather62 ,trident63 ,diamond_sword64 ,snowball65 ,shield66 ,golden_helmet67 ,sculk68 ,oak_sapling69 ,oak_sapling70 ,oak_sapling71 ,oak_sapling72 ,verdant_froglight73 ,pearlescent_froglight74 ,oak_sapling77 ,oak_sapling78 ,diamond79 ,oak_sapling80 ,oak_sapling81 ,oak_sapling82 ,oak_sapling84 ,dragon_head85 ,oak_sapling86 ,oak_sapling87 ,oak_sapling88 ,oak_sapling89 ,oak_sapling90 ,oak_sapling91 ,oak_sapling92 ,oak_sapling93 ,oak_sapling94 ,oak_sapling95 );
    }


}
