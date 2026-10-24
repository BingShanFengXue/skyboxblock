package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.*;
import java.nio.ByteBuffer;

/** Meter-only input proxy. Never paints a hand, changes scene/bloom RGB, edits the shaderpack,
 * writes its exposure image, or disables its automatic exposure. The original compute runs unchanged. */
public final class SkyExposureMeter {
    record Inputs(int atlas,int exposure,int depth,int width,int height,int unit,float pixelX,float pixelY) {}
    private static final SkyMesh MESH=new SkyMesh();
    private static final float[] QUAD={-1,-1,0,1,-1,0,1,1,0,-1,-1,0,1,1,0,-1,1,0};
    private static ShaderInstance shader;
    private static int framebuffer,texture,width,height;
    private static boolean warned;
    private SkyExposureMeter() {}
    public static void setShader(ShaderInstance instance) { shader=instance;warned=false; }

    /** ComputeProgram.use() has already bound the actual samplers; dispatch() will not update them again. */
    public static Runnable beforeCompute() {
        var frame=SkyWorldRenderer.activeGameFrame();
        if(shader==null || frame==null || !frame.root || frame.portal || frame.shadow
                || !frame.drawn || !SkyWorldRenderer.needsDarkSkyMeter(frame) || !ModCompat.irisActive()) return null;
        if(Boolean.getBoolean("skyboxblock.regression") && Boolean.getBoolean("skyboxblock.regression.negativeDarkMeter")) return null;
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if(program==0 || GL20.glGetUniformLocation(program,"img_pixelData2D")<0
                || GL20.glGetUniformLocation(program,"pixelData2D")<0 || GL20.glGetUniformLocation(program,"colortex8")<0) return null;
        int[] size=new int[3];GL20.glGetProgramiv(program,GL43.GL_COMPUTE_WORK_GROUP_SIZE,size);
        if(size[0]!=32 || size[1]!=16 || size[2]!=1) return null;
        Inputs input=ItrpSkyBridge.exposureMeterInputs(frame,program);
        if(input==null) return null;
        try {
            // The caller's dispatch barrier has not run yet; make the bloom-meter atlas available first.
            GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT|GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT);
            renderProxy(frame,input);
            int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0+input.unit());
            int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if(previous!=input.atlas()) { GL13.glActiveTexture(active);return null; }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            GL13.glActiveTexture(active);
            frame.exposureMeterCorrected=true;frame.exposureMeterSource=previous;frame.exposureMeterProxy=texture;
            return () -> {
                int current=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
                GL13.glActiveTexture(GL13.GL_TEXTURE0+input.unit());GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);
                frame.exposureMeterRestored=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)==previous;
                GL13.glActiveTexture(current);
            };
        } catch(RuntimeException error) {
            if(!warned) {warned=true;SkyboxBlockMod.LOGGER.warn("[skyboxblock] dark-sky meter proxy unavailable",error);}
            return null;
        }
    }
    private static void renderProxy(SkyWorldRenderer.Frame frame,Inputs input) {
        try(SkyGlState state=new SkyGlState(6)) {
            if(framebuffer==0) framebuffer=GL30.glGenFramebuffers();
            if(texture==0 || width!=input.width() || height!=input.height()) {
                if(texture!=0) GL11.glDeleteTextures(texture);
                texture=GL11.glGenTextures();width=input.width();height=input.height();
                SkyGlState.active(GL13.GL_TEXTURE0);SkyGlState.bindTexture(texture);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA16F,width,height,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            }
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Dark-sky meter target incomplete");
            com.mojang.blaze3d.platform.GlStateManager._viewport(0,0,width,height);GL11.glViewport(0,0,width,height);
            SkyGlState.opaqueState();state.clearSamplers();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();GL11.glDisable(GL11.GL_DEPTH_TEST);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);GL11.glDepthMask(false);
            GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            // Render only the exposure atlas tile (+2 texels for bilinear taps); no world framebuffer copy.
            float offset=.46875F+12F*input.pixelX();
            int left=Math.max(0,(int)Math.floor(offset*width)-2);
            int right=Math.min(width,(int)Math.ceil((offset+1F/64F)*width)+2);
            int top=Math.min(height,(int)Math.ceil(height/64F)+2);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(left,0,right-left,top);
            shader.setSampler("MeterAtlas",input.atlas());
            shader.setSampler("GeneratedSky",frame.ownSkyColorTexture);
            shader.setSampler("SkyCoverage",frame.ownSkyCoverageTexture);
            shader.setSampler("WorldOpaqueDepth",input.depth());
            shader.setSampler("ExposureData",input.exposure());
            shader.getUniform("TextureExtent").set((float)width,(float)height);
            shader.getUniform("MeterOffset").set(offset,0F);
            shader.getUniform("CoverageTransform").set(frame.rasterScale.x,frame.rasterScale.y,
                    frame.rasterJitter.x*.5F,frame.rasterJitter.y*.5F);
            MESH.draw(shader,QUAD,QUAD.length,false);
        }
    }
    public static void release() {
        MESH.close();
        if(texture!=0) GL11.glDeleteTextures(texture);
        if(framebuffer!=0) GL30.glDeleteFramebuffers(framebuffer);
        texture=framebuffer=width=height=0;
    }
}
