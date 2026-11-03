package dev.yg.skyboxblock.mixin;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.lang.reflect.Method;
import java.util.Set;

/** Sky windows are not textured solid LOD cubes. Keep DH terrain live; exclude only this block's LOD material. */
@Pseudo
@Mixin(targets="com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper",remap=false)
public abstract class DistantBlockIgnoreMixin {
    @Unique private static volatile java.util.List<Object> skyboxblock$skyWrappers;
    @Unique private static boolean skyboxblock$warned;
    @Unique private static final ThreadLocal<Boolean> skyboxblock$inside=ThreadLocal.withInitial(() -> false);

    @SuppressWarnings("unchecked")
    @Inject(method="getRendererIgnoredBlocks",at=@At("RETURN"),require=0,remap=false)
    private static void skyboxblock$ignoreOnlySkyMaterial(@Coerce Object level,CallbackInfoReturnable<?> cir) {
        if(skyboxblock$inside.get() || !(cir.getReturnValue() instanceof Set<?> raw)) return;
        java.util.List<Object> wrappers=skyboxblock$skyWrappers;
        if(wrappers!=null && raw.containsAll(wrappers)) return;
        skyboxblock$inside.set(true);
        try {
            synchronized(raw) {
                wrappers=skyboxblock$skyWrappers;
                if(wrappers==null) {
                    Class<?> type=Class.forName("com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper");
                    Class<?> levelType=Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper");
                    Method factory=type.getMethod("fromBlockState",BlockState.class,levelType);
                    java.util.List<Object> created=new java.util.ArrayList<>();
                    for(var block:SkyboxBlockMod.SKY_BLOCKS.values()) {
                        Object wrapper=factory.invoke(null,block.defaultBlockState(),level);
                        if(wrapper==null) throw new IllegalStateException("DH sky block wrapper unavailable");
                        created.add(wrapper);
                    }
                    wrappers=java.util.List.copyOf(created);
                    skyboxblock$skyWrappers=wrappers;
                }
                ((Set<Object>)raw).addAll(wrappers);
            }
        } catch(ReflectiveOperationException | RuntimeException error) {
            if(!skyboxblock$warned) {
                skyboxblock$warned=true;
                SkyboxBlockMod.LOGGER.warn("[skyboxblock] optional DH sky-material exclusion unavailable",error);
            }
        } finally { skyboxblock$inside.set(false); }
    }
}
