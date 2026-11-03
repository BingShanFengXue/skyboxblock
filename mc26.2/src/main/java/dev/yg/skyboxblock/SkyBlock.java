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
    /** 26.1/26.2 需要方块 codec；26.3 把这一套删了（见 src/26.3 的同名文件）。 */
    public static final MapCodec<SkyBlock> CODEC = simpleCodec(SkyBlock::new);

    private final SkySkin skin;
    public SkyBlock(Properties properties) { this(SkyStyle.VANILLA.skin(), properties); }
    public SkyBlock(SkyStyle style, Properties properties) { this(style.skin(), properties); }
    public SkyBlock(SkySkin skin, Properties properties) { super(properties); this.skin = skin; }
    public SkySkin skin() { return skin; }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new SkyBlockEntity(pos, state); }
    // 26.2：签名简化为 (BlockState)，且是 protected。
    @Override protected boolean propagatesSkylightDown(BlockState state) { return true; }
    @Override protected boolean useShapeForLightOcclusion(BlockState state) { return false; }
    // noOcclusion/skylight propagation do not override the full-collision block's default AO value (0.2).
    // Keep a solid collision/outline, but let neighboring terrain sample this sky window like glass/air.
    @Override protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }
}
