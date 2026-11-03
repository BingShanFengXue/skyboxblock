package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;

/**
 * 天空取样数学的回归（26.2 新架构）。
 *
 * <p>替代 1.21.1 时代的 `SkyGeometryRegression`：那套测的是自研 mesh 的顶点布局，
 * 26.2 已经没有那套结构；但"方向 → UV"的约定仍然是整块面显示对不对的唯一依据，
 * 而且它在游戏里几乎不可观测（算错只表现为整面黑/方向偏）。
 *
 * <p>运行：{@code gradlew regSkyUv}
 */
public final class SkyUvMathRegression {
    private static int checks;
    private static String report;

    public static void main(String[] args) {
        if (args.length > 0) report = args[0];
        identityProjection();
        perspectiveBasics();
        behindCamera();
        equirectCardinals();
        equirectContinuity();
        System.out.println("SkyUvMathRegression: " + checks + " checks OK");
        if (report != null) write(report);
    }

    // ── 用例 ───────────────────────────────────────────────────────────────────────

    /** 单位投影 + 单位视图：UV 就是 NDC 的线性映射，用来钉死公式本身。 */
    private static void identityProjection() {
        Matrix4f id = new Matrix4f();
        float[] centre = SkyUvMath.project(id, id, 0, 0, 0, 0, 0, 1);
        require(near(centre[0], 0.5f) && near(centre[1], 0.5f), "光轴中心映射到 (0.5, 0.5)");

        float[] x = SkyUvMath.project(id, id, 0, 0, 0, 0.5f, 0, 1);
        require(near(x[0], 0.75f), "NDC x=0.5 -> u=0.75");

        float[] y = SkyUvMath.project(id, id, 0, 0, 0, 0, 0.5f, 1);
        require(near(y[1], 0.25f), "NDC y=0.5 -> v=0.25（天空纹理 v=0 在顶部）");
    }

    /** 真实透视矩阵：相机前方的点应落在 (0,1)，且左右对称。 */
    private static void perspectiveBasics() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 512f);
        Matrix4f view = new Matrix4f();

        float[] ahead = SkyUvMath.project(view, proj, 0, 0, 0, 0, 0, -10);
        require(near(ahead[0], 0.5f) && near(ahead[1], 0.5f), "正前方 -> 画面中心");

        float[] right = SkyUvMath.project(view, proj, 0, 0, 0, 5, 0, -10);
        float[] left = SkyUvMath.project(view, proj, 0, 0, 0, -5, 0, -10);
        require(right[0] > 0.5f && left[0] < 0.5f, "右侧 u 增大、左侧 u 减小");
        require(near(right[0] + left[0], 1.0f), "左右对称");

        float[] up = SkyUvMath.project(view, proj, 0, 0, 0, 0, 5, -10);
        require(up[1] < 0.5f, "上方的点在图像更高的位置（v 更小）");
    }

    /** 相机背后的点不能产生除零/NaN。 */
    private static void behindCamera() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 1f, 0.05f, 512f);
        float[] behind = SkyUvMath.project(new Matrix4f(), proj, 0, 0, 0, 0, 0, 10);
        require(near(behind[0], 0.5f) && near(behind[1], 0.5f), "相机背后的点退回中心采样");
        require(Float.isFinite(behind[0]) && Float.isFinite(behind[1]), "退回值必须是有限数");
    }

    /** 等距柱状的四个基本方向。 */
    private static void equirectCardinals() {
        float[] forward = SkyUvMath.equirect(0, 0, 0, 0, 0, 1);
        require(near(forward[0], 0.5f) && near(forward[1], 0.5f), "+Z -> (0.5, 0.5)");

        float[] east = SkyUvMath.equirect(0, 0, 0, 1, 0, 0);
        require(near(east[0], 0.75f), "+X -> u=0.75");

        float[] west = SkyUvMath.equirect(0, 0, 0, -1, 0, 0);
        require(near(west[0], 0.25f), "-X -> u=0.25");

        float[] zenith = SkyUvMath.equirect(0, 0, 0, 0, 1, 0);
        require(near(zenith[1], 0.0f), "天顶 -> v=0");

        float[] nadir = SkyUvMath.equirect(0, 0, 0, 0, -1, 0);
        require(near(nadir[1], 1.0f), "天底 -> v=1");

        float[] back = SkyUvMath.equirect(0, 0, 0, 0, 0, -1);
        require(near(Math.abs(back[0]), 1.0f) || near(back[0], 1.0f), "-Z 落在 u 的接缝上");
    }

    /** u 在接缝处必须连续（否则方块面会出现一条竖着的地平线）。 */
    private static void equirectContinuity() {
        float[] justEast = SkyUvMath.equirect(0, 0, 0, (float) Math.sin(0.001), 0, (float) Math.cos(0.001));
        float[] justWest = SkyUvMath.equirect(0, 0, 0, (float) Math.sin(-0.001), 0, (float) Math.cos(-0.001));
        require(Math.abs(justEast[0] - 0.5f) < 1e-3f && Math.abs(justWest[0] - 0.5f) < 1e-3f,
                "+Z 两侧的 u 连续");

        float[] almostBack = SkyUvMath.equirect(0, 0, 0, 0.001f, 0, -1);
        require(almostBack[0] > 0.99f || almostBack[0] < 0.01f, "-Z 附近 u 收敛到接缝两端之一");
    }

    // ── 基础设施 ───────────────────────────────────────────────────────────────────

    private static boolean near(float a, float b) { return Math.abs(a - b) < 1.0e-4f; }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + message);
    }

    private static void write(String path) {
        try {
            java.nio.file.Path target = java.nio.file.Path.of(path);
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, "PASS: SkyUvMathRegression " + checks + " checks\n");
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
    }
}
