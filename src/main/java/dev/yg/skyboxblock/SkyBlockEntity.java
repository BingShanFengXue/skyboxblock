package dev.yg.skyboxblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** No sky style or per-frame cached visibility survives block/neighbor changes. */
public final class SkyBlockEntity extends BlockEntity {
    /**
     * Loaded sky windows, maintained across chunk load/unload. Renderer section lists can be stale or
     * replaced (Sodium owns block-entity extraction in some setups), so the opaque pass uses this
     * index as the authoritative fallback instead of trusting a single renderer's section list.
     */
    private static final java.util.Set<SkyBlockEntity> LOADED=java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));
    public static SkyBlockEntity[] loaded() {
        synchronized(LOADED) { return LOADED.toArray(new SkyBlockEntity[0]); }
    }
    public SkyBlockEntity(BlockPos pos, BlockState state) { super(SkyboxBlockMod.SKY_BLOCK_ENTITY, pos, state); }
    @Override public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        synchronized(LOADED) {
            if(level!=null && level.isClientSide()) LOADED.add(this); else LOADED.remove(this);
        }
    }
    @Override public void setRemoved() {
        synchronized(LOADED) { LOADED.remove(this); }
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
