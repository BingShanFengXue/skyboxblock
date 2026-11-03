package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.yg.skyboxblock.SkySkin;
import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.client.state.SkyBoxRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.*;
import org.lwjgl.opengl.*;

/**
 * View-owned sky-window renderer for 26.2. Opaque surface depth is written before transparency.
 * A material+depth provenance buffer then permits a strictly surface-local Iris final resolve.
 * All GL work is transactional; no MC pipeline or Iris hook is invoked by private rendering.
 */
public final class SkyWindowRenderer {
    private static final java.util.ArrayDeque<Frame> FRAMES=new java.util.ArrayDeque<>();
    private static final java.util.ArrayList<Gpu> GPU=new java.util.ArrayList<>();
    private static final Matrix4f PROJECTION=new Matrix4f();
    private static boolean projectionValid;
    private static volatile boolean reloadRequested;
    private static SkyGlProgram window,itrpBackground;
    private static int nearest,linear,panorama;
    private static long transaction,sequence;
    private record GameView(Matrix4f projection,boolean valid,long token,int depth) { }
    private static final java.util.ArrayDeque<GameView> GAME_VIEWS=new java.util.ArrayDeque<>();
    private static int diagnostics,bridgeDraws;
    private static Frame fixtureFrame;
    private SkyWindowRenderer() { }
    static long transaction() {return transaction;}

    private static final class Frame {
        final Matrix4f projection=new Matrix4f(),view=new Matrix4f(),rasterProjection=new Matrix4f(),inverse=new Matrix4f();
        final Vector2f scale=new Vector2f(1),jitter=new Vector2f();
        final java.util.LinkedHashMap<SkySkin,SkyVanillaScene.Floats> batches=new java.util.LinkedHashMap<>();
        final java.util.HashSet<Long> seen=new java.util.HashSet<>();
        final Vec3 camera;
        final SkySceneState sky;
        final boolean shadow;
        final int slot;
        final long token=transaction;
        Vector4f clip;
        boolean drawn,resolved,depthCopied,beforeTransparency,afterTransparency;
        int width,height,sceneTexture,worldColor,worldDepth,opaqueFbo;
        ItrpSceneBridge.MaterialTargets itrpMaterial;
        boolean itrpInjected;
        int itrpSceneInput,itrpOpaqueDepth,itrpExposure;
        Frame(LevelRenderState state,Matrix4fc view,float partial,int slot) {
            this.slot=slot;this.view.set(view);camera=state.cameraRenderState.pos;
            projection.set(projectionValid ? PROJECTION : state.cameraRenderState.projectionMatrix);
            shadow=ModCompat.irisShadowPass();sky=new SkySceneState(state,partial);
        }
    }
    private static final class Gpu implements AutoCloseable {
        final SkyVanillaScene scene=new SkyVanillaScene();
        int worldFbo=GL30.glGenFramebuffers(),coverageFbo=GL30.glGenFramebuffers(),finalFbo=GL30.glGenFramebuffers(),hdrFbo=GL30.glGenFramebuffers();
        int coverage,depthCopy,transBefore,transAfter,width,height;
        void ensure(int w,int h) {
            if(coverage!=0&&width==w&&height==h)return;
            if(coverage!=0)GL11.glDeleteTextures(coverage);if(depthCopy!=0)GL11.glDeleteTextures(depthCopy);
            if(transBefore!=0)GL11.glDeleteTextures(transBefore);if(transAfter!=0)GL11.glDeleteTextures(transAfter);
            width=w;height=h;
            transBefore=SkyVanillaScene.image(GL30.GL_RGBA16F,w,h,GL11.GL_RGBA,GL11.GL_FLOAT);
            transAfter=SkyVanillaScene.image(GL30.GL_RGBA16F,w,h,GL11.GL_RGBA,GL11.GL_FLOAT);
            coverage=SkyVanillaScene.image(GL30.GL_RG32F,w,h,GL30.GL_RG,GL11.GL_FLOAT);
            depthCopy=SkyVanillaScene.image(GL30.GL_DEPTH_COMPONENT32F,w,h,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT);
        }
        @Override public void close(){scene.close();GL30.glDeleteFramebuffers(worldFbo);GL30.glDeleteFramebuffers(coverageFbo);GL30.glDeleteFramebuffers(finalFbo);GL30.glDeleteFramebuffers(hdrFbo);if(coverage!=0)GL11.glDeleteTextures(coverage);if(depthCopy!=0)GL11.glDeleteTextures(depthCopy);if(transBefore!=0)GL11.glDeleteTextures(transBefore);if(transAfter!=0)GL11.glDeleteTextures(transAfter);}
    }

    /** Called before the real renderLevel, before any world/shadow/portal view is entered. */
    public static void beginGame() {
        if(GAME_VIEWS.isEmpty()) {
            if(reloadRequested){close();reloadRequested=false;}
            FRAMES.clear();
        }
        GAME_VIEWS.push(new GameView(new Matrix4f(PROJECTION),projectionValid,transaction,FRAMES.size()));
        projectionValid=false;transaction=++sequence;
    }
    public static void endGame() {
        GameView game=GAME_VIEWS.pop();
        while(FRAMES.size()>game.depth())FRAMES.pop();
        PROJECTION.set(game.projection());projectionValid=game.valid();transaction=game.token();
    }
    /** Receives the actual post-bob/post-hurt/post-nausea projection, not Camera's unmodified matrix. */
    public static Matrix4f captureProjection(Matrix4f projection) {PROJECTION.set(projection);projectionValid=true;return projection;}
    public static void beginLevel(LevelRenderState state,Matrix4fc view,float partial) {FRAMES.push(new Frame(state,view,partial,FRAMES.size()));}
    public static void endLevel() {
        Frame f=FRAMES.peek();if(f==null)return;
        try {if(!f.resolved && !f.shadow && (ModCompat.irisActive() || ModCompat.DISTANT_HORIZONS))resolve(f);}
        finally {FRAMES.pop();}
    }

    public static void collect(SkyBoxRenderState state,Matrix4fc pose) {
        Frame f=FRAMES.peek();if(f==null||f.drawn||f.shadow||ModCompat.irisShadowPass()||state.skin==null)return;
        if(!f.seen.add(state.blockPos.asLong()))return;
        SkyVanillaScene.Floats out=f.batches.computeIfAbsent(state.skin,k -> new SkyVanillaScene.Floats());
        if(state.up)face(out,state.blockPos,f.camera,0,1,0,0,1,1,1,1,1,1,1,0);
        if(state.down)face(out,state.blockPos,f.camera,0,0,1,0,0,0,1,0,0,1,0,1);
        if(state.north)face(out,state.blockPos,f.camera,1,0,0,0,0,0,0,1,0,1,1,0);
        if(state.south)face(out,state.blockPos,f.camera,0,0,1,1,0,1,1,1,1,0,1,1);
        if(state.west)face(out,state.blockPos,f.camera,0,0,0,0,0,1,0,1,1,0,1,0);
        if(state.east)face(out,state.blockPos,f.camera,1,0,1,1,0,0,1,1,0,1,1,1);
    }
    private static void face(SkyVanillaScene.Floats out,net.minecraft.core.BlockPos block,Vec3 camera,float... points) {
        for(int i:new int[]{0,1,2,0,2,3}) {
            // Match the 1.21.1 collector: add the grid corner in double precision BEFORE the
            // single float conversion. Per-block float-pose addition gives shared edges different bits.
            out.vertex(SkyRenderMath.relativeGridVertex(block.getX(),points[i*3],camera.x),
                    SkyRenderMath.relativeGridVertex(block.getY(),points[i*3+1],camera.y),
                    SkyRenderMath.relativeGridVertex(block.getZ(),points[i*3+2],camera.z),0,0,1,1,1,1);
        }
    }
    /** Immutable, borrowed-name snapshot for the meter only. Never exposes a mutable Frame/Gpu. */
    record MeterView(long token,boolean root,boolean portal,int slot,boolean shadow,
                     int generatedSky,int coverage,float[] projection,float[] view,
                     float scaleX,float scaleY,float jitterX,float jitterY,int width,int height,
                     Object pipeline,Object targets,Object composite,boolean sceneInjected,boolean darkSky) {
        MeterView {projection=projection.clone();view=view.clone();}
        @Override public float[] projection(){return projection.clone();}
        @Override public float[] view(){return view.clone();}
    }
    /** Read-only: the canvas is the private vanilla sky, never the chosen black/panorama display. */
    static MeterView activeRootMeterView() {
        Frame f=FRAMES.peek();
        if(f==null || !f.drawn || f.resolved || f.slot>=GPU.size() || f.itrpMaterial==null)return null;
        boolean portal=f.slot!=0 || GAME_VIEWS.size()!=1 || f.clip!=null;
        if(ModCompat.IMMERSIVE_PORTALS)try {
            portal|=Boolean.TRUE.equals(Class.forName("qouteall.imm_ptl.core.render.context_management.PortalRendering")
                    .getMethod("isRendering").invoke(null));
        } catch(ReflectiveOperationException|RuntimeException error) {return null;}
        boolean dark=false;for(SkySkin skin:f.batches.keySet())if(skin.darkSky()){dark=true;break;}
        Gpu g=GPU.get(f.slot);
        return new MeterView(f.token,f.slot==0 && GAME_VIEWS.size()==1,portal,f.slot,f.shadow,
                f.sceneTexture,g.coverage,f.projection.get(new float[16]),f.view.get(new float[16]),
                f.scale.x,f.scale.y,f.jitter.x,f.jitter.y,f.width,f.height,
                f.itrpMaterial.pipeline(),f.itrpMaterial.targets(),f.itrpMaterial.composite(),f.itrpInjected,dark);
    }
    private static Gpu gpu(Frame f) {while(GPU.size()<=f.slot)GPU.add(new Gpu());return GPU.get(f.slot);}
    private static void programs() {
        if(window==null)window=new SkyGlProgram("window");
        if(nearest==0)nearest=sampler(false,false);if(linear==0)linear=sampler(false,true);if(panorama==0)panorama=sampler(true,true);
    }
    /** Invoked inside the actual framegraph main pass immediately AFTER executeSolid(). */
    public static void drawOpaque() {
        Frame f=FRAMES.peek();if(f==null||f.drawn||f.shadow||ModCompat.irisShadowPass())return;
        f.drawn=true;if(f.batches.isEmpty())return;
        if(!System.getProperty("skyboxblock.fixture.control", "").isBlank()) fixtureFrame=f;
        // Lazy TextureManager loads use engine-side bindings/caches; perform them before raw GL scope.
        texture(Identifier.withDefaultNamespace("textures/environment/celestial/sun.png"));
        texture(Identifier.withDefaultNamespace("textures/environment/celestial/moon/"+
                net.minecraft.world.level.MoonPhase.values()[java.lang.Math.floorMod(f.sky.moonPhase,8)].getSerializedName()+".png"));
        for(SkySkin skin:f.batches.keySet())if(skin.texture()!=null)texture(skin.texture());
        try(SkyGlScope state=new SkyGlScope()) {
            programs();Gpu g=gpu(f);ModCompat.view(f.view);ModCompat.raster(f.projection,f.scale,f.jitter);
            f.clip=ModCompat.portalClip(f.camera);
            if(!worldTarget(f,g))return;
            g.ensure(f.width,f.height);
            f.rasterProjection.set(f.projection);
            // Same shader-side subrectangle convention as Iris/ITRP; never apply scale twice.
            SkyWindowMath.rasterProjection(f.projection,f.scale.x,f.scale.y,f.jitter.x,f.jitter.y,f.rasterProjection);
            // Render at the active pixel extent. Native canvas UVs are sampled at screen UV, not interpolated face UV.
            f.sceneTexture=g.scene.render(f.sky,f.rasterProjection,f.view,f.width,f.height,f.camera);
            f.itrpMaterial=ItrpSceneBridge.opaque(f.opaqueFbo,f.worldColor,f.worldDepth,f.width,f.height);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,g.worldFbo);
            // Private alias only: never rewrite a foreign Iris FBO's draw routing or attachments.
            attach(GL30.GL_COLOR_ATTACHMENT1,f.itrpMaterial==null?0:f.itrpMaterial.solid());
            attach(GL30.GL_COLOR_ATTACHMENT2,f.itrpMaterial==null?0:f.itrpMaterial.normal());
            if(f.itrpMaterial!=null)GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2});
            else GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            checkFramebuffer("world sky material alias");GL11.glViewport(0,0,f.width,f.height);
            SkyGlScope.clean();GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_GEQUAL);GL11.glDepthMask(true);
            clip(f);draw(f,g,0,false);
            // Record only the actual nearest sky surface, with a per-material identity, using hardware equality.
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,g.coverageFbo);
            attach(GL30.GL_COLOR_ATTACHMENT0,g.coverage);attach(GL30.GL_DEPTH_ATTACHMENT,f.worldDepth);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);checkFramebuffer("sky provenance");
            GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{0,0,0,0});GL11.glDepthFunc(GL11.GL_EQUAL);GL11.glDepthMask(false);
            draw(f,g,1,false);
            if(diagnostics++<3)SkyboxBlockMod.LOGGER.info("[skyboxblock] isolated opaque view={} blocks={} materials={} target={}x{} Iris={} scale={} jitter={}",f.token,f.seen.size(),f.batches.size(),f.width,f.height,ModCompat.irisActive(),f.scale,f.jitter);
        }
    }
    private static boolean worldTarget(Frame f,Gpu g) {
        int opaque=ModCompat.opaqueFramebuffer();f.opaqueFbo=opaque;
        if(ModCompat.irisActive()) {
            if(opaque<=0)throw new IllegalStateException("Iris 1.11.4 BLOCK_ENTITY opaque framebuffer is missing");
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,opaque);
            int attachment=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
            f.worldColor=ModCompat.attachment(opaque,attachment);f.worldDepth=ModCompat.attachment(opaque,GL30.GL_DEPTH_ATTACHMENT);
        } else {
            var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
            f.worldColor=gl(target.getColorTexture());f.worldDepth=gl(target.getDepthTexture());
        }
        if(f.worldColor<=0||f.worldDepth<=0)throw new IllegalStateException("Opaque framebuffer does not expose color/depth textures");
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,f.worldColor);
        f.width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
        f.height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,g.worldFbo);
        attach(GL30.GL_COLOR_ATTACHMENT0,f.worldColor);attach(GL30.GL_DEPTH_ATTACHMENT,f.worldDepth);
        // Never retain a previous view/pack's borrowed auxiliary attachments, including resize/reload.
        attach(GL30.GL_COLOR_ATTACHMENT1,0);attach(GL30.GL_COLOR_ATTACHMENT2,0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);checkFramebuffer("world sky alias");
        return f.width>0&&f.height>0;
    }
    /** Real scene RGB, before ITRP's refraction samples it; never an overlay on the final glass. */
    public static void beforeIrisComposite(Object owner) {
        Frame f=FRAMES.peek();
        if(f==null || f.shadow || !f.drawn || f.resolved || f.itrpInjected || f.itrpMaterial==null || f.batches.isEmpty())return;
        try(SkyGlScope ignored=new SkyGlScope()) {
            Gpu g=gpu(f);
            ItrpSceneBridge.SceneInput input=ItrpSceneBridge.sceneInput(owner,f.itrpMaterial,f.width,f.height,g.coverage,f.sceneTexture);
            if(input==null)return;
            if(itrpBackground==null)itrpBackground=new SkyGlProgram("itrp_background");
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,g.hdrFbo);
            attach(GL30.GL_COLOR_ATTACHMENT0,input.color());
            try {
                GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);checkFramebuffer("ITRP sky scene input");
                GL11.glViewport(0,0,f.width,f.height);SkyGlScope.clean();GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);clip(f);
                itrpBackground.use();itrpBackground.matrix("Projection",f.rasterProjection);itrpBackground.matrix("View",f.view);
                itrpBackground.matrix("RayInverse",SkyWindowMath.rayInverse(f.rasterProjection,f.view,f.inverse));
                itrpBackground.vector2("RayDepth",1,RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? .5F : 0);
                itrpBackground.vector4("Viewport",0,0,f.width,f.height);
                itrpBackground.integer("Clipping",f.clip==null?0:1);
                Vector4f plane=f.clip==null?new Vector4f(0,0,0,1):f.clip;
                itrpBackground.vector4("ClipPlane",plane.x,plane.y,plane.z,plane.w);
                itrpBackground.texture("Coverage",1,g.coverage,nearest);
                itrpBackground.texture("WorldOpaqueDepth",2,input.opaqueDepth(),nearest);
                itrpBackground.texture("ExposureData",4,input.exposure(),nearest);
                itrpBackground.integer("DistantEnabled",0);SkyDistantDepth.configure(itrpBackground,f.token,f.camera,f.view,f.width,f.height,nearest);
                for(var entry:f.batches.entrySet()) {
                    SkySkin skin=entry.getKey();int style=skin.texture()!=null?3:skin.shaderMode();
                    itrpBackground.integer("Style",style);itrpBackground.integer("Material",skin.material());
                    itrpBackground.texture("Image",0,skin.texture()!=null?texture(skin.texture()):f.sceneTexture,style==3?panorama:linear);
                    itrpBackground.draw(entry.getValue().data,entry.getValue().count);
                }
                f.itrpSceneInput=input.color();f.itrpOpaqueDepth=input.opaqueDepth();f.itrpExposure=input.exposure();f.itrpInjected=true;
                if(bridgeDraws++<3)SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP scene input injected before composite40: view={} materials={} input={} depth={} exposure={}",f.token,f.batches.size(),input.color(),input.opaqueDepth(),input.exposure());
            } finally {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,g.hdrFbo);attach(GL30.GL_COLOR_ATTACHMENT0,0);
            }
        }
    }
    /** Observe only transparency, bracketed AFTER deferred lighting and BEFORE composite/final. */
    public static void transparencyBoundary(boolean after) {
        Frame f=FRAMES.peek();if(f==null||!f.drawn||f.shadow||f.batches.isEmpty()||f.worldColor<=0)return;
        try(SkyGlScope ignored=new SkyGlScope()) {
            Gpu g=gpu(f);int source=ModCompat.irisActive()?ModCompat.translucentFramebuffer():g.worldFbo;
            if(source<=0)return;
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,source);
            int buffer=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,source);
            // Read-buffer selection belongs to a foreign FBO: restore it even though GL bindings are scoped.
            int previous=GL11.glGetInteger(GL11.GL_READ_BUFFER);
            try {
                GL11.glReadBuffer(buffer);GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,after?g.transAfter:g.transBefore);
                GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,f.width,f.height);
            } finally {GL11.glReadBuffer(previous);}
            if(after)f.afterTransparency=true;else f.beforeTransparency=true;
        }
    }
    /** Iris final-pass entry is still before native hand depth clear, and includes Iris's own hand depth. */
    public static void beforeIrisFinal() {
        Frame f=FRAMES.peek();if(f==null||!f.drawn||f.batches.isEmpty()||f.shadow)return;
        try(SkyGlScope ignored=new SkyGlScope()){copyDepth(f,gpu(f));}
    }
    /** Runs immediately after the real per-view Iris final pass, not a HUD/end-frame overlay. */
    public static void afterIrisFinal(){Frame f=FRAMES.peek();if(f!=null)resolve(f);}
    private static void copyDepth(Frame f,Gpu g) {
        if(f.worldDepth<=0||g.depthCopy==0)return;
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,g.worldFbo);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,g.depthCopy);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,0,0,f.width,f.height);f.depthCopied=true;
    }
    private static void resolve(Frame f) {
        if(f.resolved||!f.drawn||f.shadow||f.batches.isEmpty()||f.worldColor<=0)return;
        try(SkyGlScope ignored=new SkyGlScope()) {
            programs();Gpu g=gpu(f);if(!f.depthCopied)copyDepth(f,g);if(!f.depthCopied)return;
            var target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
            int w=target.width,h=target.height;
            int scene=g.scene.render(f.sky,f.projection,f.view,w,h,f.camera);f.sceneTexture=scene;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,g.finalFbo);
            attach(GL30.GL_COLOR_ATTACHMENT0,gl(target.getColorTexture()));
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);checkFramebuffer("final sky alias");
            GL11.glViewport(0,0,w,h);SkyGlScope.clean();GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);
            clip(f);draw(f,g,2,true);f.resolved=true;
        }
    }
    private static void clip(Frame f) {
        if(f.clip!=null)GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
    }
    private static void draw(Frame f,Gpu g,int pass,boolean display) {
        Matrix4fc p=display ? f.projection : f.rasterProjection;
        window.use();window.matrix("Projection",p);window.matrix("View",f.view);
        window.matrix("RayInverse",SkyWindowMath.rayInverse(p,f.view,f.inverse));
        window.vector2("RayDepth",1,RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? .5F : 0);
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
        window.vector4("Viewport",viewport[0],viewport[1],viewport[2],viewport[3]);
        window.vector2("DepthScale",display?f.scale.x:1,display?f.scale.y:1);
        window.vector2("DepthOffset",display?f.jitter.x*.5F:0,display?f.jitter.y*.5F:0);
        window.integer("Pass",pass);window.integer("ItrpMaterial",pass==0&&f.itrpMaterial!=null?1:0);window.integer("Clipping",f.clip==null?0:1);
        Vector4f clip=f.clip==null?new Vector4f(0,0,0,1):f.clip;
        window.vector4("ClipPlane",clip.x,clip.y,clip.z,clip.w);
        window.texture("Coverage",1,g.coverage,nearest);window.texture("WorldDepth",2,g.depthCopy,nearest);
        window.texture("TransBefore",4,g.transBefore,nearest);window.texture("TransAfter",5,g.transAfter,nearest);
        window.integer("TransparentGuard",f.beforeTransparency&&f.afterTransparency?1:0);
        window.integer("DistantEnabled",0);SkyDistantDepth.configure(window,f.token,f.camera,f.view,f.width,f.height,nearest);
        for(var entry:f.batches.entrySet()) {
            SkySkin skin=entry.getKey();int style=skin.texture()!=null?3:skin.shaderMode();
            window.integer("Style",style);window.integer("Material",skin.material());
            window.texture("Image",0,skin.texture()!=null?texture(skin.texture()):f.sceneTexture,style==3?panorama:linear);
            window.draw(entry.getValue().data,entry.getValue().count);
        }
    }
    static int gl(com.mojang.blaze3d.textures.GpuTexture texture) {
        if(!(texture instanceof GlTexture gl))throw new IllegalStateException("The isolated sky renderer requires the 26.2 OpenGL backend");return gl.glId();
    }
    static int texture(Identifier id) {return gl(Minecraft.getInstance().getTextureManager().getTexture(id).getTexture());}
    private static void attach(int attachment,int texture){GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,texture,0);}
    static void checkFramebuffer(String label){int status=GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);if(status!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException(label+" framebuffer status=0x"+Integer.toHexString(status));}
    static int sampler(boolean repeat,boolean smooth) {
        int id=GL33.glGenSamplers();GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_WRAP_S,repeat?GL11.GL_REPEAT:GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_MIN_FILTER,smooth?GL11.GL_LINEAR:GL11.GL_NEAREST);
        GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_MAG_FILTER,smooth?GL11.GL_LINEAR:GL11.GL_NEAREST);
        GL33.glSamplerParameteri(id,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);return id;
    }
    /** Explicit fixture-only readback: capture the actual provenance/transparent inputs, never inferred colors. */
    public static com.google.gson.JsonObject fixtureDebug(java.nio.file.Path directory) throws java.io.IOException {
        if(System.getProperty("skyboxblock.fixture.control", "").isBlank()) throw new IllegalStateException("fixture not enabled");
        Frame f=fixtureFrame;if(f==null || f.slot>=GPU.size()) throw new IllegalStateException("no fixture sky frame");
        java.nio.file.Files.createDirectories(directory);
        Gpu g=GPU.get(f.slot);
        com.google.gson.JsonObject meta=new com.google.gson.JsonObject();
        meta.addProperty("width",f.width);meta.addProperty("height",f.height);meta.addProperty("token",f.token);
        meta.addProperty("resolved",f.resolved);meta.addProperty("depthCopied",f.depthCopied);
        meta.addProperty("beforeTransparency",f.beforeTransparency);meta.addProperty("afterTransparency",f.afterTransparency);
        meta.addProperty("scaleX",f.scale.x);meta.addProperty("scaleY",f.scale.y);
        meta.addProperty("jitterX",f.jitter.x);meta.addProperty("jitterY",f.jitter.y);
        meta.addProperty("cameraX",f.camera.x);meta.addProperty("cameraY",f.camera.y);meta.addProperty("cameraZ",f.camera.z);
        var json=new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        meta.add("projection",json.toJsonTree(f.projection.get(new float[16])));
        meta.add("rasterProjection",json.toJsonTree(f.rasterProjection.get(new float[16])));
        meta.add("view",json.toJsonTree(f.view.get(new float[16])));
        var materials=new com.google.gson.JsonArray();
        for(var skin:f.batches.keySet()){var item=new com.google.gson.JsonObject();item.addProperty("id",skin.id());item.addProperty("material",skin.material());materials.add(item);}
        meta.add("materials",materials);
        try(SkyGlScope ignored=new SkyGlScope()) {
            int pbo=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int alignment=GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),row=GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
            int skipRows=GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS),skipPixels=GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,1);GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,0);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,0);GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,0);
                fixtureTexture(directory,"coverage",g.coverage,f.width,f.height,GL30.GL_RG,2);
                fixtureTexture(directory,"depth",g.depthCopy,f.width,f.height,GL11.GL_DEPTH_COMPONENT,1);
                fixtureTexture(directory,"before",g.transBefore,f.width,f.height,GL11.GL_RGBA,4);
                fixtureTexture(directory,"after",g.transAfter,f.width,f.height,GL11.GL_RGBA,4);
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,pbo);GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,alignment);
                GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH,row);GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS,skipRows);
                GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS,skipPixels);
            }
        }
        java.nio.file.Files.writeString(directory.resolve("gpu-inputs.json"),json.toJson(meta),java.nio.charset.StandardCharsets.UTF_8);
        return meta;
    }
    private static void fixtureTexture(java.nio.file.Path directory,String label,int texture,int width,int height,int format,int components) throws java.io.IOException {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        java.nio.ByteBuffer buffer=org.lwjgl.BufferUtils.createByteBuffer(width*height*components*4);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,format,GL11.GL_FLOAT,buffer);
        byte[] data=new byte[buffer.remaining()];buffer.get(data);
        java.nio.file.Files.write(directory.resolve(label+".f32"),data);
    }
    public static void invalidate(){reloadRequested=true;}
    public static void close() {
        for(Gpu g:GPU)g.close();GPU.clear();if(window!=null){window.close();window=null;}
        if(itrpBackground!=null){itrpBackground.close();itrpBackground=null;}ItrpDarkMeterProxy.release();ItrpSceneBridge.invalidate();bridgeDraws=0;
        for(int s:new int[]{nearest,linear,panorama})if(s!=0)GL33.glDeleteSamplers(s);nearest=linear=panorama=0;
        FRAMES.clear();projectionValid=false;fixtureFrame=null;
    }
}
