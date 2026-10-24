package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.*;

/** Per-view sample coverage and nearest SKY ownership. Stores only generated sky, never world RGB. */
final class SkySurfaceLayer {
    private static final SkyMesh QUAD=new SkyMesh();
    private static final float[] VERTICES={-1,-1,0, 1,-1,0, 1,1,0, -1,-1,0, 1,1,0, -1,1,0};
    private static ShaderInstance composite;
    private static final boolean NATIVE_DH_COLOR=net.fabricmc.loader.api.FabricLoader.getInstance()
            .getModContainer("distanthorizons").map(c -> c.getMetadata().getVersion().getFriendlyString().equals("3.3.3")).orElse(false);
    private static int multisample,resolve,color,depth,texture,width,height,samples;
    private SkySurfaceLayer() {}
    static void setShader(ShaderInstance shader) { composite=shader; }
    static boolean available(SkyWorldRenderer.Frame frame) {
        return composite!=null && frame.root && !frame.portal && !frame.shadow
                && !(Boolean.getBoolean("skyboxblock.regression") && Boolean.getBoolean("skyboxblock.regression.negativeSurfaceLayer"))
                && GL11.glGetInteger(GL30.GL_MAX_SAMPLES)>=4;
    }
    /** Caller owns SkyGlState(6). Borrow ONLY the native 3.3.3 apply pass's verified source textures. */
    static int nativeBackgroundColor(SkyWorldRenderer.Frame frame,int layer,int[] viewport) {
        frame.nativeBackgroundReady=false;frame.nativeBackgroundColorTexture=0;
        if(!NATIVE_DH_COLOR || ModCompat.irisActive() || !frame.root || frame.portal || frame.shadow
                || !DistantSkyDepth.current(frame) || !frame.distantDepthEnabled) return 0;
        try {
            Object proxy=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy").get(null);
            if(proxy==null || !Boolean.TRUE.equals(proxy.getClass().getMethod("isNativeRenderer").invoke(proxy))) return 0;
            Object dr=proxy.getClass().getMethod("getDhDepthTextureGlId").invoke(proxy);
            Object cr=proxy.getClass().getMethod("getDhColorTextureGlId").invoke(proxy);
            if(!dr.getClass().getField("success").getBoolean(dr) || !cr.getClass().getField("success").getBoolean(cr)) return 0;
            int depth=((Number)dr.getClass().getField("payload").get(dr)).intValue();
            int color=((Number)cr.getClass().getField("payload").get(cr)).intValue();
            Object renderer=Class.forName("com.seibel.distanthorizons.common.render.openGl.GlDhMetaRenderer").getField("INSTANCE").get(null);
            if(renderer==null || depth!=frame.distantDepthTexture
                    || depth!=((Number)renderer.getClass().getMethod("getActiveDepthTextureId").invoke(renderer)).intValue()
                    || color!=((Number)renderer.getClass().getMethod("getActiveColorTextureId").invoke(renderer)).intValue()) return 0;
            int main=net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().getColorTextureId();
            if(color<=0 || color==layer || color==frame.ownSkyColorTexture || color==main || !GL11.glIsTexture(color)) return 0;
            SkyGlState.active(GL13.GL_TEXTURE1);SkyGlState.bindTexture(color);
            int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
            int h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            int format=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if(w!=viewport[2] || h!=viewport[3] || w!=frame.targetViewport[2] || h!=frame.targetViewport[3]
                    || !(format==GL11.GL_RGBA8 || format==GL11.GL_RGB8 || format==GL30.GL_RGBA16F
                    || format==GL30.GL_RGB16F || format==GL30.GL_RGBA32F || format==GL30.GL_RGB32F)) return 0;
            SkyGlState.bindTexture(main);
            if(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT)!=GL11.GL_RGBA8) return 0;
            frame.nativeBackgroundReady=true;frame.nativeBackgroundColorTexture=color;return color;
        } catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return 0; }
    }

    /** Caller owns shader uniforms and samplers. All mutable GL state is scoped, including renderbuffers/MSAA. */
    static int render(SkyWorldRenderer.Frame frame,ShaderInstance shader,int sky,int[] viewport) {
        int w=viewport[2],h=viewport[3];
        if(!available(frame) || w<=0 || h<=0 || (long)w*h>16_777_216L) return 0;
        int renderbuffer=GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        boolean msaa=GL11.glIsEnabled(GL13.GL_MULTISAMPLE);
        boolean alphaCoverage=GL11.glIsEnabled(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);
        boolean sampleCoverage=GL11.glIsEnabled(GL13.GL_SAMPLE_COVERAGE);
        boolean alphaOne=GL11.glIsEnabled(GL13.GL_SAMPLE_ALPHA_TO_ONE);
        boolean sampleMask=GL11.glIsEnabled(GL32.GL_SAMPLE_MASK);
        try(SkyGlState state=new SkyGlState(6)) {
            prepare(w,h);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,multisample);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) return 0;
            SkyGlState.opaqueState();state.clearSamplers();
            GL11.glEnable(GL13.GL_MULTISAMPLE);
            GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE);GL11.glDisable(GL13.GL_SAMPLE_COVERAGE);
            GL11.glDisable(GL13.GL_SAMPLE_ALPHA_TO_ONE);GL11.glDisable(GL32.GL_SAMPLE_MASK);
            GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
            GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{1});
            GlStateManager._viewport(0,0,w,h);GL11.glViewport(0,0,w,h);
            shader.getUniform("SurfaceLayer").set(1);
            SkyWorldRenderer.geometryUniforms(shader,frame,new int[]{0,0,w,h},true);
            SkyWorldRenderer.drawMaterialFaces(frame,shader,sky);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,multisample);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,resolve);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) return 0;
            GL30.glBlitFramebuffer(0,0,w,h,0,0,w,h,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);
            frame.surfaceLayerUsed=true;frame.surfaceLayerSamples=samples;
            return texture;
        } finally {
            shader.getUniform("SurfaceLayer").set(0);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,renderbuffer);
            toggle(GL13.GL_MULTISAMPLE,msaa);toggle(GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,alphaCoverage);toggle(GL13.GL_SAMPLE_COVERAGE,sampleCoverage);
            toggle(GL13.GL_SAMPLE_ALPHA_TO_ONE,alphaOne);toggle(GL32.GL_SAMPLE_MASK,sampleMask);
        }
    }
    /** Resolved RGB is already multiplied by geometric coverage; use premultiplied blending. */
    static void composite(int layer,int[] viewport) { composite(layer,viewport,false,0,0); }
    static void composite(int layer,int[] viewport,boolean fullCoverageOnly,int nativeDepth,int nativeColor) {
        try(SkyGlState state=new SkyGlState(6)) {
            SkyGlState.opaqueState();state.restoreClipTests();state.clearSamplers();
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            RenderSystem.disableDepthTest();GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthMask(false);GL11.glDepthMask(false);
            RenderSystem.enableBlend();GL11.glEnable(GL11.GL_BLEND);
            GlStateManager._blendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,GL11.GL_ONE);
            GL14.glBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ZERO,GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);
            GlStateManager._viewport(viewport[0],viewport[1],viewport[2],viewport[3]);GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            composite.setSampler("SurfaceColor",layer);
            composite.getUniform("FullCoverageOnly").set(fullCoverageOnly ? 1 : 0);
            composite.getUniform("NativeBackgroundEnabled").set(fullCoverageOnly && nativeDepth>0 && nativeColor>0 ? 1 : 0);
            composite.setSampler("NativeDepth",nativeDepth>0 ? nativeDepth : layer);
            composite.setSampler("NativeColor",nativeColor>0 ? nativeColor : layer);
            composite.getUniform("SkyViewport").set((float)viewport[0],(float)viewport[1],(float)viewport[2],(float)viewport[3]);
            QUAD.draw(composite,VERTICES,VERTICES.length,false);
        }
    }
    private static void prepare(int w,int h) {
        if(texture!=0 && width==w && height==h)return;
        release();width=w;height=h;samples=4;
        multisample=GL30.glGenFramebuffers();resolve=GL30.glGenFramebuffers();
        color=GL30.glGenRenderbuffers();depth=GL30.glGenRenderbuffers();texture=GL11.glGenTextures();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,multisample);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,color);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER,samples,GL11.GL_RGBA8,w,h);
        GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_RENDERBUFFER,color);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER,depth);
        GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER,samples,GL30.GL_DEPTH_COMPONENT32F,w,h);
        GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_RENDERBUFFER,depth);
        SkyGlState.active(GL13.GL_TEXTURE0);SkyGlState.bindTexture(texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,w,h,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,(java.nio.ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,resolve);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
    }
    static void release() {
        if(texture!=0)GL11.glDeleteTextures(texture);
        if(color!=0)GL30.glDeleteRenderbuffers(color);
        if(depth!=0)GL30.glDeleteRenderbuffers(depth);
        if(multisample!=0)GL30.glDeleteFramebuffers(multisample);
        if(resolve!=0)GL30.glDeleteFramebuffers(resolve);
        texture=color=depth=multisample=resolve=width=height=samples=0;QUAD.close();
    }
    private static void toggle(int cap,boolean value) { if(value)GL11.glEnable(cap);else GL11.glDisable(cap); }
}
