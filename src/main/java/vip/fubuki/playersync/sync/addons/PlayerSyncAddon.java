package vip.fubuki.playersync.sync.addons;

import net.minecraft.server.level.ServerPlayer;

public interface PlayerSyncAddon {
    String id();

    default boolean isEnabled() {
        return true;
    }

    default void initialize() throws Exception {
    }

    default void onPlayerJoin(ServerPlayer player) throws Exception {
    }

    default void onPlayerSave(ServerPlayer player) throws Exception {
    }

    default void onServerStopping() throws Exception {
    }
}
