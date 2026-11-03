package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;

/** 运行时挑选 {@link SkyHost} 实现（26.3 有独立实现，见 {@link SkyHost} 注释）。 */
public final class SkyHosts {
    private static final String[] CANDIDATES = {
            "dev.yg.skyboxblock.client.v263.SkyRendererHost263",
            "dev.yg.skyboxblock.client.SkyRendererHost",
    };

    private static SkyHost impl;

    private SkyHosts() { }

    public static SkyHost get() {
        if (impl != null) return impl;
        for (String name : CANDIDATES) {
            try {
                Class<?> type = Class.forName(name);
                Object instance = type.getDeclaredConstructor().newInstance();
                impl = (SkyHost) instance;
                SkyboxBlockMod.LOGGER.info("[skyboxblock] sky host = {}", name);
                return impl;
            } catch (ClassNotFoundException absent) {
                // 该版本的实现不在这次构建里，试下一个
            } catch (ReflectiveOperationException | ClassCastException error) {
                SkyboxBlockMod.LOGGER.warn("[skyboxblock] sky host {} 初始化失败", name, error);
            }
        }
        throw new IllegalStateException("没有可用的 SkyHost 实现");
    }
}
