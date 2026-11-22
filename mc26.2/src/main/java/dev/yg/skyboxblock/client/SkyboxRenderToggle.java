package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Client shortcut for the same per-player visibility command exposed by the server. */
final class SkyboxRenderToggle {
    private static final String COMMAND_PREFIX = "skyboxblock player @s render_skybox ";
    private static final int ACK_TIMEOUT_TICKS = 40;
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            SkyboxBlockMod.id("controls"));
    private static KeyMapping key;
    private static boolean pending;
    private static long pendingRevision = -1L;
    private static int pendingTicks;

    private SkyboxRenderToggle() { }

    static void register() {
        key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.skyboxblock.toggle_render",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(SkyboxRenderToggle::tick);
    }

    private static void tick(Minecraft client) {
        if (key == null) return;
        boolean clicked = false;
        while (key.consumeClick()) clicked = true;

        // Do not let a click made in a menu or before the first server snapshot leak into gameplay.
        if (client.player == null || client.getConnection() == null) {
            pending = false;
            return;
        }
        if (!SkyboxServerState.ready()) {
            pending = false;
            return;
        }
        if (pending) {
            if (SkyboxServerState.revision() > pendingRevision || ++pendingTicks > ACK_TIMEOUT_TICKS) {
                pending = false;
            }
        }
        if (!clicked || pending) return;

        boolean target = !SkyboxServerState.get().environment().rendersSkybox();
        client.getConnection().sendCommand(COMMAND_PREFIX + target);
        pending = true;
        pendingRevision = SkyboxServerState.revision();
        pendingTicks = 0;
    }
}
