package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.yg.skyboxblock.network.SkyboxRenderTogglePayload;
import dev.yg.skyboxblock.network.SkyboxRenderToggleResultPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Client U shortcut backed by a server-side request that can only affect the sender. */
final class SkyboxRenderToggle {
    private static final int ACK_TIMEOUT_TICKS = 40;
    private static KeyMapping key;
    private static boolean pending;
    private static long pendingRequestId;
    private static int pendingTicks;
    private static boolean pendingDesired;
    private static Object pendingConnection;
    private static Object sequenceConnection;
    private static long requestSequence;

    private SkyboxRenderToggle() { }

    static void register() {
        key = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.skyboxblock.toggle_render",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories.skyboxblock"));
        ClientTickEvents.END_CLIENT_TICK.register(SkyboxRenderToggle::tick);
    }

    private static void tick(Minecraft client) {
        if (key == null) return;
        boolean clicked = false;
        while (key.consumeClick()) clicked = true;
        Object connection = client.getConnection();
        if (connection != sequenceConnection) {
            sequenceConnection = connection;
            requestSequence = 0L;
            clearPending();
        }
        if (client.screen != null || client.player == null || connection == null) {
            clearPending();
            return;
        }
        if (!SkyboxServerState.ready()) {
            clearPending();
            return;
        }
        if (pending && ++pendingTicks > ACK_TIMEOUT_TICKS) clearPending();
        if (!clicked || pending) return;
        if (!ClientPlayNetworking.canSend(SkyboxRenderTogglePayload.TYPE)) {
            client.player.displayClientMessage(Component.literal(
                    "[Sky Box Block] This server does not support the self-only U toggle request."), false);
            return;
        }
        if (requestSequence == Long.MAX_VALUE) {
            client.player.displayClientMessage(Component.literal(
                    "[Sky Box Block] Toggle request sequence exhausted; reconnect to reset it."), false);
            return;
        }
        long requestId = ++requestSequence;
        boolean expected = SkyboxServerState.get().environment().rendersSkybox();
        boolean desired = !expected;
        pending = true;
        pendingRequestId = requestId;
        pendingDesired = desired;
        pendingConnection = connection;
        pendingTicks = 0;
        ClientPlayNetworking.send(new SkyboxRenderTogglePayload(requestId, expected, desired));
    }

    static boolean acceptsResult(long pendingId, Object expectedConnection, long resultId, Object actualConnection) {
        return pendingId > 0 && pendingId == resultId
                && expectedConnection != null && expectedConnection == actualConnection;
    }

    static void receiveResult(SkyboxRenderToggleResultPayload result, Object connection, Minecraft client) {
        if (!pending || !acceptsResult(pendingRequestId, pendingConnection, result.requestId(), connection)
                || client.getConnection() != connection) return;
        clearPending();
        if (result.status() != SkyboxRenderToggleResultPayload.Status.APPLIED
                && result.status() != SkyboxRenderToggleResultPayload.Status.DUPLICATE) {
            client.player.displayClientMessage(Component.literal(
                    "[Sky Box Block] U toggle was not applied: " + result.status()), false);
        }
    }

    private static void clearPending() {
        pending = false;
        pendingRequestId = 0L;
        pendingTicks = 0;
        pendingConnection = null;
    }
}
