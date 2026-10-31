package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxSettings;
import dev.yg.skyboxblock.network.SkyboxConfigPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Objects;

/** Client cache only: no local properties/JVM option may override an active server snapshot. */
public final class SkyboxServerState {
    private static volatile boolean enforced;
    private static volatile SkyboxSettings snapshot = SkyboxSettings.DEFAULT;
    private static Object connection;
    private static long epoch;
    private static long lastRevision = -1L;
    private static boolean registered;

    private SkyboxServerState() { }

    public static boolean enforced() { return enforced; }
    public static SkyboxSettings get() { return snapshot; }

    /** Invoked on the client thread; stale snapshots never roll back a live connection. */
    public static synchronized void accept(SkyboxSettings settings) {
        Objects.requireNonNull(settings, "server settings");
        if (!enforced || settings.revision() <= lastRevision) return;
        snapshot = settings;
        lastRevision = settings.revision();
        SkyVanillaClouds.invalidate();
    }

    private static synchronized void begin(Object handler) {
        connection = handler;
        epoch++;
        snapshot = SkyboxSettings.DEFAULT;
        lastRevision = -1L;
        enforced = true;
        SkyVanillaClouds.invalidate();
    }

    private static synchronized void end(Object handler) {
        if (connection != handler) return;
        connection = null;
        epoch++;
        snapshot = SkyboxSettings.DEFAULT;
        lastRevision = -1L;
        enforced = false;
        SkyVanillaClouds.invalidate();
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            if (client.isSameThread()) begin(handler); else client.execute(() -> begin(handler));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (client.isSameThread()) end(handler); else client.execute(() -> end(handler));
        });
        ClientPlayNetworking.registerGlobalReceiver(SkyboxConfigPayload.TYPE, (payload, context) -> {
            Object sender = context.player().connection;
            final long receivedEpoch;
            synchronized (SkyboxServerState.class) {
                if (!enforced || connection != sender) return;
                receivedEpoch = epoch;
            }
            context.client().execute(() -> {
                synchronized (SkyboxServerState.class) {
                    if (connection != sender || epoch != receivedEpoch) return;
                    accept(payload.settings());
                }
            });
        });
    }
}