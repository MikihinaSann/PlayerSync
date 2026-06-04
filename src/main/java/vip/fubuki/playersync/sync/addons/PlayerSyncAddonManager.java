package vip.fubuki.playersync.sync.addons;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.config.JdbcConfig;

import java.util.ArrayList;
import java.util.List;

public final class PlayerSyncAddonManager {
    private static final List<PlayerSyncAddon> ADDONS = new ArrayList<>();
    private static boolean initialized = false;

    private PlayerSyncAddonManager() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        initialized = true;
        registerBuiltinAddons();

        for (PlayerSyncAddon addon : ADDONS) {
            if (!addon.isEnabled()) {
                continue;
            }

            try {
                addon.initialize();
                PlayerSync.LOGGER.info("Initialized PlayerSync addon {}", addon.id());
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Failed to initialize PlayerSync addon {}", addon.id(), e);
            }
        }
    }

    public static void onPlayerJoin(ServerPlayer player) {
        for (PlayerSyncAddon addon : ADDONS) {
            if (!addon.isEnabled()) {
                continue;
            }

            try {
                addon.onPlayerJoin(player);
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Failed to restore addon {} for player {}", addon.id(), player.getUUID(), e);
            }
        }
    }

    public static void onPlayerSave(ServerPlayer player) {
        for (PlayerSyncAddon addon : ADDONS) {
            if (!addon.isEnabled()) {
                continue;
            }

            try {
                addon.onPlayerSave(player);
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Failed to save addon {} for player {}", addon.id(), player.getUUID(), e);
            }
        }
    }

    public static void onServerStopping() {
        for (PlayerSyncAddon addon : ADDONS) {
            if (!addon.isEnabled()) {
                continue;
            }

            try {
                addon.onServerStopping();
            } catch (Exception e) {
                PlayerSync.LOGGER.error("Failed to stop addon {}", addon.id(), e);
            }
        }
    }

    private static void registerBuiltinAddons() {
        if (ModList.get().isLoaded("puffish_skills") && JdbcConfig.SYNC_SKILLSMOD.get()) {
            ADDONS.add(new SkillsModAddon());
        }
    }
}
