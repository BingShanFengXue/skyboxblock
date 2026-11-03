package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * 天空附加层（雾 / 云）的**版本无关**入口。
 *
 * <p>背景：这几层用的 Blaze3D API（`GpuFormat` 位置、`createRenderPass` 的 `Optional` vs `OptionalInt`、
 * `RenderPass.draw` 签名）在 26.1 与 26.2 之间不兼容，所以实现类只在 26.2 的源码集里编译；
 * 26.1 构建时它们被排除，这里的调用会因找不到类而**静默跳过**（26.1 只保证编译与基础渲染）。
 *
 * <p>用反射而不是直接引用，是为了让同一份 `SkyRendererHost` 能同时编过两个版本。
 */
public final class SkyLayers {
    private SkyLayers() { }

    public static void haze(RenderTarget target, Vector4f color) {
        invoke("dev.yg.skyboxblock.client.SkyHazeBlit", "draw",
                new Class<?>[]{RenderTarget.class, Vector4f.class}, target, color);
    }

    public static void clouds(RenderTarget target, Matrix4fc viewProjection) {
        invoke("dev.yg.skyboxblock.client.SkyCloudCpu", "draw",
                new Class<?>[]{RenderTarget.class, Matrix4fc.class}, target, viewProjection);
    }

    private static void invoke(String className, String method, Class<?>[] types, Object... args) {
        try {
            Class.forName(className).getMethod(method, types).invoke(null, args);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            // 26.1 构建没有这些类；即使有、内部也会自己兜底
        }
    }
}
