package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.lang.reflect.Method;

/** Optional pass/matrix adapters only. No sky color is ever sourced from these renderers. */
public final class ModCompat {
    public static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean IMMERSIVE_PORTALS = FabricLoader.getInstance().isModLoaded("immersive_portals");
    public static final boolean DISTANT_HORIZONS = FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static boolean irisLookedUp, portalLookedUp;
    private static Object irisApi, captured;
    private static Method irisActive, irisShadow, projection, modelView;
    private static Method portalActive, portalMirror, currentPortal;
    private static Object pipelineManager;
    private static Method getPipeline, setMainBound, shouldOverride;
    private static java.lang.reflect.Field mainBound;
    private static Class<?> pipelineType;
    private static boolean logicalStateWarning;

    private ModCompat() {}
    private static void iris() {
        if (irisLookedUp || !IRIS) return;
        irisLookedUp = true;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = api.getMethod("getInstance").invoke(null);
            irisActive = api.getMethod("isShaderPackInUse");
            irisShadow = api.getMethod("isRenderingShadowPass");
            Class<?> state = Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            captured = state.getField("INSTANCE").get(null);
            projection = state.getMethod("getGbufferProjection");
            modelView = state.getMethod("getGbufferModelView");
        } catch (ReflectiveOperationException exception) {
            SkyboxBlockMod.LOGGER.debug("[skyboxblock] optional Iris world-state adapter unavailable", exception);
        }
    }
    public static boolean irisActive() { iris(); return bool(irisActive, irisApi); }
    public static boolean irisShadowPass() { iris(); return bool(irisShadow, irisApi); }
    public static void copyWorldMatrices(Matrix4f p, Matrix4f v) {
        if (!irisActive() || captured == null) return;
        try {
            Object a = projection == null ? null : projection.invoke(captured);
            Object b = modelView == null ? null : modelView.invoke(captured);
            if (a instanceof Matrix4fc matrix) p.set(matrix);
            if (b instanceof Matrix4fc matrix) v.set(matrix);
        } catch (ReflectiveOperationException exception) {
            // The renderLevel argument copies remain the fallback, never RenderSystem's hand matrices.
        }
    }
    private static void portals() {
        if (portalLookedUp || !IMMERSIVE_PORTALS) return;
        portalLookedUp = true;
        try {
            Class<?> api = Class.forName("qouteall.imm_ptl.core.render.context_management.PortalRendering");
            portalActive = api.getMethod("isRendering");
            portalMirror = api.getMethod("isRenderingOddNumberOfMirrors");
            currentPortal = api.getMethod("getRenderingPortal");
        } catch (ReflectiveOperationException exception) {
            SkyboxBlockMod.LOGGER.debug("[skyboxblock] optional portal adapter unavailable", exception);
        }
    }
    public static boolean portalRendering() { portals(); return bool(portalActive, null); }
    public static boolean mirroredPortal() { portals(); return bool(portalMirror, null); }
    public static Vector4f portalClipPlane(Vec3 camera) {
        if (!portalRendering() || currentPortal == null) return null;
        try {
            Object portal = currentPortal.invoke(null);
            Object plane = portal.getClass().getMethod("getInnerClipping").invoke(portal);
            if (plane == null) return null;
            Vec3 point = (Vec3) plane.getClass().getMethod("pos").invoke(plane);
            Vec3 normal = (Vec3) plane.getClass().getMethod("normal").invoke(plane);
            return SkyRenderMath.relativeClipPlane(camera.x, camera.y, camera.z,
                    point.x, point.y, point.z, normal.x, normal.y, normal.z);
        } catch (ReflectiveOperationException exception) { return null; }
    }
    private static Object currentPipeline() throws ReflectiveOperationException {
        if (pipelineManager == null) {
            pipelineManager = Class.forName("net.irisshaders.iris.Iris")
                    .getMethod("getPipelineManager").invoke(null);
            getPipeline = pipelineManager.getClass().getMethod("getPipelineNullable");
        }
        Object pipeline = getPipeline.invoke(pipelineManager);
        if (pipeline != null && pipeline.getClass() != pipelineType) {
            Class<?> type = pipeline.getClass();
            var field = type.getDeclaredField("isMainBound");
            field.setAccessible(true);
            mainBound = field;
            setMainBound = type.getMethod("setIsMainBound", boolean.class);
            shouldOverride = type.getMethod("shouldOverrideShaders");
            pipelineType = type;
        }
        return pipeline;
    }

    /**
     * RenderTarget.bindWrite changes Iris's logical target even when its GL binding is later restored.
     * Keep custom shaders outside Iris's world override/lock path, then restore the EXACT prior flag.
     * Nested scopes restore false to false; only the outer world scope restores true.
     */
    static IrisTargetScope privateTargetScope() {
        if (!irisActive()) return IrisTargetScope.EMPTY;
        // Explicit diagnostic negative control, ignored outside the regression fixture.
        if (Boolean.getBoolean("skyboxblock.regression")
                && Boolean.getBoolean("skyboxblock.regression.negativeIrisState")) return IrisTargetScope.EMPTY;
        try {
            Object pipeline = currentPipeline();
            if (pipeline == null) return IrisTargetScope.EMPTY;
            boolean previous = mainBound.getBoolean(pipeline);
            setMainBound.invoke(pipeline, false);
            return new IrisTargetScope(pipeline, setMainBound, previous);
        } catch (ReflectiveOperationException exception) {
            if (!logicalStateWarning) {
                logicalStateWarning = true;
                SkyboxBlockMod.LOGGER.error("[skyboxblock] Iris logical target adapter unavailable", exception);
            }
            return IrisTargetScope.EMPTY;
        }
    }

    /** Diagnostics read logical state without changing it. */
    static Boolean irisMainBound() {
        if (!irisActive()) return null;
        try { Object pipeline = currentPipeline(); return pipeline == null ? null : mainBound.getBoolean(pipeline); }
        catch (ReflectiveOperationException exception) { return null; }
    }
    static Boolean irisOverridesShaders() {
        if (!irisActive()) return null;
        try { Object pipeline = currentPipeline(); return pipeline == null ? null : (Boolean) shouldOverride.invoke(pipeline); }
        catch (ReflectiveOperationException exception) { return null; }
    }

    static final class IrisTargetScope implements AutoCloseable {
        private static final IrisTargetScope EMPTY = new IrisTargetScope(null, null, false);
        private final Object pipeline;
        private final Method setter;
        private final boolean previous;
        private IrisTargetScope(Object pipeline, Method setter, boolean previous) {
            this.pipeline = pipeline; this.setter = setter; this.previous = previous;
        }
        @Override public void close() {
            if (pipeline == null) return;
            try { setter.invoke(pipeline, previous); }
            catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to restore Iris logical target", exception);
            }
        }
    }

    private static boolean targetWarning;
    /** Shader clear() can leave Minecraft's sky-only main target bound. Bind Iris's real opaque target. */
    public static boolean bindOpaqueTarget() {
        if (!irisActive()) return true;
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            if (pipeline == null) return false;
            Object map = pipeline.getClass().getMethod("getShaderMap").invoke(pipeline);
            Class<?> keyType = Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
            Object key = keyType.getField("BLOCK_ENTITY").get(null);
            Object shader = map.getClass().getMethod("getShader", keyType).invoke(map, key);
            java.lang.reflect.Field field = shader.getClass().getDeclaredField("writingToBeforeTranslucent");
            field.setAccessible(true);
            Object target = field.get(shader);
            target.getClass().getMethod("bind").invoke(target);
            // Dynamic-resolution mods can leave a window-sized viewport after batches.
            // Use the attachment's real storage extent. FSR's shader-side subrectangle is separate:
            // shrinking this GL viewport as well would apply the render scale twice.
            int attachment = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_DRAW_BUFFER0);
            int objectType = org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteri(
                    org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, attachment,
                    org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            if (objectType == org.lwjgl.opengl.GL11.GL_TEXTURE) {
                int texture = org.lwjgl.opengl.GL30.glGetFramebufferAttachmentParameteri(
                        org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, attachment,
                        org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                SkyGlState.active(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                SkyGlState.bindTexture(texture);
                int width = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
                int height = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                        org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
                if (width > 0 && height > 0) {
                    com.mojang.blaze3d.platform.GlStateManager._viewport(0, 0, width, height);
                    org.lwjgl.opengl.GL11.glViewport(0, 0, width, height);
                }
            }
            return true;
        } catch (ReflectiveOperationException exception) {
            if (!targetWarning) {
                targetWarning = true;
                SkyboxBlockMod.LOGGER.error("[skyboxblock] failed to bind Iris opaque framebuffer", exception);
            }
            return false;
        }
    }

    private static Object cachedValue(Object uniforms, String name) throws ReflectiveOperationException {
        if (!Boolean.TRUE.equals(uniforms.getClass().getMethod("hasVariable", String.class).invoke(uniforms, name))) return null;
        Object value = uniforms.getClass().getMethod("getVariable", String.class).invoke(uniforms, name);
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField("cached");
                field.setAccessible(true);
                return field.get(value);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    /** Match shader-side FSR/TAA coordinates without reading or replacing shaderpack sky content. */
    public static void applyRasterSettings(SkyWorldRenderer.Frame frame, int width, int height) {
        frame.skyProjection.set(frame.projection);
        if (!irisActive()) return;
        try {
            Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            var field = pipeline.getClass().getDeclaredField("customUniforms");
            field.setAccessible(true);
            Object uniforms = field.get(pipeline);
            Object scale = cachedValue(uniforms, "fsrRenderScale");
            Object size = cachedValue(uniforms, "fsrScreenSize");
            Object screen = cachedValue(uniforms, "screenSize");
            if (scale instanceof org.joml.Vector2fc vector && vector.x() > 0 && vector.y() > 0
                    && Float.isFinite(vector.x()) && Float.isFinite(vector.y())) frame.rasterScale.set(vector);
            boolean custom = size instanceof org.joml.Vector2fc a && screen instanceof org.joml.Vector2fc b
                    && Math.abs(a.x()-b.x()) < .1F && Math.abs(a.y()-b.y()) < .1F
                    && (Math.abs(frame.rasterScale.x-1)>1E-5F || Math.abs(frame.rasterScale.y-1)>1E-5F);
            Object jitter = cachedValue(uniforms, "taaJitter");
            if (jitter instanceof org.joml.Vector2fc vector
                    && Float.isFinite(vector.x()) && Float.isFinite(vector.y())) frame.rasterJitter.set(vector);
            if (custom) {
                frame.rasterJitter.mul(frame.rasterScale);
                Object projectionData = cachedValue(uniforms, "gbufferProjection0");
                if (projectionData instanceof org.joml.Vector4fc v) frame.projection.m00(v.x()).m11(v.y());
                if (projectionData instanceof org.joml.Vector3fc v) frame.projection.m00(v.x()).m11(v.y());
            }
            SkyRenderMath.jitteredSkyProjection(frame.projection, frame.rasterScale.x, frame.rasterScale.y,
                    frame.rasterJitter.x, frame.rasterJitter.y, frame.skyProjection);
            frame.viewport[2] = Math.max(1, Math.round(width * frame.rasterScale.x));
            frame.viewport[3] = Math.max(1, Math.round(height * frame.rasterScale.y));
        } catch (ReflectiveOperationException exception) {
            SkyboxBlockMod.LOGGER.debug("[skyboxblock] no shader-side raster scale adapter", exception);
        }
    }

    private static boolean bool(Method method, Object target) {
        if (method == null) return false;
        try { return Boolean.TRUE.equals(method.invoke(target)); }
        catch (ReflectiveOperationException exception) { return false; }
    }
    public static String describe() { return "iris=" + IRIS + "(pack=" + irisActive() + "),ip="
            + IMMERSIVE_PORTALS + ",dh=" + DISTANT_HORIZONS; }
}
