package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

/** Restores both real GL bindings and Minecraft's cached state after a private render operation. */
final class SkyGlState implements AutoCloseable {
    final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    final int readBuffer = GL11.glGetInteger(GL11.GL_READ_BUFFER);
    final int[] viewport = new int[4];
    private final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] textures;
    private final int[] shaderTextures;
    private final int[] samplers;
    private final boolean samplerObjects = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
    private final ShaderInstance shader = RenderSystem.getShader();
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
    private final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
    private final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
    private final int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
    private final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    private final int dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
    private final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
    private final int dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
    private final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    private final int equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
    private final boolean[] colorMask = new boolean[4];
    private final boolean depthMask;
    private final float[] shaderColor = RenderSystem.getShaderColor().clone();
    private final float[] clearColor = new float[4];
    private final float clearDepth = GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
    private final double[] depthRange = new double[2];
    private final boolean clipDistance0 = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
    private final int[] drawBuffers = snapshotDrawBuffers();
    private final ModCompat.IrisTargetScope irisTarget;

    private int[] snapshotDrawBuffers() {
        int count = drawFramebuffer == 0 ? 1 : Math.min(16, GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS));
        int[] slots = new int[Math.max(1, count)];
        for (int i = 0; i < slots.length; i++) slots[i] = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i);
        return slots;
    }

    int firstDrawBuffer() { return drawBuffers[0]; }
    int[] colorBuffers() { return drawBuffers.clone(); }
    void restoreClipTests() {
        toggle(GL11.GL_SCISSOR_TEST, scissor);
        toggle(GL11.GL_STENCIL_TEST, stencil);
    }

    SkyGlState(int textureUnits) {
        RenderSystem.assertOnRenderThread();
        textures = new int[textureUnits];
        shaderTextures = new int[textureUnits];
        samplers = new int[textureUnits];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, depthRange);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) colorMask[i] = mask.get(i) != 0;
            GL11.glGetBooleanv(GL11.GL_DEPTH_WRITEMASK, mask);
            depthMask = mask.get(0) != 0;
        }
        for (int i = 0; i < textureUnits; i++) {
            active(GL13.GL_TEXTURE0 + i);
            textures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            shaderTextures[i] = RenderSystem.getShaderTexture(i);
            if (samplerObjects) samplers[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        active(activeTexture);
        irisTarget = ModCompat.privateTargetScope();
    }

    static void active(int unit) {
        RenderSystem.activeTexture(unit);
        GL13.glActiveTexture(unit);
    }

    static void bindTexture(int id) {
        GlStateManager._bindTexture(id);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    void clearSamplers() {
        if (samplerObjects) {
            for (int i = 0; i < textures.length; i++) GL33.glBindSampler(i, 0);
        }
    }

    static void opaqueState() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.enableDepthTest();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        GL11.glDepthMask(true);
        RenderSystem.colorMask(true, true, true, true);
        GL11.glColorMask(true, true, true, true);
        RenderSystem.disableBlend();
        GL11.glDisable(GL11.GL_BLEND);
        RenderSystem.enableCull();
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glFrontFace(GL11.GL_CCW);
        GL11.glCullFace(GL11.GL_BACK);
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    @Override
    public void close() {
        try {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL11.glReadBuffer(readBuffer);
        if (drawFramebuffer == 0) GL11.glDrawBuffer(drawBuffers[0]);
        else GL20.glDrawBuffers(drawBuffers);
        GL11.glDepthRange(depthRange[0], depthRange[1]);
        toggle(GL30.GL_CLIP_DISTANCE0, clipDistance0);
        GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        // Shader/program restoration may rebind sampler textures through optional renderer hooks.
        // Restore the logical sampler array now; restore physical bindings LAST, below.
        for (int i = 0; i < textures.length; i++) RenderSystem.setShaderTexture(i, shaderTextures[i]);
        RenderSystem.setShader(() -> shader);
        GlStateManager._glUseProgram(program);
        // drawWithShader keeps its own last-buffer cache: invalidate before restoring a foreign VAO.
        BufferUploader.invalidate();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        toggle(GL11.GL_DEPTH_TEST, depth);
        if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
        toggle(GL11.GL_CULL_FACE, cull);
        if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        toggle(GL11.GL_BLEND, blend);
        toggle(GL11.GL_SCISSOR_TEST, scissor);
        toggle(GL11.GL_STENCIL_TEST, stencil);
        RenderSystem.depthFunc(depthFunc);
        GL11.glDepthFunc(depthFunc);
        RenderSystem.depthMask(depthMask);
        GL11.glDepthMask(depthMask);
        RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
        GL11.glFrontFace(frontFace);
        GL11.glCullFace(cullFace);
        RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        RenderSystem.clearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        GL11.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        GlStateManager._clearDepth(clearDepth);
        } finally {
            try {
                irisTarget.close();
            } finally {
                // Keep real GL bindings exact even when a program/target hook touched unit zero.
                for (int i = 0; i < textures.length; i++) {
                    active(GL13.GL_TEXTURE0 + i);
                    bindTexture(textures[i]);
                    if (samplerObjects) GL33.glBindSampler(i, samplers[i]);
                }
                active(activeTexture);
            }
        }
    }

    private static void toggle(int cap, boolean enabled) {
        if (enabled) GL11.glEnable(cap); else GL11.glDisable(cap);
    }
}
