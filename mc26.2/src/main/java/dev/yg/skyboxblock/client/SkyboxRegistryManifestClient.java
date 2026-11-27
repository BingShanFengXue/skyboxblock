package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.custom.CustomSkyRegistry;
import dev.yg.skyboxblock.network.SkyboxRegistryManifestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;

import java.util.concurrent.CompletableFuture;

/** Responds to the server's login query before its registry-sync task starts. */
public final class SkyboxRegistryManifestClient {
    private static boolean registered;
    private SkyboxRegistryManifestClient() { }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientLoginNetworking.registerGlobalReceiver(SkyboxRegistryManifestPayload.CHANNEL,
                (client, handler, request, callbacks) -> {
                    try {
                        SkyboxRegistryManifestPayload.decode(request);
                        return CompletableFuture.completedFuture(
                                SkyboxRegistryManifestPayload.encode(CustomSkyRegistry.styles().stream()
                                        .map(CustomSkyRegistry.Style::id).toList()));
                    } catch (RuntimeException malformed) {
                        SkyboxBlockMod.LOGGER.warn("[skyboxblock] refusing malformed pre-registry manifest query", malformed);
                        return CompletableFuture.completedFuture(null);
                    }
                });
    }
}
