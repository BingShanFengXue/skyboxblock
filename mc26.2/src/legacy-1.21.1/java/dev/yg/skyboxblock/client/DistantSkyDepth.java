package dev.yg.skyboxblock.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.*;

/** Optional client adapter: current-root DH depth, never world colors or a frozen LOD state. */
public final class DistantSkyDepth {
    private static boolean registered,warned,nativeProjectionValid;
    private static long transaction=-1;
    private static Object level;
    private static float a,b,c,d,ndcScale=2,ndcBias=-1;
    private DistantSkyDepth() {}
    public static void register() {
        if(registered) return;
        DhApi.events.bind(DhApiBeforeRenderEvent.class,new DhApiBeforeRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                if(ModCompat.irisShadowPass() || ModCompat.portalRendering()) return;
                SkyWorldRenderer.Frame frame=SkyWorldRenderer.lastRoot();
                if(frame==null || !frame.root || frame.shadow) return;
                var p=event.value.dhProjectionMatrix;
                float v23=p.m23,v32=p.m32;
                nativeProjectionValid=false;
                if(Math.abs(v32+1)<1E-4F && Math.abs(p.m33)<1E-6F) { a=p.m22;b=v23;c=v32;d=p.m33;nativeProjectionValid=true; }
                else if(Math.abs(v23+1)<1E-4F && Math.abs(p.m33)<1E-6F) { a=p.m22;b=v32;c=v23;d=p.m33;nativeProjectionValid=true; }
                if(DhApi.Delayed.renderProxy!=null && String.valueOf(DhApi.Delayed.renderProxy.getDepthRange()).equals("ZERO_TO_ONE")) { ndcScale=1;ndcBias=0; }
                else { ndcScale=2;ndcBias=-1; }
                transaction=frame.rootTransaction;level=frame.level;
            }
        });
        registered=true;
    }
    public static boolean current(SkyWorldRenderer.Frame frame) {
        return registered && frame!=null && frame.root && !frame.shadow && frame.level==level && frame.rootTransaction==transaction;
    }
    /** Caller owns SkyGlState(6). Uniforms and the borrowed texture are consumed in this root view only. */
    public static boolean configure(ShaderInstance shader,SkyWorldRenderer.Frame frame) {
        boolean iris=ModCompat.irisActive();
        // Iris owns and supplies its current pipeline textures/projection directly.
        // Its DH opaque callback can precede our LevelRenderer Frame allocation;
        // native API projections still require an exact observed root transaction.
        if(iris) {
            if(frame==null || !frame.root || frame.shadow || frame.portal || SkyWorldRenderer.activeGameFrame()!=frame) return false;
        } else if(!current(frame)) return false;
        try {
            int texture;float pa=a,pb=b,pc=c,pd=d;
            if(ModCompat.irisActive()) {
                Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
                Object pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
                Object compat=pipeline.getClass().getMethod("getDHCompat").invoke(pipeline);
                texture=((Number)compat.getClass().getMethod("getDepthTex").invoke(compat)).intValue();
                Object projection=compat.getClass().getMethod("getProjection").invoke(null);
                if(!(projection instanceof Matrix4fc matrix)) return false;
                pa=matrix.m22();pb=matrix.m32();pc=matrix.m23();pd=matrix.m33();
            } else {
                if(!nativeProjectionValid || DhApi.Delayed.renderProxy==null) return false;
                var result=DhApi.Delayed.renderProxy.getDhDepthTextureGlId();
                if(!result.success || result.payload==null) return false;
                texture=result.payload;
            }
            if(texture<=0 || !GL11.glIsTexture(texture) || !Float.isFinite(pa) || !Float.isFinite(pb) || !Float.isFinite(pc) || !Float.isFinite(pd)) return false;
            SkyGlState.active(GL13.GL_TEXTURE3);SkyGlState.bindTexture(texture);
            int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
            int height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            if(width!=frame.targetViewport[2] || height!=frame.targetViewport[3]) return false;
            shader.setSampler("DistantDepth",texture);
            shader.getUniform("DistantDepthEnabled").set(1);
            shader.getUniform("DistantProjectionDepth").set(pa,pb,pc,pd);
            shader.getUniform("DistantDepthNdc").set(ndcScale,ndcBias);
            frame.distantDepthEnabled=true;frame.distantDepthTexture=texture;
            return true;
        } catch(ReflectiveOperationException | RuntimeException | LinkageError error) {
            if(!warned) { warned=true;SkyboxBlockMod.LOGGER.warn("[skyboxblock] current-root DH depth adapter unavailable",error); }
            return false;
        }
    }
    public static void clear() { transaction=-1;level=null;nativeProjectionValid=false; }
}
