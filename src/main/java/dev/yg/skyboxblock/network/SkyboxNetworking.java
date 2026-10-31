package dev.yg.skyboxblock.network;

import dev.yg.skyboxblock.SkyboxSettings;
import dev.yg.skyboxblock.server.SkyboxServerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common/server half only. Client receiver registration lives in the client-only state class. */
public final class SkyboxNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("skyboxblock");
    private static boolean registered;

    private SkyboxNetworking() { }

    public static synchronized void register() {
        if (registered) return;
        PayloadTypeRegistry.playS2C().register(SkyboxConfigPayload.TYPE, SkyboxConfigPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(SkyboxServerConfig::get);
        ServerLifecycleEvents.SERVER_STOPPED.register(SkyboxServerConfig::release);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                send(handler.player, SkyboxServerConfig.get(server).settings()));
        registered = true;
        // Deliberately NO playC2S registration: mutations use the OP2 server command tree.
    }

    public static void send(ServerPlayer player, SkyboxSettings settings) {
        try {
            if (ServerPlayNetworking.canSend(player, SkyboxConfigPayload.TYPE))
                ServerPlayNetworking.send(player, new SkyboxConfigPayload(settings));
        } catch (RuntimeException failure) {
            // A failed individual connection must not roll back a committed server file or stop other recipients.
            LOGGER.warn("[skyboxblock] settings sync failed for {}", player.getUUID(), failure);
        }
    }

    /** Called only after an atomic persistence commit or successful explicit reload. */
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