package dev.yg.skyboxblock.client;

import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 版本无关的"自绘天空"门面。
 *
 * <p>26.1/26.2/26.3 的实现在接口形状上不同（26.3 把 {@code SkyRenderer} 的五个渲染方法合并成
 * {@code render(GpuBufferSlice, SkyRenderState)}，颜色字段也从 int 变成 Vector3fc/Vector4fc），
 * 所以由 {@link SkyHosts} 在运行时挑一个实现：
 * <ul>
 *   <li>{@code dev.yg.skyboxblock.client.SkyRendererHost} —— 26.1 / 26.2；</li>
 *   <li>{@code dev.yg.skyboxblock.client.v263.SkyRendererHost263} —— 26.3（编译期由 src/26.3 提供）。</li>
 * </ul>
 */
public interface SkyHost {
    /** 方块面用的 RenderType；天空纹理还没就绪时返回 null。 */
    RenderType faceRenderType();

    /**
     * 本帧相机实际使用的 view-projection 矩阵（世界相对坐标 → 裁剪空间）。
     * 用它取样天空帧，方块面上的天空才会和屏幕上的天空逐像素一致；返回 null 时退回合成投影。
     */
    org.joml.Matrix4fc viewProjection();

    /** 由 {@code LevelRenderEvents.START_MAIN} 每帧调用。 */
    void onStartMain();

    /** 由 {@code LevelRenderEvents.END_MAIN} 调用（调试落盘用，可为空实现）。 */
    void maybeDumpMainFrame();

    /** 客户端停止时释放 GPU 资源。 */
    void close();
}
