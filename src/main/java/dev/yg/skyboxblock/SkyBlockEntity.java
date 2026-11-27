package dev.yg.skyboxblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No sky style or per-frame cached visibility survives block/neighbor changes. */
public final class SkyBlockEntity extends BlockEntity {
    private static final SkyBlockSpatialIndex<SkyBlockEntity> LOADED = new SkyBlockSpatialIndex<>();
    private transient SkyBlockSpatialIndex.Handle<SkyBlockEntity> loadedHandle;

    /** Query only occupied chunk buckets within this view's horizontal render-distance square. */
    public static SkyBlockSpatialIndex.Query<SkyBlockEntity> loadedNear(
            net.minecraft.world.level.Level level, double cameraX, double cameraZ, int distance) {
        return LOADED.query(level, cameraX, cameraZ, distance);
    }
    public SkyBlockEntity(BlockPos pos, BlockState state) { super(SkyboxBlockMod.SKY_BLOCK_ENTITY, pos, state); }
    @Override public void setLevel(net.minecraft.world.level.Level level) {
        removeFromLoadedIndex();
        super.setLevel(level);
        if (level != null && level.isClientSide() && !isRemoved())
            loadedHandle = LOADED.add(level, worldPosition.getX(), worldPosition.getZ(), this);
    }
    private void removeFromLoadedIndex() {
        if (loadedHandle != null) {
            LOADED.remove(loadedHandle);
            loadedHandle = null;
        }
    }
    @Override public void setRemoved() {
        removeFromLoadedIndex();
        super.setRemoved();
    }

    public boolean shouldRenderFace(Direction direction) {
        if (level == null) return true;
        BlockPos neighbor = worldPosition.relative(direction);
        if (level.getBlockState(neighbor).getBlock() instanceof SkyBlock adjacent
                && getBlockState().getBlock() instanceof SkyBlock own && adjacent.skin().equals(own.skin())) return false;
        return Block.shouldRenderFace(getBlockState(), level, worldPosition, direction, neighbor);
    }
}
