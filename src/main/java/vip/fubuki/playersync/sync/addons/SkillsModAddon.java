package vip.fubuki.playersync.sync.addons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.PSThreadPoolFactory;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SkillsModAddon implements PlayerSyncAddon {
    private static final String TABLE_NAME = "skillsmod_data";
    private static final String SKILLS_API_CLASS = "net.puffish.skillsmod.api.SkillsAPI";

    private final Method exportMethod;
    private final Method importMethod;
    private final ExecutorService saveExecutor;

    public SkillsModAddon() {
        try {
            Class<?> skillsApiClass = Class.forName(SKILLS_API_CLASS);
            exportMethod = skillsApiClass.getMethod("exportPlayerData", ServerPlayer.class, CompoundTag.class);
            importMethod = skillsApiClass.getMethod("importPlayerData", ServerPlayer.class, CompoundTag.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve skillsmod API", e);
        }

        saveExecutor = Executors.newSingleThreadExecutor(new PSThreadPoolFactory("PlayerSync-Skills"));
    }

    @Override
    public String id() {
        return "puffish_skills";
    }

    @Override
    public void initialize() throws SQLException {
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `%s` (" +
                        "`uuid` CHAR(36) NOT NULL," +
                        "`skills_data` MEDIUMTEXT," +
                        "PRIMARY KEY (`uuid`)" +
                        ")",
                TABLE_NAME
        );
    }

    @Override
    public void onPlayerJoin(ServerPlayer player) throws Exception {
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery(
                "SELECT skills_data FROM %s WHERE uuid='%s'",
                TABLE_NAME,
                player.getUUID()
        )) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                return;
            }

            String encoded = rs.getString("skills_data");
            if (encoded == null || encoded.isBlank()) {
                return;
            }

            CompoundTag tag = TagParser.parseTag(VanillaSync.deserializeString(encoded));
            runOnServerThread(player, () -> {
                importMethod.invoke(null, player, tag);
                return null;
            });
        }
    }

    @Override
    public void onPlayerSave(ServerPlayer player) throws Exception {
        if (!player.getTags().contains("player_synced")) {
            return;
        }

        MinecraftServer server = resolveServer(player);
        if (server.isSameThread()) {
            saveExecutor.submit(() -> {
                try {
                    savePlayerData(player);
                } catch (Exception e) {
                    PlayerSync.LOGGER.error("Failed to offload skillsmod save for player {}", player.getUUID(), e);
                }
            });
            return;
        }

        savePlayerData(player);
    }

    @Override
    public void onServerStopping() {
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                saveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            saveExecutor.shutdownNow();
        }

        PlayerSync.LOGGER.info("Stopping PlayerSync addon {}", id());
    }

    private void savePlayerData(ServerPlayer player) throws Exception {
        CompoundTag tag = exportPlayerData(player);
        JDBCsetUp.update(
                "REPLACE INTO " + TABLE_NAME + " (uuid, skills_data) VALUES (?, ?)",
                player.getStringUUID(),
                VanillaSync.serialize(tag.toString())
        );
    }

    private CompoundTag exportPlayerData(ServerPlayer player) throws ExecutionException, InterruptedException {
        return runOnServerThread(player, () -> {
            CompoundTag exported = new CompoundTag();
            exportMethod.invoke(null, player, exported);
            return exported;
        });
    }

    private MinecraftServer resolveServer(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            server = ServerLifecycleHooks.getCurrentServer();
        }

        if (server == null) {
            throw new IllegalStateException("No active server found for player " + player.getUUID());
        }

        return server;
    }

    private <T> T runOnServerThread(ServerPlayer player, ThrowingSupplier<T> supplier) throws ExecutionException, InterruptedException {
        MinecraftServer server = resolveServer(player);
        if (server.isSameThread()) {
            try {
                return supplier.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        MinecraftServer finalServer = server;
        finalServer.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
