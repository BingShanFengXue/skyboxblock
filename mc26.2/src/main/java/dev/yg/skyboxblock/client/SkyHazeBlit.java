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
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.yg.skyboxblock.SkyboxBlockMod;
import org.joml.Vector4f;

import java.util.Optional;

/**
 * 地平线雾层（第二版：走原版 {@code PostPass} 的写法）。
 *
 * <p>关键差别（第一版失败的教训）：原版是 **screenquad** 管线 —— 顶点由
 * {@code core/screenquad.vsh} 用 {@code gl_VertexID} 生成，**不绑顶点缓冲**，一次
 * {@code draw(3, 1, 0, 0)} 画满全屏；片元用 {@code core/blit_screen.fsh} 采样一张贴图。
 *
 * <p>所以这里：
 * <ol>
 *   <li>建一张 1×2 的 RGBA 贴图：v=0（屏幕下方）是雾色 + alpha，v=1（屏幕中部）alpha=0，
 *       LINEAR 采样后下半个屏幕就是一条平滑的地平线渐变；</li>
 *   <li>管线 = screenquad.vsh + blit_screen.fsh，混合 TRANSLUCENT；</li>
 *   <li>无顶点缓冲，`draw(3, 1, 0, 0)`。</li>
 * </ol>
 */
public final class SkyHazeBlit {
    private static RenderPipeline pipeline;
    private static GpuTexture texture;
    private static GpuTextureView view;
    private static GpuSampler sampler;
    private static float bakedR = Float.NaN, bakedG, bakedB;
    private static boolean disabled;
    private static boolean probed;

    private SkyHazeBlit() { }

    public static boolean enabled() {
        // 默认开启：实测把"墙面 vs 环境天空"的差值从 (-68,-51,-19) 收窄到 (-40,-40,-35)；
        // 设 SKYBOX_HAZE_BLIT=0 可关掉做对照。
        return !"0".equals(System.getenv("SKYBOX_HAZE_BLIT")) && !disabled;
    }

    /** 混合目标：直接取"环境天空的样子"（浅灰蓝），比雾色更接近真实观感。 */
    private static final Vector4f HAZE = new Vector4f(0.80f, 0.82f, 0.90f, 1f);
    /** 最大叠加强度。0.95 会把整片天空洗成灰白（实测），0.35 才接近"只在地平线附近提亮"。 */
    private static final float MAX_ALPHA = 0.35f;

    public static void draw(RenderTarget target, Vector4f fogColor) {
        if (!enabled() || target == null) return;
        try {
            ensurePipeline();
            // 二分诊断：SKYBOX_CLOUD_VIA_BLIT=1 时改用**云贴图**跑这条已验证可用的 blit 管线。
            // 若能看见云图案 ⇒ screenquad/采样器这条链没问题，问题只在自定义 shader 或 UBO。
            boolean cloudDiagnostic = "1".equals(System.getenv("SKYBOX_CLOUD_VIA_BLIT"));
            GpuTextureView bindView = view;
            GpuSampler bindSampler = sampler;
            if (cloudDiagnostic) {
                var cloudTexture = net.minecraft.client.Minecraft.getInstance().getTextureManager()
                        .getTexture(net.minecraft.resources.Identifier.withDefaultNamespace(
                                "textures/environment/clouds.png"));
                if (cloudTexture == null || cloudTexture.getTextureView() == null) return;
                bindView = cloudTexture.getTextureView();
                bindSampler = cloudTexture.getSampler();
            } else {
                ensureTexture(HAZE);
            }
            if (pipeline == null || bindView == null || bindSampler == null) return;

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "skyboxblock:haze_blit", target.getColorTextureView(),
                            Optional.empty(), target.getDepthTextureView(), java.util.OptionalDouble.empty())) {
                pass.setPipeline(pipeline);
                pass.bindTexture("InSampler", bindView, bindSampler);
                pass.draw(3, 1, 0, 0);
            }
            if (!probed) {
                probed = true;
                SkyboxBlockMod.LOGGER.info("[skyboxblock] 雾层(blit) 已绘制一帧");
            }
        } catch (RuntimeException | LinkageError error) {
            disabled = true;
            SkyboxBlockMod.LOGGER.info("[skyboxblock] 雾层(blit) 不可用：{}", error.toString());
        }
    }

    private static void ensurePipeline() {
        if (pipeline != null) return;
        pipeline = RenderPipeline.builder()
                .withLocation(SkyboxBlockMod.id("haze_blit"))
                .withVertexShader("core/screenquad")
                .withFragmentShader("core/blit_screen")
                // 关键：管线必须**声明**采样器，否则 bindTexture 会被忽略、采样恒为不透明黑。
                .withBindGroupLayout(com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
                        .withSampler("InSampler")
                        .build())
                .withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)
                .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
                        GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
                .withCull(false)
                .build();
    }

    /** 1×2 贴图：下方（v=0）雾最浓，中部（v=1）透明。 */
    private static void ensureTexture(Vector4f fogColor) {
        if (texture != null
                && Math.abs(fogColor.x - bakedR) < 0.01f
                && Math.abs(fogColor.y - bakedG) < 0.01f
                && Math.abs(fogColor.z - bakedB) < 0.01f) return;

        boolean solidTest = "1".equals(System.getenv("SKYBOX_HAZE_SOLID"));
        float r = solidTest ? 1f : fogColor.x;
        float g = solidTest ? 0f : fogColor.y;
        float b = solidTest ? 1f : fogColor.z;
        float strongAlpha = solidTest ? 1f : MAX_ALPHA;
        float clearAlpha = solidTest ? 1f : 0f;

        // 16×16 而不是 1×2：小尺寸贴图在上传/采样上更容易踩坑（实测 1×2 上传后采样为全黑）。
        // 第 0 行对应 v=0，也就是屏幕下方 —— 雾最浓；往上逐渐透明。
        int size = 16;
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false)) {
            for (int y = 0; y < size; y++) {
                // 实测：这组（sqrt 衰减 + 0.95 上限）比"保底 50%"更接近环境天空；
                // 保底更高反而整体变暗（混合方向与预期相反），所以保持这个版本。
                float alpha = strongAlpha * (float) Math.sqrt(1f - (float) y / (size - 1));
                // 诊断结论：NativeImage.Format.RGBA + setPixelABGR 实际是 **BGR** 顺序（实测颜色偏红/偏棕），
                // 所以这里按 b,g,r 写入，读出来才是 (0.80, 0.82, 0.90) 的浅灰蓝。
                int argb = ((int) (alpha * 255) << 24)
                        | ((int) (b * 255) << 16) | ((int) (g * 255) << 8) | (int) (r * 255);
                for (int x = 0; x < size; x++) image.setPixelABGR(x, y, argb);
            }

            if (texture == null) {
                texture = RenderSystem.getDevice().createTexture(() -> "skyboxblock haze gradient",
                        GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                        GpuFormat.RGBA8_UNORM, size, size, 1, 1);
                view = RenderSystem.getDevice().createTextureView(texture);
                sampler = RenderSystem.getDevice().createSampler(
                        AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                        FilterMode.LINEAR, FilterMode.LINEAR, 1, java.util.OptionalDouble.empty());
            }
            // 按原版 SpriteContents.uploadFirstFrame 的写法：writeToTexture(texture, image, mip, 0, 0, 0)
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, 0, 0, 0, 0);
        }
        bakedR = fogColor.x;
        bakedG = fogColor.y;
        bakedB = fogColor.z;
    }

    private static void putTexel(java.nio.ByteBuffer buffer, float r, float g, float b, float a) {
        buffer.put((byte) (r * 255)).put((byte) (g * 255)).put((byte) (b * 255)).put((byte) (a * 255));
    }

    private static int pack(float r, float g, float b, float a) {
        return ((int) (a * 255) << 24) | ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }

    public static void close() {
        if (texture != null) {
            try { texture.close(); } catch (RuntimeException ignored) { }
            texture = null;
            view = null;
            sampler = null;
        }
        pipeline = null;
        bakedR = Float.NaN;
    }
}
