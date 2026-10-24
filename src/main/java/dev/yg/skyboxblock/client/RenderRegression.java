package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Explicitly enabled, integrated-server fixture. Captures real first-person hands after rendering. */
public final class RenderRegression {
    private static final boolean ENABLED = Boolean.getBoolean("skyboxblock.regression");
    private static final List<Scene> SCENES = new ArrayList<>();
    private static final boolean SETTLED=Boolean.getBoolean("skyboxblock.regression.settledTransparency");
    private static final long SETTLE_NANOS=15_000_000_000L;
    private static final int SETTLE_FRAMES=300;
    private static long poseStableSince;
    private static int poseStableFrames;
    private static double targetEyeX,targetEyeY,targetEyeZ;
    private static int index = -1, ticks, remaining, finish = -1, initialError,sceneRenderFrames;
    private static String pending;
    private static long neverSkyObservedRootFrames;
    public static void verifyNeverSkyFrame(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || !Boolean.getBoolean("skyboxblock.regression.nativeTransparencyControl")
                || frame==null || !frame.root || frame.shadow) return;
        neverSkyObservedRootFrames++;
        if (frame.blocks()!=0 || frame.vertices()!=0 || frame.itrpMaterialWrites || frame.itrpBackgroundInjected
                || frame.itrpInjectionCount!=0 || frame.finalResolved)
            throw new IllegalStateException("NeverSky control performed private rendering in real root frame "+frame.viewId);
    }
    private static String dimensionTarget;
    private static com.google.gson.JsonObject dimensionFixtureEvidence,distantTerrainEvidence;
    private static java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> dimensionProbe;
    private static int dimensionWaitChecks,dimensionClientReadyAt=-1;

    private static int fixtureBlocks(net.minecraft.world.level.BlockGetter level,int x0,int y0,int z0,int x1,int y1,int z1,net.minecraft.world.level.block.Block wanted) {
        int count=0;
        for(int x=x0;x<=x1;x++) for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++)
            if(level.getBlockState(new net.minecraft.core.BlockPos(x,y,z)).is(wanted)) count++;
        return count;
    }
    private static com.google.gson.JsonObject dimensionBlocks(net.minecraft.world.level.BlockGetter level) {
        var data=new com.google.gson.JsonObject();
        data.addProperty("sky",fixtureBlocks(level,-5,181,2,5,190,2,SkyboxBlockMod.SKY_BLOCK));
        data.addProperty("backing",fixtureBlocks(level,-6,180,4,6,191,4,net.minecraft.world.level.block.Blocks.SANDSTONE));
        data.addProperty("floor",fixtureBlocks(level,-6,179,-8,6,179,4,net.minecraft.world.level.block.Blocks.BEDROCK));
        return data;
    }
    private static boolean completeDimensionBlocks(com.google.gson.JsonObject data) {
        return data.get("sky").getAsInt()==110 && data.get("backing").getAsInt()==156 && data.get("floor").getAsInt()==169;
    }
    private static boolean dimensionReady(Minecraft mc) {
        if (dimensionProbe==null) {
            dimensionProbe=new java.util.concurrent.CompletableFuture<>(); var future=dimensionProbe;
            var key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(dimensionTarget));
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level=mc.getSingleplayerServer().getLevel(key); var data=dimensionBlocks(level);
                    data.addProperty("gameTime",level.getGameTime()); future.complete(data);
                } catch(Exception error) { future.completeExceptionally(error); }
            });
            return false;
        }
        if (!dimensionProbe.isDone()) return false;
        var server=dimensionProbe.join(); var client=dimensionBlocks(mc.level);
        var evidence=new com.google.gson.JsonObject(); evidence.addProperty("expectedDimension",dimensionTarget);
        evidence.addProperty("actualClientDimension",mc.level.dimension().location().toString());
        evidence.add("server",server); evidence.add("client",client);
        evidence.addProperty("waitChecks",dimensionWaitChecks);
        dimensionFixtureEvidence=evidence;
        if (!completeDimensionBlocks(server)) throw new IllegalStateException("Fixture server geometry incomplete: "+evidence);
        boolean ready=mc.level.dimension().location().toString().equals(dimensionTarget) && completeDimensionBlocks(client);
        if (!ready) {
            dimensionClientReadyAt=-1;
            if (++dimensionWaitChecks>200) throw new IllegalStateException("Fixture client chunk synchronization timed out: "+evidence);
            if (dimensionWaitChecks==1 || dimensionWaitChecks%20==0)
                SkyboxBlockMod.LOGGER.info("[skybox-regression] waiting for actual dimension geometry {}",evidence);
            dimensionProbe=null; return false;
        }
        if (dimensionClientReadyAt<0) dimensionClientReadyAt=ticks;
        evidence.addProperty("clientReadyTicks",ticks-dimensionClientReadyAt);
        return ticks-dimensionClientReadyAt>=20;
    }
    private static WorldCapture before, opaque, lineBefore, nativeHandBefore;
    private static NativeImage nativeHandMask;
    private static NativeImage overlayBefore, overlayMask, lineMask;
    private static String lineShader;

    public static void beforeNativeHands() {
        if (!ENABLED || pending==null || capturedFrame==null || ModCompat.irisActive() || ModCompat.portalRendering()) return;
        if (nativeHandBefore!=null) nativeHandBefore.close();
        nativeHandBefore=readWorld(capturedFrame);
    }
    public static void afterNativeHands() {
        if (nativeHandBefore==null || capturedFrame==null) return;
        var frame=capturedFrame;
        try (WorldCapture after=readWorld(frame);
             NativeImage oldColor=activeImage(nativeHandBefore,frame);
             NativeImage newColor=activeImage(after,frame)) {
            int w=frame.viewport[2],h=frame.viewport[3],fullW=frame.targetViewport[2];
            int dx=frame.viewport[0]-frame.targetViewport[0],dy=frame.viewport[1]-frame.targetViewport[1];
            if (nativeHandMask==null) { nativeHandMask=new NativeImage(w,h,false); nativeHandMask.fillRect(0,0,w,h,0xFF000000); }
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
                int pixel=(dy+h-1-y)*fullW+dx+x;
                // A vanilla hand pass may clear WORLD depth to 1; that clear alone is not a hand.
                boolean write=oldColor.getPixelRGBA(x,y)!=newColor.getPixelRGBA(x,y)
                        || after.depth()[pixel]<nativeHandBefore.depth()[pixel]-.0000001F;
                if (write) nativeHandMask.setPixelRGBA(x,y,0xFFFFFFFF);
            }
            // Native DH now resolves before hand depth is cleared; retain genuine later hand evidence.
            if(finalEvidenceCaptured && frame.finalResolved) {
                Files.createDirectories(output());
                nativeHandMask.writeToFile(output().resolve(pending+"-post-hand-mask.png"));
            }
        } catch(IOException exception) { throw new RuntimeException(exception); }
        finally { nativeHandBefore.close(); nativeHandBefore=null; }
    }

    public static void beforeLines(net.minecraft.client.renderer.RenderType type) {
        if (!ENABLED || pending == null || lineBefore!=null || !type.toString().toLowerCase(Locale.ROOT).contains("lines")) return;
        SkyboxBlockMod.LOGGER.info("[skybox-regression] real lines {} captured={} root={} type={}",
                pending,capturedFrame!=null,SkyWorldRenderer.current()==capturedFrame,type);
        if (capturedFrame == null || (SkyWorldRenderer.current()!=null && SkyWorldRenderer.current()!=capturedFrame)
                || capturedFrame.portal || capturedFrame.shadow) return;
        lineBefore=readWorld(capturedFrame);
    }
    public static void afterLines(net.minecraft.client.renderer.RenderType type) {
        if (!ENABLED || lineBefore == null || !type.toString().toLowerCase(Locale.ROOT).contains("lines")) return;
        var shader=com.mojang.blaze3d.systems.RenderSystem.getShader();
        lineShader=shader==null ? "null" : shader.getClass().getName();
        try (WorldCapture after=readWorld(capturedFrame)) {
            int w=after.color().getWidth(),h=after.color().getHeight();
            if (lineMask==null) {
                lineMask=new NativeImage(w,h,false);
                lineMask.fillRect(0,0,w,h,0xFF000000);
            }
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
                if (lineBefore.color().getPixelRGBA(x,y)!=after.color().getPixelRGBA(x,y))
                    lineMask.setPixelRGBA(x,y,0xFFFFFFFF);
            }
        } finally { lineBefore.close(); lineBefore=null; }
    }
    private static SkyWorldRenderer.Frame capturedFrame,captureCandidate;
    private static org.joml.Vector2f transparentJitter;
    private static int transparentJitterWidth,transparentJitterHeight;

    public static void prepareCapture(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || !frame.root || frame.shadow) return;
        if (index>=0) sceneRenderFrames++;
        if(SETTLED && transparencyFixture!=null && cameraLocked) {
            double dx=frame.camera.x-targetEyeX,dy=frame.camera.y-targetEyeY,dz=frame.camera.z-targetEyeZ;
            if(dx*dx+dy*dy+dz*dz<.000001) {
                if(poseStableSince==0) poseStableSince=System.nanoTime();
                poseStableFrames++;
            } else { poseStableSince=0; poseStableFrames=0; }
        }
        captureCandidate=null;
        if (pending==null) return;
        if (SETTLED && transparencyFixture!=null && !settlementReady()) return;
        boolean matched=true;
        if (transparencyFixture!=null && Boolean.getBoolean("skyboxblock.regression.freezeShaderTime")) {
            if (transparentJitter==null || transparentJitterWidth!=frame.targetViewport[2]
                    || transparentJitterHeight!=frame.targetViewport[3]) {
                String bits=System.getProperty("skyboxblock.regression.captureJitterBits","");
                if (bits.isBlank()) transparentJitter=new org.joml.Vector2f(frame.rasterJitter);
                else {
                    String[] words=bits.split(":");
                    if (words.length!=2) throw new IllegalArgumentException("captureJitterBits requires two hex words");
                    float x=Float.intBitsToFloat((int)Long.parseUnsignedLong(words[0],16));
                    float y=Float.intBitsToFloat((int)Long.parseUnsignedLong(words[1],16));
                    if (!Float.isFinite(x)||!Float.isFinite(y)) throw new IllegalArgumentException("captureJitterBits must be finite");
                    transparentJitter=new org.joml.Vector2f(x,y);
                }
                transparentJitterWidth=frame.targetViewport[2]; transparentJitterHeight=frame.targetViewport[3];
            }
            matched=Float.floatToRawIntBits(transparentJitter.x)==Float.floatToRawIntBits(frame.rasterJitter.x)
                    && Float.floatToRawIntBits(transparentJitter.y)==Float.floatToRawIntBits(frame.rasterJitter.y);
        }
        if (matched) captureCandidate=frame;
    }
    private static boolean settlementReady() {
        return poseStableSince!=0 && poseStableFrames>=SETTLE_FRAMES && System.nanoTime()-poseStableSince>=SETTLE_NANOS;
    }
    private static com.google.gson.JsonObject settlementEvidence() {
        if(!SETTLED || transparencyFixture==null) return null;
        var data=new com.google.gson.JsonObject();
        data.addProperty("protocol","fixed-real-time-and-root-render-frames");
        data.addProperty("requiredMilliseconds",SETTLE_NANOS/1_000_000L); data.addProperty("requiredFrames",SETTLE_FRAMES);
        data.addProperty("observedMilliseconds",poseStableSince==0 ? 0 : (System.nanoTime()-poseStableSince)/1_000_000L);
        data.addProperty("observedFrames",poseStableFrames); data.addProperty("stablePoseRequired",true);
        return data;
    }
    private static boolean cameraLocked;
    private static java.util.concurrent.CompletableFuture<Void> reloadFuture,worldSetupFuture;
    private static java.util.concurrent.CompletableFuture<com.google.gson.JsonObject> skyCountFuture;
    private static float lockedYaw, lockedPitch;
    private static Boolean mainBoundBefore, mainBoundAfter, overridesBefore, overridesAfter;
    private static boolean finalEvidenceCaptured;

    public static void beforePrivatePass() {
        if (!ENABLED || pending == null || ModCompat.portalRendering()) return;
        mainBoundBefore=ModCompat.irisMainBound(); overridesBefore=ModCompat.irisOverridesShaders();
    }
    public static void afterPrivatePass() {
        if (!ENABLED || pending == null || ModCompat.portalRendering()) return;
        mainBoundAfter=ModCompat.irisMainBound(); overridesAfter=ModCompat.irisOverridesShaders();
    }
    private RenderRegression() {}

    public static void register() {
        if (!ENABLED) return;
        String suite = System.getProperty("skyboxblock.regression.suite", "all");
        if (suite.equals("all") || suite.equals("bugs")) bugScenes();
        if (suite.equals("all") || suite.equals("sky") || suite.equals("clouds")) skyScenes();
        if (suite.equals("all") || suite.equals("alignment")) alignmentScenes();
        if (suite.equals("all") || suite.equals("seams")) seamScenes();
        if (suite.equals("all") || suite.equals("dimensions")) dimensionScenes();
        if (suite.equals("reference")) vanillaReferenceScenes();
        if (suite.equals("blue")) blueScenes();
        if (suite.equals("ambient")) ambientOcclusionScenes();
        if (suite.equals("handexposure")) handExposureScenes();
        if (suite.equals("materials")) materialScenes();
        if (suite.equals("panomotion")) panoramaMotionScenes();
        if (suite.equals("edges")) edgeScenes();
        if (suite.equals("joints")) jointScenes();
        if (SETTLED && suite.equals("transparency")) {
            SCENES.add(new Scene("warmup-empty-front",300,mc -> {
                transparencySetup(mc,false,"none","none",true,"baseline"); transparencyFixture=null;
            }));
            SCENES.add(new Scene("warmup-empty-oblique",300,mc -> {
                transparencySetup(mc,false,"none","none",true,"baseline"); transparencyFixture=null;
                look(mc,1.15,140,-3,.5,142.2,6);
            }));
        }
        if (suite.equals("all") || suite.equals("transparency")) transparencyScenes();
        String selection=System.getProperty("skyboxblock.regression.scenes","");
        if (!selection.isBlank()) {
            var wanted=new java.util.LinkedHashSet<>(java.util.Arrays.asList(selection.split(",")));
            var actual=new java.util.HashSet<String>(); for(var scene:SCENES) actual.add(scene.name());
            if (!actual.containsAll(wanted) || wanted.isEmpty()) throw new IllegalArgumentException("Unknown fixture scene selection: "+selection);
            SCENES.removeIf(scene -> !wanted.contains(scene.name()));
        }
        ClientTickEvents.START_CLIENT_TICK.register(FixtureClientControl::beforeTick);
        ClientTickEvents.START_CLIENT_TICK.register(RenderRegression::panoramaMotionInput);
        ClientTickEvents.END_CLIENT_TICK.register(RenderRegression::tick);
        SkyboxBlockMod.LOGGER.info("[skybox-regression] enabled: {} scenes", SCENES.size());
    }

    private static void setup(Minecraft mc) {
        mc.options.hideGui = false;
        mc.options.bobView().set(false);
        mc.options.particles().set(net.minecraft.client.ParticleStatus.MINIMAL);
        mc.options.fov().set(70);
        mc.options.renderDistance().set(12);
        mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
        command(mc, "gamemode creative");
        command(mc, "gamerule doDaylightCycle false");
        command(mc, "gamerule doWeatherCycle false");
        command(mc, "gamerule doMobSpawning false");
        command(mc, "weather clear");
        command(mc, "time set day");
        command(mc, "fill -8 140 -8 8 146 8 minecraft:air");
        command(mc, "fill -8 139 -14 8 139 8 minecraft:bedrock");
        held(mc, "minecraft:air");
        offhand(mc, "minecraft:air");
    }

    private static boolean panoramaMotion;
    private static int panoramaMotionTicks;
    private static boolean edgeMotion;
    private static int edgeMotionDirection=1;
    private static void panoramaMotionInput(Minecraft mc) {
        if(!panoramaMotion || !FixtureClientControl.owns(mc) || mc.player==null) return;
        panoramaMotionTicks++;
        if(edgeMotion) {
            if(mc.player.getX()>2) edgeMotionDirection=-1;
            if(mc.player.getX()<-2) edgeMotionDirection=1;
            mc.options.keyLeft.setDown(edgeMotionDirection>0);
            mc.options.keyRight.setDown(edgeMotionDirection<0);
        } else mc.options.keyLeft.setDown(true);
        lockedYaw=(float)(Math.sin(panoramaMotionTicks*.17)*5.0);
        lockedPitch=(float)(Math.sin(panoramaMotionTicks*.11)*2.0);
    }
    /** Actual walking on solid ground with Minecraft bobView enabled; front/rear surfaces share a material. */
    private static void panoramaMotionScenes() {
        for(var style:dev.yg.skyboxblock.SkyStyle.values()) {
            String prefix="motion-"+style.name().toLowerCase(java.util.Locale.ROOT);
            SCENES.add(new Scene(prefix+"-setup",60,mc -> {
                panoramaMotion=false; panoramaMotionTicks=0;
                setup(mc); mc.options.bobView().set(true); mc.options.hideGui=true;
                command(mc,"gamemode survival");
                command(mc,"fill -28 140 -10 28 158 12 minecraft:air");
                command(mc,"fill -28 139 -10 28 139 12 minecraft:sandstone");
                command(mc,"fill -24 140 2 24 155 2 skyboxblock:"+style.blockId());
                command(mc,"fill -7 140 6 7 151 6 skyboxblock:"+style.blockId());
                command(mc,"fill -3 143 9 10 149 9 skyboxblock:"+style.blockId());
                command(mc,"fill -10 141 5 -10 153 10 skyboxblock:"+style.blockId());
                look(mc,-6,140,-.5,-6,141.62,8);
            }));
            for(int sample=0;sample<8;sample++) {
                int n=sample;
                SCENES.add(new Scene(prefix+"-"+String.format(java.util.Locale.ROOT,"%02d",sample),sample==0 ? 8 : 4,mc -> {
                    if(n==0) panoramaMotion=true;
                }));
            }
        }
        SCENES.add(new Scene("motion-clean",60,mc -> {
            panoramaMotion=false; setup(mc);
            command(mc,"fill -28 140 -10 28 158 12 minecraft:air");
        }));
    }

    /** R34: static jitter phases, mixed front/rear ownership, silhouettes and genuine walking. */
    private static void edgeScenes() {
        for(String layout:new String[]{"mixed","silhouette","foreground"}) {
            SCENES.add(new Scene("edge-"+layout+"-setup",140,mc -> {
                panoramaMotion=false; panoramaMotionTicks=0; edgeMotion=true; edgeMotionDirection=1;
                setup(mc); mc.options.hideGui=true; mc.options.bobView().set(true);
                command(mc,"gamemode survival");
                command(mc,"fill -28 140 -10 28 158 12 minecraft:air");
                command(mc,"fill -28 139 -10 28 139 12 minecraft:sandstone");
                if(layout.equals("silhouette")) {
                    command(mc,"fill -4 140 5 4 149 5 skyboxblock:white_sky_block");
                } else {
                    command(mc,"fill -12 140 8 12 155 8 skyboxblock:apocalypse_sky_block");
                    command(mc,"fill -7 140 5 -1 152 5 skyboxblock:white_sky_block");
                    command(mc,"fill 0 140 5 6 152 5 skyboxblock:black_sky_block");
                    command(mc,"fill -3 144 4 2 148 4 skyboxblock:galaxy_sky_block");
                }
                if(layout.equals("foreground")) {
                    command(mc,"fill -2 140 2 -2 149 2 minecraft:sandstone");
                    command(mc,"fill 2 140 2 2 149 2 minecraft:glass");
                }
                look(mc,-.625,140,-3.375,.75,144.7,7);
            }));
            for(int sample=0;sample<32;sample++)
                SCENES.add(new Scene("edge-"+layout+"-static-"+String.format(java.util.Locale.ROOT,"%02d",sample),1,mc -> {}));
            for(int sample=0;sample<24;sample++) {
                int n=sample;
                SCENES.add(new Scene("edge-"+layout+"-walk-"+String.format(java.util.Locale.ROOT,"%02d",sample),1,mc -> {
                    if(n==0) panoramaMotion=true;
                }));
            }
            SCENES.add(new Scene("edge-"+layout+"-reload",90,mc -> {
                panoramaMotion=false; reloadFuture=mc.reloadResourcePacks();
            }));
            SCENES.add(new Scene("edge-"+layout+"-resize",65,mc -> window(mc,1024,768)));
            SCENES.add(new Scene("edge-"+layout+"-removed",70,mc -> {
                command(mc,"fill -28 140 -10 28 158 12 minecraft:air"); window(mc,1280,720);
            }));
        }
    }

    /** R35 user-shaped contacts: coplanar rings, recessed rings and an ordinary floor/wall. */
    private static void jointScenes() {
        for(String layout:new String[]{"coplanar","recessed"}) {
            SCENES.add(new Scene("joint-"+layout+"-setup",160,mc -> {
                panoramaMotion=false; panoramaMotionTicks=0; edgeMotion=true; edgeMotionDirection=1;
                setup(mc); mc.options.hideGui=true; mc.options.bobView().set(true);
                command(mc,"gamemode survival");
                command(mc,"fill -16 140 -12 16 166 20 minecraft:air");
                command(mc,"fill -16 139 -12 16 139 20 minecraft:sandstone");
                command(mc,"fill -14 140 -6 -14 163 16 minecraft:stone");
                command(mc,"fill 14 140 -6 14 163 16 minecraft:stone");
                command(mc,"fill -13 140 16 13 165 16 skyboxblock:sky_block");
                String[] styles={"white","black","kloppenheim","syferfontein","dusk","galaxy","lush","apocalypse"};
                for(int i=0;i<styles.length;i++) {
                    int r=11-i,b=142+i,t=164-i,z=layout.equals("coplanar") ? 16 : 15-i;
                    String block="skyboxblock:"+styles[i]+"_sky_block";
                    command(mc,"fill "+(-r)+" "+b+" "+z+" "+r+" "+b+" "+z+" "+block);
                    command(mc,"fill "+(-r)+" "+t+" "+z+" "+r+" "+t+" "+z+" "+block);
                    command(mc,"fill "+(-r)+" "+b+" "+z+" "+(-r)+" "+t+" "+z+" "+block);
                    command(mc,"fill "+r+" "+b+" "+z+" "+r+" "+t+" "+z+" "+block);
                }
                look(mc,.213,140,-3.375,.75,147.8,16);
            }));
            for(int sample=0;sample<32;sample++)
                SCENES.add(new Scene("joint-"+layout+"-static-"+String.format(Locale.ROOT,"%02d",sample),1,mc -> {}));
            SCENES.add(new Scene("joint-"+layout+"-grazing",60,mc -> look(mc,-.375,140,-3.375,2.1,141.95,16)));
            for(int sample=0;sample<32;sample++)
                SCENES.add(new Scene("joint-"+layout+"-grazing-"+String.format(Locale.ROOT,"%02d",sample),1,mc -> {}));
            for(int sample=0;sample<24;sample++) {
                int n=sample;
                SCENES.add(new Scene("joint-"+layout+"-walk-"+String.format(Locale.ROOT,"%02d",sample),1,mc -> {
                    if(n==0) panoramaMotion=true;
                }));
            }
            SCENES.add(new Scene("joint-"+layout+"-removed",70,mc -> {
                panoramaMotion=false; command(mc,"fill -13 140 8 13 165 16 minecraft:air");
            }));
        }
    }

    /** R31 opt-in owned fixtures: continuous direction projection, all styles, reload and real foreground. */
    private static void materialScenes() {
        for(var style:dev.yg.skyboxblock.SkyStyle.values()) {
            String prefix="material-"+style.name().toLowerCase(java.util.Locale.ROOT);
            SCENES.add(new Scene(prefix+"-front",70,mc -> {
                setup(mc); mc.options.hideGui=true;
                command(mc,"fill -12 139 -12 12 159 12 minecraft:air");
                command(mc,"fill -10 140 -10 10 156 10 skyboxblock:"+style.blockId()+" hollow");
                look(mc,.5,146,.5,.5,147.62,10);
            }));
            SCENES.add(new Scene(prefix+"-right",35,mc -> look(mc,.5,146,.5,10,147.62,.5)));
            SCENES.add(new Scene(prefix+"-back",35,mc -> look(mc,.5,146,.5,.5,147.62,-10)));
            SCENES.add(new Scene(prefix+"-left",35,mc -> look(mc,.5,146,.5,-10,147.62,.5)));
            SCENES.add(new Scene(prefix+"-up",35,mc -> look(mc,.5,146,.5,.5,158,.5)));
            SCENES.add(new Scene(prefix+"-down",35,mc -> look(mc,.5,146,.5,.5,139,.5)));
            SCENES.add(new Scene(prefix+"-joint",35,mc -> look(mc,.125,146,-.375,10,153,10)));
            SCENES.add(new Scene(prefix+"-wrap",35,mc -> look(mc,.5,146,.5,-10,147.62,.50001)));
            SCENES.add(new Scene(prefix+"-foreground",70,mc -> {
                look(mc,.5,146,.5,.5,147.62,10);
                command(mc,"fill -1 145 4 0 151 4 minecraft:sandstone");
                command(mc,"fill 2 145 4 3 151 4 minecraft:glass");
                held(mc,"minecraft:sandstone"); mc.options.hideGui=false;
            }));
        }
        SCENES.add(new Scene("material-mixed",100,mc -> {
            setup(mc); mc.options.hideGui=true;
            command(mc,"fill -12 139 -12 12 159 12 minecraft:air");
            int i=0;
            for(var style:dev.yg.skyboxblock.SkyStyle.values()) {
                int x=-6+(i%3)*4,y=142+(i/3)*4;
                command(mc,"fill "+x+" "+y+" 8 "+(x+3)+" "+(y+3)+" 8 skyboxblock:"+style.blockId()); i++;
            }
            command(mc,"fill -8 140 10 7 155 10 skyboxblock:apocalypse_sky_block");
            look(mc,0,146,-6,0,148,8);
        }));
        SCENES.add(new Scene("material-mixed-reload",80,mc -> reloadFuture=mc.reloadResourcePacks()));
        SCENES.add(new Scene("material-mixed-resized",65,mc -> window(mc,1024,768)));
        SCENES.add(new Scene("material-mixed-removed",80,mc -> command(mc,"fill -8 140 8 7 155 10 minecraft:air")));
    }

    private static void bugScenes() {
        SCENES.add(new Scene("bug-panel-empty", 60, mc -> {
            setup(mc);
            command(mc, "fill -8 140 7 8 146 7 minecraft:sandstone");
            command(mc, "fill -3 140 3 3 144 3 skyboxblock:sky_block");
            look(mc, .5,140,1.5, .5,141.62,5);
        }));
        SCENES.add(new Scene("bug-panel-sky-hand",35,mc -> held(mc,"skyboxblock:sky_block")));
        SCENES.add(new Scene("bug-panel-solid-hand",35,mc -> held(mc,"minecraft:sandstone")));
        SCENES.add(new Scene("bug-panel-torch-hand",35,mc -> held(mc,"minecraft:torch")));
        SCENES.add(new Scene("bug-panel-return-empty",35,mc -> held(mc,"minecraft:air")));
        SCENES.add(new Scene("bug-panel-offhand",35,mc -> offhand(mc,"skyboxblock:sky_block")));
        SCENES.add(new Scene("bug-panel-dual-hand",35,mc -> {
            held(mc,"minecraft:sandstone"); offhand(mc,"minecraft:torch");
        }));
        SCENES.add(new Scene("bug-pillar-near-empty",45,mc -> {
            command(mc,"fill -8 140 -8 8 146 8 minecraft:air");
            command(mc,"fill -8 140 7 8 146 7 minecraft:sandstone");
            command(mc,"setblock 0 141 3 skyboxblock:sky_block");
            command(mc,"fill 0 140 0 0 145 0 minecraft:sandstone");
            held(mc,"minecraft:air"); offhand(mc,"minecraft:air");
            look(mc,.5,140,-1.5,.5,141.62,5);
        }));
        SCENES.add(new Scene("bug-pillar-far-empty",35,mc -> look(mc,.5,140,-2.5,.5,141.62,5)));
        SCENES.add(new Scene("bug-pillar-far-sky-hand",35,mc -> held(mc,"skyboxblock:sky_block")));
        SCENES.add(new Scene("bug-pillar-far-solid-hand",35,mc -> held(mc,"minecraft:sandstone")));
        SCENES.add(new Scene("bug-pillar-farther-empty",35,mc -> {
            held(mc,"minecraft:air"); look(mc,.5,140,-5.5,.5,141.62,5);
        }));
        SCENES.add(new Scene("bug-pillar-side-left",35,mc -> look(mc,1.5,140,-2.5,1.5,141.62,5)));
        SCENES.add(new Scene("bug-pillar-side-right",35,mc -> look(mc,-.5,140,-2.5,-.5,141.62,5)));
        SCENES.add(new Scene("bug-pillar-turned",35,mc -> look(mc,1.8,140,-2.8,.5,141.62,3.5)));
        SCENES.add(new Scene("bug-pillar-resized-hidden",45,mc -> {
            window(mc,1024,768); held(mc,"skyboxblock:sky_block");
            look(mc,.5,140,-2.5,.5,141.62,5);
        }));
        SCENES.add(new Scene("bug-pillar-resized-side",35,mc -> look(mc,1.5,140,-2.5,1.5,141.62,5)));
        SCENES.add(new Scene("bug-pillar-restored-side",45,mc -> {
            window(mc,1280,800); held(mc,"minecraft:air");
            look(mc,-.5,140,-2.5,-.5,141.62,5);
        }));
        SCENES.add(new Scene("bug-clean",35,mc -> {
            held(mc,"minecraft:air"); command(mc,"fill -8 140 -8 8 146 8 minecraft:air");
        }));
    }

    private static void alignmentScenes() {
        SCENES.add(new Scene("align-solid-no-sky",60,mc -> {
            setup(mc);
            command(mc,"kill @e[type=minecraft:item]");
            command(mc,"fill -6 180 -6 6 194 6 minecraft:air");
            command(mc,"fill -6 140 5 6 146 5 minecraft:sandstone");
            command(mc,"setblock 0 141 2 minecraft:sandstone");
            alignmentItems(mc);
            look(mc,.5,140,-1.5,.5,141.62,2.5);
        }));
        SCENES.add(new Scene("align-solid-sky",40,mc ->
                command(mc,"fill -3 140 4 3 144 4 skyboxblock:sky_block")));
        SCENES.add(new Scene("align-sky-selected",40,mc ->
                command(mc,"setblock 0 141 2 skyboxblock:sky_block")));
        SCENES.add(new Scene("align-other-solid",40,mc ->
                command(mc,"setblock 0 141 2 minecraft:white_concrete")));
        SCENES.add(new Scene("align-oblique",40,mc ->
                look(mc,1.15,140,-1.5,.5,141.62,2.5)));
        SCENES.add(new Scene("align-resized",45,mc -> window(mc,1024,768)));
        SCENES.add(new Scene("align-reload",45,mc -> reloadFuture=mc.reloadResourcePacks()));
        SCENES.add(new Scene("align-sky-removed",50,mc -> {
            command(mc,"fill -3 140 4 3 144 4 minecraft:air");
            command(mc,"setblock 0 141 2 minecraft:sandstone");
            window(mc,1280,800);
        }));
        SCENES.add(new Scene("align-clean",35,mc -> {
            command(mc,"kill @e[type=minecraft:item]");
            command(mc,"fill -8 140 -8 8 146 8 minecraft:air");
        }));
    }

    private static void seamScenes() {
        SCENES.add(new Scene("seam-front",70,mc -> {
            setup(mc);
            command(mc,"fill -12 140 -8 12 153 10 minecraft:air");
            command(mc,"fill -12 140 8 12 152 8 minecraft:red_concrete");
            command(mc,"fill -10 140 6 10 150 6 skyboxblock:sky_block");
            look(mc,.123457,140.234568,-2.789123,.5,143,6.5);
        }));
        SCENES.add(new Scene("seam-oblique-left",40,mc ->
                look(mc,-6.234567,140.234568,-1.876543,.5,143,6.5)));
        SCENES.add(new Scene("seam-oblique-right",40,mc ->
                look(mc,7.123457,141.234568,1.123457,.5,144,6.5)));
        SCENES.add(new Scene("seam-grazing",40,mc ->
                look(mc,10.867531,141.345679,3.125731,.5,144,6.5)));
        SCENES.add(new Scene("seam-tilted",40,mc ->
                look(mc,-.123457,147.123457,-8.345679,.5,144,6.5)));
        SCENES.add(new Scene("seam-resized",45,mc -> window(mc,1024,768)));
        SCENES.add(new Scene("seam-reload",45,mc -> reloadFuture=mc.reloadResourcePacks()));
        SCENES.add(new Scene("seam-clean",40,mc -> {
            command(mc,"fill -12 140 -8 12 153 10 minecraft:air");
            window(mc,1280,800);
        }));
    }

    private static void dimensionScenes() {
        SCENES.add(new Scene("dim-overworld-day",80,mc -> {
            dimensionSetup(mc,"minecraft:overworld",() -> { gameTime(mc,6000); command(mc,"time set 6000"); });
        }));
        SCENES.add(new Scene("dim-overworld-time-set",50,mc -> command(mc,"time set 18000")));
        SCENES.add(new Scene("dim-overworld-moon-next",50,mc -> command(mc,"time add 24000")));
        SCENES.add(new Scene("dim-overworld-frozen",80,mc -> {}));
        SCENES.add(new Scene("dim-overworld-jump-day",50,mc -> command(mc,"time set 6000")));
        SCENES.add(new Scene("dim-nether-day",120,mc -> {
            dimensionSetup(mc,"minecraft:the_nether",() -> { gameTime(mc,6000); command(mc,"time set 18000"); });
        }));
        SCENES.add(new Scene("dim-nether-time-set",60,mc -> command(mc,"time set 1000")));
        SCENES.add(new Scene("dim-nether-night",60,mc -> gameTime(mc,18000)));
        SCENES.add(new Scene("dim-nether-moon-next",60,mc -> gameTime(mc,42000)));
        SCENES.add(new Scene("dim-end-day",120,mc -> {
            dimensionSetup(mc,"minecraft:the_end",() -> gameTime(mc,6000));
        }));
        SCENES.add(new Scene("dim-end-night",60,mc -> gameTime(mc,18000)));
        SCENES.add(new Scene("dim-end-reload",60,mc -> reloadFuture=mc.reloadResourcePacks()));
        SCENES.add(new Scene("dim-fixed-day",120,mc -> dimensionSetup(mc,"skybox_fixture:normal_fixed")));
        SCENES.add(new Scene("dim-fixed-command",60,mc -> command(mc,"time set 18000")));
        SCENES.add(new Scene("dim-dark-night",120,mc -> dimensionSetup(mc,"skybox_fixture:normal_dark")));
        SCENES.add(new Scene("dim-dark-command",60,mc -> command(mc,"time set 6000")));
        SCENES.add(new Scene("dim-return",120,mc -> {
            dimensionSetup(mc,"minecraft:overworld",() -> { gameTime(mc,6000); command(mc,"time set 6000"); });
        }));
    }

    private static void dimensionSetup(Minecraft mc,String dimension) { dimensionSetup(mc,dimension,() -> {}); }
    private static void dimensionSetup(Minecraft mc,String dimension,Runnable ready) {
        dimensionTarget=dimension;
        var completion=new java.util.concurrent.CompletableFuture<Void>();
        worldSetupFuture=completion;
        var server=mc.getSingleplayerServer();
        server.execute(() -> {
            try {
                var key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.parse(dimension));
                var target=server.getLevel(key);
                if (target==null) throw new IllegalStateException("Missing fixture dimension "+dimension);
                // /fill is rejected in unloaded chunks; load the exact four fixture chunks before queuing it.
                for (int x=-1;x<=0;x++) for (int z=-1;z<=0;z++) {
                    target.setChunkForced(x,z,true); // Keep the owned fixture loaded across the client/network round trip.
                    target.getChunk(x,z);
                }
                SkyboxBlockMod.LOGGER.info("[skybox-regression] prepared {} buildY={}..{} chunks -1/0 forced; minLoaded={} maxLoaded={}",
                        dimension,target.getMinBuildHeight(),target.getMaxBuildHeight(),
                        target.hasChunkAt(new net.minecraft.core.BlockPos(-6,179,-8)),
                        target.hasChunkAt(new net.minecraft.core.BlockPos(6,194,4)));
                mc.execute(() -> {
                    String at="execute in "+dimension+" run ";
                    command(mc,at+"tp @s .5 180 -4.5"); setup(mc);
                    command(mc,at+"fill -6 179 -8 6 179 4 minecraft:bedrock");
                    command(mc,at+"fill -6 180 -8 6 194 4 minecraft:air");
                    command(mc,at+"fill -6 180 4 6 191 4 minecraft:sandstone");
                    command(mc,at+"fill -5 181 2 5 190 2 skyboxblock:sky_block");
                    look(mc,.5,180,-4.5,.5,185.2,2.5);
                    ready.run();
                    completion.complete(null);
                });
            } catch (Exception exception) { completion.completeExceptionally(exception); }
        });
    }

    /** Advance to the next requested phase; a fixture must not rewind engine scheduling clocks. */
    static long forwardFixtureTime(long current,long requested) {
        long candidate=Math.addExact(Math.multiplyExact(Math.floorDiv(current,24000L),24000L),Math.floorMod(requested,24000L));
        return candidate<current ? Math.addExact(candidate,24000L) : candidate;
    }
    private static void gameTime(Minecraft mc,long value) {
        var server=mc.getSingleplayerServer();
        if (server!=null) server.execute(() -> {
            var data=server.getWorldData().overworldData(); long before=data.getGameTime();
            boolean negative=ENABLED && Boolean.getBoolean("skyboxblock.regression.negativeFixtureClock");
            long after=negative ? value : forwardFixtureTime(before,value);
            data.setGameTime(after);
            SkyboxBlockMod.LOGGER.info("[skybox-regression] fixture gameTime {} -> {} requested={} negativeRollback={}",before,after,value,negative);
        });
    }

    private static com.google.gson.JsonObject transparencyFixture;
    private static void transparencyScenes() {
        for (boolean sky:new boolean[]{false,true}) for (String front:new String[]{"none","glass","water"})
            for (String protection:new String[]{"none","pillar","hand","both"}) for (boolean behind:new boolean[]{false,true}) {
                String name="trans-"+(sky?"sky":"open")+"-"+front+"-"+protection+"-"+(behind?"marker":"empty");
                SCENES.add(new Scene(name,70,mc -> transparencySetup(mc,sky,front,protection,behind,"baseline")));
            }
        for (String front:new String[]{"none","glass","water"})
            SCENES.add(new Scene("trans-removed-"+front,90,mc -> transparencySetup(mc,false,front,"none",true,"removed")));
    }
    private static void transparencySetup(Minecraft mc,boolean sky,String front,String protection,boolean behind,String role) {
        boolean requestedSky=sky;
        boolean nativeControl=ENABLED && Boolean.getBoolean("skyboxblock.regression.nativeTransparencyControl");
        sky=sky && !nativeControl;
        setup(mc);
        boolean legacyGlobalFreeze=Boolean.getBoolean("skyboxblock.regression.negativeGlobalFluidFreeze");
        if (legacyGlobalFreeze) command(mc,"tick freeze");
        // Normal fixture freezes only scheduled water flow, not server/chunk/network/IP ticks.
        command(mc,"fill -8 140 -4 8 149 10 minecraft:air");
        command(mc,"fill -6 180 -8 6 194 4 minecraft:air");
        command(mc,"fill -8 139 -8 8 139 12 minecraft:bedrock");
        String markerPattern=System.getProperty("skyboxblock.regression.markerPattern","striped");
        if (!markerPattern.equals("striped") && !markerPattern.equals("solid"))
            throw new IllegalArgumentException("Unknown fixed marker pattern: "+markerPattern);
        if (behind) for (int x=-3;x<=3;x+=2) {
            command(mc,"fill "+x+" 140 8 "+x+" 147 8 minecraft:black_concrete");
            // Fixed before any SkyOn inspection: raise physical reference contrast, not oracle tolerance.
            if (markerPattern.equals("striped")) for (int y=141;y<=147;y+=2)
                command(mc,"setblock "+x+" "+y+" 8 minecraft:white_concrete");
        }
        if (sky) command(mc,"fill -5 140 5 5 147 5 skyboxblock:sky_block");
        if (front.equals("glass")) command(mc,"fill -2 140 2 2 147 2 minecraft:purple_stained_glass");
        if (front.equals("water")) command(mc,"fill -2 140 2 2 147 2 minecraft:water[level=0]");
        boolean pillar=protection.equals("pillar")||protection.equals("both");
        boolean hand=protection.equals("hand")||protection.equals("both");
        if (pillar) command(mc,"fill 0 140 0 0 146 0 minecraft:stone");
        held(mc,hand?"minecraft:sandstone":"minecraft:air");
        offhand(mc,hand?"minecraft:torch":"minecraft:air");
        look(mc,.5,140,-3,.5,142.2,6);
        var data=new com.google.gson.JsonObject(); var gson=new com.google.gson.Gson();
        data.addProperty("front",front); data.addProperty("protection",protection); data.addProperty("behind",behind);
        data.addProperty("skyPresent",sky); data.addProperty("role",role);
        data.addProperty("requestedSky",requestedSky); data.addProperty("nativeRepeatControl",nativeControl);
        data.addProperty("fluidFreezeMode",legacyGlobalFreeze ? "legacy-global-server" : "owned-scheduled-water-only");
        data.add("skyBounds",gson.toJsonTree(new double[][]{{-5,140,5},{6,148,6}}));
        data.add("behindBounds",gson.toJsonTree(new double[][]{{-3,140,8},{4,148,9}}));
        var markerBoxes=new java.util.ArrayList<double[][]>();
        for (int x=-3;x<=3;x+=2) markerBoxes.add(new double[][]{{x,140,8},{x+1,148,9}});
        data.add("markerBoxes",gson.toJsonTree(markerBoxes));
        data.addProperty("markerMaterial",markerPattern.equals("striped") ? "alternating-black-white-concrete-y" : "minecraft:black_concrete");
        data.addProperty("markerPattern",markerPattern);
        data.addProperty("fixtureGeometryVersion",3);
        data.addProperty("geometryReadPolicy","getChunkNow and raw block entity map; one server/client sample, no count-readiness gate");
        data.add("frontBounds",front.equals("none") ? com.google.gson.JsonNull.INSTANCE
                : gson.toJsonTree(new double[][]{{-2,140,2},{3,148,3}}));
        data.add("pillarBounds",pillar ? gson.toJsonTree(new double[][]{{0,140,0},{1,147,1}}) : com.google.gson.JsonNull.INSTANCE);
        transparencyFixture=data;
    }

    private static net.minecraft.world.level.block.state.BlockState actualFixtureState(net.minecraft.world.level.Level level,int x,int y,int z) {
        var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
        return chunk==null ? null : chunk.getBlockState(new net.minecraft.core.BlockPos(x,y,z));
    }
    private static int actualFixtureCount(net.minecraft.world.level.Level level,int x0,int y0,int z0,int x1,int y1,int z1,net.minecraft.world.level.block.Block wanted) {
        int count=0;
        for(int x=x0;x<=x1;x++) for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) {
            var state=actualFixtureState(level,x,y,z); if(state!=null && state.is(wanted)) count++;
        }
        return count;
    }
    private static int actualFixtureEntities(net.minecraft.world.level.Level level,int x0,int y0,int z0,int x1,int y1,int z1) {
        int count=0;
        for(int x=x0;x<=x1;x++) for(int y=y0;y<=y1;y++) for(int z=z0;z<=z1;z++) {
            var chunk=level.getChunkSource().getChunkNow(x>>4,z>>4);
            if(chunk!=null && chunk.getBlockEntities().get(new net.minecraft.core.BlockPos(x,y,z)) instanceof dev.yg.skyboxblock.SkyBlockEntity) count++;
        }
        return count;
    }
    /** Read once, without creating chunks/entities or waiting for a desired count. */
    private static com.google.gson.JsonObject transparencyBlocks(net.minecraft.world.level.Level level) {
        var data=new com.google.gson.JsonObject(); int chunks=0,black=0,white=0;
        for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) if(level.getChunkSource().getChunkNow(x,z)!=null) chunks++;
        for(int x=-3;x<=3;x+=2) {
            black+=actualFixtureCount(level,x,140,8,x,147,8,net.minecraft.world.level.block.Blocks.BLACK_CONCRETE);
            white+=actualFixtureCount(level,x,140,8,x,147,8,net.minecraft.world.level.block.Blocks.WHITE_CONCRETE);
        }
        int waterOutside=0;
        for(int x=-3;x<=3;x++) for(int y=139;y<=148;y++) for(int z=1;z<=3;z++) {
            if(x>=-2 && x<=2 && y>=140 && y<=147 && z==2) continue;
            var state=actualFixtureState(level,x,y,z);
            if(state!=null && state.is(net.minecraft.world.level.block.Blocks.WATER)) waterOutside++;
        }
        data.addProperty("waterOutside",waterOutside);
        data.addProperty("sampledGameTime",level.getGameTime());
        data.addProperty("existingChunks",chunks); data.addProperty("markerBlack",black); data.addProperty("markerWhite",white);
        data.addProperty("sky",actualFixtureCount(level,-5,140,5,5,147,5,SkyboxBlockMod.SKY_BLOCK));
        data.addProperty("skyEntities",actualFixtureEntities(level,-5,140,5,5,147,5));
        data.addProperty("highSky",actualFixtureCount(level,-5,181,2,5,190,2,SkyboxBlockMod.SKY_BLOCK));
        data.addProperty("highSkyEntities",actualFixtureEntities(level,-5,181,2,5,190,2));
        data.addProperty("glass",actualFixtureCount(level,-2,140,2,2,147,2,net.minecraft.world.level.block.Blocks.PURPLE_STAINED_GLASS));
        data.addProperty("water",actualFixtureCount(level,-2,140,2,2,147,2,net.minecraft.world.level.block.Blocks.WATER));
        data.addProperty("pillar",actualFixtureCount(level,0,140,0,0,146,0,net.minecraft.world.level.block.Blocks.STONE));
        data.addProperty("floor",actualFixtureCount(level,-8,139,-8,8,139,12,net.minecraft.world.level.block.Blocks.BEDROCK));
        return data;
    }

    private static void alignmentItems(Minecraft mc) {
        for (double x : new double[]{-.65,1.65}) {
            command(mc,String.format(Locale.ROOT,
                    "summon minecraft:item %.3f 141.0 1.2 {NoGravity:1b,Invulnerable:1b,PickupDelay:32767s,Age:-32768s,Tags:[\"skybox_alignment\"],Item:{id:\"minecraft:emerald\",count:1}}",x));
        }
    }

    private static long handTransitionNanos;
    private static void handExposureScenes() {
        SCENES.add(new Scene("hand-outside-start", 200, mc -> {
            setup(mc);
            command(mc, "fill -16 178 -16 16 190 16 minecraft:air");
            command(mc, "fill -16 179 -16 16 179 16 minecraft:sandstone");
            String material = System.getProperty("skyboxblock.regression.handMaterial", "sky");
            String block = switch (material) {
                case "air" -> "minecraft:air";
                case "glass" -> "minecraft:glass";
                case "stone" -> "minecraft:sandstone";
                case "black", "mixed" -> "skyboxblock:black_sky_block";
                case "galaxy" -> "skyboxblock:galaxy_sky_block";
                case "apocalypse" -> "skyboxblock:apocalypse_sky_block";
                default -> "skyboxblock:sky_block";
            };
            command(mc, "fill -6 179 0 6 186 12 " + block + " hollow");
            if(material.equals("mixed")) {
                command(mc,"fill -6 179 12 6 186 12 skyboxblock:apocalypse_sky_block");
                command(mc,"fill -6 179 0 -6 186 12 skyboxblock:galaxy_sky_block");
            }
            command(mc, "time set 6000");
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            mc.options.hideGui = false;
            held(mc, "minecraft:air"); offhand(mc, "minecraft:air");
            look(mc, .5, 180, -4.5, .5, 181.62, 8);
            handTransitionNanos = System.nanoTime();
        }));
        SCENES.add(new Scene("hand-enter-00", 2, mc -> {
            look(mc, .5, 180, 6.5, .5, 181.62, 20); handTransitionNanos = System.nanoTime();
        }));
        SCENES.add(new Scene("hand-inside-01", 18, mc -> {}));
        SCENES.add(new Scene("hand-inside-03", 40, mc -> {}));
        SCENES.add(new Scene("hand-inside-10", 140, mc -> {}));
        SCENES.add(new Scene("hand-inside-20", 200, mc -> {}));
        SCENES.add(new Scene("hand-inside-30", 200, mc -> {}));
        SCENES.add(new Scene("hand-exit-00", 2, mc -> {
            look(mc, .5, 180, -4.5, .5, 181.62, 8); handTransitionNanos = System.nanoTime();
        }));
        SCENES.add(new Scene("hand-exit-03", 60, mc -> {}));
        SCENES.add(new Scene("hand-exit-10", 140, mc -> {}));
        SCENES.add(new Scene("hand-reenter-00", 2, mc -> {
            look(mc, .5, 180, 6.5, .5, 181.62, 20); handTransitionNanos = System.nanoTime();
        }));
        SCENES.add(new Scene("hand-reenter-10", 200, mc -> {}));
        SCENES.add(new Scene("hand-reenter-30", 400, mc -> {}));
        SCENES.add(new Scene("hand-removed-00", 2, mc -> {
            command(mc, "fill -6 179 0 6 186 12 minecraft:air");
            command(mc, "fill -16 179 -16 16 179 16 minecraft:sandstone");
            handTransitionNanos = System.nanoTime();
        }));
        SCENES.add(new Scene("hand-removed-10", 200, mc -> {}));
    }

    private static com.google.gson.JsonObject handExposureEvidence(Minecraft mc, String scene, SkyWorldRenderer.Frame frame) {
        if (!scene.startsWith("hand-")) return null;
        var data = new com.google.gson.JsonObject();
        data.addProperty("material", System.getProperty("skyboxblock.regression.handMaterial", "sky"));
        data.addProperty("elapsedSinceTransitionMilliseconds", (System.nanoTime() - handTransitionNanos) / 1_000_000L);
        data.addProperty("cameraTransfer", "controlled-server-teleport-between-fixed-poses");
        data.addProperty("actualSkyBlocks", fixtureBlocks(mc.level, -6, 179, 0, 6, 186, 12, SkyboxBlockMod.SKY_BLOCK));
        var styles=new com.google.gson.JsonObject(); int totalSky=0;
        for(var entry:SkyboxBlockMod.SKY_BLOCKS.entrySet()) {
            int count=fixtureBlocks(mc.level,-6,179,0,6,186,12,entry.getValue());
            styles.addProperty(entry.getKey().name(),count); totalSky+=count;
        }
        data.addProperty("actualTotalSkyBlocks",totalSky); data.add("actualSkyStyles",styles);
        data.addProperty("actualGlassBlocks", fixtureBlocks(mc.level, -6, 179, 0, 6, 186, 12, net.minecraft.world.level.block.Blocks.GLASS));
        data.addProperty("expectedShellBlocks", 13 * 8 * 13 - 11 * 6 * 11);
        var eye = net.minecraft.core.BlockPos.containing(frame.camera);
        data.addProperty("skyLight", mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye));
        data.addProperty("blockLight", mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, eye));
        data.addProperty("timeOfDay", mc.level.getDayTime());
        data.addProperty("hudAndHandEnabled", !mc.options.hideGui);
        data.addProperty("meterProxyActive",frame.exposureMeterCorrected);
        data.addProperty("meterSamplerRestored",frame.exposureMeterRestored);
        data.addProperty("meterAtlasSource",frame.exposureMeterSource);
        data.addProperty("meterAtlasProxy",frame.exposureMeterProxy);
        data.add("exposure", ItrpSkyBridge.exposureDiagnostics(frame));
        return data;
    }

    /** Same-position material controls; only used inside an owned regression world. */
    private static void ambientOcclusionScenes() {
        for (String layout : new String[]{"floor", "corner"})
            for (String time : new String[]{"day", "night"})
                for (boolean smooth : new boolean[]{true, false})
                    for (String material : new String[]{"air", "glass", "stone", "sky", "removed"}) {
                        String name = "ao-" + layout + "-" + time + "-" + (smooth ? "on" : "off") + "-" + material;
                        SCENES.add(new Scene(name, 80, mc -> ambientOcclusionSetup(mc, layout, time, smooth, material)));
                    }
    }

    private static void ambientOcclusionSetup(Minecraft mc, String layout, String time, boolean smooth, String material) {
        setup(mc);
        mc.options.hideGui = true;
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        mc.options.gamma().set(0.5D);
        mc.options.ambientOcclusion().set(smooth);
        command(mc, "fill -9 178 -11 9 187 8 minecraft:air");
        command(mc, "fill -7 179 -8 7 179 7 minecraft:smooth_sandstone");
        if (layout.equals("corner")) {
            command(mc, "fill -4 180 1 -4 185 5 minecraft:smooth_sandstone");
            command(mc, "fill 4 180 1 4 185 5 minecraft:smooth_sandstone");
            command(mc, "fill -3 185 1 3 185 5 minecraft:smooth_sandstone");
        }
        // No local light sources: keep the vanilla lightmap's random torch flicker out of the paired measurement.
        // Both real day and real night remain tested; production lighting is not frozen or patched.
        String block = switch (material) {
            case "glass" -> "minecraft:glass";
            case "stone" -> "minecraft:stone";
            case "sky" -> "skyboxblock:sky_block";
            default -> "minecraft:air";
        };
        command(mc, "fill -3 180 3 3 184 3 " + block);
        command(mc, "time set " + (time.equals("day") ? 6000 : 18000));
        look(mc, .5, 180, -4.5, .5, 180.4, 3.5);
    }

    private static com.google.gson.JsonObject ambientOcclusionEvidence(Minecraft mc, String scene) {
        if (!scene.startsWith("ao-")) return null;
        var data = new com.google.gson.JsonObject();
        var gson = new com.google.gson.Gson();
        var pos = new net.minecraft.core.BlockPos(0, 180, 3);
        var state = mc.level.getBlockState(pos);
        data.addProperty("smoothLighting", mc.options.ambientOcclusion().get());
        data.addProperty("gamma", mc.options.gamma().get());
        data.addProperty("irisActive", ModCompat.irisActive());
        data.addProperty("material", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        data.addProperty("shadeBrightness", state.getShadeBrightness(mc.level, pos));
        data.addProperty("lightBlock", state.getLightBlock(mc.level, pos));
        data.addProperty("emission", state.getLightEmission());
        data.addProperty("propagatesSkylight", state.propagatesSkylightDown(mc.level, pos));
        data.addProperty("solidRender", state.isSolidRender(mc.level, pos));
        data.addProperty("fullCollision", state.isCollisionShapeFullBlock(mc.level, pos));
        data.addProperty("planeBlockCount", actualFixtureCount(mc.level, -3, 180, 3, 3, 184, 3, state.getBlock()));
        data.add("planeBounds", gson.toJsonTree(new double[]{-3, 180, 3, 4, 185, 4}));
        data.add("floorBounds", gson.toJsonTree(new double[]{-7, 179, -8, 8, 180, 8}));
        data.add("wallBounds", gson.toJsonTree(scene.startsWith("ao-corner-") ? new double[][]{
                {-4, 180, 1, -3, 186, 6}, {4, 180, 1, 5, 186, 6}, {-3, 185, 1, 4, 186, 6}
        } : new double[0][]));
        var samples = new com.google.gson.JsonArray();
        for (int x = -3; x <= 3; x++) for (int z = 0; z <= 2; z++) {
            var sample = new net.minecraft.core.BlockPos(x, 180, z);
            samples.add(gson.toJsonTree(new int[]{x, 180, z,
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, sample),
                    mc.level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, sample)}));
        }
        data.add("physicalLightSamples", samples);
        return data;
    }

    private static void blueScenes() {
        SCENES.add(new Scene("blue-dark-room",100,mc -> {
            setup(mc);
            command(mc,"fill -9 179 -10 9 196 6 minecraft:bedrock hollow");
            command(mc,"time set 18000");
            look(mc,.5,180,-4.5,.5,182.5,4.5); mc.options.hideGui=true;
        }));
        SCENES.add(new Scene("blue-night-wall",160,mc -> command(mc,"fill -8 180 5 8 195 5 skyboxblock:sky_block")));
        SCENES.add(new Scene("blue-night-settled",300,mc -> {}));
        SCENES.add(new Scene("blue-day-wall",160,mc -> command(mc,"time set 6000")));
        SCENES.add(new Scene("blue-return-night",160,mc -> command(mc,"time set 18000")));
        SCENES.add(new Scene("blue-removed",160,mc -> command(mc,"fill -8 180 5 8 195 5 minecraft:air")));
    }

    private static void vanillaReferenceScenes() {
        SCENES.add(new Scene("ref-day",80,mc -> {
            setup(mc);
            command(mc,"fill -9 174 -13 9 201 5 minecraft:sandstone hollow");
            command(mc,"fill -8 175 -12 8 200 4 skyboxblock:sky_block hollow");
            referencePose(mc,6000,0,-25);
        }));
        for(int phase=0;phase<8;phase++) {
            final int p=phase;
            SCENES.add(new Scene("ref-moon-"+p,45,mc -> referencePose(mc,18000+p*24000L,0,-90)));
        }
        for(int time:new int[]{23000,0,1000,11000,12000,13000}) {
            SCENES.add(new Scene("ref-horizon-"+time,45,mc -> referencePose(mc,time,time<2000 || time>22000 ? -90 : 90,0)));
        }
        for(int yaw:new int[]{0,90,180,270})
            SCENES.add(new Scene("ref-night-"+yaw,45,mc -> referencePose(mc,18000,yaw,0)));
        SCENES.add(new Scene("ref-cloud-96",60,mc -> {
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.FANCY);
            clientCommand(mc,"skyboxblock cloudheight 96"); referencePose(mc,6000,0,-35);
        }));
        SCENES.add(new Scene("ref-cloud-240",60,mc -> clientCommand(mc,"skyboxblock cloudheight 240")));
        SCENES.add(new Scene("ref-cloud-reload",60,mc -> clientCommand(mc,"skyboxblock reload")));
        SCENES.add(new Scene("ref-cloud-reset",60,mc -> clientCommand(mc,"skyboxblock cloudheight reset")));
    }
    private static void referencePose(Minecraft mc,long time,float yaw,float pitch) {
        command(mc,"time set "+time);
        double ry=Math.toRadians(yaw),rp=Math.toRadians(pitch);
        look(mc,.5,180,-4.5,.5-Math.sin(ry)*Math.cos(rp)*20,181.62-Math.sin(rp)*20,
                -4.5+Math.cos(ry)*Math.cos(rp)*20);
        mc.options.hideGui=true;
    }
    private static void clientCommand(Minecraft mc,String text) {
        try {
            var dispatcher=net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
            var source=(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)(Object)mc.getConnection().getSuggestionsProvider();
            if(dispatcher==null || dispatcher.execute(text,source)!=1) throw new IllegalStateException("Client command failed: "+text);
        } catch(com.mojang.brigadier.exceptions.CommandSyntaxException exception) { throw new RuntimeException(exception); }
    }

    private static void skyScenes() {
        SCENES.add(new Scene("sky-day",60,mc -> {
            setup(mc);
            command(mc,"fill -6 179 -8 6 179 4 minecraft:bedrock");
            command(mc,"fill -5 181 2 5 190 2 skyboxblock:sky_block");
            command(mc,"fill -6 180 4 6 191 4 minecraft:sandstone");
            look(mc,.5,180,-4.5,.5,185.2,2.5);
        }));
        SCENES.add(new Scene("sky-night",40,mc -> command(mc,"time set midnight")));
        SCENES.add(new Scene("sky-reload",40,mc -> reloadFuture=mc.reloadResourcePacks()));
        SCENES.add(new Scene("sky-resized",45,mc -> window(mc,1024,768)));
        SCENES.add(new Scene("sky-rain",60,mc -> command(mc,"weather rain")));
        SCENES.add(new Scene("sky-clear",60,mc -> command(mc,"weather clear")));
        SCENES.add(new Scene("sky-occluded",120,mc -> command(mc,"fill -6 180 0 6 191 0 minecraft:sandstone")));
        SCENES.add(new Scene("sky-inside",40,mc -> look(mc,.5,182,2.5,.5,183.62,5)));
        SCENES.add(new Scene("sky-clean",40,mc -> {
            command(mc,"fill -6 180 -6 6 194 6 minecraft:air");
            window(mc,1280,800);
        }));
    }

    /** Resource reload/window focus must not turn the fixture camera via OS mouse events. */
    public static void lockCamera() {
        if (!ENABLED || !cameraLocked) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        player.setYRot(lockedYaw); player.yRotO = lockedYaw;
        player.setXRot(lockedPitch); player.xRotO = lockedPitch;
        player.yHeadRot = lockedYaw; player.yHeadRotO = lockedYaw;
    }

    private static String preWorldScreen;
    private static void inspectPreWorldScreen(Minecraft mc) {
        if (mc.screen==null) return;
        String owner=System.getProperty("skyboxblock.regression.ownedGameDir","");
        var actual=mc.gameDirectory.toPath().toAbsolutePath().normalize();
        if (owner.isEmpty() || !actual.equals(Path.of(owner).toAbsolutePath().normalize())
                || !actual.toString().contains("render-regression")) return;
        String type=mc.screen.getClass().getName();
        if (type.equals(preWorldScreen)) return;
        preWorldScreen=type;
        SkyboxBlockMod.LOGGER.info("[skybox-regression] pre-world screen={} title={}",type,mc.screen.getTitle().getString());
        net.minecraft.client.gui.components.Button proceed=null;
        for (var child:mc.screen.children()) if (child instanceof net.minecraft.client.gui.components.Button button) {
            SkyboxBlockMod.LOGGER.info("[skybox-regression] pre-world button={}",button.getMessage());
            if (button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && text.getKey().equals("selectWorld.backupJoinSkipButton")) proceed=button;
        }
        try (SkyGlState ignored=new SkyGlState(1); NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Files.createDirectories(output());
            image.writeToFile(output().resolve("preworld-"+mc.screen.getClass().getSimpleName()+".png"));
        } catch (IOException exception) { throw new RuntimeException(exception); }
        if (proceed!=null) {
            SkyboxBlockMod.LOGGER.info("[skybox-regression] continue owned NEW experimental fixture without redundant backup");
            proceed.onPress();
        }
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) { inspectPreWorldScreen(mc); return; }
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null) mc.setScreen(null);
        lockCamera();
        if (++ticks < 60) return;
        if (index<0 && !FixtureStartup.ready(mc)) return;
        if (finish >= 0) {
            if (++finish == 30 && Boolean.getBoolean("skyboxblock.regression.exit")) mc.stop();
            return;
        }
        if (worldSetupFuture!=null) {
            if (!worldSetupFuture.isDone()) return;
            worldSetupFuture.join(); worldSetupFuture=null;
        }
        if (reloadFuture != null) {
            if (!reloadFuture.isDone()) return;
            reloadFuture.join();
            reloadFuture = null;
        }
        if (pending != null) return;
        if (remaining > 0 && --remaining > 0) return;
        if (index >= 0 && remaining == 0) {
            if (SCENES.get(index).name().startsWith("dim-") && !dimensionReady(mc)) return;
            if (SETTLED && transparencyFixture!=null && !settlementReady()) return;
            if (transparencyFixture!=null) {
                if (skyCountFuture==null) {
                    var result=new java.util.concurrent.CompletableFuture<com.google.gson.JsonObject>(); skyCountFuture=result;
                    var key=mc.level.dimension();
                    mc.getSingleplayerServer().execute(() -> {
                        try {
                            var level=mc.getSingleplayerServer().getLevel(key);
                            result.complete(transparencyBlocks(level));
                        } catch (Exception exception) { result.completeExceptionally(exception); }
                    });
                    return;
                }
                if (!skyCountFuture.isDone()) return;
                var serverGeometry=skyCountFuture.join(); var clientGeometry=transparencyBlocks(mc.level);
                transparencyFixture.add("actualServerGameTime",serverGeometry.remove("sampledGameTime"));
                transparencyFixture.add("actualClientGameTime",clientGeometry.remove("sampledGameTime"));
                transparencyFixture.addProperty("serverSkyBlockCount",serverGeometry.get("sky").getAsInt());
                transparencyFixture.add("actualServerGeometry",serverGeometry);
                transparencyFixture.add("actualClientGeometry",clientGeometry);
                skyCountFuture=null;
            }
            if (!BlockCollectionDiagnostics.serverSampleReady(mc)) return;
            pending = SCENES.get(index).name(); remaining = -1; return;
        }
        if (++index >= SCENES.size()) {
            finish = 0;
            SkyboxBlockMod.LOGGER.info("[skybox-regression] FINISHED: {} scenes", SCENES.size());
            return;
        }
        Scene scene = SCENES.get(index);
        sceneRenderFrames=0; poseStableSince=0; poseStableFrames=0;
        dimensionProbe=null; dimensionFixtureEvidence=null; distantTerrainEvidence=null; dimensionWaitChecks=0; dimensionClientReadyAt=-1;
        BlockCollectionDiagnostics.startScene(scene.name());
        scene.setup().accept(mc); remaining = scene.waitTicks();
        SkyboxBlockMod.LOGGER.info("[skybox-regression] scene {}",scene.name());
    }

    private static Path output() {
        return Path.of(System.getProperty("skyboxblock.regression.output",
                Minecraft.getInstance().gameDirectory.toPath().resolve("render-results").toString()));
    }
    public static boolean capturePending() { return ENABLED && pending!=null && captureCandidate!=null; }

    /** Test-only oracle: actual vanilla sky immediately after renderSky, before terrain/clouds. */
    public static void nativeSkySnapshot() {
        if (!ENABLED || pending==null || ModCompat.irisActive()) return;
        var frame=SkyWorldRenderer.current();
        if(frame==null || !frame.root || frame.shadow || !frame.nativeSkyFogCaptured) return;
        try (SkyGlState ignored=new SkyGlState(1);
             NativeImage image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            Files.createDirectories(output());
            image.writeToFile(output().resolve(pending+"-native-sky.png"));
        } catch(IOException exception) { throw new RuntimeException(exception); }
    }

    public static void sceneSnapshot(RenderTarget target, String stage) {
        if (!ENABLED || pending == null || captureCandidate==null) return;
        try (SkyGlState ignored = new SkyGlState(1); NativeImage image = Screenshot.takeScreenshot(target)) {
            Files.createDirectories(output());
            image.writeToFile(output().resolve(pending + "-scene-" + stage + ".png"));
            if (stage.endsWith("clouds")) {
                Path diagnostic=output().resolve("cloud-diagnostics"); Files.createDirectories(diagnostic);
                var data=SkyVanillaClouds.cloudDrawDiagnostics();
                data.addProperty("scene",pending); data.addProperty("stage",stage);
                data.addProperty("canvasTexture",target.getColorTextureId());
                data.addProperty("configuredVanillaCloudHeight",SkyboxConfig.get().vanillaCloudHeight());
                data.addProperty("nativeDimensionCloudHeight",Minecraft.getInstance().level.effects().getCloudHeight());
                data.addProperty("drawFramebufferAtSnapshot",GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
                Files.writeString(diagnostic.resolve(pending+"-"+stage+".json"),
                        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data),StandardCharsets.UTF_8);
            }
        } catch (IOException exception) { throw new RuntimeException(exception); }
    }
    /** Measure only an actual vanilla foreground draw, never infer exclusions from sky/reference differences. */
    public static void beforeWorldOverlay() {
        if (!ENABLED || pending == null || capturedFrame == null || ModCompat.irisActive()
                || ModCompat.portalRendering()) return;
        try (SkyGlState ignored = new SkyGlState(1)) {
            overlayBefore = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        }
    }
    public static void afterWorldOverlay() {
        if (!ENABLED || overlayBefore == null) return;
        try (SkyGlState ignored = new SkyGlState(1);
             NativeImage after = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
            int width = after.getWidth(), height = after.getHeight();
            if (overlayMask == null) {
                overlayMask = new NativeImage(width, height, false);
                overlayMask.fillRect(0, 0, width, height, 0xFF000000);
            }
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                if (overlayBefore.getPixelRGBA(x, y) != after.getPixelRGBA(x, y))
                    overlayMask.setPixelRGBA(x, y, 0xFFFFFFFF);
            }
        } finally {
            overlayBefore.close(); overlayBefore = null;
        }
    }
    public static void beforeFinalResolve() {
        if (!ENABLED || pending == null || capturedFrame == null) return;
        var frame=capturedFrame;
        boolean irisHands=ModCompat.irisActive();
        try (SkyGlState ignored = new SkyGlState(1);
             NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
             WorldCapture afterHands = readWorld(frame);
             NativeImage mask = new NativeImage(frame.viewport[2],frame.viewport[3],false);
             NativeImage foreground = new NativeImage(frame.viewport[2],frame.viewport[3],false)) {
            Files.createDirectories(output());
            image.writeToFile(output().resolve(pending + "-pre-resolve.png"));
            if (!finalEvidenceCaptured) distantTerrainEvidence=FixtureDistantTerrain.capture(frame,pending,output());
            int dx=frame.viewport[0]-frame.targetViewport[0],dy=frame.viewport[1]-frame.targetViewport[1];
            for(int y=0;y<mask.getHeight();y++) for(int x=0;x<mask.getWidth();x++) {
                int pixel=(dy+mask.getHeight()-1-y)*afterHands.color().getWidth()+dx+x;
                float depth=afterHands.depth()[pixel];
                boolean hand=irisHands ? depth<.7F : nativeHandMask!=null && (nativeHandMask.getPixelRGBA(x,y)&0xFFFFFF)!=0;
                mask.setPixelRGBA(x,y,hand ? 0xFFFFFFFF : 0xFF000000);
                // Hit outlines/other foreground drawn after our opaque pass are legitimate occluders too.
                foreground.setPixelRGBA(x,y,depth<opaque.depth()[pixel]-.000002F ? 0xFFFFFFFF : 0xFF000000);
            }
            mask.writeToFile(output().resolve(pending + "-post-hand-mask.png"));
            foreground.writeToFile(output().resolve(pending + "-post-opaque-foreground.png"));
            finalEvidenceCaptured=true;
        } catch (IOException exception) { throw new RuntimeException(exception); }
    }
    public static void beforeWorldDraw(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || pending == null || frame.portal || frame.shadow || captureCandidate!=frame) return;
        capturedFrame = frame; initialError = GL11.glGetError();
        before = readWorld(frame);
    }
    public static void afterWorldDraw(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || pending == null || frame.portal || frame.shadow || capturedFrame!=frame) return;
        opaque = readWorld(frame);
    }
    public static void worldNoGeometry(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || pending == null || frame.portal || frame.shadow) return;
        beforeWorldDraw(frame); afterWorldDraw(frame);
    }

    /** Full GL target, including the inactive FSR region; depth proves draws even if RGB is unchanged. */
    private static WorldCapture readWorld(SkyWorldRenderer.Frame frame) {
        int[] viewport = frame.targetViewport;
        int w = viewport[2], h = viewport[3];
        try (SkyGlState state = new SkyGlState(1)) {
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,frame.framebuffer);
            int oldRead = GL11.glGetInteger(GL11.GL_READ_BUFFER);
            GL11.glReadBuffer(frame.colorAttachment);
            var pixels = BufferUtils.createByteBuffer(w*h*4).order(ByteOrder.LITTLE_ENDIAN);
            GL11.glReadPixels(viewport[0],viewport[1],w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            var depths = BufferUtils.createFloatBuffer(w*h);
            GL11.glReadPixels(viewport[0],viewport[1],w,h,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,depths);
            GL11.glReadBuffer(oldRead);
            NativeImage color = new NativeImage(w,h,false);
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) color.setPixelRGBA(x,h-1-y,pixels.getInt((y*w+x)*4));
            float[] depth = new float[w*h];
            depths.get(depth);
            return new WorldCapture(color, depth, viewport.clone());
        }
    }

    private static NativeImage activeImage(WorldCapture capture, SkyWorldRenderer.Frame frame) {
        int w = frame.viewport[2], h = frame.viewport[3];
        int dx = frame.viewport[0] - capture.viewport()[0];
        int dy = capture.color().getHeight() - (frame.viewport[1] - capture.viewport()[1]) - h;
        NativeImage image = new NativeImage(w,h,false);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setPixelRGBA(x,y,capture.color().getPixelRGBA(x+dx,y+dy));
        return image;
    }

    private static boolean depthChanged(int x, int y) {
        int width = before.color().getWidth(), height = before.color().getHeight();
        int pixel = (height-1-y)*width+x;
        return Float.floatToIntBits(before.depth()[pixel]) != Float.floatToIntBits(opaque.depth()[pixel]);
    }

    public static void afterFrame() {
        if (!ENABLED || pending == null || capturedFrame == null || before == null || opaque == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (!finalEvidenceCaptured) beforeFinalResolve();
        String name = pending;
        var frame = capturedFrame;
        try (SkyGlState ignored = new SkyGlState(1);
             NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
             NativeImage beforeActive = activeImage(before,frame);
             NativeImage opaqueActive = activeImage(opaque,frame);
             NativeImage mask = new NativeImage(frame.viewport[2],frame.viewport[3],false)) {
            Files.createDirectories(output());
            image.writeToFile(output().resolve(name + ".png"));
            if (overlayMask != null) overlayMask.writeToFile(output().resolve(name + "-world-overlay-mask.png"));
            if (lineMask != null) lineMask.writeToFile(output().resolve(name + "-line-mask-full.png"));
            beforeActive.writeToFile(output().resolve(name + "-before.png"));
            opaqueActive.writeToFile(output().resolve(name + "-opaque.png"));
            int changed=0, depthChanged=0, outsideColor=0, outsideDepth=0;
            int activeX=frame.viewport[0]-frame.targetViewport[0];
            int activeY=frame.targetViewport[3]-(frame.viewport[1]-frame.targetViewport[1])-frame.viewport[3];
            for(int y=0;y<before.color().getHeight();y++) for(int x=0;x<before.color().getWidth();x++) {
                boolean colorChange=before.color().getPixelRGBA(x,y)!=opaque.color().getPixelRGBA(x,y);
                boolean depthChange=depthChanged(x,y);
                boolean active=x>=activeX && x<activeX+frame.viewport[2] && y>=activeY && y<activeY+frame.viewport[3];
                if(active) {
                    if(colorChange) changed++;
                    if(depthChange) depthChanged++;
                    mask.setPixelRGBA(x-activeX,y-activeY,depthChange ? 0xFFFFFFFF : 0xFF000000);
                } else {
                    if(colorChange) outsideColor++;
                    if(depthChange) outsideDepth++;
                }
            }
            mask.writeToFile(output().resolve(name + "-depth-change.png"));
            if(outsideColor>0 || outsideDepth>0) {
                before.color().writeToFile(output().resolve(name + "-full-target-before.png"));
                opaque.color().writeToFile(output().resolve(name + "-full-target-opaque.png"));
            }
            float[] inv = new Matrix4f(frame.skyProjection).mul(frame.view).invert().get(new float[16]);
            String json = "{\n\"scene\":\""+name+"\",\n\"width\":"+image.getWidth()+",\n\"height\":"+image.getHeight()
                    +",\n\"opaqueWidth\":"+frame.viewport[2]+",\n\"opaqueHeight\":"+frame.viewport[3]
                    +",\n\"targetViewport\":"+java.util.Arrays.toString(frame.targetViewport)
                    +",\n\"activeViewport\":"+java.util.Arrays.toString(frame.viewport)
                    +",\n\"changedPixels\":"+changed+",\n\"depthChangedPixels\":"+depthChanged
                    +",\n\"outsideRasterChangedPixels\":"+outsideColor+",\n\"outsideRasterDepthChangedPixels\":"+outsideDepth
                    +",\n\"initialGlError\":"+initialError+",\n\"finalGlError\":"+GL11.glGetError()
                    +",\n\"camera\":["+frame.camera.x+","+frame.camera.y+","+frame.camera.z+"]"
                    +",\n\"worldView\":"+java.util.Arrays.toString(frame.view.get(new float[16]))
                    +",\n\"panoramaMotion\":"+panoramaMotion+",\n\"panoramaMotionTicks\":"+panoramaMotionTicks
                    +",\n\"bobViewEnabled\":"+mc.options.bobView().get()
                    +",\n\"negativePanoramaRays\":"+Boolean.getBoolean("skyboxblock.regression.negativePanoramaRays")
                    +",\n\"inverseViewProjection\":"+java.util.Arrays.toString(inv)
                    +",\n\"inverseDisplayViewProjection\":"+java.util.Arrays.toString(new Matrix4f(frame.projection).mul(frame.view).invert().get(new float[16]))
                    +",\n\"surfaceLayerUsed\":"+frame.surfaceLayerUsed
                    +",\n\"surfaceLayerSamples\":"+frame.surfaceLayerSamples
                    +",\n\"nativeBackgroundReady\":"+frame.nativeBackgroundReady
                    +",\n\"nativeBackgroundColorTexture\":"+frame.nativeBackgroundColorTexture
                    +",\n\"finalResolved\":"+frame.finalResolved
                    +",\n\"resourceReloadPending\":"+(reloadFuture!=null && !reloadFuture.isDone())
                    +",\n\"rasterScale\":["+frame.rasterScale.x+","+frame.rasterScale.y+"]"
                    +",\n\"rasterJitter\":["+frame.rasterJitter.x+","+frame.rasterJitter.y+"]"
                    +",\n\"irisFrameCounter\":"+irisFrameCounter()
                    +",\n\"sceneRenderFrames\":"+sceneRenderFrames
                    +",\n\"neverSkyObservedRootFrames\":"+neverSkyObservedRootFrames
                    +",\n\"suppressedFixtureWaterTicks\":"+dev.yg.skyboxblock.RegressionFluidControl.suppressedTicks()
                    +",\n\"fixtureInputGuardTicks\":"+FixtureClientControl.inputTicks()
                    +",\n\"frozenFixtureWaterSprites\":"+FixtureClientControl.frozenSprites()
                    +",\n\"observedFixtureWaterSprites\":"+FixtureClientControl.observedWaterSprites()
                    +",\n\"staticFixtureWaterSprites\":"+FixtureClientControl.staticWaterSprites()
                    +",\n\"suppressedFixtureWaterSpriteTicks\":"+FixtureClientControl.waterTicks()
                    +",\n\"fixtureSettlement\":"+settlementEvidence()
                    +",\n\"sceneWaitTicks\":"+SCENES.get(index).waitTicks()
                    +",\n\"depthRange\":"+java.util.Arrays.toString(frame.depthRange)
                    +",\n\"hideGui\":"+mc.options.hideGui+",\n\"cameraType\":\""+mc.options.getCameraType()+"\""
                    +",\n\"heldItem\":\""+BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem())+"\""
                    +",\n\"offhandItem\":\""+BuiltInRegistries.ITEM.getKey(mc.player.getOffhandItem().getItem())+"\""
                    +",\n\"drawStage\":\"opaque-before-iris-hand\""
                    +",\n\"blocks\":"+frame.blocks()+",\n\"vertices\":"+frame.vertices()
                    +",\n\"cloudsEnabled\":"+SkyboxConfig.get().clouds()
                    +",\n\"cloudFrames\":"+SkyVanillaClouds.renderedFrames()
                    +",\n\"cloudWorldHeight\":"+SkyboxConfig.get().vanillaCloudHeight()
                    +",\n\"cloudRelativeY\":"+SkyRenderMath.cloudRelativeY(frame.camera.y,SkyboxConfig.get().vanillaCloudHeight())
                    +",\n\"privateSkyClipBefore\":"+frame.privateSkyClipBefore
                    +",\n\"privateSkyClipDuring\":"+frame.privateSkyClipDuring
                    +",\n\"cloudProgramExpected\":"+SkyVanillaClouds.cloudProgramExpected()
                    +",\n\"cloudProgramActual\":"+SkyVanillaClouds.cloudProgramActual()
                    +",\n\"cloudTextureId\":"+SkyVanillaClouds.cloudTextureId()
                    +",\n\"cloudClipEnabled\":"+SkyVanillaClouds.cloudClipEnabled()
                    +",\n\"irisMainBoundBefore\":"+mainBoundBefore+",\n\"irisMainBoundAfter\":"+mainBoundAfter
                    +",\n\"irisOverridesBefore\":"+overridesBefore+",\n\"irisOverridesAfter\":"+overridesAfter
                    +",\n\"dimension\":\""+frame.level.dimension().location()+"\""
                    +",\n\"displaySky\":"+displaySkyEvidence(frame)
                    +",\n\"dimensionFixture\":"+dimensionFixtureEvidence
                    +",\n\"fixtureStartup\":"+FixtureStartup.evidence()
                    +",\n\"blockCollection\":"+BlockCollectionDiagnostics.evidence(frame)
                    +",\n\"distantTerrain\":"+distantTerrainEvidence
                    +",\n\"transparency\":"+transparencyFixture
                    +",\n\"handMaskEvidence\":\""+(ModCompat.irisActive()?"iris-post-hand-depth":"native-hand-draw-span")+"\""
                    +",\n\"itrpMaterialWrites\":"+frame.itrpMaterialWrites
                    +",\n\"itrpBackgroundInjected\":"+frame.itrpBackgroundInjected
                    +",\n\"itrpInjectionCount\":"+frame.itrpInjectionCount
                    +",\n\"itrpBridgeFailure\":"+new com.google.gson.Gson().toJson(frame.itrpBridgeFailure)
                    +",\n\"itrpSceneInputTexture\":"+frame.itrpSceneInputTexture
                    +",\n\"itrpExposureTexture\":"+frame.itrpExposureTexture
                    +",\n\"itrpOpaqueDepthTexture\":"+frame.itrpOpaqueDepthTexture
                    +",\n\"handExposure\":"+handExposureEvidence(mc,name,frame)
                    +",\n\"ambientOcclusion\":"+ambientOcclusionEvidence(mc,name)
                    +",\n\"alignment\":"+alignmentEvidence(mc)+"\n}\n";
            Files.writeString(output().resolve(name+".json"),json,StandardCharsets.UTF_8);
            SkyboxBlockMod.LOGGER.info("[skybox-regression] CAPTURED {} opaqueChanged={} depthChanged={} outsideColor={} outsideDepth={}",
                    name,changed,depthChanged,outsideColor,outsideDepth);
        } catch(IOException exception) { throw new RuntimeException(exception); }
        finally {
            before.close(); opaque.close();
            if (overlayBefore != null) overlayBefore.close();
            if (overlayMask != null) overlayMask.close();
            if (lineMask != null) lineMask.close();
            if (lineBefore != null) lineBefore.close();
            if (nativeHandBefore!=null) nativeHandBefore.close();
            if (nativeHandMask!=null) nativeHandMask.close();
            nativeHandBefore=null; nativeHandMask=null;
            overlayBefore=overlayMask=lineMask=null;
            lineBefore=null; lineShader=null;
            before=opaque=null; capturedFrame=null; pending=null; finalEvidenceCaptured=false;
        }
    }
    private static Integer irisFrameCounter() {
        if (!ModCompat.irisActive()) return null;
        try {
            var type=Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms");
            var field=type.getDeclaredField("COUNTER"); field.setAccessible(true);
            Object counter=field.get(null);
            var getter=counter.getClass().getMethod("getAsInt"); getter.setAccessible(true);
            return ((Number)getter.invoke(counter)).intValue();
        } catch (ReflectiveOperationException exception) { return null; }
    }

    private static com.google.gson.JsonObject displaySkyEvidence(SkyWorldRenderer.Frame frame) {
        if (frame.sky==null) frame.sky=SkyDisplayState.sample(frame.level,frame.camera,frame.partialTick);
        var sky=frame.sky;
        var data=new com.google.gson.JsonObject();
        var gson=new com.google.gson.Gson();
        data.addProperty("timeSource",sky.timeSource); data.addProperty("nativeSkyType",sky.nativeSkyType);
        data.addProperty("gameTime",sky.gameTime); data.addProperty("dayTime",sky.dayTime);
        data.addProperty("phase",sky.phase); data.addProperty("nativePhase",sky.nativePhase);
        data.addProperty("partialTick",frame.partialTick); data.addProperty("moonPhase",sky.moonPhase);
        data.addProperty("nativeMoonPhase",frame.level.getMoonPhase());
        data.addProperty("nativeFixedTime",sky.nativeFixedTime);
        data.addProperty("hasSkylight",frame.level.dimensionType().hasSkyLight());
        data.addProperty("starBrightness",sky.starBrightness);
        data.addProperty("rain",sky.rain); data.addProperty("thunder",sky.thunder);
        data.add("skyColor",gson.toJsonTree(new double[]{sky.skyColor.x,sky.skyColor.y,sky.skyColor.z}));
        data.add("fogColor",gson.toJsonTree(new double[]{sky.fogColor.x,sky.fogColor.y,sky.fogColor.z}));
        data.add("cloudColor",gson.toJsonTree(new double[]{sky.cloudColor.x,sky.cloudColor.y,sky.cloudColor.z}));
        return data;
    }

    private static com.google.gson.JsonObject alignmentEvidence(Minecraft mc) {
        var evidence = new com.google.gson.JsonObject();
        var gson = new com.google.gson.Gson();
        if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            var state = mc.level.getBlockState(pos);
            var shape = state.getShape(mc.level,pos);
            evidence.add("hitBlock",gson.toJsonTree(new int[]{pos.getX(),pos.getY(),pos.getZ()}));
            evidence.addProperty("hitBlockId",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            if (!shape.isEmpty()) {
                var bounds = shape.bounds().move(pos);
                evidence.add("outlineBounds",gson.toJsonTree(new double[][]{
                        {bounds.minX,bounds.minY,bounds.minZ},{bounds.maxX,bounds.maxY,bounds.maxZ}}));
            }
        }
        var items = new com.google.gson.JsonArray();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity item)
                    || item.getItem().getItem()!=net.minecraft.world.item.Items.EMERALD) continue;
            var data = new com.google.gson.JsonObject();
            data.addProperty("id",item.getId());
            data.addProperty("item",BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
            data.add("position",gson.toJsonTree(new double[]{item.getX(),item.getY(),item.getZ()}));
            data.addProperty("tickCount",item.tickCount);
            data.addProperty("age",item.getAge());
            data.addProperty("bobOffset",item.bobOffs);
            data.addProperty("partialTick",capturedFrame.partialTick);
            items.add(data);
        }
        evidence.add("items",items);
        evidence.addProperty("lineShader",lineShader);
        return evidence;
    }

    private static void command(Minecraft mc,String text) { if(mc.player!=null) mc.player.connection.sendCommand(text); }
    private static void held(Minecraft mc,String item) { command(mc,"item replace entity @s weapon.mainhand with "+item); }
    private static void offhand(Minecraft mc,String item) { command(mc,"item replace entity @s weapon.offhand with "+item); }
    private static void window(Minecraft mc,int width,int height) {
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),width,height);
    }
    private static void look(Minecraft mc,double x,double y,double z,double tx,double ty,double tz) {
        double dx=tx-x,dy=ty-(y+1.62),dz=tz-z;
        targetEyeX=x; targetEyeY=y+mc.player.getEyeHeight(); targetEyeZ=z;
        poseStableSince=0; poseStableFrames=0;
        lockedYaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        lockedPitch=(float)-Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz)));
        cameraLocked=true;
        command(mc,String.format(Locale.ROOT,"tp @s %.6f %.6f %.6f %.6f %.6f",x,y,z,lockedYaw,lockedPitch));
        lockCamera();
    }
    private record WorldCapture(NativeImage color,float[] depth,int[] viewport) implements AutoCloseable {
        @Override public void close() { color.close(); }
    }
    private record Scene(String name,int waitTicks,java.util.function.Consumer<Minecraft> setup) {}
}
