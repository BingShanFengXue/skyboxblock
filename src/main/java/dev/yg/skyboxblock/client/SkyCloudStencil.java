package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import java.nio.ByteBuffer;

/** One cloud color fragment per private-canvas pixel. Never attaches to a world framebuffer. */
final class SkyCloudStencil {
    private static final ThreadLocal<Boolean> RAW=ThreadLocal.withInitial(() -> false);
    private SkyCloudStencil() { }

    /** Upgrade this newly allocated, project-owned depth image; the RenderTarget still owns its ID. */
    static void install(RenderTarget canvas) {
        int depth=canvas.getDepthTextureId();
        if(depth<=0)throw new IllegalArgumentException("Private cloud canvas needs a depth texture");
        canvas.bindWrite(true);
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        SkyGlState.active(GL13.GL_TEXTURE0);
        int binding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            SkyGlState.bindTexture(depth);
            int original=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
            if(original!=GL30.GL_DEPTH32F_STENCIL8)
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH32F_STENCIL8,canvas.width,canvas.height,0,
                        GL30.GL_DEPTH_STENCIL,GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV,(ByteBuffer)null);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_STENCIL_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
            int status=GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if(status!=GL30.GL_FRAMEBUFFER_COMPLETE) {
                // Keep the original attachment usable instead of leaving an incomplete target behind.
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,original,canvas.width,canvas.height,0,
                        GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,(ByteBuffer)null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_STENCIL_ATTACHMENT,GL11.GL_TEXTURE_2D,0,0);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
                throw new IllegalStateException("Private cloud depth-stencil framebuffer incomplete: "+status);
            }
        } finally {
            SkyGlState.bindTexture(binding);SkyGlState.active(active);
        }
    }

    /** Bounded same-frame negative control, available only in an explicitly enabled owned probe. */
    static AutoCloseable rawForDiagnostics() {
        if(!Boolean.getBoolean("skyboxblock.r36.cloudDiagnostics")
                || !FabricLoader.getInstance().isModLoaded("skybox_r30_probe"))
            throw new IllegalStateException("Cloud negative control requires the owned diagnostic probe");
        boolean previous=RAW.get();RAW.set(true);return () -> RAW.set(previous);
    }
    static Scope save() { return new Scope(); }
    static final class Scope implements AutoCloseable {
        private final boolean enabled=GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        private final int clear=GL11.glGetInteger(GL11.GL_STENCIL_CLEAR_VALUE);
        private final int frontFunc=GL11.glGetInteger(GL11.GL_STENCIL_FUNC),frontRef=GL11.glGetInteger(GL11.GL_STENCIL_REF);
        private final int frontMask=GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK),frontWrite=GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        private final int frontFail=GL11.glGetInteger(GL11.GL_STENCIL_FAIL),frontDepthFail=GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL),frontPass=GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        private final int backFunc=GL11.glGetInteger(GL20.GL_STENCIL_BACK_FUNC),backRef=GL11.glGetInteger(GL20.GL_STENCIL_BACK_REF);
        private final int backMask=GL11.glGetInteger(GL20.GL_STENCIL_BACK_VALUE_MASK),backWrite=GL11.glGetInteger(GL20.GL_STENCIL_BACK_WRITEMASK);
        private final int backFail=GL11.glGetInteger(GL20.GL_STENCIL_BACK_FAIL),backDepthFail=GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_FAIL),backPass=GL11.glGetInteger(GL20.GL_STENCIL_BACK_PASS_DEPTH_PASS);
        Scope() { GL11.glDisable(GL11.GL_STENCIL_TEST); }
        void color() {
            if(RAW.get())return;
            int attachment=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_STENCIL_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if(attachment==GL11.GL_NONE)throw new IllegalStateException("Cloud deduplication requires a private stencil attachment");
            GL11.glStencilMask(0xff);GL11.glClearStencil(0);GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
            GL11.glEnable(GL11.GL_STENCIL_TEST);GL11.glStencilMask(1);
            GL11.glStencilFunc(GL11.GL_EQUAL,0,1);
            GL11.glStencilOp(GL11.GL_KEEP,GL11.GL_KEEP,GL11.GL_INCR);
        }
        @Override public void close() {
            GL20.glStencilFuncSeparate(GL11.GL_FRONT,frontFunc,frontRef,frontMask);
            GL20.glStencilFuncSeparate(GL11.GL_BACK,backFunc,backRef,backMask);
            GL20.glStencilMaskSeparate(GL11.GL_FRONT,frontWrite);GL20.glStencilMaskSeparate(GL11.GL_BACK,backWrite);
            GL20.glStencilOpSeparate(GL11.GL_FRONT,frontFail,frontDepthFail,frontPass);
            GL20.glStencilOpSeparate(GL11.GL_BACK,backFail,backDepthFail,backPass);
            GL11.glClearStencil(clear);
            if(enabled)GL11.glEnable(GL11.GL_STENCIL_TEST);else GL11.glDisable(GL11.GL_STENCIL_TEST);
        }
    }
}