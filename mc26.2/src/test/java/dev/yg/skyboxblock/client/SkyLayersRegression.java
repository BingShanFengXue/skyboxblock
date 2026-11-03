package dev.yg.skyboxblock.client;

/**
 * {@link SkyLayers} 的降级路径回归。
 *
 * <p>雾层/云层的实现类只在 26.2 的源码集里编译（它们用的 Blaze3D API 在 26.1 上不存在）。
 * 26.1 构建里 `SkyLayers` 的反射调用会找不到类——这个套件保证那条路径**安静地什么都不做**，
 * 而不是把异常抛到渲染线程里（那会直接崩游戏）。
 *
 * <p>运行：{@code gradlew regSkyLayers}
 */
public final class SkyLayersRegression {
    private static int checks;
    private static String report;

    public static void main(String[] args) {
        if (args.length > 0) report = args[0];
        nullArgumentsAreSafe();
        repeatedCallsAreSafe();
        System.out.println("SkyLayersRegression: " + checks + " checks OK");
        if (report != null) write(report);
    }

    /** 参数为 null（26.1 上还会叠加"类不存在"）时必须静默返回。 */
    private static void nullArgumentsAreSafe() {
        requireNoThrow(() -> SkyLayers.haze(null, null), "haze(null, null) 不抛异常");
        requireNoThrow(() -> SkyLayers.clouds(null, null), "clouds(null, null) 不抛异常");
    }

    /** 连续调用不应累积状态或抛异常（每帧都会调）。 */
    private static void repeatedCallsAreSafe() {
        requireNoThrow(() -> {
            for (int i = 0; i < 100; i++) {
                SkyLayers.haze(null, null);
                SkyLayers.clouds(null, null);
            }
        }, "连续 100 次调用不抛异常");
    }

    private static void requireNoThrow(Runnable action, String message) {
        checks++;
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            throw new AssertionError("FAILED: " + message + " —— " + error);
        }
    }

    private static void write(String path) {
        try {
            java.nio.file.Path target = java.nio.file.Path.of(path);
            if (target.getParent() != null) java.nio.file.Files.createDirectories(target.getParent());
            java.nio.file.Files.writeString(target, "PASS: SkyLayersRegression " + checks + " checks\n");
        } catch (java.io.IOException error) {
            throw new RuntimeException(error);
        }
    }
}
