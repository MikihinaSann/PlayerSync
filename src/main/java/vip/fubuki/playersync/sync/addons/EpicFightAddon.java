package vip.fubuki.playersync.sync.addons;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.server.ServerLifecycleHooks;
import vip.fubuki.playersync.PlayerSync;
import vip.fubuki.playersync.sync.VanillaSync;
import vip.fubuki.playersync.util.JDBCsetUp;
import vip.fubuki.playersync.util.PSThreadPoolFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class EpicFightAddon implements PlayerSyncAddon {
    private static final String TABLE_NAME = "epicfight_data";
    private static final String EPIC_FIGHT_CAPABILITIES_CLASS = "yesman.epicfight.world.capabilities.EpicFightCapabilities";
    private static final String EPIC_FIGHT_SKILL_CLASS = "yesman.epicfight.world.capabilities.skill.CapabilitySkill";
    private static final String EPIC_FIGHT_NETWORK_MANAGER_CLASS = "yesman.epicfight.network.EpicFightNetworkManager";
    private static final String EPIC_FIGHT_INIT_PACKET_CLASS = "yesman.epicfight.network.server.SPInitSkills";
    private static final String CAPABILITY_SKILL_FIELD = "CAPABILITY_SKILL";
    private static final String PLAYER_MODE_KEY = "playerMode";

    private final Capability<?> skillCapability;
    private final Method serializeMethod;
    private final Method deserializeMethod;
    private final Method clearContainersMethod;
    private final Method sendToPlayerMethod;
    private final java.lang.reflect.Constructor<?> initSkillsPacketConstructor;
    private final ExecutorService saveExecutor;

    public EpicFightAddon() {
        try {
            Class<?> capabilitiesClass = Class.forName(EPIC_FIGHT_CAPABILITIES_CLASS);
            Class<?> skillClass = Class.forName(EPIC_FIGHT_SKILL_CLASS);
            Class<?> networkManagerClass = Class.forName(EPIC_FIGHT_NETWORK_MANAGER_CLASS);
            Class<?> initPacketClass = Class.forName(EPIC_FIGHT_INIT_PACKET_CLASS);
            Field capabilityField = capabilitiesClass.getField(CAPABILITY_SKILL_FIELD);

            this.skillCapability = (Capability<?>) capabilityField.get(null);
            this.serializeMethod = skillClass.getMethod("serialize");
            this.deserializeMethod = skillClass.getMethod("deserialize", CompoundTag.class);
            this.clearContainersMethod = skillClass.getMethod("clearContainersAndLearnedSkills", boolean.class);
            this.sendToPlayerMethod = networkManagerClass.getMethod("sendToPlayer", Object.class, ServerPlayer.class, Object[].class);
            this.initSkillsPacketConstructor = initPacketClass.getConstructor(CompoundTag.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to resolve Epic Fight capability API", e);
        }

        this.saveExecutor = Executors.newSingleThreadExecutor(new PSThreadPoolFactory("PlayerSync-EpicFight"));
    }

    @Override
    public String id() {
        return "epicfight";
    }

    @Override
    public void initialize() throws SQLException {
        JDBCsetUp.executeUpdate(
                "CREATE TABLE IF NOT EXISTS `%s` (" +
                        "`uuid` CHAR(36) NOT NULL," +
                        "`skill_data` MEDIUMTEXT," +
                        "PRIMARY KEY (`uuid`)" +
                        ")",
                TABLE_NAME
        );
    }

    @Override
    public void onPlayerJoin(ServerPlayer player) throws Exception {
        try (JDBCsetUp.QueryResult qr = JDBCsetUp.executeQuery(
                "SELECT skill_data FROM %s WHERE uuid='%s'",
                TABLE_NAME,
                player.getUUID()
        )) {
            ResultSet rs = qr.resultSet();
            if (!rs.next()) {
                return;
            }

            String encoded = rs.getString("skill_data");
            if (encoded == null || encoded.isBlank()) {
                return;
            }

            CompoundTag tag = TagParser.parseTag(VanillaSync.deserializeString(encoded));
            tag.remove(PLAYER_MODE_KEY);

            runOnServerThread(player, () -> {
                Object skillData = getSkillCapabilityData(player);
                if (skillData == null) {
                    PlayerSync.LOGGER.warn("Epic Fight skill capability missing for player {} during restore", player.getUUID());
                    return null;
                }

                clearContainersMethod.invoke(skillData, true);
                deserializeMethod.invoke(skillData, tag);
                syncClientSkillState(player, tag);
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
                    PlayerSync.LOGGER.error("Failed to offload Epic Fight save for player {}", player.getUUID(), e);
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
        if (tag == null) {
            return;
        }

        tag.remove(PLAYER_MODE_KEY);
        JDBCsetUp.update(
                "REPLACE INTO " + TABLE_NAME + " (uuid, skill_data) VALUES (?, ?)",
                player.getStringUUID(),
                VanillaSync.serialize(tag.toString())
        );
    }

    private CompoundTag exportPlayerData(ServerPlayer player) throws ExecutionException, InterruptedException {
        return runOnServerThread(player, () -> {
            Object skillData = getSkillCapabilityData(player);
            if (skillData == null) {
                PlayerSync.LOGGER.warn("Epic Fight skill capability missing for player {} during save", player.getUUID());
                return null;
            }

            return (CompoundTag) serializeMethod.invoke(skillData);
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object getSkillCapabilityData(ServerPlayer player) {
        LazyOptional capabilityOptional = player.getCapability((Capability) this.skillCapability);
        return capabilityOptional.orElse(null);
    }

    private void syncClientSkillState(ServerPlayer player, CompoundTag tag) throws ReflectiveOperationException {
        Object packet = this.initSkillsPacketConstructor.newInstance(tag.copy());
        this.sendToPlayerMethod.invoke(null, packet, player, new Object[0]);
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
