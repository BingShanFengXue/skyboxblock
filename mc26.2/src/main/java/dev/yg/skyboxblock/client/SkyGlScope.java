package dev.yg.skyboxblock.client;

import org.lwjgl.opengl.*;

/**
 * Exact raw-GL transaction. Never calls RenderSystem/GlStateManager state setters, so their caches
 * and Iris's phase/target/program trackers are untouched. Restoring the actual entry state leaves
 * those caches correct. In particular, MRT blend/color masks and sampler objects are not global.
 */
final class SkyGlScope implements AutoCloseable {
    private static final int UNITS = 6;
    // Minecraft requests a 3.3 context even on a 4.6-capable GPU. ARB entry points are distinct.
    private final boolean coreIndexedBlend = GL.getCapabilities().OpenGL40;
    private final boolean arbIndexedBlend = GL.getCapabilities().GL_ARB_draw_buffers_blend;
    private final boolean indexedBlend = coreIndexedBlend || arbIndexedBlend;
    private final int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
    private final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
    private final int array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    private final int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
    private final int[] viewport = new int[4], scissor = new int[4], polygon = new int[2];
    private final double[] range = new double[2];
    private final int[] textures = new int[UNITS], samplers = new int[UNITS];
    private final boolean[] enabled = new boolean[CAPS.length], clip = new boolean[8];
    private final int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
    private final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
    private final int frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
    private final int cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
    private final int count = Math.min(8, GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS));
    private final boolean[] blend = new boolean[count];
    private final int[][] factors = new int[count][6];
    private final boolean[][] masks = new boolean[count][4];
    private static final int[] CAPS = { GL11.GL_DEPTH_TEST, GL11.GL_CULL_FACE, GL11.GL_SCISSOR_TEST,
            GL11.GL_STENCIL_TEST, GL30.GL_FRAMEBUFFER_SRGB, GL11.GL_POLYGON_OFFSET_FILL,
            GL32.GL_DEPTH_CLAMP, GL30.GL_RASTERIZER_DISCARD, GL11.GL_COLOR_LOGIC_OP };

    SkyGlScope() {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygon);
        GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, range);
        for (int i=0;i<CAPS.length;i++) enabled[i]=GL11.glIsEnabled(CAPS[i]);
        for (int i=0;i<clip.length;i++) clip[i]=GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0+i);
        int[] params={GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,
                GL14.GL_BLEND_DST_ALPHA,GL20.GL_BLEND_EQUATION_RGB,GL20.GL_BLEND_EQUATION_ALPHA};
        for (int i=0;i<count;i++) {
            blend[i]=GL30.glIsEnabledi(GL11.GL_BLEND,i);
            for(int j=0;j<params.length;j++) factors[i][j]=indexedBlend
                    ? GL30.glGetIntegeri(params[j],i) : GL11.glGetInteger(params[j]);
            java.nio.ByteBuffer mask=org.lwjgl.BufferUtils.createByteBuffer(4);
            GL30.glGetBooleani_v(GL11.GL_COLOR_WRITEMASK,i,mask);
            for(int j=0;j<4;j++) masks[i][j]=mask.get(j)!=0;
        }
        for (int i=0;i<UNITS;i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
            textures[i]=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[i]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i);
        }
        GL13.glActiveTexture(active);
    }

    static void clean() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB); GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        for(int i=0;i<8;i++) GL11.glDisable(GL30.GL_CLIP_DISTANCE0+i);
        GL11.glDisable(GL11.GL_BLEND); GL11.glColorMask(true,true,true,true);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,GL11.GL_FILL);
        GL11.glDepthRange(0,1);
    }

    @Override public void close() {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,draw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,read);
        GL11.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        GL11.glScissor(scissor[0],scissor[1],scissor[2],scissor[3]);
        GL20.glUseProgram(program); GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,array);
        for(int i=0;i<UNITS;i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,textures[i]); GL33.glBindSampler(i,samplers[i]);
        }
        GL13.glActiveTexture(active);
        for(int i=0;i<CAPS.length;i++) enable(CAPS[i],enabled[i]);
        for(int i=0;i<clip.length;i++) enable(GL30.GL_CLIP_DISTANCE0+i,clip[i]);
        GL11.glDepthFunc(depthFunc); GL11.glDepthMask(depthMask); GL11.glDepthRange(range[0],range[1]);
        GL11.glFrontFace(frontFace); GL11.glCullFace(cullFace);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK,polygon[0]);
        for(int i=0;i<count;i++) {
            if(blend[i]) GL30.glEnablei(GL11.GL_BLEND,i); else GL30.glDisablei(GL11.GL_BLEND,i);
            int[] f=factors[i];
            if(coreIndexedBlend) {
                GL40.glBlendFuncSeparatei(i,f[0],f[1],f[2],f[3]);
                GL40.glBlendEquationSeparatei(i,f[4],f[5]);
            } else if(arbIndexedBlend) {
                ARBDrawBuffersBlend.glBlendFuncSeparateiARB(i,f[0],f[1],f[2],f[3]);
                ARBDrawBuffersBlend.glBlendEquationSeparateiARB(i,f[4],f[5]);
            } else if(i==0) {
                // Without draw_buffers_blend the factors/equations are global, not per draw buffer.
                GL14.glBlendFuncSeparate(f[0],f[1],f[2],f[3]);
                GL20.glBlendEquationSeparate(f[4],f[5]);
            }
            boolean[] m=masks[i]; GL30.glColorMaski(i,m[0],m[1],m[2],m[3]);
        }
    }
    private static void enable(int cap,boolean value) { if(value) GL11.glEnable(cap); else GL11.glDisable(cap); }
}
