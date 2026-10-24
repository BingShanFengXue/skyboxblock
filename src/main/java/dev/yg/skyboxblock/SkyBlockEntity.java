package dev.yg.skyboxblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No sky style or per-frame cached visibility survives block/neighbor changes. */
public final class SkyBlockEntity extends BlockEntity {
    public SkyBlockEntity(BlockPos pos, BlockState state) { super(SkyboxBlockMod.SKY_BLOCK_ENTITY, pos, state); }

    public boolean shouldRenderFace(Direction direction) {
        if (level == null) return true;
        BlockPos neighbor = worldPosition.relative(direction);
        if (level.getBlockState(neighbor).getBlock() instanceof SkyBlock adjacent
                && getBlockState().getBlock() instanceof SkyBlock own && adjacent.style() == own.style()) return false;
        return Block.shouldRenderFace(getBlockState(), level, worldPosition, direction, neighbor);
    }
}
