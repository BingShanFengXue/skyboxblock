package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.*;
import org.lwjgl.opengl.*;

/** Read-only adapters to the exact installed renderers. No phase/target/program overrides or setters. */
public final class ModCompat {
    public static final boolean DISTANT_HORIZONS=FabricLoader.getInstance().isModLoaded("distanthorizons");
    public static final boolean IRIS=FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean IMMERSIVE_PORTALS=FabricLoader.getInstance().isModLoaded("immersive_portals");
    private static boolean warned;
    private ModCompat() { }
    public static boolean irisActive() {return irisBoolean("isShaderPackInUse");}
    public static boolean irisShadowPass() {return irisBoolean("isRenderingShadowPass");}
    private static boolean irisBoolean(String name) {
        if(!IRIS)return false;
        try {Class<?> api=Class.forName("net.irisshaders.iris.api.v0.IrisApi");return Boolean.TRUE.equals(api.getMethod(name).invoke(api.getMethod("getInstance").invoke(null)));}
        catch(ReflectiveOperationException error){warn(error);return false;}
    }
    static Object pipeline() throws ReflectiveOperationException {
        Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
        return manager.getClass().getMethod("getPipelineNullable").invoke(manager);
    }
    /** The block-entity BEFORE-translucent FBO owns world color+depth, unlike the sky-only main FBO. */
    static int opaqueFramebuffer() {return framebuffer(false);}
    static int translucentFramebuffer() {return framebuffer(true);}
    private static int framebuffer(boolean translucent) {
        if(!irisActive())return -1;
        try {
            Object p=pipeline();if(p==null)return -1;
            Object map=p.getClass().getMethod("getShaderMap").invoke(p);
            Class<?> key=Class.forName("net.irisshaders.iris.pipeline.programs.ShaderKey");
            Object shader=map.getClass().getMethod("getShader",key).invoke(map,key.getField("BLOCK_ENTITY").get(null));
            Object target=field(shader,translucent?"writingToAfterTranslucent":"writingToBeforeTranslucent");
            return ((Number)target.getClass().getMethod("getId").invoke(target)).intValue();
        } catch(ReflectiveOperationException|RuntimeException error) {warn(error);return -1;}
    }
    /** Iris stores conventional non-reversed gbuffer projection; keep our captured native projection. */
    static void view(Matrix4f view) {
        if(!irisActive())return;
        try {Class<?> c=Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");Object state=c.getField("INSTANCE").get(null);
            Object v=c.getMethod("getGbufferModelView").invoke(state);if(v instanceof Matrix4fc m)view.set(m);}
        catch(ReflectiveOperationException error){warn(error);}
    }
    static void raster(Matrix4f projection,Vector2f scale,Vector2f jitter) {
        if(!irisActive())return;
        try {
            Object p=pipeline();if(p==null)return;Object uniforms=field(p,"customUniforms");
            Object s=cached(uniforms,"fsrRenderScale"),j=cached(uniforms,"taaJitter");
            if(s instanceof Vector2fc v && Float.isFinite(v.x())&&Float.isFinite(v.y())&&v.x()>0&&v.y()>0)scale.set(v);
            if(j instanceof Vector2fc v && Float.isFinite(v.x())&&Float.isFinite(v.y()))jitter.set(v);
            if(scale.distanceSquared(1,1)>1E-10F) {
                Object size=cached(uniforms,"fsrScreenSize"),screen=cached(uniforms,"screenSize");
                if(size instanceof Vector2fc a && screen instanceof Vector2fc b && a.distanceSquared(b)<.01F) {
                    jitter.mul(scale);Object data=cached(uniforms,"gbufferProjection0");
                    if(data instanceof Vector4fc v)projection.m00(v.x()).m11(v.y());
                    if(data instanceof Vector3fc v)projection.m00(v.x()).m11(v.y());
                }
            }
        } catch(ReflectiveOperationException|RuntimeException error){warn(error);}
    }
    private static Object cached(Object uniforms,String name) throws ReflectiveOperationException {
        if(!Boolean.TRUE.equals(uniforms.getClass().getMethod("hasVariable",String.class).invoke(uniforms,name)))return null;
        return field(uniforms.getClass().getMethod("getVariable",String.class).invoke(uniforms,name),"cached");
    }
    static Object field(Object object,String name) throws ReflectiveOperationException {
        if(object==null)return null;
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try {var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(object.getClass().getName()+"."+name);
    }
    static Vector4f portalClip(net.minecraft.world.phys.Vec3 camera) {
        if(!IMMERSIVE_PORTALS)return null;
        try {Class<?> c=Class.forName("qouteall.imm_ptl.core.render.context_management.PortalRendering");
            if(!Boolean.TRUE.equals(c.getMethod("isRendering").invoke(null)))return null;
            Object portal=c.getMethod("getRenderingPortal").invoke(null);Object plane=portal.getClass().getMethod("getInnerClipping").invoke(portal);
            if(plane==null)return null;
            var pos=(net.minecraft.world.phys.Vec3)plane.getClass().getMethod("pos").invoke(plane);
            var normal=(net.minecraft.world.phys.Vec3)plane.getClass().getMethod("normal").invoke(plane);
            return new Vector4f((float)normal.x,(float)normal.y,(float)normal.z,(float)(-(pos.x-camera.x)*normal.x-(pos.y-camera.y)*normal.y-(pos.z-camera.z)*normal.z));
        }catch(ReflectiveOperationException error){warn(error);return null;}
    }
    static int attachment(int fbo,int attachment) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,fbo);
        if(GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11.GL_TEXTURE)return 0;
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_READ_FRAMEBUFFER,attachment,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }
    private static void warn(Throwable error) {if(!warned){warned=true;SkyboxBlockMod.LOGGER.warn("[skyboxblock] optional renderer adapter: {}",error.toString());}}
    public static void logEnvironment() {SkyboxBlockMod.LOGGER.info("[skyboxblock] isolated renderer: Iris={} DH={} IP={} (native 26.2 reversed depth)",IRIS,DISTANT_HORIZONS,IMMERSIVE_PORTALS);}
}
