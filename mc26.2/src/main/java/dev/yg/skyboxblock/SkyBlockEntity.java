package dev.yg.skyboxblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No sky style or per-frame cached visibility survives block/neighbor changes. */
public final class SkyBlockEntity extends BlockEntity {
    public SkyBlockEntity(BlockPos pos, BlockState state) { super(SkyboxBlockMod.SKY_BLOCK_ENTITY, pos, state); }

    /**
     * 26.2：{@code Block.shouldRenderFace} 只剩
     * {@code (BlockState own, BlockState neighbour, Direction)} —— 不再接受 level/pos。
     *
     * <p>相邻方块的贴合面一律剔除：天空盒是实心立方体，两个不同风格挨着时如果各自都画内侧面，
     * 两片共面几何会 z-fighting，实机表现为方块之间的**暗色竖缝**（26.2 首版就是这个现象）。
     */
    public boolean shouldRenderFace(Direction direction) {
        if (level == null) return true;
        BlockPos neighbor = worldPosition.relative(direction);
        BlockState neighborState = level.getBlockState(neighbor);
        if (neighborState.getBlock() instanceof SkyBlock && getBlockState().getBlock() instanceof SkyBlock)
            return false;
        return Block.shouldRenderFace(getBlockState(), neighborState, direction);
    }
}
