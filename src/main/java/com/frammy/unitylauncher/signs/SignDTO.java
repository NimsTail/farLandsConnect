package com.frammy.unitylauncher.signs;

public record SignDTO(
        String world,      // world name
        int x, int y, int z,
        SignCategory category,
        String ownerName,  // можно null
        String label       // можно null; если есть в файле
) {}
