package dev.yg.skyboxblock.client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression for Minecraft's actual 3.3 core context, not only the driver's maximum 4.x context. */
public final class SkyGl33Regression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) throw new AssertionError("GLFW init");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        long handle = GLFW.glfwCreateWindow(64, 64, "skybox GL3.3 regression", 0, 0);
        if (handle == 0) throw new AssertionError("3.3 hidden context");
        GLFW.glfwMakeContextCurrent(handle);
        var caps = GL.createCapabilities();
        try {
            System.out.println("GL3.3 probe: " + GL11.glGetString(GL11.GL_VERSION)
                    + " core40=" + caps.OpenGL40 + " ARB_draw_buffers_blend=" + caps.GL_ARB_draw_buffers_blend);
            check(caps.OpenGL33, "Minecraft minimum GL3.3 available");
            Path shaders = Path.of("src/main/resources/assets/skyboxblock/shaders/isolated");
            int scene = program(shaders, "scene"), window = program(shaders, "window");
            check(scene > 0 && window > 0, "production GLSL compiles/links on GL3.3");
            GL11.glViewport(3, 4, 41, 42);
            GL11.glEnable(GL11.GL_BLEND); GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glDepthFunc(GL11.GL_GREATER); GL11.glDepthMask(false);
            GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            if (caps.OpenGL40) GL40.glBlendFuncSeparatei(0, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            else if (caps.GL_ARB_draw_buffers_blend) ARBDrawBuffersBlend.glBlendFuncSeparateiARB(0, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            else GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL30.glColorMaski(0, true, false, true, false);
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL20.glUseProgram(scene);
            GL11.glEnable(GL11.GL_COLOR_LOGIC_OP); GL11.glLogicOp(GL11.GL_XOR);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_REVERSE_SUBTRACT,GL14.GL_FUNC_SUBTRACT);
            try (SkyGlScope ignored = new SkyGlScope()) {
                SkyGlScope.clean();
                check(!GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP)
                        && GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)==GL14.GL_FUNC_ADD
                        && GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)==GL14.GL_FUNC_ADD,
                        "private scene neutralizes foreign XOR/subtract state");
                GL20.glUseProgram(window);
                GL11.glViewport(0, 0, 8, 8); GL11.glDepthFunc(GL11.GL_ALWAYS);
                GL11.glDepthMask(true); GL13.glActiveTexture(GL13.GL_TEXTURE1);
            }
            int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            check(java.util.Arrays.equals(viewport, new int[]{3, 4, 41, 42}), "viewport restored");
            check(GL11.glIsEnabled(GL11.GL_BLEND) && GL11.glIsEnabled(GL11.GL_CULL_FACE)
                    && GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0), "blend/cull/clip restored");
            check(GL11.glGetInteger(GL11.GL_DEPTH_FUNC) == GL11.GL_GREATER && !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), "depth restored");
            check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) == GL13.GL_TEXTURE4, "texture unit restored");
            check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) == scene, "program restored");
            boolean indexed = caps.OpenGL40 || caps.GL_ARB_draw_buffers_blend;
            int src = indexed ? GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB, 0) : GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int dst = indexed ? GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB, 0) : GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            check(src == GL11.GL_ONE && dst == GL11.GL_ZERO, "blend factors restored using available entry point");
            check(GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP)
                    && GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE)==GL11.GL_XOR
                    && GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)==GL14.GL_FUNC_REVERSE_SUBTRACT
                    && GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)==GL14.GL_FUNC_SUBTRACT,
                    "foreign XOR and separate blend equations are restored exactly");
            check(GL11.glGetError() == GL11.GL_NO_ERROR, "GL3.3 zero GL errors; no unsupported native entry point");
            GL20.glDeleteProgram(scene); GL20.glDeleteProgram(window);
            System.out.println("SkyGl33Regression: " + checks + " checks passed");
        } finally { GLFW.glfwDestroyWindow(handle); GLFW.glfwTerminate(); }
    }
    private static int program(Path root, String name) throws Exception {
        int vertex = shader(GL20.GL_VERTEX_SHADER, Files.readString(root.resolve(name + ".vsh")));
        int fragment = shader(GL20.GL_FRAGMENT_SHADER, Files.readString(root.resolve(name + ".fsh")));
        int result = GL20.glCreateProgram();
        GL20.glAttachShader(result, vertex); GL20.glAttachShader(result, fragment); GL20.glLinkProgram(result);
        if (GL20.glGetProgrami(result, GL20.GL_LINK_STATUS) == 0) throw new AssertionError(GL20.glGetProgramInfoLog(result));
        GL20.glDeleteShader(vertex); GL20.glDeleteShader(fragment); return result;
    }
    private static int shader(int type, String source) {
        int shader = GL20.glCreateShader(type); GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) throw new AssertionError(GL20.glGetShaderInfoLog(shader));
        return shader;
    }
    private static void check(boolean condition, String label) {
        checks++; if (!condition) throw new AssertionError(label);
    }
}
