package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** Owned depth provenance, never world RGB. Borrowed depth attachments are neither cleared nor written. */
final class SkyCoverage {
    private static final ArrayList<Slot> SLOTS=new ArrayList<>();
    private static ShaderInstance shader;
    private static boolean warned;
    private SkyCoverage() {}
    static void setShader(ShaderInstance instance) { shader=instance; }

    static int record(SkyWorldRenderer.Frame frame) {
        if ((!ModCompat.irisActive() && !ModCompat.DISTANT_HORIZONS) || shader==null || frame.floatCount==0 || frame.viewSlot>=32
                || frame.targetViewport[0]!=0 || frame.targetViewport[1]!=0) return 0;
        if (Boolean.getBoolean("skyboxblock.regression") && Boolean.getBoolean("skyboxblock.regression.negativeSkyCoverage")) return 0;
        try (SkyGlState state=new SkyGlState(6)) {
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,frame.framebuffer);
            int depthType=type(GL30.GL_DEPTH_ATTACHMENT),depthName=name(GL30.GL_DEPTH_ATTACHMENT,depthType);
            int stencilType=type(GL30.GL_STENCIL_ATTACHMENT),stencilName=name(GL30.GL_STENCIL_ATTACHMENT,stencilType);
            if (depthName==0) return 0;
            while (SLOTS.size()<=frame.viewSlot) SLOTS.add(new Slot());
            Slot slot=SLOTS.get(frame.viewSlot);
            slot.prepare(frame.targetViewport[2],frame.targetViewport[3]);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,slot.framebuffer);
            attach(GL30.GL_DEPTH_ATTACHMENT,depthType,depthName);
            attach(GL30.GL_STENCIL_ATTACHMENT,stencilType,stencilName);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE) {
                if (!warned) { warned=true; SkyboxBlockMod.LOGGER.warn("[skyboxblock] sky depth provenance target incomplete; retaining strict depth resolve"); }
                return 0;
            }
            SkyGlState.opaqueState();
            GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0}); // Color ONLY; never clear borrowed world depth.
            state.restoreClipTests(); state.clearSamplers();
            com.mojang.blaze3d.systems.RenderSystem.depthFunc(GL11.GL_EQUAL);
            GL11.glDepthFunc(GL11.GL_EQUAL);
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false); GL11.glDepthMask(false);
            com.mojang.blaze3d.platform.GlStateManager._viewport(0,0,slot.width,slot.height);
            GL11.glViewport(0,0,slot.width,slot.height);
            SkyWorldRenderer.geometryUniforms(shader,frame,frame.viewport,false);
            SkyWorldRenderer.drawCoverageFaces(frame,shader);
            return slot.color;
        }
    }
    private static int type(int attachment) {
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
    }
    private static int name(int attachment,int type) {
        return type==GL11.GL_NONE ? 0 : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }
    private static void attach(int attachment,int type,int name) {
        if (type==GL30.GL_RENDERBUFFER) GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL30.GL_RENDERBUFFER,name);
        else GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,name,0);
    }
    static void release() {
        for (Slot slot:SLOTS) slot.close();
        SLOTS.clear(); warned=false;
    }
    private static final class Slot {
        int framebuffer,color,width,height;
        void prepare(int w,int h) {
            if (framebuffer==0) framebuffer=GL30.glGenFramebuffers();
            if (color!=0 && width==w && height==h) return;
            if (color!=0) GL11.glDeleteTextures(color);
            color=GL11.glGenTextures(); width=w; height=h;
            SkyGlState.active(GL13.GL_TEXTURE0); SkyGlState.bindTexture(color);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RG32F,w,h,0,GL30.GL_RG,GL11.GL_FLOAT,(ByteBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
        }
        void close() {
            if (color!=0) GL11.glDeleteTextures(color);
            if (framebuffer!=0) GL30.glDeleteFramebuffers(framebuffer);
            color=framebuffer=width=height=0; // Borrowed depth objects belong to Iris, never delete them.
        }
    }
}
