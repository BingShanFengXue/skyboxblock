package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyStyle;

/**
 * 风格 → 渲染方式的映射回归。
 *
 * <p>1.21.1 用 `shaderMode` 决定"这一格显示什么"：0 = 当前维度真实天空、1 = 纯白、2 = 纯黑、3 = 自己的全景图。
 * 26.2 移植后这条语义靠 `SkySkin.shaderMode()` + `texture()` 两件事共同表达，
 * 一旦映射错，玩家看到的就是"本该纯黑的格子显示天空"这种硬伤，而它在游戏里只有肉眼能发现。
 *
 * <p>运行：{@code gradlew regSkyStyleMapping}
 */
public final class SkyStyleMappingRegression {
    private static int checks;
    private static String report;

    public static void main(String[] args) {
        if (args.length > 0) report = args[0];
        everyStyleHasIdentity();
        shaderModeSemantics();
        panoramaOnlyForModeThree();
        System.out.println("SkyStyleMappingRegression: " + checks + " checks OK");
        if (report != null) write(report);
    }

    /** 每个风格都必须有稳定的方块 id，且互不重复（id 会进存档）。 */
    private static void everyStyleHasIdentity() {
        var seen = new java.util.HashSet<String>();
        for (SkyStyle style : SkyStyle.values()) {
            require(style.blockId() != null && !style.blockId().isBlank(), style + " 有方块 id");
            require(seen.add(style.blockId()), style + " 的方块 id 唯一：" + style.blockId());
            require(style.skin() != null, style + " 能取到 skin");
            require(style.skin().id() != null, style + " 的 skin 有 id");
        }
    }

    /** shaderMode 只能是 0/1/2/3，且语义固定。 */
    private static void shaderModeSemantics() {
        require(SkyStyle.VANILLA.shaderMode() == 0, "原版风格 = mode 0（显示真实天空）");
        require(SkyStyle.WHITE.shaderMode() == 1, "纯白 = mode 1");
        require(SkyStyle.BLACK.shaderMode() == 2, "纯黑 = mode 2");
        for (SkyStyle style : SkyStyle.values()) {
            int mode = style.shaderMode();
            require(mode >= 0 && mode <= 3, style + " 的 mode 在 0..3 内：" + mode);
        }
    }

    /** mode 0/1/2 不带贴图（靠天空帧或顶点色），mode 3 必须带全景图。 */
    private static void panoramaOnlyForModeThree() {
        for (SkyStyle style : SkyStyle.values()) {
            int mode = style.shaderMode();
            if (mode == 3) {
                require(style.panorama() != null, style + "（mode 3）声明了全景图名");
                require(style.skin().texture() != null, style + "（mode 3）解析出了纹理");
            } else {
                require(style.panorama() == null, style + "（mode " + mode + "）不该有全景图名");
            }
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError("FAILED: " + message);
    }

    private static void write(String path) {
        try {
            java.nio.file.Path target = java.nio.file.Path.of(path);
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, "PASS: SkyStyleMappingRegression " + checks + " checks\n");
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
    }
}
