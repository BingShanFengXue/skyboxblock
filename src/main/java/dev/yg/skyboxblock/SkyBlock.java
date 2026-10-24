package dev.yg.skyboxblock;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The ordinary item model is independent of the world-only sky block renderer.
 * Design reference: Literal Sky Block (Kristiāns Micītis, MIT); see THIRD_PARTY_NOTICES.md.
 * This current implementation is not an unchanged upstream source-file import.
 */
public final class SkyBlock extends BaseEntityBlock {
    public static final MapCodec<SkyBlock> CODEC = simpleCodec(SkyBlock::new);

    private final SkyStyle style;
    public SkyBlock(Properties properties) { this(SkyStyle.VANILLA, properties); }
    public SkyBlock(SkyStyle style, Properties properties) { super(properties); this.style = style; }
    public SkyStyle style() { return style; }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new SkyBlockEntity(pos, state); }
    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
    @Override protected boolean useShapeForLightOcclusion(BlockState state) { return false; }
    // noOcclusion/skylight propagation do not override the full-collision block's default AO value (0.2).
    // Keep a solid collision/outline, but let neighboring terrain sample this sky window like glass/air.
    @Override protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }
}
