package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * 云层（最终方案）：**screenquad + 自写片元着色器做视线–云层求交**。
 *
 * <p>为什么绕这么大一圈：实测在自绘 target 上，带顶点缓冲的几何画不出任何像素（日志还"成功"），
 * 而 screenquad（`gl_VertexID` 生成顶点、不绑缓冲）能稳定画进去。所以云只能靠片元算出来。
 *
 * <p>片元里用 {@code InvViewProj} 反投影出视线，与 y = cloudHeight 求交，再用世界坐标除以
 * "一格云的方块数 × 256" 得到云图 UV。
 */
public final class SkyCloudRay {
    private static final Identifier CLOUD_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/clouds.png");
    private static final float CLOUD_HEIGHT = 192f;
    private static final float CELL_SIZE = 12f;
    private static final float CLOUD_ALPHA = 0.85f;
    /** std140：mat4(64) + vec4(16)。 */
    private static final int UBO_BYTES = 80;

    private static RenderPipeline pipeline;
    private static boolean disabled;
    private static boolean probed;

    private SkyCloudRay() { }

    public static boolean enabled() {
        return "1".equals(System.getenv("SKYBOX_CLOUDS")) && !disabled;
    }

    public static void draw(RenderTarget target, Matrix4fc viewProjection, long gameTime, float partial) {
        if (!enabled() || target == null) return;
        try {
            ensurePipeline();
            Minecraft mc = Minecraft.getInstance();
            var texture = mc.getTextureManager().getTexture(CLOUD_TEXTURE);
            if (texture == null || texture.getTextureView() == null) return;

            Matrix4f inverse = new Matrix4f(viewProjection).invert();
            ByteBuffer ubo = ByteBuffer.allocateDirect(UBO_BYTES);
            inverse.get(ubo);
            ubo.position(64);
            ubo.putFloat(CLOUD_HEIGHT);
            ubo.putFloat(CELL_SIZE);
            ubo.putFloat(gameTime / 20f);
            ubo.putFloat(CLOUD_ALPHA);
            ubo.position(0);

            try (GpuBuffer params = RenderSystem.getDevice().createBuffer(
                    () -> "skyboxblock cloud params", GpuBuffer.USAGE_UNIFORM, ubo);
                 RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                         .createRenderPass(() -> "skyboxblock:clouds_ray", target.getColorTextureView(),
                                 Optional.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("CloudParams", params);
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.draw(3, 1, 0, 0);
            }
            if (!probed) {
                probed = true;
                SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层（片元求交）已绘制一帧");
            }
        } catch (RuntimeException | LinkageError error) {
            disabled = true;
            SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层（片元求交）不可用：{}", error.toString());
        }
    }

    private static void ensurePipeline() {
        if (pipeline != null) return;
        pipeline = RenderPipeline.builder()
                .withLocation(SkyboxBlockMod.id("cloud_ray"))
                .withVertexShader(SkyboxBlockMod.id("core/cloud_ray"))
                .withFragmentShader(SkyboxBlockMod.id("core/cloud_ray"))
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("CloudParams", UniformType.UNIFORM_BUFFER)
                        .withSampler("Sampler0")
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
                        GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withCull(false)
                .build();
    }

    public static void close() {
        pipeline = null;
    }
}
