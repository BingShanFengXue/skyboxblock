package dev.yg.skyboxblock;

import org.slf4j.Logger;

/**
 * 真实 SkyboxBlockMod 需要整个 MC 注册表，对本测试无意义。
 *
 * <p>被测类只用到它的 {@code LOGGER} 静态字段（warn/info 两种），所以这里放同名字段，
 * 由 classpath 顺序保证被测字节码拿到的是这个 stub 而不是生产 class。</p>
 */
public final class SkyboxBlockMod {
    public static final String MOD_ID = "skyboxblock";
    public static final Logger LOGGER = new CapturingLogger();

    private SkyboxBlockMod() { }
}
