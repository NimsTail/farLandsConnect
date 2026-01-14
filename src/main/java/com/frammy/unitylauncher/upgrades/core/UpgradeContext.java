package com.frammy.unitylauncher.upgrades.core;

import com.frammy.unitylauncher.UnityLauncher;
import com.frammy.unitylauncher.upgrades.config.UpgradesCfg;
import com.frammy.unitylauncher.zones.ZoneManager;

public record UpgradeContext(UnityLauncher plugin, ZoneManager zoneManager, UpgradesCfg config) {}
