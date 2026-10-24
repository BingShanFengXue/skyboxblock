package dev.yg.skyboxfixture;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The complete optional-mod boundary: no IP, Iris, or skyboxblock compile-time references. */
final class PortalReflection {
    static final String TAG = "skybox_ip_fixture_owned";
    private static final String PREFIX = "qouteall.imm_ptl.core.";
    private static boolean observed;
    private static Method isRendering, getPortalLayer, oddMirrors, renderingPortal, activeClip;
    private static Method worldLayer, worldDescription, worldRendering;
    private static Object irisApi;
    private static Method irisShadow;
    private static String observationError;

    private PortalReflection() {}

    static Entity createForward(ServerLevel level) throws ReflectiveOperationException {
        Class<?> portal = Class.forName(PREFIX + "portal.Portal");
        Class<?> manipulation = Class.forName(PREFIX + "portal.PortalManipulation");
        Class<?> api = Class.forName(PREFIX + "api.PortalAPI");
        Object entityType = portal.getField("ENTITY_TYPE").get(null);
        // NORTH-facing plane at z=2; its centre and destination are exactly the requested fixture geometry.
        Object result = manipulation.getMethod("createOrthodoxPortal", EntityType.class,
                ServerLevel.class, ServerLevel.class, Direction.class, AABB.class, Vec3.class)
                .invoke(null, entityType, level, level, Direction.NORTH,
                        new AABB(-1.5, 140, 2, 2.5, 146, 2), new Vec3(32.5, 143, 2));
        Entity entity = (Entity) result;
        entity.addTag(TAG);
        api.getMethod("spawnServerEntity", Entity.class).invoke(null, entity);
        return entity;
    }

    static Entity createReverse(Entity forward) throws ReflectiveOperationException {
        Class<?> portal = Class.forName(PREFIX + "portal.Portal");
        Class<?> api = Class.forName(PREFIX + "api.PortalAPI");
        Entity reverse = (Entity) api.getMethod("createReversePortal", portal).invoke(null, forward);
        reverse.addTag(TAG);
        api.getMethod("spawnServerEntity", Entity.class).invoke(null, reverse);
        return reverse;
    }

    static JsonObject portalDescription(Entity entity) {
        JsonObject result = new JsonObject();
        if (entity == null) return result;
        result.addProperty("uuid", entity.getUUID().toString());
        result.add("position", PortalFixtureClient.vector(entity.position()));
        result.addProperty("alive", entity.isAlive());
        try {
            Class<?> portal = Class.forName(PREFIX + "portal.Portal");
            result.add("destination", PortalFixtureClient.vector((Vec3) portal.getMethod("getDestination").invoke(entity)));
            result.addProperty("width", ((Number) portal.getMethod("getWidth").invoke(entity)).doubleValue());
            result.addProperty("height", ((Number) portal.getMethod("getHeight").invoke(entity)).doubleValue());
            result.addProperty("destinationDimension", String.valueOf(portal.getMethod("getDestDim").invoke(entity)));
        } catch (ReflectiveOperationException exception) {
            result.addProperty("reflectionError", describeError(exception));
        }
        return result;
    }

    private static void observeLookup() {
        if (observed) return;
        observed = true;
        try {
            Class<?> rendering = Class.forName(PREFIX + "render.context_management.PortalRendering");
            isRendering = rendering.getMethod("isRendering");
            getPortalLayer = rendering.getMethod("getPortalLayer");
            oddMirrors = rendering.getMethod("isRenderingOddNumberOfMirrors");
            renderingPortal = rendering.getMethod("getRenderingPortal");
            activeClip = rendering.getMethod("getActiveClippingPlane");
            Class<?> info = Class.forName(PREFIX + "render.context_management.WorldRenderInfo");
            worldLayer = info.getMethod("getRenderingLayer");
            worldDescription = info.getMethod("getRenderingDescription");
            worldRendering = info.getMethod("isRendering");
        } catch (ReflectiveOperationException exception) {
            observationError = describeError(exception);
        }
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = iris.getMethod("getInstance").invoke(null);
                irisShadow = iris.getMethod("isRenderingShadowPass");
            } catch (ReflectiveOperationException exception) {
                observationError = describeError(exception);
            }
        }
    }

    static boolean portalRendering() {
        observeLookup();
        try { return isRendering != null && Boolean.TRUE.equals(isRendering.invoke(null)); }
        catch (ReflectiveOperationException exception) { return false; }
    }

    static boolean shadowRendering() {
        observeLookup();
        try { return irisShadow != null && Boolean.TRUE.equals(irisShadow.invoke(irisApi)); }
        catch (ReflectiveOperationException exception) { return false; }
    }

    static JsonObject renderingState() {
        observeLookup();
        JsonObject result = new JsonObject();
        result.addProperty("ipLoaded", FabricLoader.getInstance().isModLoaded("immersive_portals"));
        result.addProperty("irisShadow", shadowRendering());
        if (observationError != null) result.addProperty("lookupError", observationError);
        try {
            boolean active = isRendering != null && Boolean.TRUE.equals(isRendering.invoke(null));
            result.addProperty("portalRendering", active);
            result.addProperty("portalLayer", getPortalLayer == null ? -1 : ((Number) getPortalLayer.invoke(null)).intValue());
            result.addProperty("oddMirrors", oddMirrors != null && Boolean.TRUE.equals(oddMirrors.invoke(null)));
            result.addProperty("worldRenderInfoActive", worldRendering != null && Boolean.TRUE.equals(worldRendering.invoke(null)));
            result.addProperty("worldRenderInfoLayer", worldLayer == null ? -1 : ((Number) worldLayer.invoke(null)).intValue());
            result.addProperty("worldRenderDescription", worldDescription == null ? "unavailable" : String.valueOf(worldDescription.invoke(null)));
            if (active && renderingPortal != null) {
                Entity portal = (Entity) renderingPortal.invoke(null);
                result.addProperty("renderingPortalUuid", portal.getUUID().toString());
                Object plane = activeClip.invoke(null);
                if (plane != null) {
                    result.add("clipPoint", PortalFixtureClient.vector((Vec3) plane.getClass().getMethod("pos").invoke(plane)));
                    result.add("clipNormal", PortalFixtureClient.vector((Vec3) plane.getClass().getMethod("normal").invoke(plane)));
                }
            }
            Class<?> global = Class.forName(PREFIX + "IPCGlobal");
            Object renderer = global.getField("renderer").get(null);
            result.addProperty("rendererClass", renderer == null ? "null" : renderer.getClass().getName());
        } catch (ReflectiveOperationException exception) {
            result.addProperty("readError", describeError(exception));
        }
        if (irisApi != null) {
            try {
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                result.addProperty("shaderpack", String.valueOf(iris.getMethod("getCurrentPackName").invoke(null)));
                Object manager = iris.getMethod("getPipelineManager").invoke(null);
                Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
                result.addProperty("pipelineClass", pipeline == null ? "null" : pipeline.getClass().getName());
                result.addProperty("pipelineIdentity", pipeline == null ? 0 : System.identityHashCode(pipeline));
            } catch (ReflectiveOperationException exception) {
                result.addProperty("irisReadError", describeError(exception));
            }
        }
        return result;
    }

    static JsonObject rasterHints() {
        JsonObject result=new JsonObject();
        String[] variables={"gbufferProjection0","fsrRenderScale","taaJitter","fsrScreenSize","screenSize"};
        result.addProperty("irisActive",false);
        result.add("capturedProjection",com.google.gson.JsonNull.INSTANCE);
        result.add("capturedView",com.google.gson.JsonNull.INSTANCE);
        result.add("frameTimeCounter",com.google.gson.JsonNull.INSTANCE);
        result.add("frameCounter",com.google.gson.JsonNull.INSTANCE);
        for (String name:variables) result.add(name,com.google.gson.JsonNull.INSTANCE);
        if (!FabricLoader.getInstance().isModLoaded("iris")) return result;
        try {
            Class<?> api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance=api.getMethod("getInstance").invoke(null);
            boolean enabled=Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(instance));
            result.addProperty("irisActive",enabled);
            if (!enabled) return result;
            Class<?> clock=Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            Object timer=clock.getField("TIMER").get(null),counter=clock.getField("COUNTER").get(null);
            result.addProperty("frameTimeCounter",((Number)timer.getClass().getMethod("getFrameTimeCounter").invoke(timer)).floatValue());
            result.addProperty("frameCounter",((Number)counter.getClass().getMethod("getAsInt").invoke(counter)).intValue());
            Class<?> state=Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Object captured=state.getField("INSTANCE").get(null);
            for (String[] method:new String[][]{{"capturedProjection","getGbufferProjection"},{"capturedView","getGbufferModelView"}}) {
                Object value=state.getMethod(method[1]).invoke(captured);
                if (value instanceof org.joml.Matrix4fc matrix) {
                    com.google.gson.JsonArray array=new com.google.gson.JsonArray();
                    for (float component:matrix.get(new float[16])) array.add(component);
                    result.add(method[0],array);
                }
            }
            Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            var field=pipeline.getClass().getDeclaredField("customUniforms"); field.setAccessible(true);
            Object uniforms=field.get(pipeline);
            for (String name:variables) {
                if (!Boolean.TRUE.equals(uniforms.getClass().getMethod("hasVariable",String.class).invoke(uniforms,name))) continue;
                Object expression=uniforms.getClass().getMethod("getVariable",String.class).invoke(uniforms,name);
                Object value=null;
                for (Class<?> at=expression.getClass();at!=null;at=at.getSuperclass()) {
                    try { var cached=at.getDeclaredField("cached"); cached.setAccessible(true); value=cached.get(expression); break; }
                    catch (NoSuchFieldException ignored) {}
                }
                com.google.gson.JsonArray array=new com.google.gson.JsonArray();
                if (value instanceof org.joml.Vector4fc v) { array.add(v.x()); array.add(v.y()); array.add(v.z()); array.add(v.w()); }
                else if (value instanceof org.joml.Vector3fc v) { array.add(v.x()); array.add(v.y()); array.add(v.z()); }
                else if (value instanceof org.joml.Vector2fc v) { array.add(v.x()); array.add(v.y()); }
                if (!array.isEmpty()) result.add(name,array);
            }
        } catch (ReflectiveOperationException exception) { result.addProperty("readError",describeError(exception)); }
        return result;
    }

    static String describeError(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getName() + ": " + String.valueOf(cause.getMessage());
    }
}
