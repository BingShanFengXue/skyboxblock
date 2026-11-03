package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.util.Optional;

/**
 * 云层（CPU 求交 + blit 上屏）—— 这是目前唯一**全部环节都验证过**的方案。
 *
 * <p>为什么在 CPU 上算：实测自绘 target 上
 * <ul>
 *   <li>带顶点缓冲的几何 → 不产像素；</li>
 *   <li>screenquad + `core/blit_screen` + 采样器 → **能画**（用云贴图直接 blit 已看到云图案）；</li>
 *   <li>自定义 shader + UBO → 不产像素。</li>
 * </ul>
 *
 * <p>所以把"视线–云层求交"搬到 CPU：每帧生成一张小图（默认 256×160），逐像素反投影求交、
 * 采样云贴图，再按 blit 方式贴到天空帧上。开销 ≈ 4 万次求交/帧，可接受。
 */
public final class SkyCloudCpu {
    private static final Identifier CLOUD_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/clouds.png");
    private static final float CLOUD_HEIGHT = 192f;
    private static final float CELL_SIZE = 12f;
    private static final int WIDTH = 224;
    private static final int HEIGHT = 140;
    private static final float ALPHA = 0.85f;

    private static RenderPipeline pipeline;
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static GpuSampler sampler;
    private static int[] cloudPixels;      // 云贴图（ARGB），一次性读入
    private static int cloudW, cloudH;
    private static boolean disabled;
    private static boolean probed;

    private SkyCloudCpu() { }

    public static boolean enabled() {
        // 默认开启：实机测得 160×100 时 0.515 ms/帧（约 3% 帧预算），224×140 约翻倍。
        // 想要对照实验时设 SKYBOX_CLOUDS=0。
        return !"0".equals(System.getenv("SKYBOX_CLOUDS")) && !disabled;
    }

    public static void draw(RenderTarget target, Matrix4fc viewProjection) {
        if (!enabled() || target == null) return;
        long started = System.nanoTime();
        try {
            ensureResources();
            if (cloudPixels == null || pipeline == null || view == null) return;

            Matrix4f inverse = new Matrix4f(viewProjection).invert();
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, WIDTH, HEIGHT, false);
            try {
                Vector4f near = new Vector4f();
                Vector4f far = new Vector4f();
                for (int y = 0; y < HEIGHT; y++) {
                    // 注意：这张小图上传后由 blit 贴到天空帧上，行序与 NDC 的 y 是反的
                    // （实机第一版云出现在画面底部，就是这里没翻）。
                    float ndcY = 2f * (y + 0.5f) / HEIGHT - 1f;
                    for (int x = 0; x < WIDTH; x++) {
                        float ndcX = 2f * (x + 0.5f) / WIDTH - 1f;
                        near.set(ndcX, ndcY, -1f, 1f);
                        far.set(ndcX, ndcY, 1f, 1f);
                        inverse.transform(near);
                        inverse.transform(far);
                        float ox = near.x / near.w, oy = near.y / near.w, oz = near.z / near.w;
                        float dx = far.x / far.w - ox, dy = far.y / far.w - oy, dz = far.z / far.w - oz;
                        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (len < 1e-5f) continue;
                        dy /= len; dx /= len; dz /= len;
                        if (Math.abs(dy) < 1e-4f) continue;
                        float t = (CLOUD_HEIGHT - oy) / dy;
                        if (t <= 0f) continue;
                        float worldX = ox + dx * t;
                        float worldZ = oz + dz * t;
                        int cellX = (int) Math.floor(worldX / CELL_SIZE) & (cloudW - 1);
                        int cellZ = (int) Math.floor(worldZ / CELL_SIZE) & (cloudH - 1);
                        int argb = cloudPixels[cellZ * cloudW + cellX];
                        int alpha = (argb >>> 24) & 0xFF;
                        if (alpha < 16) continue;
                        float fade = Math.max(0f, 1f - t / 512f);
                        int a = (int) (alpha * ALPHA * fade);
                        if (a < 8) continue;
                        image.setPixelABGR(x, y, (a << 24) | (argb & 0xFFFFFF));
                    }
                }
                RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
            } finally {
                image.close();
            }

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "skyboxblock:clouds_cpu", target.getColorTextureView(),
                            Optional.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("InSampler", view, sampler);
                pass.draw(3, 1, 0, 0);
            }
            if (!probed) {
                probed = true;
                SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层（CPU 求交）已绘制一帧 {}x{}", WIDTH, HEIGHT);
            }
            frameCount++;
            frameNanos += System.nanoTime() - started;
            if (frameCount == 200) {
                SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层 CPU 开销：平均 {} µs/帧（{}x{}，{} 帧样本）",
                        String.format("%.0f", frameNanos / 200.0 / 1000.0), WIDTH, HEIGHT, frameCount);
            }
        } catch (RuntimeException | LinkageError error) {
            disabled = true;
            SkyboxBlockMod.LOGGER.info("[skyboxblock] 云层（CPU 求交）不可用：{}", error.toString());
        }
    }

    private static int frameCount;
    private static long frameNanos;

    private static void ensureResources() {
        if (pipeline == null) {
            pipeline = RenderPipeline.builder()
                    .withLocation(SkyboxBlockMod.id("clouds_cpu"))
                    .withVertexShader("core/screenquad")
                    .withFragmentShader("core/blit_screen")
                    .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                    .withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
                            .withSampler("InSampler")
                            .build())
                    .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
                            GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                    .withCull(false)
                    .build();
        }
        if (texture == null) {
            texture = RenderSystem.getDevice().createTexture(() -> "skyboxblock clouds cpu",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
            view = RenderSystem.getDevice().createTextureView(texture);
            sampler = RenderSystem.getDevice().createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty());
        }
        if (cloudPixels == null) {
            Minecraft mc = Minecraft.getInstance();
            var found = mc.getResourceManager().getResource(CLOUD_TEXTURE);
            if (found.isEmpty()) { disabled = true; return; }
            try (var stream = found.get().open(); NativeImage image = NativeImage.read(stream)) {
                cloudW = image.getWidth();
                cloudH = image.getHeight();
                cloudPixels = new int[cloudW * cloudH];
                for (int y = 0; y < cloudH; y++) {
                    for (int x = 0; x < cloudW; x++) cloudPixels[y * cloudW + x] = image.getPixel(x, y);
                }
            } catch (java.io.IOException error) {
                disabled = true;
                SkyboxBlockMod.LOGGER.info("[skyboxblock] 云贴图读取失败：{}", error.toString());
            }
        }
    }
}
