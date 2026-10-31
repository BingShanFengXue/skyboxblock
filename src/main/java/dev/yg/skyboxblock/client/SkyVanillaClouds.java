package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.yg.skyboxblock.mixin.CloudMeshAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Vanilla cloud texture, geometry and time-of-day color, rendered only into our private probe. */
public final class SkyVanillaClouds {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private static ShaderInstance shader;
    private static VertexBuffer buffer;
    private static SkyCloudMesh privateBuffer;
    private static final boolean LEGACY_BUFFER=Boolean.getBoolean("skyboxblock.regression")
            && Boolean.getBoolean("skyboxblock.regression.negativeCloudBuffer");
    private static int cellX = Integer.MIN_VALUE, cellY, cellZ;
    private static Vec3 previousColor = Vec3.ZERO;
    private static boolean dirty = true;
    private static int renderedFrames;
    private static final boolean DIAGNOSTICS = Boolean.getBoolean("skyboxblock.regression");
    private static int lastProgramExpected, lastProgramActual, lastTextureId;
    private static boolean lastClipEnabled;
    private static int meshVertices,meshIndices,meshStride;
    private static String meshFormat="",meshHead="";
    private static com.google.gson.JsonObject lastDrawState;

    /** Captured at the real draw, not inferred later from a different private canvas. */
    public static com.google.gson.JsonObject cloudDrawDiagnostics() {
        return lastDrawState==null ? new com.google.gson.JsonObject() : lastDrawState.deepCopy();
    }

    private static void captureDrawState() {
        if (!DIAGNOSTICS || !RenderRegression.capturePending()) return;
        com.google.gson.JsonObject data=new com.google.gson.JsonObject();
        int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        data.addProperty("programExpected",shader.getId()); data.addProperty("programActual",program);
        data.addProperty("cloudBufferPath",LEGACY_BUFFER ? "legacy-world-VertexBuffer" : "owned-raw-cloud-mesh");
        if (!LEGACY_BUFFER && privateBuffer!=null) {
            data.addProperty("expectedVao",privateBuffer.vao());
            data.addProperty("expectedVbo",privateBuffer.vbo());
            data.addProperty("expectedEbo",privateBuffer.ebo());
        }
        data.addProperty("drawFramebuffer",GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        data.addProperty("clipEnabled",GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0));
        data.addProperty("depthEnabled",GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        data.addProperty("depthFunc",GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        data.addProperty("depthMask",GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        data.addProperty("cullEnabled",GL11.glIsEnabled(GL11.GL_CULL_FACE));
        data.addProperty("blendEnabled",GL11.glIsEnabled(GL11.GL_BLEND));
        data.addProperty("blendEquationRgb",GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB));
        data.addProperty("blendEquationAlpha",GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA));
        data.addProperty("blendSrcRgb",GL11.glGetInteger(GL20.GL_BLEND_SRC_RGB));
        data.addProperty("blendDstRgb",GL11.glGetInteger(GL20.GL_BLEND_DST_RGB));
        data.addProperty("logicEnabled",GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP));
        data.addProperty("logicOperation",GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE));
        data.addProperty("vao",GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
        int ebo=GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        data.addProperty("elementBuffer",ebo);
        data.addProperty("elementBytes",ebo==0 ? 0 : org.lwjgl.opengl.GL15.glGetBufferParameteri(
                org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER,org.lwjgl.opengl.GL15.GL_BUFFER_SIZE));
        data.addProperty("meshVertices",meshVertices); data.addProperty("meshIndices",meshIndices);
        data.addProperty("meshStride",meshStride); data.addProperty("meshFormat",meshFormat); data.addProperty("meshHeadHex",meshHead);
        com.google.gson.JsonObject attributes=new com.google.gson.JsonObject();
        for (String name:new String[]{"Position","UV0","Color","Normal"}) {
            int location=GL20.glGetAttribLocation(program,name);
            com.google.gson.JsonObject attribute=new com.google.gson.JsonObject(); attribute.addProperty("location",location);
            if (location>=0) {
                attribute.addProperty("enabled",GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
                attribute.addProperty("buffer",GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
                attribute.addProperty("stride",GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
                attribute.addProperty("size",GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
                attribute.addProperty("type",GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE));
                attribute.addProperty("normalized",GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED));
                if (org.lwjgl.opengl.GL.getCapabilities().OpenGL33 || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_instanced_arrays)
                    attribute.addProperty("divisor",GL20.glGetVertexAttribi(location,org.lwjgl.opengl.GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR));
                if (name.equals("Position")) {
                    int original=GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
                    int vertex=GL20.glGetVertexAttribi(location,GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING);
                    if (vertex>0) {
                        org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,vertex);
                        int bytes=org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,org.lwjgl.opengl.GL15.GL_BUFFER_SIZE);
                        boolean mapped=org.lwjgl.opengl.GL15.glGetBufferParameteri(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,org.lwjgl.opengl.GL15.GL_BUFFER_MAPPED)!=0;
                        data.addProperty("vertexBytes",bytes); data.addProperty("vertexMapped",mapped);
                        int count=Math.min(meshStride*4,bytes);
                        if (!mapped && count>0) {
                            var head=org.lwjgl.BufferUtils.createByteBuffer(count);
                            org.lwjgl.opengl.GL15.glGetBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,0L,head);
                            byte[] actual=new byte[count]; head.get(actual);
                            String gpuHead=java.util.HexFormat.of().formatHex(actual);
                            data.addProperty("gpuHeadHex",gpuHead); data.addProperty("gpuHeadMatchesMesh",gpuHead.equals(meshHead));
                        }
                    }
                    org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,original);
                }
            }
            attributes.add(name,attribute);
        }
        data.add("attributes",attributes);
        int samplerLocation=GL20.glGetUniformLocation(program,"Sampler0");
        int sampler=samplerLocation<0 ? -1 : GL20.glGetUniformi(program,samplerLocation);
        data.addProperty("samplerUnit",sampler);
        int previous=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        if (sampler>=0 && sampler<6) {
            SkyGlState.active(GL13.GL_TEXTURE0+sampler);
            int texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            data.addProperty("samplerTexture",texture);
            data.addProperty("expectedTexture",Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).getId());
            data.addProperty("textureWidth",GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH));
            data.addProperty("textureHeight",GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT));
            SkyGlState.active(previous);
        }
        for (String name:new String[]{"CloudFogStart","CloudFogEnd"}) {
            int location=GL20.glGetUniformLocation(program,name);
            if (location>=0) data.addProperty(name,GL20.glGetUniformf(program,location));
        }
        int[] viewport=new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        data.add("viewport",new com.google.gson.Gson().toJsonTree(viewport));
        try (org.lwjgl.system.MemoryStack stack=org.lwjgl.system.MemoryStack.stackPush()) {
            var mask=stack.malloc(4); GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
            com.google.gson.JsonArray values=new com.google.gson.JsonArray();
            for(int i=0;i<4;i++) values.add(mask.get(i)!=0); data.add("colorMask",values);
        }
        lastDrawState=data;
    }

    private SkyVanillaClouds() {}

    public static void setShader(ShaderInstance instance) { shader = instance; dirty = true; clearDiagnostics(); }
    public static void invalidate() { dirty = true; clearDiagnostics(); }
    public static int renderedFrames() { return renderedFrames; }
    /** Last real cloud draw in regression mode; zero/false when no draw was observed. */
    public static int cloudProgramExpected() { return lastProgramExpected; }
    public static int cloudProgramActual() { return lastProgramActual; }
    public static int cloudTextureId() { return lastTextureId; }
    public static boolean cloudClipEnabled() { return lastClipEnabled; }

    private static void clearDiagnostics() {
        lastProgramExpected = lastProgramActual = lastTextureId = 0;
        lastClipEnabled = false;
        lastDrawState = null;
    }

    public static void release() {
        if (buffer != null) buffer.close();
        buffer = null;
        if (privateBuffer!=null) privateBuffer.close();
        privateBuffer=null;
        dirty = true;
        renderedFrames = 0;
        clearDiagnostics();
    }

    /** Caller owns framebuffer/viewport and wraps this operation in SkyGlState. */
    public static boolean render(LevelRenderer renderer, net.minecraft.client.multiplayer.ClientLevel level,
                                 Vec3 cameraPos, Matrix4f view, Matrix4f projection, float partialTick,
                                 SkyDisplayState displaySky) {
        Minecraft mc = Minecraft.getInstance();
        if (DIAGNOSTICS) clearDiagnostics();
        if (shader == null || level == null || !SkyboxConfig.get().clouds() || displaySky.cloudOpacity<=0F) return false;
        SkyEnvironment env=displaySky.environment;
        float fadeEnd=SkyEnvironmentMath.cloudEnd(env,SkyboxConfig.get().vanillaCloudFadeEnd());
        float fadeStart=SkyEnvironmentMath.cloudStart(env,SkyboxConfig.get().vanillaCloudFadeStart(),fadeEnd);
        CloudMeshAccess access = (CloudMeshAccess) renderer;
        double animation = Boolean.getBoolean("skyboxblock.regression.freezeClouds")
                && Boolean.getBoolean("skyboxblock.regression") ? 0 : (level.getGameTime() + (double)partialTick) * .03;
        double x = (cameraPos.x + animation) / 12.0;
        double y = SkyRenderMath.cloudRelativeY(cameraPos.y,
                SkyEnvironmentMath.cloudHeight(env,SkyboxConfig.get().vanillaCloudHeight()));
        double z = cameraPos.z / 12.0 + .33000001311302185;
        x -= Math.floor(x / 2048) * 2048;
        z -= Math.floor(z / 2048) * 2048;
        int nextX = (int) Math.floor(x), nextY = (int) Math.floor(y / 4), nextZ = (int) Math.floor(z);
        Vec3 color = displaySky.cloudColor;
        if (dirty || (LEGACY_BUFFER ? buffer==null : privateBuffer==null) || nextX != cellX || nextY != cellY || nextZ != cellZ
                || color.distanceToSqr(previousColor) > 2.0E-4) {
            CloudStatus previousType = access.skyboxblock$getCloudType();
            MeshData mesh;
            try {
                // buildClouds only reads this field; restore it before vanilla/Sodium resumes.
                access.skyboxblock$setCloudType(CloudStatus.FANCY);
                try(SkyCloudRange.Scope range=SkyCloudRange.build(fadeEnd)) {
                    mesh = access.skyboxblock$buildClouds(Tesselator.getInstance(), x, y, z, color);
                }
            } finally {
                access.skyboxblock$setCloudType(previousType);
            }
            if (DIAGNOSTICS) {
                var draw=mesh.drawState(); meshVertices=draw.vertexCount(); meshIndices=draw.indexCount();
                meshStride=draw.format().getVertexSize(); meshFormat=draw.format().toString();
                var bytes=mesh.vertexBuffer().duplicate(); byte[] head=new byte[Math.min(bytes.remaining(),meshStride*4)];
                bytes.get(head); meshHead=java.util.HexFormat.of().formatHex(head);
            }
            if (LEGACY_BUFFER) {
                if (buffer != null) buffer.close();
                buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind(); buffer.upload(mesh); VertexBuffer.unbind();
            } else {
                if (privateBuffer==null) privateBuffer=new SkyCloudMesh();
                privateBuffer.upload(mesh);
                GL30.glBindVertexArray(0);
            }
            cellX = nextX;
            cellY = nextY;
            cellZ = nextZ;
            previousColor = color;
            dirty = false;
        }
        Matrix4f modelView = new Matrix4f(view).scale(12, 1, 12).translate(
                -(float) (x - Math.floor(x)), (float) (y - Math.floor(y / 4) * 4),
                -(float) (z - Math.floor(z)));
        SkyGlState.opaqueState();
        RenderSystem.disableCull();
        GL11.glDisable(GL11.GL_CULL_FACE);
        GlStateManager._clearDepth(1);
        GL11.glClearDepth(1);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        SkyGlState.active(GL13.GL_TEXTURE0);
        int texture = mc.getTextureManager().getTexture(TEXTURE).getId();
        SkyGlState.bindTexture(texture);
        if (DIAGNOSTICS) lastTextureId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        RenderSystem.setShaderTexture(0, texture);
        if (shader.getUniform("CloudFogStart") != null) shader.getUniform("CloudFogStart").set(fadeStart);
        if (shader.getUniform("CloudFogEnd") != null) shader.getUniform("CloudFogEnd").set(fadeEnd);
        if (shader.getUniform("CloudOpacity") != null) shader.getUniform("CloudOpacity").set(displaySky.cloudOpacity);
        RenderSystem.setShader(() -> shader);
        if (LEGACY_BUFFER) buffer.bind();
        // Native cloud planes intersect at tiny offsets. Quantized equal-depth fragments must not
        // repeatedly alpha-blend at those intersections. Only the private color pass uses stencil.
        try(SkyCloudStencil.Scope stencil=SkyCloudStencil.save()) {
            RenderSystem.colorMask(false, false, false, false);
            GL11.glColorMask(false, false, false, false);
            drawPass(modelView, SkyCloudRange.projection(projection,fadeEnd));
            RenderSystem.colorMask(true, true, true, true);
            GL11.glColorMask(true, true, true, true);
            RenderSystem.depthMask(false);GL11.glDepthMask(false);
            RenderSystem.enableBlend();GL11.glEnable(GL11.GL_BLEND);
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            stencil.color();
            drawPass(modelView, SkyCloudRange.projection(projection,fadeEnd));
        }
        VertexBuffer.unbind();
        renderedFrames++;
        return true;
    }

    private static void drawPass(Matrix4f modelView, Matrix4f projection) {
        shader.getUniform("ModelViewMat").set(modelView);
        shader.getUniform("ProjMat").set(projection);
        shader.setSampler("Sampler0", Minecraft.getInstance().getTextureManager().getTexture(TEXTURE).getId());
        shader.apply();
        if (DIAGNOSTICS) {
            lastProgramExpected = shader.getId();
            lastProgramActual = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            lastClipEnabled = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
        }
        if (!LEGACY_BUFFER) privateBuffer.prepareDraw(shader);
        captureDrawState();
        if (LEGACY_BUFFER) buffer.draw(); else privateBuffer.draw();
        shader.clear();
    }
}

