package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.Optional;

/**
 * 云层（第四版）：按原版思路**逐格生成几何**。
 *
 * <p>前三版的教训：
 * <ol>
 *   <li>帧图外调 {@code CloudRenderer.render} → 不产出几何；</li>
 *   <li>一块大平面贴云贴图 → 被低通成一整片灰白（原版云贴图的每个像素是**一格**，不是纹理）；</li>
 *   <li>UV 超出 0..1 会被 CLAMP 拉边成纯色。</li>
 * </ol>
 *
 * <p>所以这一版读 {@code textures/environment/clouds.png}，把每个**非透明像素**变成一块
 * {@link #CELL_SIZE}×{@link #CELL_SIZE} 的水平面（朝下），UV 取该格的贴图坐标（恒在 0..1 内）。
 */
public final class SkyCloudPlane {
    private static final Identifier CLOUD_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/clouds.png");
    /** 原版一格云是 12 格方块。 */
    private static final float CELL_SIZE = 12f;
    /** 云层离地高度（与 {@code LevelRenderState.cloudHeight} 默认值一致）。 */
    private static final float CLOUD_HEIGHT = 192f;

    private static RenderPipeline pipeline;
    private static GpuBuffer buffer;
    private static int quadCount;
    private static boolean disabled;
    private static boolean probed;

    /** 云层厚度（原版约 4 格）。 */
    private static final float CLOUD_THICKNESS = 4f;

    private SkyCloudPlane() { }

    public static boolean enabled() {
        // 默认关闭：几何能生成（18103 格）、也能画，但视觉上仍不对（云格看起来被抹成整片浅色）。
        // 想继续实验设 SKYBOX_CLOUDS=1。
        return "1".equals(System.getenv("SKYBOX_CLOUDS")) && !disabled;
    }

    public static void draw(RenderTarget target, Matrix4f rotation, double camX, double camY, double camZ,
                            long gameTime, float partial) {
        if (!enabled() || target == null) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            ensurePipeline();
            ensureBuffer(mc);
            if (pipeline == null || buffer == null) return;
            var texture = mc.getTextureManager().getTexture(CLOUD_TEXTURE);
            if (texture == null || texture.getTextureView() == null) return;

            var stack = RenderSystem.getModelViewStack();
            stack.pushMatrix();
            // 顶点是**世界坐标**，所以要先平移到相机原点，再套相机旋转（顺序不能反）。
            // 之前只套了旋转，云面全落在相机背后（视图空间 +Z 是朝后），于是一个像素都看不到。
            stack.mul(rotation);
            stack.translate((float) -camX, (float) -camY, (float) -camZ);
            // 云会飘：按游戏时间平移一个格宽的量（原版约 0.6 格/秒）。
            float drift = (gameTime % 24000L + partial) * 0.024f;
            stack.translate(drift, 0f, 0f);
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    // 不挂深度附件：天空盘已经把深度写成近值，再按深度测试画云会被整体判为"被遮挡"。
                    .createRenderPass(() -> "skyboxblock:clouds", target.getColorTextureView(),
                            Optional.empty())) {
                pass.setPipeline(pipeline);
                RenderSystem.bindDefaultUniforms(pass);
                pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
                pass.setVertexBuffer(0, buffer.slice());
                pass.draw(0, quadCount * 6, 0, 1);
            } finally {
                stack.popMatrix();
            }
            if (!probed) {
                probed = true;
                SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层（逐格几何）已绘制一帧，格子数={}", quadCount);
            }
        } catch (RuntimeException | LinkageError error) {
            disabled = true;
            SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层（逐格几何）不可用：{}", error.toString());
        }
    }

    private static void ensurePipeline() {
        if (pipeline != null) return;
        pipeline = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
                .withLocation(SkyboxBlockMod.id("cloud_cells"))
                .withVertexShader("core/position_tex")
                .withFragmentShader("core/position_tex")
                .withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
                        .withSampler("Sampler0")
                        .build())
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
                        GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withCull(false)
                .build();
    }

    /** 读云贴图，逐格生成朝下的水平面。 */
    private static void ensureBuffer(Minecraft mc) {
        if (buffer != null) return;
        NativeImage image = null;
        try {
            var found = mc.getResourceManager().getResource(CLOUD_TEXTURE);
            if (found.isEmpty()) { disabled = true; return; }
            try (var stream = found.get().open()) {
                image = NativeImage.read(stream);
            }
            int w = image.getWidth();
            int h = image.getHeight();
            float halfW = w * CELL_SIZE / 2f;
            float halfH = h * CELL_SIZE / 2f;

            java.nio.ByteBuffer copy;
            int count = 0;
            try (var allocator = new ByteBufferBuilder(1 << 20)) {
                var builder = new BufferBuilder(allocator, com.mojang.blaze3d.PrimitiveTopology.TRIANGLES,
                        DefaultVertexFormat.POSITION_TEX);
                for (int cellZ = 0; cellZ < h; cellZ++) {
                    for (int cellX = 0; cellX < w; cellX++) {
                        int argb = image.getPixel(cellX, cellZ);
                        if (((argb >>> 24) & 0xFF) < 8) continue;   // 透明格跳过
                        float x0 = cellX * CELL_SIZE - halfW;
                        float z0 = cellZ * CELL_SIZE - halfH;
                        float x1 = x0 + CELL_SIZE;
                        float z1 = z0 + CELL_SIZE;
                        float u0 = cellX / (float) w;
                        float v0 = cellZ / (float) h;
                        float u1 = (cellX + 1) / (float) w;
                        float v1 = (cellZ + 1) / (float) h;
                        // 朝下的一面（从地面往上看）
                        builder.addVertex(x0, CLOUD_HEIGHT, z0).setUv(u0, v0);
                        builder.addVertex(x0, CLOUD_HEIGHT, z1).setUv(u0, v1);
                        builder.addVertex(x1, CLOUD_HEIGHT, z1).setUv(u1, v1);
                        builder.addVertex(x0, CLOUD_HEIGHT, z0).setUv(u0, v0);
                        builder.addVertex(x1, CLOUD_HEIGHT, z1).setUv(u1, v1);
                        builder.addVertex(x1, CLOUD_HEIGHT, z0).setUv(u1, v0);
                        // 四个侧面：没有侧面时云是"贴纸"，加上才有原版那种厚度感
                        float bottom = CLOUD_HEIGHT - CLOUD_THICKNESS;
                        side(builder, x0, bottom, z0, x1, bottom, z0, u0, u1, v0);
                        side(builder, x1, bottom, z0, x1, bottom, z1, u0, u1, v0);
                        side(builder, x1, bottom, z1, x0, bottom, z1, u0, u1, v0);
                        side(builder, x0, bottom, z1, x0, bottom, z0, u0, u1, v0);
                        count++;
                    }
                }
                try (MeshData mesh = builder.buildOrThrow()) {
                    java.nio.ByteBuffer source = mesh.vertexBuffer();
                    copy = java.nio.ByteBuffer.allocateDirect(source.remaining());
                    copy.put(source.duplicate());
                    copy.flip();
                }
            }
            if (count == 0) { disabled = true; return; }
            buffer = RenderSystem.getDevice().createBuffer(() -> "skyboxblock cloud cells",
                    GpuBuffer.USAGE_VERTEX, copy);
            quadCount = count;
        } catch (java.io.IOException | RuntimeException error) {
            disabled = true;
            SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层几何生成失败：{}", error.toString());
        } finally {
            if (image != null) image.close();
        }
    }

    /** 一格的一个侧面：从 (ax,az) 到 (bx,bz) 的竖直四边形。 */
    private static void side(BufferBuilder builder, float ax, float baseY, float az,
                             float bx, float baseY2, float bz, float u0, float u1, float v) {
        builder.addVertex(ax, baseY, az).setUv(u0, v);
        builder.addVertex(bx, baseY2, bz).setUv(u1, v);
        builder.addVertex(bx, CLOUD_HEIGHT, bz).setUv(u1, v);
        builder.addVertex(ax, baseY, az).setUv(u0, v);
        builder.addVertex(bx, CLOUD_HEIGHT, bz).setUv(u1, v);
        builder.addVertex(ax, CLOUD_HEIGHT, az).setUv(u0, v);
    }

    public static void close() {
        if (buffer != null) {
            try { buffer.close(); } catch (RuntimeException ignored) { }
            buffer = null;
        }
        pipeline = null;
        quadCount = 0;
    }
}
