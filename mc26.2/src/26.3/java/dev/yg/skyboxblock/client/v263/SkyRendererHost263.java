package dev.yg.skyboxblock.client.v263;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.client.SkyHost;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * 26.3 版自绘天空宿主。
 *
 * <p>26.3 的变化（javap 实测）：
 * <ul>
 *   <li>渲染入口合并成 {@code SkyRenderer.render(GpuBufferSlice, SkyRenderState)}；</li>
 *   <li>{@code SkyRenderState.skyColor} 是 {@code Vector3fc}、{@code sunriseAndSunsetColor} 是 {@code Vector4fc}；</li>
 *   <li>编码器/格式/过滤模式搬到 {@code com.mojang.renderpearl.api.*}；</li>
 *   <li>{@code RenderSystem.outputColorTextureOverride} 已删除 —— 不再需要，因为 SkyRenderer 直接收自己的 target。</li>
 * </ul>
 */
public final class SkyRendererHost263 implements SkyHost {
    private static final Identifier TEXTURE_ID = SkyboxBlockMod.id("textures/sky/frame.png");

    private TextureTarget target;
    private SkyRenderer renderer;
    private ProjectionMatrixBuffer projection;
    private AbstractTexture texture;
    private RenderType faceType;
    private final SkyRenderState state = new SkyRenderState();
    private int width = -1;
    private int height = -1;
    private int frames;
    private int frameCounter;
    private int mainFrames;
    private final org.joml.Matrix4f vp = new org.joml.Matrix4f();
    private boolean vpValid;

    @Override public org.joml.Matrix4fc viewProjection() {
        return vpValid ? vp : null;
    }

    @Override public RenderType faceRenderType() {
        if (texture == null) return null;
        if (faceType == null) {
            faceType = RenderType.create("skyboxblock:sky_face",
                    RenderSetup.builder(RenderTypes.entitySolid(TEXTURE_ID).pipeline())
                            .withTexture("Sampler0", TEXTURE_ID)
                            .createRenderSetup());
        }
        return faceType;
    }

    @Override public void onStartMain() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        ensure(mc, w, h);
        if (renderer == null || target == null) return;

        Camera camera = mc.gameRenderer.mainCamera();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        state.reset();
        renderer.extractRenderState(level, partial, camera, state);
        camera.getViewRotationProjectionMatrix(vp);
        vpValid = true;

        Vector4f clear = new Vector4f(state.skyColor.x(), state.skyColor.y(), state.skyColor.z(), 1f);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.clearColorAndDepthTextures(target.getColorTexture(), clear, target.getDepthTexture(), 1.0);

        Matrix4f matrix = new Matrix4f().perspective(
                (float) Math.toRadians(camera.getFov()), (float) w / (float) h, 0.05f, 512f);
        renderer.render(projection.getBuffer(matrix), state);

        if (frames < 3) {
            frames++;
            SkyboxBlockMod.LOGGER.info("[skyboxblock] (26.3) sky frame #{} {}x{} skybox={} skyColor={}",
                    frames, w, h, state.skybox, state.skyColor);
        }

        frameCounter++;
        if (frameCounter == 60) dump(target, "build/sky-frame-263.png");
    }

    @Override public void maybeDumpMainFrame() {
        mainFrames++;
        if (mainFrames != 120) return;
        Minecraft mc = Minecraft.getInstance();
        dump(mc.gameRenderer.mainRenderTarget(), "build/world-frame-263.png");
    }

    private void dump(com.mojang.blaze3d.pipeline.RenderTarget source, String relative) {
        net.minecraft.client.Screenshot.takeScreenshot(source, image -> {
            java.nio.file.Path out = java.nio.file.Path.of(
                    "E:/DSHworkspace/DSHminecraft/skybox-mod-26.2/" + relative);
            try {
                image.writeToFile(out);
                SkyboxBlockMod.LOGGER.info("[skyboxblock] dumped -> {}", out);
            } catch (java.io.IOException error) {
                SkyboxBlockMod.LOGGER.warn("[skyboxblock] dump failed", error);
            } finally {
                image.close();
            }
        });
    }

    private void ensure(Minecraft mc, int w, int h) {
        if (target != null && w == width && h == height) return;
        close();
        width = w;
        height = h;
        // 26.3 的 TextureTarget 是 (label, w, h, colorFormat, depthFormat)，没有 boolean。
        target = new TextureTarget("skyboxblock_sky", w, h,
                com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM,
                com.mojang.renderpearl.api.GpuFormat.D32_FLOAT);
        renderer = new SkyRenderer(mc.getTextureManager(), mc.getAtlasManager(), target);
        projection = new ProjectionMatrixBuffer("skyboxblock_sky_proj");
        texture = new TargetTexture(target);
        mc.getTextureManager().register(TEXTURE_ID, texture);
        faceType = null;
        SkyboxBlockMod.LOGGER.info("[skyboxblock] (26.3) sky target created {}x{}", w, h);
    }

    @Override public void close() {
        if (renderer != null) {
            try { renderer.close(); } catch (RuntimeException ignored) { }
            renderer = null;
        }
        if (projection != null) {
            try { projection.close(); } catch (RuntimeException ignored) { }
            projection = null;
        }
        if (target != null) {
            try { target.destroyBuffers(); } catch (RuntimeException ignored) { }
            target = null;
        }
        texture = null;
        faceType = null;
        width = height = -1;
    }

    private static final class TargetTexture extends AbstractTexture {
        TargetTexture(TextureTarget source) {
            this.texture = source.getColorTexture();
            this.textureView = source.getColorTextureView();
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
