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
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
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
    private final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
    private final boolean stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final int frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
    private final int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
    private final boolean depthMask;
    private final float[] shaderColor = RenderSystem.getShaderColor().clone();
    private final float[] clearColor = new float[4];
    private final float clearDepth = GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
    private final double[] depthRange = new double[2];
    private final boolean clipDistance0 = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
    private final int drawBufferLimit = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
    private final int[] drawBuffers = snapshotDrawBuffers();
    private final boolean indexedBlend = GL.getCapabilities().OpenGL40
            || GL.getCapabilities().GL_ARB_draw_buffers_blend;
    // These are context state, not FBO state: globals touch all slots even while FBO 0 is bound.
    private final boolean[] blendEnabled = new boolean[drawBufferLimit];
    private final int[][] blendParameters = new int[indexedBlend ? drawBufferLimit : 1][6];
    private final boolean[][] attachmentColorMasks = new boolean[drawBufferLimit][4];
    private final ModCompat.IrisTargetScope irisTarget;

    private int[] snapshotDrawBuffers() {
        int[] slots = new int[drawFramebuffer == 0 ? 1 : drawBufferLimit];
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
            GL11.glGetBooleanv(GL11.GL_DEPTH_WRITEMASK, mask);
            depthMask = mask.get(0) != 0;
            int[] blendParams = { GL14.GL_BLEND_SRC_RGB, GL14.GL_BLEND_DST_RGB,
                    GL14.GL_BLEND_SRC_ALPHA, GL14.GL_BLEND_DST_ALPHA,
                    GL20.GL_BLEND_EQUATION_RGB, GL20.GL_BLEND_EQUATION_ALPHA };
            for (int i = 0; i < drawBufferLimit; i++) {
                blendEnabled[i] = GL30.glIsEnabledi(GL11.GL_BLEND, i);
                GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, i, mask);
                for (int channel = 0; channel < 4; channel++)
                    attachmentColorMasks[i][channel] = mask.get(channel) != 0;
            }
            for (int i = 0; i < blendParameters.length; i++) {
                for (int parameter = 0; parameter < blendParams.length; parameter++)
                    blendParameters[i][parameter] = indexedBlend
                            ? GL30.glGetIntegeri(blendParams[parameter], i)
                            : GL11.glGetInteger(blendParams[parameter]);
            }
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
        if (blendEnabled[0]) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        toggle(GL11.GL_SCISSOR_TEST, scissor);
        toggle(GL11.GL_STENCIL_TEST, stencil);
        RenderSystem.depthFunc(depthFunc);
        GL11.glDepthFunc(depthFunc);
        RenderSystem.depthMask(depthMask);
        GL11.glDepthMask(depthMask);
        boolean[] firstColorMask = attachmentColorMasks[0];
        // Keep RenderSystem's single-slot cache aligned with attachment zero, then restore all
        // physical per-attachment masks below because the vanilla setter broadcasts to every slot.
        RenderSystem.colorMask(firstColorMask[0], firstColorMask[1], firstColorMask[2], firstColorMask[3]);
        int[] firstFactors = blendParameters[0];
        GlStateManager._blendFuncSeparate(firstFactors[0], firstFactors[1], firstFactors[2], firstFactors[3]);
        for (int i = 0; i < drawBufferLimit; i++) {
            if (blendEnabled[i]) GL30.glEnablei(GL11.GL_BLEND, i);
            else GL30.glDisablei(GL11.GL_BLEND, i);
            boolean[] mask = attachmentColorMasks[i];
            GL30.glColorMaski(i, mask[0], mask[1], mask[2], mask[3]);
            int[] factors = blendParameters[indexedBlend ? i : 0];
            if (indexedBlend) {
                if (GL.getCapabilities().OpenGL40) {
                    GL40.glBlendFuncSeparatei(i, factors[0], factors[1], factors[2], factors[3]);
                    GL40.glBlendEquationSeparatei(i, factors[4], factors[5]);
                } else {
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(i, factors[0], factors[1], factors[2], factors[3]);
                    ARBDrawBuffersBlend.glBlendEquationSeparateiARB(i, factors[4], factors[5]);
                }
            } else if (i == 0) {
                GL14.glBlendFuncSeparate(factors[0], factors[1], factors[2], factors[3]);
                GL20.glBlendEquationSeparate(factors[4], factors[5]);
            }
        }
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
