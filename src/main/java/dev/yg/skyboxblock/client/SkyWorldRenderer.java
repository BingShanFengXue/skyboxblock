package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyBlockEntity;
import dev.yg.skyboxblock.SkyBlock;
import dev.yg.skyboxblock.SkyStyle;
import java.util.EnumMap;
import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.mixin.CloudMeshAccess;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** World-space sky with view-owned state, early occlusion and an immediate per-view Iris resolve. */
public final class SkyWorldRenderer {
    private static final ArrayDeque<Frame> FRAMES=new ArrayDeque<>();
    private static final ArrayDeque<GameView> GAME_VIEWS=new ArrayDeque<>();
    private static final SkyMesh MESH=new SkyMesh();
    private static final boolean LEGACY_SEAMS=Boolean.getBoolean("skyboxblock.regression")
            && Boolean.getBoolean("skyboxblock.regression.negativeSeams");
    private static final boolean LEGACY_ENTITY_VALIDITY=Boolean.getBoolean("skyboxblock.regression")
            && Boolean.getBoolean("skyboxblock.regression.negativeEntityValidity");
    private static ShaderInstance shader,resolveShader;
    private static int depthTexture,depthWidth,depthHeight,debugFrames;
    private static long transaction,viewCounter;
    private static Frame lastRoot;

    private static final class GameView {
        final boolean root;
        final int entryWorldDepth;
        Frame frame;
        int children;
        GameView(boolean root) { this.root=root; entryWorldDepth=FRAMES.size(); }
    }

    private static final class MaterialBatch {
        float[] vertices=new float[1024];
        int count;
        void append(float[] source,int start,int length) {
            if(count+length>vertices.length) vertices=Arrays.copyOf(vertices,Math.max(vertices.length*2,count+length));
            System.arraycopy(source,start,vertices,count,length);
            count+=length;
        }
    }

    public static final class Frame {
        public final LevelRenderer renderer;
        public final ClientLevel level;
        public final Vec3 camera;
        public final Matrix4f projection,view;
        public final Matrix4f skyProjection=new Matrix4f();
        private final Matrix4f panoramaRayInverse=new Matrix4f();
        public final org.joml.Vector2f rasterScale=new org.joml.Vector2f(1,1);
        public final org.joml.Vector2f rasterJitter=new org.joml.Vector2f();
        public final float partialTick;
        public final boolean portal,shadow,root,mirrored;
        public final int viewSlot;
        public final long viewId,rootTransaction;
        public Vector4f clipPlane;
        public SkyDisplayState sky;
        public int framebuffer,ownSkyCoverageTexture,ownSkyColorTexture,distantDepthTexture;
        public boolean distantDepthEnabled,distantOpaqueReady,distantHdrReady,edgeHistoryUsed;
        public int edgeHistoryFrames;
        public boolean surfaceLayerUsed;
        public int surfaceLayerSamples;
        /** Borrowed current native-DH backdrop; never an input to generated sky and never deleted here. */
        public boolean nativeBackgroundReady;
        public int nativeBackgroundColorTexture;
        public long geometrySignature;
        public boolean nativeSkyFogCaptured;
        public float nativeSkyFogStart,nativeSkyFogEnd;
        public int nativeSkyFogShape;
        /** Full physical GL viewport, separate from the pack's active lower-left raster. */
        public int[] targetViewport=new int[4],viewport=new int[4];
        public double[] depthRange=new double[2];
        public int floatCount,finalResolveCount;
        public boolean drawn,finalResolved,irisFinalHook;
        public boolean itrpMaterialWrites,itrpBackgroundInjected;
        public boolean exposureMeterCorrected,exposureMeterRestored;
        public int exposureMeterSource,exposureMeterProxy;
        public boolean privateSkyClipBefore,privateSkyClipDuring;
        public int itrpInjectionCount,itrpSceneInputTexture,itrpOpaqueDepthTexture,itrpExposureTexture;
        public String itrpBridgeFailure,referenceWorld,referenceDisplay,preViewImage,postViewImage,viewImageStage;
        public int colorAttachment;
        private float[] vertices=new float[1024];
        private final Set<Long> seen=new HashSet<>();
        private final EnumMap<SkyStyle,MaterialBatch> materials=new EnumMap<>(SkyStyle.class);

        Frame(LevelRenderer renderer,Camera camera,Matrix4f view,Matrix4f projection,float partialTick) {
            this.renderer=renderer;
            level=((CloudMeshAccess)renderer).skyboxblock$getLevel();
            this.camera=camera.getPosition();
            this.projection=new Matrix4f(projection); this.view=new Matrix4f(view); this.partialTick=partialTick;
            portal=ModCompat.portalRendering(); shadow=ModCompat.irisShadowPass(); mirrored=ModCompat.mirroredPortal();
            root=GAME_VIEWS.isEmpty() ? !portal : GAME_VIEWS.peek().root;
            viewSlot=Math.max(0,GAME_VIEWS.size()-1);
            viewId=++viewCounter; rootTransaction=transaction;
        }
        public int blocks() { return seen.size(); }
        public int vertices() { return floatCount/3; }
        boolean collected(long packed) { return seen.contains(packed); }
        long[] collectedPositions() { return seen.stream().mapToLong(Long::longValue).toArray(); }
        private void face(SkyStyle style,int x,int y,int z,float[] corners) {
            int start=floatCount;
            if (floatCount+54>vertices.length) vertices=Arrays.copyOf(vertices,vertices.length*2);
            for (int vertex:new int[]{0,1,2,0,2,3}) {
                int o=vertex*3;
                vertices[floatCount++]=LEGACY_SEAMS ? (float)(x-camera.x)+corners[o] : SkyRenderMath.relativeGridVertex(x,corners[o],camera.x);
                vertices[floatCount++]=LEGACY_SEAMS ? (float)(y-camera.y)+corners[o+1] : SkyRenderMath.relativeGridVertex(y,corners[o+1],camera.y);
                vertices[floatCount++]=LEGACY_SEAMS ? (float)(z-camera.z)+corners[o+2] : SkyRenderMath.relativeGridVertex(z,corners[o+2],camera.z);
            }
            materials.computeIfAbsent(style,ignored -> new MaterialBatch()).append(vertices,start,floatCount-start);
        }
    }
    private SkyWorldRenderer() {}
    public static void setShader(ShaderInstance instance) { shader=instance; }
    public static void setResolveShader(ShaderInstance instance) { resolveShader=instance; }
    public static Frame lastRoot() { return lastRoot; }
    public static Frame current() { return FRAMES.peek(); }
    static Frame activeGameFrame() { return GAME_VIEWS.isEmpty() ? current() : GAME_VIEWS.peek().frame; }

    /** Called by a try/finally WrapMethod: cancellation and cross-portal extra views cannot leak scopes. */
    public static void beginFrame() {
        boolean root=GAME_VIEWS.isEmpty();
        if (root) { FRAMES.clear(); lastRoot=null; transaction++; }
        else GAME_VIEWS.peek().children++;
        GAME_VIEWS.push(new GameView(root));
    }
    public static void endFrame(boolean completed) {
        if (GAME_VIEWS.isEmpty()) return;
        GameView game=GAME_VIEWS.peek();
        try {
            if (completed && game.root) {
                // A late fallback is only valid before any child view has reused the depth targets.
                if (game.children==0 && game.frame!=null && !game.frame.irisFinalHook) resolveView(game.frame);
                RenderRegression.verifyNeverSkyFrame(game.frame);
                RenderRegression.afterFrame();
            }
        } finally {
            while (FRAMES.size()>game.entryWorldDepth) FRAMES.pop();
            GAME_VIEWS.pop();
        }
    }
    public static void beginView(LevelRenderer renderer,Camera camera,Matrix4f view,Matrix4f projection,float partial) {
        Frame frame=new Frame(renderer,camera,view,projection,partial);
        FRAMES.push(frame);
        if (!frame.shadow && !GAME_VIEWS.isEmpty()) GAME_VIEWS.peek().frame=frame;
        if (frame.root && !frame.shadow) lastRoot=frame;
        BlockCollectionDiagnostics.beginView(frame);
        PortalViewCapture.beginView(frame);
    }
    public static void captureSkyEnvironment() {
        Frame frame=current();
        if (frame==null || frame.shadow) return;
        frame.nativeSkyFogCaptured=true;
        frame.nativeSkyFogStart=com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        frame.nativeSkyFogEnd=com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        frame.nativeSkyFogShape=com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape().getIndex();
    }
    public static void endView() {
        Frame frame=current();
        // Native renderItemInHand clears the WORLD depth buffer. Repair DH composition while
        // full near terrain/translucent depth is still alive, then let the real hand draw over it.
        // Iris retains its own immediate final-pass hook and is deliberately unchanged here.
        if(frame!=null && !ModCompat.irisActive() && ModCompat.DISTANT_HORIZONS && DistantSkyDepth.current(frame))
            resolveView(frame);
        if (!FRAMES.isEmpty()) FRAMES.pop();
    }
    public static void resolveIrisView() {
        Frame frame=activeGameFrame();
        if (frame==null || frame.shadow) return;
        frame.irisFinalHook=true;
        PortalViewCapture.preView(frame,"iris-final");
        resolveView(frame);
        PortalViewCapture.postView(frame,"iris-final");
    }
    /** Compatibility fallback; not used to paint root sky over already composed portals. */
    public static void resolveFinal() {
        if (GAME_VIEWS.isEmpty() || GAME_VIEWS.peek().children==0) resolveView(lastRoot);
    }

    /** Shared uniforms for the real world meshes, owned depth markers and explicit diagnostics. */
    static void geometryUniforms(ShaderInstance instance,Frame frame,int[] viewport,boolean display) {
        instance.getUniform("WorldProjection").set(frame.projection);
        instance.getUniform("WorldView").set(frame.view);
        instance.getUniform("SkyViewport").set((float)viewport[0],(float)viewport[1],(float)viewport[2],(float)viewport[3]);
        var panoramaRay=instance.getUniform("PanoramaRayInverse");
        if(panoramaRay!=null) panoramaRay.set(SkyRenderMath.panoramaRayInverse(
                display ? frame.projection : frame.skyProjection,frame.view,frame.panoramaRayInverse));
        var legacyPanorama=instance.getUniform("PanoramaLegacyRays");
        if(legacyPanorama!=null) legacyPanorama.set(Boolean.getBoolean("skyboxblock.regression")
                && Boolean.getBoolean("skyboxblock.regression.negativePanoramaRays") ? 1 : 0);
        instance.getUniform("RasterScale").set(display ? 1F : frame.rasterScale.x,display ? 1F : frame.rasterScale.y);
        instance.getUniform("RasterJitter").set(display ? 0F : frame.rasterJitter.x,display ? 0F : frame.rasterJitter.y);
        instance.getUniform("ClippingEnabled").set(frame.clipPlane==null ? 0 : 1);
        Vector4f plane=frame.clipPlane==null ? new Vector4f(0,0,0,1) : frame.clipPlane;
        instance.getUniform("ClipPlane").set(plane.x,plane.y,plane.z,plane.w);
        var normalSign=instance.getUniform("NormalSign");
        if (normalSign!=null) normalSign.set(frame.mirrored ? -1F : 1F);
        GL11.glFrontFace(frame.mirrored ? GL11.GL_CW : GL11.GL_CCW);
        GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
    }
    /** Diagnostics reuse the exact production vertices, never reconstructed screen geometry. */
    static void drawDiagnosticFaces(Frame frame,ShaderInstance instance) { MESH.draw(instance,frame.vertices,frame.floatCount,false); }
    static boolean needsDarkSkyMeter(Frame frame) {
        return frame!=null && (frame.materials.containsKey(SkyStyle.BLACK)
                || frame.materials.containsKey(SkyStyle.GALAXY) || frame.materials.containsKey(SkyStyle.APOCALYPSE));
    }
    /** All color-producing passes use identical style batches; depth-only diagnostics stay combined. */
    static void drawMaterialFaces(Frame frame,ShaderInstance instance,int vanillaTexture) {
        for(var entry:frame.materials.entrySet()) {
            SkyStyle style=entry.getKey();
            instance.getUniform("SkyStyle").set(style.shaderMode());
            var material=instance.getUniform("SkyMaterial");
            if(material!=null) material.set(Boolean.getBoolean("skyboxblock.regression")
                    && Boolean.getBoolean("skyboxblock.regression.negativeMaterialOwnership") ? -1 : style.ordinal());
            int texture=vanillaTexture;
            if(style.panorama()!=null) {
                texture=net.minecraft.client.Minecraft.getInstance().getTextureManager()
                        .getTexture(SkyboxBlockMod.id("textures/sky/"+style.panorama()+".png")).getId();
                SkyGlState.active(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                SkyGlState.bindTexture(texture);
                // Periodic longitude, clamped poles; no per-face UVs or implicit mip selection at atan's wrap.
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL11.GL_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            }
            instance.setSampler("Sampler0",texture);
            MaterialBatch batch=entry.getValue();
            MESH.draw(instance,batch.vertices,batch.count,false);
        }
    }
    static void drawCoverageFaces(Frame frame,ShaderInstance instance) {
        for(var entry:frame.materials.entrySet()) {
            instance.getUniform("SkyMaterial").set(entry.getKey().ordinal());
            MaterialBatch batch=entry.getValue();
            MESH.draw(instance,batch.vertices,batch.count,false);
        }
    }
    public static void collect(SkyBlockEntity block) {
        Frame frame=current();
        BlockCollectionDiagnostics.collect(frame,block);
        if (frame==null || frame.shadow || frame.drawn || frame.level==null || block.getLevel()!=frame.level) return;
        var pos=block.getBlockPos();
        if (!LEGACY_ENTITY_VALIDITY) {
            if (block.isRemoved()) return;
            var chunk=frame.level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
            // Cached third-party render lists can still contain a removed/replaced instance.
            // Ownership must be validated before the old instance can occupy this view's seen set.
            if (chunk==null || !(chunk.getBlockState(pos).getBlock() instanceof SkyBlock)
                    || chunk.getBlockState(pos)!=block.getBlockState()
                    || chunk.getBlockEntities().get(pos)!=block) return;
        }
        long key=pos.asLong();
        if (!frame.seen.add(key)) return;
        long mixed=key; mixed^=mixed>>>33; mixed*=0xff51afd7ed558ccdL; mixed^=mixed>>>33; mixed*=0xc4ceb9fe1a85ec53L; mixed^=mixed>>>33;
        if (!(block.getBlockState().getBlock() instanceof SkyBlock skyBlock)) return;
        SkyStyle style=skyBlock.style();
        frame.geometrySignature+=mixed ^ (0x9e3779b97f4a7c15L * style.ordinal());
        for (Direction direction:Direction.values()) if (block.shouldRenderFace(direction))
            frame.face(style,pos.getX(),pos.getY(),pos.getZ(),FACES[direction.ordinal()]);
    }
    public static void drawOpaque() {
        Frame frame=current();
        if (frame==null || frame.drawn || frame.shadow || frame.level==null) return;
        frame.drawn=true;
        if(frame.root && frame.floatCount==0) SkyTemporalEdgeMask.idle();
        BlockCollectionDiagnostics.opaque(frame);
        ModCompat.copyWorldMatrices(frame.projection,frame.view);
        frame.clipPlane=ModCompat.portalClipPlane(frame.camera);
        if (frame.root) RenderRegression.beforePrivatePass();
        try (SkyGlState originalTarget=new SkyGlState(6)) {
            if (!ModCompat.bindOpaqueTarget()) return;
            try (SkyGlState state=new SkyGlState(6)) {
                frame.framebuffer=state.drawFramebuffer; frame.colorAttachment=state.firstDrawBuffer();
                frame.targetViewport=state.viewport.clone(); frame.viewport=state.viewport.clone();
                ModCompat.applyRasterSettings(frame,state.viewport[2],state.viewport[3]);
                RenderRegression.prepareCapture(frame);
                GL11.glGetDoublev(GL11.GL_DEPTH_RANGE,frame.depthRange);
                boolean nativeView=!ModCompat.irisActive();
                if (nativeView) PortalViewCapture.preView(frame,"opaque");
                if (frame.floatCount==0 || shader==null) {
                    RenderRegression.worldNoGeometry(frame);
                    if (nativeView) PortalViewCapture.postView(frame,"opaque");
                    return;
                }
                int skyTexture=VanillaSkyScene.render(frame,frame.viewport[2],frame.viewport[3]);
                if (skyTexture<0) return;
                frame.ownSkyColorTexture=skyTexture;
                RenderRegression.beforeWorldDraw(frame);
                int nativeLayer=0;
                if(nativeView && resolveShader!=null && SkySurfaceLayer.available(frame)) {
                    copyFinalDepth(frame); // Pre-sky WORLD depth only; no world color capture.
                    resolveShader.getUniform("DistantDepthEnabled").set(0);
                    resolveShader.setSampler("DistantDepth",depthTexture);
                    if(ModCompat.DISTANT_HORIZONS) DistantSkyDepth.configure(resolveShader,frame);
                    resolveShader.getUniform("CoverageEnabled").set(0);
                    resolveShader.setSampler("SkyCoverage",depthTexture);
                    resolveShader.setSampler("WorldDepth",depthTexture);
                    resolveShader.getUniform("ForegroundEdgeGuard").set(0);
                    resolveShader.getUniform("ForegroundHistoryEnabled").set(0);
                    resolveShader.setSampler("ForegroundHistory",depthTexture);
                    resolveShader.getUniform("DepthUvTransform").set(1F,1F,0F,0F);
                    nativeLayer=SkySurfaceLayer.render(frame,resolveShader,skyTexture,frame.viewport);
                }
                SkyGlState.opaqueState(); state.restoreClipTests(); state.clearSamplers();
                // The bridge routes extra MRT outputs only after exact pack/owner/texture validation.
                ItrpSkyBridge.configureOpaque(frame,shader,state.colorBuffers());
                shader.setSampler("Sampler0",skyTexture);
                shader.getUniform("DistantDepthEnabled").set(0);
                shader.setSampler("DistantDepth",skyTexture);
                shader.getUniform("TextureExtent").set((float)frame.targetViewport[2],(float)frame.targetViewport[3]);
                frame.distantOpaqueReady=ModCompat.DISTANT_HORIZONS && DistantSkyDepth.configure(shader,frame);
                geometryUniforms(shader,frame,frame.viewport,false);
                if(nativeLayer>0) {
                    com.mojang.blaze3d.systems.RenderSystem.colorMask(false,false,false,false);
                    GL11.glColorMask(false,false,false,false);
                }
                drawMaterialFaces(frame,shader,skyTexture);
                if(nativeLayer>0) {
                    com.mojang.blaze3d.systems.RenderSystem.colorMask(true,true,true,true);
                    GL11.glColorMask(true,true,true,true);
                    SkySurfaceLayer.composite(nativeLayer,frame.viewport);
                }
                frame.ownSkyCoverageTexture=SkyCoverage.record(frame);
                RenderRegression.afterWorldDraw(frame);
                if (nativeView) PortalViewCapture.postView(frame,"opaque");
            }
        } finally { if (frame.root) RenderRegression.afterPrivatePass(); }
        if (SkyboxConfig.get().debug() && debugFrames++%300==0)
            SkyboxBlockMod.LOGGER.info("[skyboxblock] opaque sky: blocks={} vertices={} fbo={} portal={} view={} slot={}",
                    frame.blocks(),frame.vertices(),frame.framebuffer,frame.portal,frame.viewId,frame.viewSlot);
    }
    /** Consume this view's depth immediately after its real Iris final pass, before IP snapshots/reuses it. */
    private static void resolveView(Frame frame) {
        boolean iris=ModCompat.irisActive();
        boolean distant=ModCompat.DISTANT_HORIZONS && DistantSkyDepth.current(frame);
        if ((!iris && !distant) || frame==null || !frame.drawn || frame.floatCount==0
                || frame.shadow || frame.finalResolved || resolveShader==null) return;
        try (SkyGlState original=new SkyGlState(6)) {
            copyFinalDepth(frame);
            resolveShader.getUniform("DistantDepthEnabled").set(0);
            resolveShader.getUniform("ForegroundEdgeGuard").set(iris && (frame.rasterScale.x<.99999F || frame.rasterScale.y<.99999F
                    || frame.rasterJitter.lengthSquared()>0F) ? 1 : 0);
            resolveShader.setSampler("DistantDepth",depthTexture);
            boolean distantReady=ModCompat.DISTANT_HORIZONS && DistantSkyDepth.configure(resolveShader,frame);
            if(!iris && !distantReady) return;
            net.minecraft.client.Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
            try (SkyGlState target=new SkyGlState(6)) {
                int[] viewport=target.viewport;
                // Native DH already has a full-resolution private canvas from this same view.
                // Reuse it rather than drawing the sky/clouds twice just to repair DH composition.
                int skyTexture=!iris && frame.ownSkyColorTexture>0 && frame.rasterScale.x==1F && frame.rasterScale.y==1F
                        && frame.viewport[2]==viewport[2] && frame.viewport[3]==viewport[3]
                        ? frame.ownSkyColorTexture : VanillaSkyScene.renderDisplay(frame,viewport[2],viewport[3]);
                if (skyTexture<0) return;
                boolean temporal=iris && frame.root && !frame.portal && (frame.rasterScale.x<.99999F || frame.rasterScale.y<.99999F || frame.rasterJitter.lengthSquared()>0F);
                int edgeHistory=temporal ? SkyTemporalEdgeMask.update(frame,viewport,depthTexture,depthWidth,depthHeight) : 0;
                resolveShader.getUniform("ForegroundHistoryEnabled").set(edgeHistory>0 ? 1 : 0);
                resolveShader.setSampler("ForegroundHistory",edgeHistory>0 ? edgeHistory : depthTexture);
                if (frame.root) RenderRegression.beforeFinalResolve();
                SkyGlState.opaqueState(); target.restoreClipTests(); target.clearSamplers();
                com.mojang.blaze3d.systems.RenderSystem.disableDepthTest(); GL11.glDisable(GL11.GL_DEPTH_TEST);
                com.mojang.blaze3d.systems.RenderSystem.depthMask(false); GL11.glDepthMask(false);
                if (target.drawFramebuffer==0) GL11.glDrawBuffer(target.firstDrawBuffer());
                else GL20.glDrawBuffers(new int[]{target.firstDrawBuffer()});
                resolveShader.setSampler("Sampler0",skyTexture);
                resolveShader.setSampler("WorldDepth",depthTexture);
                resolveShader.setSampler("SkyCoverage",frame.ownSkyCoverageTexture==0 ? depthTexture : frame.ownSkyCoverageTexture);
                resolveShader.getUniform("CoverageEnabled").set(frame.ownSkyCoverageTexture==0 ? 0 : 1);
                geometryUniforms(resolveShader,frame,viewport,true);
                resolveShader.getUniform("DepthUvTransform").set(frame.rasterScale.x,frame.rasterScale.y,
                        (float)(frame.viewport[0]-frame.targetViewport[0])/depthWidth+frame.rasterJitter.x*.5F,
                        (float)(frame.viewport[1]-frame.targetViewport[1])/depthHeight+frame.rasterJitter.y*.5F);
                boolean preserveNativeFringe=!iris && frame.surfaceLayerUsed;
                int surface=(frame.itrpMaterialWrites || !iris) && SkySurfaceLayer.available(frame)
                        ? SkySurfaceLayer.render(frame,resolveShader,skyTexture,viewport) : 0;
                if(surface>0 && preserveNativeFringe) {
                    int background=SkySurfaceLayer.nativeBackgroundColor(frame,surface,viewport);
                    if(background>0) SkySurfaceLayer.composite(surface,viewport,true,frame.distantDepthTexture,background);
                    else drawMaterialFaces(frame,resolveShader,skyTexture); // Unverified native renderer: original center ownership, not a stale fringe.
                } else if(surface>0) SkySurfaceLayer.composite(surface,viewport);
                else drawMaterialFaces(frame,resolveShader,skyTexture);
                frame.finalResolved=true; frame.finalResolveCount++;
                if (SeamScan.isActive()) SeamScan.inspect(frame,skyTexture,depthTexture,depthWidth,depthHeight);
            }
        }
    }
    private static void copyFinalDepth(Frame frame) {
        int width=frame.targetViewport[2],height=frame.targetViewport[3];
        SkyGlState.active(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        if (depthTexture==0 || depthWidth!=width || depthHeight!=height) {
            if (depthTexture!=0) GL11.glDeleteTextures(depthTexture);
            depthTexture=GL11.glGenTextures(); depthWidth=width; depthHeight=height;
            SkyGlState.bindTexture(depthTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_DEPTH_COMPONENT32F,width,height,0,
                    GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,(java.nio.ByteBuffer)null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);
        } else SkyGlState.bindTexture(depthTexture);
        com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,frame.framebuffer);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,frame.targetViewport[0],frame.targetViewport[1],width,height);
    }
    public static void release() {
        try (SkyGlState ignored=new SkyGlState(6)) {
            if (depthTexture!=0) GL11.glDeleteTextures(depthTexture);
            depthTexture=depthWidth=depthHeight=0;
            MESH.close(); SkyCoverage.release(); SkyExposureMeter.release(); ItrpSkyBridge.release(); PortalViewCapture.release();
            VanillaSkyScene.release(); SkyVanillaClouds.release(); SkyTemporalEdgeMask.release(); SkySurfaceLayer.release();
        }
        FRAMES.clear(); GAME_VIEWS.clear(); lastRoot=null;
        if(ModCompat.DISTANT_HORIZONS) DistantSkyDepth.clear();
    }
    public static final float[][] FACES=new float[6][];
    static {
        FACES[Direction.SOUTH.ordinal()]=new float[]{0,0,1, 1,0,1, 1,1,1, 0,1,1};
        FACES[Direction.NORTH.ordinal()]=new float[]{1,0,0, 0,0,0, 0,1,0, 1,1,0};
        FACES[Direction.EAST.ordinal()]=new float[]{1,0,1, 1,0,0, 1,1,0, 1,1,1};
        FACES[Direction.WEST.ordinal()]=new float[]{0,0,0, 0,0,1, 0,1,1, 0,1,0};
        FACES[Direction.UP.ordinal()]=new float[]{0,1,1, 1,1,1, 1,1,0, 0,1,0};
        FACES[Direction.DOWN.ordinal()]=new float[]{0,0,0, 1,0,0, 1,0,1, 0,0,1};
    }
}
