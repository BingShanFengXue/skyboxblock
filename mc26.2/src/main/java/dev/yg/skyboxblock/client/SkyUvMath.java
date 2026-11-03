package dev.yg.skyboxblock.client;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * 天空取样的纯数学部分：屏幕投影 UV 与等距柱状 UV。
 *
 * <p>抽出来是为了能脱离 OpenGL 做回归 —— 26.2 的两条取样路径（自绘天空帧 / 自定义全景）
 * 全部依赖这两个函数，算错就是"整面黑"或"方向错"，而它们在游戏里很难观测。
 */
public final class SkyUvMath {
    private SkyUvMath() { }

    /**
     * 把世界坐标投影成天空帧的 UV（0..1）。
     *
     * <p>实测约定：这条渲染路径的 {@code v=0} 在图像底部（GL 约定），所以 NDC 的 +Y 映射到更大的 v。
     * 相机背后的点（w ≤ 0）退回中心采样，避免除零把整块面撕成 NaN。
     */
    public static float[] project(Matrix4fc viewRotation, Matrix4fc projection,
                                  double camX, double camY, double camZ,
                                  float wx, float wy, float wz) {
        Vector4f v = new Vector4f((float) (wx - camX), (float) (wy - camY), (float) (wz - camZ), 1f);
        viewRotation.transform(v);
        projection.transform(v);
        if (v.w <= 1.0e-4f) return new float[]{0.5f, 0.5f};
        float ndcX = v.x / v.w;
        float ndcY = v.y / v.w;
        // 实测：天空纹理的 v=0 在**图像顶部**（与 NDC 的 +Y 同向），所以要翻转一次，
        // 否则方块面上的天空会上下颠倒（地平线跑到顶端、天顶跑到下方）。
        return new float[]{ndcX * 0.5f + 0.5f, 0.5f - ndcY * 0.5f};
    }

    /** 等距柱状（equirect）UV：yaw → u（+Z 为 0.5），pitch → v（天顶 0、天底 1）。 */
    public static float[] equirect(double camX, double camY, double camZ,
                                   float wx, float wy, float wz) {
        double dx = wx - camX;
        double dy = wy - camY;
        double dz = wz - camZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.atan2(dx, dz);
        double pitch = Math.atan2(dy, horizontal);
        return new float[]{(float) (yaw / (2 * Math.PI) + 0.5), (float) (0.5 - pitch / Math.PI)};
    }
}
