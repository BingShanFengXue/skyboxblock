package dev.yg.skyboxblock;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 26.1/26.2 版的 {@code isViewBlocking} 适配：这一代 lambda 是 {@code (state, level, pos)} 三参。
 * 26.3 换成四参（多一个 {@code AABB}），因此那一版有同名类覆盖（见 src/26.3/java）。
 */
public final class BlockPropertyCompat {
    private BlockPropertyCompat() { }

    public static BlockBehaviour.Properties viewBlocking(BlockBehaviour.Properties properties) {
        return properties.isViewBlocking((state, level, pos) -> false);
    }
}
