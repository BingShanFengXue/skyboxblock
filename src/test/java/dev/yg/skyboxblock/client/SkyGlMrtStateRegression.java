package dev.yg.skyboxblock.client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.system.MemoryStack;

/** Hidden-context regression for the exact per-attachment MRT state used by Iris. */
public final class SkyGlMrtStateRegression {
    private static int checks;
    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    private static boolean[] colorMask(int index) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.malloc(4);
            GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK, index, value);
            return new boolean[]{value.get(0) != 0, value.get(1) != 0, value.get(2) != 0, value.get(3) != 0};
        }
    }
    private static void blendFunction(int index, boolean indexed, int src, int dst) {
        if (!indexed) {
            GL14.glBlendFuncSeparate(src, dst, GL11.GL_ONE, GL11.GL_ZERO);
        } else if (GL.getCapabilities().OpenGL40) {
            GL40.glBlendFuncSeparatei(index, src, dst, GL11.GL_ONE, GL11.GL_ZERO);
        } else {
            ARBDrawBuffersBlend.glBlendFuncSeparateiARB(index, src, dst, GL11.GL_ONE, GL11.GL_ZERO);
        }
    }
    private static int blendSource(int index, boolean indexed) {
        return indexed ? GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB, index) : GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
    }
    private static int blendEquation(int index, boolean indexed) {
        return indexed ? GL30.glGetIntegeri(GL20.GL_BLEND_EQUATION_RGB, index)
                : GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
    }
    private static void blendEquation(int index, boolean indexed, int equation) {
        if (!indexed) GL20.glBlendEquationSeparate(equation, equation);
        else if (GL.getCapabilities().OpenGL40) GL40.glBlendEquationSeparatei(index, equation, equation);
        else ARBDrawBuffersBlend.glBlendEquationSeparateiARB(index, equation, equation);
    }

    private static void checkRestore(int framebuffer, boolean indexed) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        int maxSlot = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS) - 1;
        int[] slots = {0, 1, maxSlot};
        int draw0 = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
        int draw1 = GL11.glGetInteger(GL20.GL_DRAW_BUFFER1);
        boolean[][] masks = new boolean[slots.length][];
        int[] sources = new int[slots.length], equations = new int[slots.length];
        boolean[] enabled = new boolean[slots.length];
        for (int i : slots) {
            if (i == 0) GL30.glDisablei(GL11.GL_BLEND, i); else GL30.glEnablei(GL11.GL_BLEND, i);
            GL30.glColorMaski(i, i == 0, i == 1, false, true);
            blendFunction(i, indexed, i == 0 ? GL11.GL_ONE : GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            blendEquation(i, indexed, i == 0 ? GL14.GL_FUNC_ADD : GL14.GL_FUNC_SUBTRACT);
        }
        for (int j = 0; j < slots.length; j++) {
            int i = slots[j];
            enabled[j] = GL30.glIsEnabledi(GL11.GL_BLEND, i);
            masks[j] = colorMask(i);
            sources[j] = blendSource(i, indexed);
            equations[j] = blendEquation(i, indexed);
        }
        try (SkyGlState state = new SkyGlState(3)) {
            SkyGlState.opaqueState();
            GL14.glBlendFuncSeparate(GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_REVERSE_SUBTRACT, GL14.GL_FUNC_REVERSE_SUBTRACT);
            GL11.glDrawBuffer(GL11.GL_NONE);
        }
        String label = framebuffer == 0 ? "default framebuffer" : "MRT framebuffer";
        check(GL11.glGetInteger(GL20.GL_DRAW_BUFFER0) == draw0, label + " draw buffer 0 routing restored");
        check(GL11.glGetInteger(GL20.GL_DRAW_BUFFER1) == draw1, label + " draw buffer 1 routing restored");
        for (int j = 0; j < slots.length; j++) {
            int i = slots[j];
            String slot = label + " slot " + i;
            check(GL30.glIsEnabledi(GL11.GL_BLEND, i) == enabled[j], slot + " blend-enable restored");
            check(java.util.Arrays.equals(colorMask(i), masks[j]), slot + " color mask restored");
            check(blendSource(i, indexed) == sources[j], slot + " blend factor restored");
            check(blendEquation(i, indexed) == equations[j], slot + " blend equation restored");
        }
        check(GL11.glGetError() == GL11.GL_NO_ERROR, label + " no GL error");
    }

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW initialization failed");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(32, 32, "Skybox MRT regression", 0L, 0L);
        if (window == 0L) throw new AssertionError("OpenGL 3.3 context creation failed");
        try {
            GLFW.glfwMakeContextCurrent(window);
            var capabilities = GL.createCapabilities();
            org.lwjgl.opengl.GL.createCapabilities();
            com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            if (!capabilities.OpenGL33 && !capabilities.GL_ARB_draw_buffers_blend)
                throw new AssertionError("OpenGL 3.3 or ARB_draw_buffers_blend required");
            int framebuffer = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            int[] textures = new int[2];
            for (int i = 0; i < textures.length; i++) {
                textures[i] = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[i]);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA8, 16, 16, 0,
                        GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i,
                        GL11.GL_TEXTURE_2D, textures[i], 0);
            }
            org.lwjgl.opengl.GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE,
                    "two-attachment framebuffer complete");
            boolean indexed = capabilities.OpenGL40 || capabilities.GL_ARB_draw_buffers_blend;

            checkRestore(framebuffer, indexed);
            checkRestore(0, indexed);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            for (int texture : textures) GL11.glDeleteTextures(texture);
            GL30.glDeleteFramebuffers(framebuffer);
            System.out.println("SkyGlMrtStateRegression: PASS " + checks + " checks; indexedBlend=" + indexed
                    + " OpenGL=" + GL11.glGetString(GL11.GL_VERSION)
                    + " renderer=" + GL11.glGetString(GL11.GL_RENDERER));
        } finally {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }
}
