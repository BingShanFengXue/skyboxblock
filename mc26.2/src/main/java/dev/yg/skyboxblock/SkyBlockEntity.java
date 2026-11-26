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
