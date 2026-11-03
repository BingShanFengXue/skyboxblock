package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Explicitly opted-in, owned-fixture-only animation clock control.
 * The audited Iris getter is ()F. No timer state, frame delta, TAA counter or world clock is changed.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public abstract class RegressionShaderTimeMixin {
    @Unique private static Minecraft skyboxblock$timeGuardClient;
    @Unique private static Path skyboxblock$timeGuardRawGameDir;
    @Unique private static Path skyboxblock$timeGuardRawOwnerDir;
    @Unique private static Path skyboxblock$timeGuardRealGameDir;
    @Unique private static Path skyboxblock$timeGuardRealOwnerDir;
    @Unique private static boolean skyboxblock$timeFreezeReported;

    @Inject(method = "getFrameTimeCounter()F", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private void skyboxblock$fixtureFrameTimeCounter(CallbackInfoReturnable<Float> cir) {
        if (!Boolean.getBoolean("skyboxblock.regression")
                || !Boolean.getBoolean("skyboxblock.regression.freezeShaderTime")) return;
        String suite = System.getProperty("skyboxblock.regression.suite", "");
        if (!suite.equals("transparency") && !suite.equals("all")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null || mc.level == null) return;
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || server.getWorldData() == null
                || !"Skybox render fixture".equals(server.getWorldData().getLevelName())) return;
        if (!skyboxblock$ownsRegressionDirectory(mc)) return;

        // Override only this supplier result. The real Timer continues updating, including lastFrameTime.
        cir.setReturnValue(0.0F);
        if (!skyboxblock$timeFreezeReported) {
            skyboxblock$timeFreezeReported = true;
            SkyboxBlockMod.LOGGER.info("[skybox-regression] fixture-only Iris frameTimeCounter=0 active; "
                            + "frameCounter/frameTime/world clocks unchanged; gameDir={}",
                    skyboxblock$timeGuardRealGameDir);
        }
    }

    @Unique
    private static boolean skyboxblock$ownsRegressionDirectory(Minecraft mc) {
        String explicitOwner = System.getProperty("skyboxblock.regression.ownedGameDir", "");
        if (explicitOwner.isBlank()) return false;
        try {
            Path rawGameDir = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            Path ownerInput = Path.of(explicitOwner);
            if (!ownerInput.isAbsolute()) return false;
            Path rawOwnerDir = ownerInput.toAbsolutePath().normalize();
            // Canonical paths are cached only for this client and these exact configured directory names.
            // The live client/server/world guards above still run on every getter call.
            boolean cached = skyboxblock$timeGuardClient == mc
                    && rawGameDir.equals(skyboxblock$timeGuardRawGameDir)
                    && rawOwnerDir.equals(skyboxblock$timeGuardRawOwnerDir)
                    && skyboxblock$timeGuardRealGameDir != null
                    && skyboxblock$timeGuardRealOwnerDir != null;
            if (!cached) {
                Path actual = rawGameDir.toRealPath();
                Path declared = rawOwnerDir.toRealPath();
                if (!actual.equals(declared) || !skyboxblock$isRegressionChild(actual)) return false;
                skyboxblock$timeGuardClient = mc;
                skyboxblock$timeGuardRawGameDir = rawGameDir;
                skyboxblock$timeGuardRawOwnerDir = rawOwnerDir;
                skyboxblock$timeGuardRealGameDir = actual;
                skyboxblock$timeGuardRealOwnerDir = declared;
            }
            return skyboxblock$timeGuardRealGameDir.equals(skyboxblock$timeGuardRealOwnerDir)
                    && skyboxblock$isRegressionChild(skyboxblock$timeGuardRealGameDir);
        } catch (IOException | InvalidPathException | SecurityException exception) {
            // Missing/uninitialised directories or a bad opt-in leave the genuine Iris getter untouched.
            return false;
        }
    }

    @Unique
    private static boolean skyboxblock$isRegressionChild(Path path) {
        for (int i = 0; i + 2 < path.getNameCount(); i++) {
            if (path.getName(i).toString().equalsIgnoreCase("build")
                    && path.getName(i + 1).toString().equalsIgnoreCase("render-regression")) return true;
        }
        return false;
    }
}
