package dev.yg.skyboxblock;

import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * 26.3 版的 {@code isViewBlocking} 适配：lambda 多了一个 {@code AABB} 参数。
 */
public final class BlockPropertyCompat {
    private BlockPropertyCompat() { }

    public static BlockBehaviour.Properties viewBlocking(BlockBehaviour.Properties properties) {
        return properties.isViewBlocking((state, level, pos, box) -> false);
    }
}
