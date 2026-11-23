package com.frammy.unitylauncher.advs;

import com.frammy.unitylauncher.UnityLauncher;

/**
 * Grants a profile-wall frame reward to a player. The site's Postgres
 * (Frame/PlayerFrame) is the only place ownership is actually tracked and
 * displayed/equippable — this used to write into the plugin's own MySQL
 * `Frames.Users` CSV column instead, which the site never read, so a
 * "granted" frame never showed up anywhere. Mirrors the same fire-and-forget
 * pattern as FarLandsApiClient.transaction()/lastSeen() etc.
 */
public final class FramesDao {
    private FramesDao() {}

    public static void addUserToFrame(int frameId, String nick) {
        UnityLauncher.getInstance().getFarLandsApi().grantFrame(nick, frameId);
    }
}
