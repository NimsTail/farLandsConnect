/*package com.frammy.unitylauncher;

import com.frammy.unitylauncher.advs.AdvancementTabNamespaces;
import com.frammy.unitylauncher.advs.frameadv.*;
import com.fren_gor.ultimateAdvancementAPI.AdvancementTab;
import com.fren_gor.ultimateAdvancementAPI.UltimateAdvancementAPI;
import com.fren_gor.ultimateAdvancementAPI.advancement.Advancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.RootAdvancement;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.FancyAdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.util.AdvancementKey;
import com.fren_gor.ultimateAdvancementAPI.util.CoordAdapter;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public class AdvancementLib extends JavaPlugin {

    public static UltimateAdvancementAPI api;
    public AdvancementTab frameadv;

    public void initializeTabs() {
        api = UltimateAdvancementAPI.getInstance(this);
        frameadv = api.createAdvancementTab(AdvancementTabNamespaces.frameadv_NAMESPACE);
        AdvancementKey rootKey = new AdvancementKey(frameadv.getNamespace(), "root");
        CoordAdapter adapterframeadv = CoordAdapter.builder().add(rootKey, 0f, 0f).add(Gigant.KEY, 1f, 0f).add(Leaves.KEY, 2f, 0f).add(Glowberry.KEY, 1f, 1f).add(Wheat.KEY, 2f, 1f).add(Diorite.KEY, 1f, -1f).add(Cactus.KEY, 3f, 2f).add(Rawiron.KEY, 3f, 0f).build();
        RootAdvancement root = new RootAdvancement(frameadv, rootKey.getKey(), new FancyAdvancementDisplay(Material.PLAYER_HEAD, "Все дороги ведут..", AdvancementFrameType.TASK, true, true, adapterframeadv.getX(rootKey), adapterframeadv.getY(rootKey),"", "§eРедактируй свой профиль с", "§eпомощью этих рамок!"),"textures/block/dark_oak_trapdoor.png",1);
        Gigant gigant = new Gigant(root,adapterframeadv.getX(Gigant.KEY), adapterframeadv.getY(Gigant.KEY));
        Leaves leaves = new Leaves(gigant,adapterframeadv.getX(Leaves.KEY), adapterframeadv.getY(Leaves.KEY));
        Glowberry glowberry = new Glowberry(root,adapterframeadv.getX(Glowberry.KEY), adapterframeadv.getY(Glowberry.KEY));
        Wheat wheat = new Wheat(glowberry,adapterframeadv.getX(Wheat.KEY), adapterframeadv.getY(Wheat.KEY));
        Diorite diorite = new Diorite(root,adapterframeadv.getX(Diorite.KEY), adapterframeadv.getY(Diorite.KEY));
        Cactus cactus = new Cactus(wheat,adapterframeadv.getX(Cactus.KEY), adapterframeadv.getY(Cactus.KEY));
        Rawiron rawiron = new Rawiron(leaves,adapterframeadv.getX(Rawiron.KEY), adapterframeadv.getY(Rawiron.KEY));
        frameadv.registerAdvancements(root ,gigant ,leaves ,glowberry ,wheat ,diorite ,cactus ,rawiron );
    }


}*/
