package dev.yg.skyboxblock.network;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyboxSettings;
import dev.yg.skyboxblock.server.SkyboxServerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/** Server networking for pre-registry ID validation, authoritative settings, and self-only toggle requests. */
public final class SkyboxNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("skyboxblock");
    private static boolean registered;

    private SkyboxNetworking() { }

    public static synchronized void register() {
        if (registered) return;
        PayloadTypeRegistry.clientboundPlay().register(SkyboxConfigPayload.TYPE, SkyboxConfigPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SkyboxRenderTogglePayload.TYPE, SkyboxRenderTogglePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SkyboxRenderToggleResultPayload.TYPE,
                SkyboxRenderToggleResultPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(SkyboxServerConfig::get);
        ServerLifecycleEvents.SERVER_STOPPED.register(SkyboxServerConfig::release);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                send(handler.player, SkyboxServerConfig.get(server).forSend(handler.player.getUUID())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SkyboxRenderToggleRequestGate.disconnect(handler.player.getUUID(), handler));
        ServerPlayNetworking.registerGlobalReceiver(SkyboxRenderTogglePayload.TYPE, (payload, context) ->
                receiveToggle(payload, context.player(), context.server()));
        registerRegistryManifestHandshake();
        registered = true;
    }

    /** Login is earlier than every configuration task, including Fabric's registry-sync early task. */
    private static void registerRegistryManifestHandshake() {
        ServerLoginNetworking.registerGlobalReceiver(SkyboxRegistryManifestPayload.CHANNEL,
                (server, listener, understood, buffer, synchronizer, responseSender) -> {
                    if (!understood) {
                        List<String> serverIds = SkyboxRegistryManifestPayload.localIds();
                        if (!serverIds.isEmpty()) {
                            String report = SkyboxRegistryManifestPayload.unsupportedClientReport(serverIds);
                            synchronizer.waitFor(server.submit(() -> listener.disconnect(Component.literal(report))));
                        }
                        return;
                    }
                    final List<String> clientIds;
                    try {
                        clientIds = SkyboxRegistryManifestPayload.decode(buffer);
                    } catch (RuntimeException malformed) {
                        synchronizer.waitFor(server.submit(() -> listener.disconnect(Component.literal(
                                "[Sky Box Block] Invalid custom registry manifest response; connection stopped before registry sync."))));
                        LOGGER.warn("[skyboxblock] rejected malformed login manifest", malformed);
                        return;
                    }
                    synchronizer.waitFor(server.submit(() -> {
                        String report = SkyboxRegistryManifestPayload.mismatchReport(
                                SkyboxRegistryManifestPayload.localIds(), clientIds);
                        if (report != null) {
                            LOGGER.warn("[skyboxblock] {}", report);
                            listener.disconnect(Component.literal(report));
                        }
                    }));
                });
        ServerLoginConnectionEvents.QUERY_START.register((listener, server, sender, synchronizer) -> {
            List<String> serverIds = SkyboxRegistryManifestPayload.localIds();
            sender.sendPacket(SkyboxRegistryManifestPayload.CHANNEL,
                    SkyboxRegistryManifestPayload.encode(serverIds));
        });
    }

    private static void receiveToggle(SkyboxRenderTogglePayload payload, ServerPlayer player, MinecraftServer server) {
        final UUID playerId = player.getUUID();
        final Object connection = player.connection;
        server.execute(() -> {
            boolean currentConnection = server.getPlayerList().getPlayer(playerId) == player
                    && player.connection == connection;
            if (!currentConnection) return;

            var store = SkyboxServerConfig.get(server);
            boolean actualVisible = store.settingsFor(playerId).environment().rendersSkybox();
            var decision = SkyboxRenderToggleRequestGate.evaluate(playerId, connection, true,
                    payload.requestId(), payload.expectedVisible(), payload.desiredVisible(), actualVisible,
                    System.nanoTime());
            SkyboxRenderToggleResultPayload.Status status;
            if (decision != SkyboxRenderToggleRequestGate.Decision.ACCEPT) {
                status = resultStatus(decision);
            } else {
                try {
                    store.setPlayerEnvironment(playerId, SkyEnvironment.RENDER_SKYBOX.id(),
                            Boolean.toString(payload.desiredVisible()));
                    status = SkyboxRenderToggleResultPayload.Status.APPLIED;
                } catch (java.io.IOException | IllegalArgumentException failure) {
                    LOGGER.warn("[skyboxblock] self visibility update failed for {}", playerId, failure);
                    status = SkyboxRenderToggleResultPayload.Status.STORAGE_FAILURE;
                }
            }

            if (status != SkyboxRenderToggleResultPayload.Status.APPLIED)
                send(player, store.forSend(playerId));
            sendToggleResult(player, payload.requestId(), status);
        });
    }

    private static SkyboxRenderToggleResultPayload.Status resultStatus(SkyboxRenderToggleRequestGate.Decision decision) {
        return switch (decision) {
            case ACCEPT -> SkyboxRenderToggleResultPayload.Status.APPLIED;
            case STALE_CONNECTION -> SkyboxRenderToggleResultPayload.Status.STALE_CONNECTION;
            case DUPLICATE -> SkyboxRenderToggleResultPayload.Status.DUPLICATE;
            case STALE_REQUEST -> SkyboxRenderToggleResultPayload.Status.STALE_REQUEST;
            case RATE_LIMITED -> SkyboxRenderToggleResultPayload.Status.RATE_LIMITED;
            case INVALID_REQUEST -> SkyboxRenderToggleResultPayload.Status.INVALID_REQUEST;
            case STALE_STATE -> SkyboxRenderToggleResultPayload.Status.STALE_STATE;
        };
    }

    private static void sendToggleResult(ServerPlayer player, long requestId,
                                          SkyboxRenderToggleResultPayload.Status status) {
        try {
            if (ServerPlayNetworking.canSend(player, SkyboxRenderToggleResultPayload.TYPE))
                ServerPlayNetworking.send(player, new SkyboxRenderToggleResultPayload(requestId, status));
        } catch (RuntimeException failure) {
            LOGGER.warn("[skyboxblock] self visibility acknowledgement failed for {}", player.getUUID(), failure);
        }
    }

    public static void send(ServerPlayer player, SkyboxSettings settings) {
        try {
            if (ServerPlayNetworking.canSend(player, SkyboxConfigPayload.TYPE))
                ServerPlayNetworking.send(player, new SkyboxConfigPayload(settings));
        } catch (RuntimeException failure) {
            LOGGER.warn("[skyboxblock] settings sync failed for {}", player.getUUID(), failure);
        }
    }

    public static void broadcast(MinecraftServer server, SkyboxSettings settings) {
        try {
            server.execute(() -> {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) send(player, settings);
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("[skyboxblock] committed settings broadcast could not be scheduled", failure);
        }
    }
}
