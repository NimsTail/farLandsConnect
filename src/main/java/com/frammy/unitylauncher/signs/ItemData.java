package com.frammy.unitylauncher.signs;

import org.bukkit.Location;

public class ItemData {
     Location chestLocation;
     String name;
     Integer quantity;
     Integer overallQuantity;
     Double price;

     public ItemData(Location chestLocation, String name, Integer quantity, Integer overallQuantity, Double price) {
         this.chestLocation = chestLocation;
         this.name = name;
         this.quantity = quantity;
         this.overallQuantity = overallQuantity;
         this.price = price;
     }
}
