package dev.yg.skyboxblock.client.state;

import dev.yg.skyboxblock.SkySkin;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;

/**
 * 26.2 的 BE 渲染拆成两步：{@code extractRenderState} 抽数据、{@code submit} 提交几何。
 * 所以"这一帧要画什么"必须在这里显式带过去，不能再像 1.21.1 那样在 render 里直接读 BlockEntity。
 */
public final class SkyBoxRenderState extends BlockEntityRenderState {
    public SkySkin skin;
    public boolean north = true;
    public boolean south = true;
    public boolean east = true;
    public boolean west = true;
    public boolean up = true;
    public boolean down = true;
}
