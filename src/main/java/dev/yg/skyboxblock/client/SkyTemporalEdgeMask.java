package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import java.util.Arrays;

/** Conservative temporal FOREGROUND ownership only. Never samples or copies world colors. */
public final class SkyTemporalEdgeMask {
    private static ShaderInstance shader;
    private static int framebuffer,width,height,read;
    private static final int[] textures=new int[2];
    private static Object level;
    private static double cameraX,cameraY,cameraZ;
    private static final Matrix4f previousView=new Matrix4f(),previousProjection=new Matrix4f();
    private static long signature,firstJitter;
    private static int blocks,vertices,observedJitters,jitterPeriod;
    private static boolean poseValid;
    private SkyTemporalEdgeMask() {}
    public static void setShader(ShaderInstance value) { shader=value;poseValid=false;observedJitters=jitterPeriod=0; }
    public static void idle() { poseValid=false; }
    public static int update(SkyWorldRenderer.Frame frame,int[] viewport,int worldDepth,int depthWidth,int depthHeight) {
        if(shader==null || !Boolean.parseBoolean(System.getProperty("skyboxblock.edgeHistory","true"))) return 0;
        int w=viewport[2],h=viewport[3];
        if(w<=0 || h<=0 || (long)w*h>16_777_216L) return 0;
        try(SkyGlState saved=new SkyGlState(6)) {
            if(width!=w || height!=h || textures[0]==0) {
                release();width=w;height=h;framebuffer=GL30.glGenFramebuffers();
                for(int i=0;i<2;i++) {
                    textures[i]=GL11.glGenTextures();SkyGlState.active(GL13.GL_TEXTURE0);SkyGlState.bindTexture(textures[i]);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RG32F,w,h,0,GL30.GL_RG,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
                }
            }
            long jitter=((long)Float.floatToRawIntBits(frame.rasterJitter.x)<<32)^(Float.floatToRawIntBits(frame.rasterJitter.y)&0xffffffffL);
            if(observedJitters==0)firstJitter=jitter;
            else if(jitterPeriod==0 && jitter==firstJitter && observedJitters<=96)jitterPeriod=observedJitters;
            observedJitters=Math.min(97,observedJitters+1);
            int history=jitterPeriod>0 ? Math.min(96,jitterPeriod+1) : 32;
            boolean changed=!poseValid || level!=frame.level || cameraX!=frame.camera.x || cameraY!=frame.camera.y || cameraZ!=frame.camera.z
                    || signature!=frame.geometrySignature || blocks!=frame.blocks() || vertices!=frame.vertices()
                    || !previousView.equals(frame.view) || previousProjection.m00()!=frame.projection.m00() || previousProjection.m11()!=frame.projection.m11()
                    || previousProjection.m22()!=frame.projection.m22() || previousProjection.m32()!=frame.projection.m32();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            SkyGlState.opaqueState();saved.clearSamplers();
            if(changed) for(int texture:textures) {
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
                GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});
            }
            int write=1-read;
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,textures[write],0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) return 0;
            int location=GL30.glGetFragDataLocation(shader.getId(),"fragGuard");
            if(location<0) return 0;
            int[] outputs=new int[location+1];Arrays.fill(outputs,GL11.GL_NONE);outputs[location]=GL30.GL_COLOR_ATTACHMENT0;GL20.glDrawBuffers(outputs);
            SkyGlState.opaqueState();saved.restoreClipTests();saved.clearSamplers();
            RenderSystem.disableDepthTest();GL11.glDisable(GL11.GL_DEPTH_TEST);RenderSystem.depthMask(false);GL11.glDepthMask(false);
            GlStateManager._viewport(0,0,w,h);GL11.glViewport(0,0,w,h);
            shader.setSampler("WorldDepth",worldDepth);shader.setSampler("SkyCoverage",frame.ownSkyCoverageTexture==0 ? worldDepth : frame.ownSkyCoverageTexture);
            shader.setSampler("HistoryMask",textures[read]);
            shader.getUniform("CoverageEnabled").set(frame.ownSkyCoverageTexture==0 ? 0 : 1);
            shader.getUniform("FrameStamp").set((int)(frame.rootTransaction&65535));shader.getUniform("HistoryFrames").set((float)history);
            SkyWorldRenderer.geometryUniforms(shader,frame,new int[]{0,0,w,h},true);
            shader.getUniform("DepthUvTransform").set(frame.rasterScale.x,frame.rasterScale.y,
                    (float)(frame.viewport[0]-frame.targetViewport[0])/depthWidth+frame.rasterJitter.x*.5F,
                    (float)(frame.viewport[1]-frame.targetViewport[1])/depthHeight+frame.rasterJitter.y*.5F);
            SkyWorldRenderer.drawDiagnosticFaces(frame,shader);read=write;
            poseValid=true;level=frame.level;cameraX=frame.camera.x;cameraY=frame.camera.y;cameraZ=frame.camera.z;
            signature=frame.geometrySignature;blocks=frame.blocks();vertices=frame.vertices();previousView.set(frame.view);previousProjection.set(frame.projection);
            frame.edgeHistoryUsed=true;frame.edgeHistoryFrames=history;
            return textures[read];
        } finally {
            if(framebuffer!=0) {
                int bound=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,0,0);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,bound);
            }
        }
    }
    public static void release() {
        for(int i=0;i<2;i++) { if(textures[i]!=0)GL11.glDeleteTextures(textures[i]);textures[i]=0; }
        if(framebuffer!=0)GL30.glDeleteFramebuffers(framebuffer);
        framebuffer=width=height=read=0;level=null;poseValid=false;observedJitters=jitterPeriod=0;
    }
}
