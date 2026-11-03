package dev.yg.skyboxblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 26.3 版天空盒方块。
 *
 * <p>与 26.1/26.2 的唯一差别：26.3 删掉了方块 `codec()` / `simpleCodec(...)` 那一套，
 * 所以这里不再声明 `CODEC`，也不再覆写 `codec()`。其余行为逐行相同。
 */
public final class SkyBlock extends BaseEntityBlock {
    private final SkySkin skin;

    public SkyBlock(Properties properties) { this(SkyStyle.VANILLA.skin(), properties); }
    public SkyBlock(SkyStyle style, Properties properties) { this(style.skin(), properties); }
    public SkyBlock(SkySkin skin, Properties properties) { super(properties); this.skin = skin; }

    public SkySkin skin() { return skin; }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SkyBlockEntity(pos, state);
    }

    @Override protected boolean propagatesSkylightDown(BlockState state) { return true; }

    @Override protected boolean useShapeForLightOcclusion(BlockState state) { return false; }

    @Override protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }
}
