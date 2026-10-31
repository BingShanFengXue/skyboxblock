package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.SkyboxBlockMod;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Separate validation mod. No production callbacks, filesystem access or world changes unless explicitly owned. */
public final class R30Probe implements ClientModInitializer {
    private static final Logger LOG=LoggerFactory.getLogger("skybox-r30-probe");
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final boolean REQUESTED=Boolean.getBoolean("skyboxblock.r30.enabled");
    private static boolean active,started,finished,reloadStarted,captureNext,stageDone,clientCommandsDone;
    private static JsonArray clientCommandResults=new JsonArray();
    private static Path owner,output;
    private static JsonObject plan,current,serverEvidence;
    private static JsonArray phases,completed=new JsonArray();
    private static int phase=-1,finishTicks,ticks,releaseSequence,temporalSourceFrames;
    private static long lastTemporalGameTick=Long.MIN_VALUE;
    private static long phaseStart,lastFrame,lastTransaction=-1,nextSample,measurementStart,measurementEnd;
    private static long[] intervals=new long[16384];
    private static int frameCount,minimumBlocks=Integer.MAX_VALUE,maximumBlocks;
    private static long measuredVertices;
    private static float yaw,pitch;
    private static BufferedWriter samples;
    private static CompletableFuture<JsonObject> worldWork;
    private static CompletableFuture<Void> reload;
    private static final ArrayList<BlockPos> placed=new ArrayList<>(); // server thread only

    @Override public void onInitializeClient() {
        if(Boolean.getBoolean("skyboxblock.r36.multiplayerProbe")) R36MultiplayerProbe.initialize();
        if (!REQUESTED) return;
        try {
            Minecraft mc=Minecraft.getInstance();
            String declared=System.getProperty("skyboxblock.r30.ownedGameDir","");
            if (declared.isBlank() || !Path.of(declared).isAbsolute()) throw new IOException("Missing absolute owned gameDir");
            owner=Path.of(declared).toRealPath();
            if (!owner.equals(mc.gameDirectory.toPath().toRealPath()) || owner.getParent()==null
                    || !owner.getParent().getFileName().toString().equals("render-regression")
                    || !owner.getParent().getParent().getFileName().toString().equals("build"))
                throw new IOException("Not an owned build/render-regression child");
            plan=JsonParser.parseString(Files.readString(owner.resolve("r30-owned.json"),StandardCharsets.UTF_8)).getAsJsonObject();
            if (!plan.has("owned") || !plan.get("owned").getAsBoolean() || !owner.toString().equals(str(plan,"gameDir","")))
                throw new IOException("Owned marker mismatch");
            if (FabricLoader.getInstance().isModLoaded("immersive_portals")) throw new IOException("Portal mod present in non-portal R30 fixture");
            if (Boolean.getBoolean("skyboxblock.regression")) throw new IOException("Legacy capture harness must be disabled to avoid timing and readback contamination");
            phases=plan.getAsJsonArray("phases");
            if (phases==null || phases.isEmpty()) throw new IOException("No explicit phases");
            output=owner.resolve("r30-results"); Files.createDirectory(output);
            samples=Files.newBufferedWriter(output.resolve("samples.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            active=true;
            if (FabricLoader.getInstance().isModLoaded("distanthorizons")) DhObserver.register();
            ClientTickEvents.START_CLIENT_TICK.register(client -> {
                if(active && !finished) { net.minecraft.client.KeyMapping.releaseAll(); net.minecraft.client.KeyMapping.resetToggleKeys(); }
            });
            ClientTickEvents.END_CLIENT_TICK.register(R30Probe::tick);
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                if (!active) return;
                try { R30TemporalCapture.close(); write("shutdown-resources.json",ResourceFootprint.snapshot()); if(samples!=null) samples.close(); }
                catch (Exception error) { LOG.error("[r30-probe] shutdown evidence failed",error); }
            });
            write("probe-start.json",environment());
            LOG.info("[r30-probe] owned probe ready: {} phases, gameDir={}",phases.size(),owner);
        } catch (Exception error) { throw new IllegalStateException("R30 owned-fixture guard rejected",error); }
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        try {
            if (finished) { if(++finishTicks==30) mc.stop(); return; }
            if (mc.player==null || mc.level==null || mc.getSingleplayerServer()==null) { preWorld(mc); return; }
            if (!"Skybox render fixture".equals(mc.getSingleplayerServer().getWorldData().getLevelName()))
                throw new IOException("Unexpected world name; owned probe halted");
            mc.options.pauseOnLostFocus=false;
            if (mc.screen!=null) mc.setScreen(null);
            mc.options.hideGui=true; mc.mouseHandler.releaseMouse();
            mc.player.setYRot(yaw); mc.player.yRotO=yaw; mc.player.yHeadRot=yaw; mc.player.yHeadRotO=yaw;
            mc.player.setXRot(pitch); mc.player.xRotO=pitch;
            if (++ticks<100) return;
            if (!started) { started=true; beginNext(mc); return; }
            if (worldWork!=null) {
                if (!worldWork.isDone()) return;
                serverEvidence=worldWork.join(); worldWork=null;
            }
            if (reload!=null) { if(!reload.isDone()) return; reload.join(); reload=null; }
            if (!reloadStarted && bool(current,"reload",false)) {
                reloadStarted=true; reload=mc.reloadResourcePacks(); return;
            }
            if(!clientCommandsDone) {
                clientCommandsDone=true;
                if(current.has("clientCommands")) for(JsonElement command:current.getAsJsonArray("clientCommands")) {
                    JsonObject outcome=new JsonObject(); outcome.addProperty("command",command.getAsString());
                    int value;
                    try { value=clientCommand(mc,command.getAsString()); }
                    catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
                        if(!bool(current,"allowFailedClientCommand",false)) throw invalid;
                        value=0; outcome.addProperty("syntaxError",invalid.getMessage());
                    }
                    outcome.addProperty("result",value); clientCommandResults.add(outcome);
                    if(value!=1 && !bool(current,"allowFailedClientCommand",false)) throw new IllegalStateException("Client command failed: "+command);
                }
            }
            if (phaseStart==0) {
                phaseStart=System.nanoTime();
                measurementStart=phaseStart+(long)(num(current,"warmupSeconds",15)*1e9);
                measurementEnd=measurementStart+(long)(num(current,"sampleSeconds",30)*1e9);
                lastFrame=0; nextSample=phaseStart;
                LOG.info("[r30-probe] PHASE {} warmup={}s sample={}s",name(),num(current,"warmupSeconds",15),num(current,"sampleSeconds",30));
            }
            if(stageDone) { stageDone=false; beginNext(mc); }
        } catch (Exception|LinkageError error) { fail(mc,error); }
    }

    private static void beginNext(Minecraft mc) throws Exception {
        if (++phase>=phases.size()) {
            JsonObject result=environment(); result.addProperty("status","EXECUTION_COMPLETE_NOT_ACCEPTANCE");
            result.addProperty("complete",true); result.addProperty("phaseCount",completed.size()); result.add("phases",completed);
            result.add("finalResources",ResourceFootprint.snapshot());
            result.addProperty("performanceAcceptance",false); result.addProperty("dynamicDhAcceptance",false);
            result.addProperty("meaning","Measurements collected; an independent explicit checker must determine acceptance");
            write("run-summary.json",result); samples.flush(); finished=true;
            LOG.info("[r30-probe] FINISHED {} phases",completed.size()); return;
        }
        current=phases.get(phase).getAsJsonObject(); phaseStart=0; lastFrame=0; frameCount=0;
        minimumBlocks=Integer.MAX_VALUE; maximumBlocks=0; measuredVertices=0;
        reloadStarted=false; captureNext=false; serverEvidence=null;
        clientCommandsDone=false; clientCommandResults=new JsonArray();
        temporalSourceFrames=0;lastTemporalGameTick=Long.MIN_VALUE;R30TemporalCapture.begin();
        yaw=(float)num(current,"yaw",0); pitch=(float)num(current,"pitch",0);
        if (current.has("width")) GLFW.glfwSetWindowSize(mc.getWindow().getWindow(),current.get("width").getAsInt(),current.get("height").getAsInt());
        var server=mc.getSingleplayerServer();
        JsonObject selected=current.deepCopy();
        worldWork=CompletableFuture.supplyAsync(() -> {
            var level=server.overworld();
            boolean preserve=bool(selected,"preservePanel",false);
            if(!preserve) {
                for(BlockPos pos:placed) level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                placed.clear();
            }
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(),"gamemode spectator @a");
            double x=num(selected,"x",.5),y=num(selected,"y",140),z=num(selected,"z",-8.5);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                    String.format(Locale.ROOT,"tp @a %.6f %.6f %.6f %.6f %.6f",x,y,z,num(selected,"yaw",0),num(selected,"pitch",0)));
            int w=(int)num(selected,"panelWidth",0),h=(int)num(selected,"panelHeight",0);
            if(w<0 || h<0 || (long)w*h>16384) throw new IllegalArgumentException("Panel size outside explicit probe bound");
            String material=str(selected,"material","sky");
            BlockState block=switch(material) { case "sky" -> SkyboxBlockMod.SKY_BLOCK.defaultBlockState();
                case "stone" -> Blocks.STONE.defaultBlockState(); case "air" -> Blocks.AIR.defaultBlockState();
                default -> {
                    var id=net.minecraft.resources.ResourceLocation.tryParse(material);
                    if(id==null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(id))
                        throw new IllegalArgumentException("Unknown material "+material);
                    yield net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                } };
            int px=(int)Math.floor(x)-w/2,py=(int)Math.floor(y)+1-h/2,pz=(int)Math.floor(z)+(int)num(selected,"panelDistance",12);
            int quadrant=bool(selected,"orientPanel",false) ? Math.floorMod((int)Math.round(num(selected,"yaw",0)/90.0),4) : 0;
            int distance=(int)num(selected,"panelDistance",12);
            if(quadrant==1 || quadrant==3) { px=(int)Math.floor(x)+(quadrant==1 ? -distance : distance); pz=(int)Math.floor(z)-w/2; }
            else if(quadrant==2) pz=(int)Math.floor(z)-distance;
            int actual=0;
            if(!preserve) for(int dx=0;dx<w;dx++) for(int dy=0;dy<h;dy++) {
                BlockPos pos=(quadrant==1 || quadrant==3) ? new BlockPos(px,py+dy,pz+dx) : new BlockPos(px+dx,py+dy,pz);
                level.setBlock(pos,block,3); placed.add(pos);
            }
            for(BlockPos pos:placed) if(level.getBlockState(pos).is(block.getBlock())) actual++;
            JsonArray commandResults=new JsonArray();
            if(selected.has("commands")) for(JsonElement command:selected.getAsJsonArray("commands")) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPermission(4).withSuppressedOutput(),command.getAsString());
                JsonObject outcome=new JsonObject(); outcome.addProperty("command",command.getAsString());
                outcome.addProperty("submitted",true); outcome.addProperty("outcomeNotInferred",true); commandResults.add(outcome);
            }
            JsonArray settingResults=new JsonArray();
            if(selected.has("settingCommands")) for(JsonElement command:selected.getAsJsonArray("settingCommands")) {
                JsonObject outcome=new JsonObject();outcome.addProperty("command",command.getAsString());
                int result;
                try { result=server.getCommands().getDispatcher().execute(command.getAsString(),
                        server.createCommandSourceStack().withPermission(4).withSuppressedOutput()); }
                catch(com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
                    result=0;outcome.addProperty("syntaxError",invalid.getMessage());
                }
                outcome.addProperty("result",result);settingResults.add(outcome);
                if(result!=1 && !bool(selected,"allowFailedSettingCommand",false))
                    throw new IllegalStateException("Server setting command failed: "+command);
            }
            JsonArray blockProbes=new JsonArray();
            if(selected.has("blockProbes")) for(JsonElement request:selected.getAsJsonArray("blockProbes")) {
                JsonArray pos=request.getAsJsonArray(); BlockPos bp=new BlockPos(pos.get(0).getAsInt(),pos.get(1).getAsInt(),pos.get(2).getAsInt());
                JsonObject observed=new JsonObject(); observed.add("position",pos.deepCopy());
                observed.addProperty("block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString()); blockProbes.add(observed);
            }
            JsonObject evidence=new JsonObject(); evidence.add("commandResults",commandResults);evidence.add("settingCommandResults",settingResults); evidence.add("blockProbes",blockProbes); evidence.addProperty("material",material); evidence.addProperty("requestedPanelBlocks",preserve ? placed.size() : w*h);
            evidence.addProperty("preservedPanel",preserve);
            if(preserve && !placed.isEmpty()) { px=placed.getFirst().getX(); py=placed.getFirst().getY(); pz=placed.getFirst().getZ(); }
            evidence.addProperty("actualServerPanelBlocks",actual); evidence.addProperty("originX",px); evidence.addProperty("originY",py);
            evidence.addProperty("originZ",pz); evidence.addProperty("serverGameTime",level.getGameTime()); return evidence;
        },server::execute);
    }

    public static boolean ownedActive() { return active && !finished; }
    public static boolean ownerValidated() { return active; }
    public static boolean edgeCaptureDue() { return active && !finished && !stageDone && current!=null && phaseStart!=0 && System.nanoTime()>=measurementEnd; }
    public static Path edgeOutput() { return output; }
    public static String edgeStem() { return stem(); }
    public static boolean suppressVanillaTerrain() { return ownedActive() && current!=null && bool(current,"lodOnlyWorld",false); }

    /** Called at the actual production release RETURN, not an ambiguously ordered lifecycle callback. */
    public static void afterSkyRelease() {
        if(!active || output==null) return;
        try {
            JsonObject evidence=ResourceFootprint.snapshot(); evidence.add("gpuLifetime",R30GpuLifetime.snapshot(true));
            evidence.addProperty("stage","SkyWorldRenderer.release RETURN");
            evidence.addProperty("sequence",++releaseSequence);
            write(String.format(Locale.ROOT,"post-release-%03d.json",releaseSequence),evidence);
            LOG.info("[r30-probe] POST_RELEASE liveTracked={}",evidence.get("liveUniqueObjects"));
        } catch(Exception error) { LOG.error("[r30-probe] post-release observation failed",error); }
    }

    public static void afterFrame() {
        if(!active || finished || phaseStart==0 || stageDone || current==null) return;
        Minecraft mc=Minecraft.getInstance();
        try {
            var frame=SkyWorldRenderer.lastRoot();
            if(frame==null || !frame.root || frame.shadow || frame.level!=mc.level || frame.rootTransaction==lastTransaction) return;
            lastTransaction=frame.rootTransaction;
            long now=System.nanoTime();
            if(now>=measurementStart && now<measurementEnd && lastFrame>=measurementStart) {
                if(frameCount>=2_000_000) throw new IOException("Raw interval sample bound reached");
                if(frameCount==intervals.length) intervals=Arrays.copyOf(intervals,Math.min(2_000_000,intervals.length*2));
                intervals[frameCount++]=now-lastFrame;
                minimumBlocks=Math.min(minimumBlocks,frame.blocks()); maximumBlocks=Math.max(maximumBlocks,frame.blocks());
                measuredVertices+=frame.vertices();
            }
            lastFrame=now;
            if(now>=nextSample) {
                JsonObject sample=memory(); sample.addProperty("phase",name()); sample.addProperty("phaseIndex",phase);
                sample.addProperty("elapsedSeconds",(now-phaseStart)*1e-9); sample.addProperty("measurement",now>=measurementStart && now<measurementEnd);
                sample.addProperty("rootTransaction",frame.rootTransaction); sample.addProperty("skyBlocks",frame.blocks());
                sample.addProperty("skyVertices",frame.vertices()); sample.addProperty("finalResolved",frame.finalResolved);
                sample.add("resources",ResourceFootprint.snapshot()); sample.add("gpuLifetime",R30GpuLifetime.snapshot(false));
                if(FabricLoader.getInstance().isModLoaded("distanthorizons")) sample.add("dh",DhObserver.counters());
                samples.write(new Gson().toJson(sample)); samples.newLine(); samples.flush(); nextSample=now+1_000_000_000L;
            }
            int requestedFrames=(int)num(current,"captureFrames",0);
            if(requestedFrames<0 || requestedFrames>240) throw new IllegalArgumentException("Temporal frame bound 0..240");
            int interval=Math.max(1,(int)num(current,"captureEveryFrames",3));
            if(now>=measurementStart && now<measurementEnd && requestedFrames>0 && R30TemporalCapture.captured()<requestedFrames) {
                long gameTick=frame.level.getGameTime();
                int tickInterval=(int)num(current,"captureEveryGameTicks",0);
                boolean due=tickInterval>0 ? lastTemporalGameTick==Long.MIN_VALUE || gameTick-lastTemporalGameTick>=tickInterval
                        : temporalSourceFrames++%interval==0;
                if(due) { R30TemporalCapture.capture(frame,output,stem());lastTemporalGameTick=gameTick; }
            }
            if(now>=measurementEnd) {
                if(!R34EdgeStages.complete(frame)) return;
                finishPhase(mc,frame); stageDone=true; lastFrame=0;
            }
        } catch(Exception|LinkageError error) { fail(mc,error); }
    }

    private static void finishPhase(Minecraft mc,SkyWorldRenderer.Frame frame) throws Exception {
        JsonObject result=new JsonObject(); result.addProperty("name",name()); result.addProperty("index",phase);
        result.add("request",current.deepCopy()); result.add("serverGeometry",serverEvidence);
        result.addProperty("measurementFrames",frameCount); result.addProperty("frameIntervalMeaning","CPU wall time between complete root-frame GameRenderer.render TAIL callbacks; not a GPU timer");
        result.addProperty("minimumCollectedBlocks",minimumBlocks==Integer.MAX_VALUE ? 0 : minimumBlocks);
        result.addProperty("maximumCollectedBlocks",maximumBlocks); result.addProperty("meanCollectedVertices",frameCount==0 ? 0 : measuredVertices/(double)frameCount);
        result.add("frameTimeMilliseconds",distribution()); result.add("resources",ResourceFootprint.snapshot()); result.add("memory",memory());
        result.add("gpuLifetime",R30GpuLifetime.snapshot(true)); result.add("clientCommandResults",clientCommandResults.deepCopy());
        result.add("cloudState",cloudState()); result.add("temporalCapture",R30TemporalCapture.finish());
        result.add("environmentState",environmentState(frame));
        result.addProperty("width",mc.getWindow().getWidth()); result.addProperty("height",mc.getWindow().getHeight());
        result.addProperty("rasterScaleX",frame.rasterScale.x); result.addProperty("rasterScaleY",frame.rasterScale.y);
        result.addProperty("actualActiveWidth",frame.viewport[2]); result.addProperty("actualActiveHeight",frame.viewport[3]);
        result.addProperty("rootTransaction",frame.rootTransaction); result.addProperty("frameLevelGameTime",frame.level.getGameTime());
        result.add("camera",new Gson().toJsonTree(new double[]{frame.camera.x,frame.camera.y,frame.camera.z}));
        result.add("actualPlayer",new Gson().toJsonTree(new double[]{mc.player.getX(),mc.player.getY(),mc.player.getZ(),mc.player.getYRot(),mc.player.getXRot()}));
        result.add("worldProjectionColumnMajor",new Gson().toJsonTree(frame.projection.get(new float[16])));
        result.add("worldViewColumnMajor",new Gson().toJsonTree(frame.view.get(new float[16])));
        var collected=SkyWorldRenderer.Frame.class.getDeclaredMethod("collectedPositions"); collected.setAccessible(true);
        long[] collectedPositions=(long[])collected.invoke(frame); Arrays.sort(collectedPositions);
        result.add("actualCollectedSkyBlockPosLong",new Gson().toJsonTree(collectedPositions));
        for(String fieldName:new String[]{"distantDepthEnabled","distantDepthTexture","distantOpaqueReady","distantHdrReady","edgeHistoryUsed","edgeHistoryFrames","geometrySignature"}) {
            try { result.add(fieldName,new Gson().toJsonTree(frame.getClass().getField(fieldName).get(frame))); }
            catch(NoSuchFieldException absent) { result.addProperty(fieldName+"Available",false); }
        }
        result.add("privateStateAudit",R30StateAudit.summary());
        JsonArray glErrors=new JsonArray(); for(int i=0;i<16;i++) { int error=org.lwjgl.opengl.GL11.glGetError(); if(error==0)break; glErrors.add(error); }
        result.add("phaseEndGlErrors",glErrors); result.addProperty("glErrorScope","Sampled and consumed once at phase end, not a per-frame claim");
        result.addProperty("irisFinalHook",frame.irisFinalHook); result.addProperty("finalResolveCount",frame.finalResolveCount);
        if(FabricLoader.getInstance().isModLoaded("distanthorizons")) result.add("dh",DhObserver.snapshot(frame,output,stem()));
        result.addProperty("controlledVanillaTerrainSuppression",suppressVanillaTerrain());
        result.addProperty("rasterJitterX",frame.rasterJitter.x); result.addProperty("rasterJitterY",frame.rasterJitter.y);
        JsonObject references=new JsonObject();
        try(AutoCloseable ignored=glScope()) {
            try(NativeImage image=Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(output.resolve(stem()+".png")); }
            if(frame.floatCount>0) for(String fieldName:new String[]{"target","displayTarget"}) {
                var field=Class.forName("dev.yg.skyboxblock.client.VanillaSkyScene").getDeclaredField(fieldName); field.setAccessible(true);
                if(field.get(null) instanceof com.mojang.blaze3d.pipeline.RenderTarget target) {
                    String file=stem()+"-private-"+fieldName+".png";
                    try(NativeImage image=Screenshot.takeScreenshot(target)) { image.writeToFile(output.resolve(file)); }
                    JsonObject ref=new JsonObject(); ref.addProperty("file",file); ref.addProperty("width",target.width); ref.addProperty("height",target.height);
                    ref.addProperty("source","Actual same-frame production private sky canvas; never reconstructed from world RGB"); references.add(fieldName,ref);
                }
            }
        }
        result.add("privateSkyReferences",references);
        if(R34EdgeStages.required()) result.add("r34EdgeStages",R34EdgeStages.snapshot());
        JsonArray readbackErrors=new JsonArray(); for(int i=0;i<16;i++) { int error=org.lwjgl.opengl.GL11.glGetError(); if(error==0)break; readbackErrors.add(error); }
        result.add("diagnosticGlErrors",readbackErrors);
        Path raw=output.resolve(stem()+"-frame-nanoseconds.bin");
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw,StandardOpenOption.CREATE_NEW)))) {
            for(int i=0;i<frameCount;i++) out.writeLong(intervals[i]);
        }
        result.addProperty("rawFrameTimes",raw.getFileName().toString()); result.addProperty("rawFormat","signed int64 big-endian nanoseconds");
        write(stem()+".json",result); completed.add(result);
        LOG.info("[r30-probe] CAPTURED {} frames={} blocks={}..{}",name(),frameCount,result.get("minimumCollectedBlocks"),maximumBlocks);
    }

    /** Read actual production snapshots/uniforms; remains compatible with the frozen R34.2 control. */
    private static JsonObject environmentState(SkyWorldRenderer.Frame frame) throws Exception {
        JsonObject out=new JsonObject();
        if(frame.sky==null) return out;
        for(String name:new String[]{"skyColor","fogColor","cloudColor","sunrise","starBrightness","moonPhase","phase","rain","cloudOpacity"}) {
            try { out.add(name,new Gson().toJsonTree(frame.sky.getClass().getField(name).get(frame.sky))); }
            catch(NoSuchFieldException absent) { out.addProperty(name+"Available",false); }
        }
        Object config=Class.forName("dev.yg.skyboxblock.client.SkyboxConfig").getMethod("get").invoke(null);
        try {
            Object env=config.getClass().getMethod("environment").invoke(config);
            JsonObject configured=new JsonObject();
            for(String id:new String[]{"sky_color","fog_color","cloud_color","sunrise_sunset_color","fog_start_distance",
                    "fog_end_distance","sky_fog_end_distance","cloud_height","cloud_fog_end_distance",
                    "sun_angle","moon_angle","star_angle","star_brightness","moon_phase","has_sky_occluder"}) {
                boolean has=(boolean)env.getClass().getMethod("has",String.class).invoke(env,id);
                if(!has) { configured.addProperty(id,"auto"); continue; }
                Object value;
                if(id.endsWith("color")) value=env.getClass().getMethod("color",String.class,int.class).invoke(env,id,0);
                else if(id.equals("moon_phase")) value=env.getClass().getMethod("moonPhase",int.class).invoke(env,0);
                else if(id.equals("has_sky_occluder")) value=env.getClass().getMethod("flag",String.class,boolean.class).invoke(env,id,false);
                else value=env.getClass().getMethod("number",String.class,float.class).invoke(env,id,0F);
                configured.add(id,new Gson().toJsonTree(value));
            }
            out.add("configured",configured);
        } catch(NoSuchMethodException absent) { out.addProperty("environmentApiAvailable",false); }
        JsonObject gpu=new JsonObject();
        var field=Class.forName("dev.yg.skyboxblock.client.VanillaSkyScene").getDeclaredField("background");
        field.setAccessible(true);
        if(field.get(null) instanceof net.minecraft.client.renderer.ShaderInstance shader) {
            for(String name:new String[]{"FogStart","FogEnd"}) {
                int location=org.lwjgl.opengl.GL20.glGetUniformLocation(shader.getId(),name);
                if(location>=0) gpu.addProperty(name,org.lwjgl.opengl.GL20.glGetUniformf(shader.getId(),location));
            }
        }
        out.add("backgroundGpu",gpu);
        var starField=Class.forName("dev.yg.skyboxblock.client.VanillaSkyScene").getDeclaredField("stars");
        starField.setAccessible(true);
        if(starField.get(null) instanceof net.minecraft.client.renderer.ShaderInstance shader) {
            int location=org.lwjgl.opengl.GL20.glGetUniformLocation(shader.getId(),"DrawColor");
            if(location>=0) {
                var buffer=org.lwjgl.BufferUtils.createFloatBuffer(4);
                org.lwjgl.opengl.GL20.glGetUniformfv(shader.getId(),location,buffer);
                float[] values=new float[4];buffer.get(values);out.add("starsGpuDrawColor",new Gson().toJsonTree(values));
            }
        }
        return out;
    }
    private static int clientCommand(Minecraft mc,String command) throws Exception {
        var dispatcher=net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.getActiveDispatcher();
        if(dispatcher==null || mc.getConnection()==null) throw new IllegalStateException("Client command dispatcher unavailable");
        var source=(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource)(Object)mc.getConnection().getSuggestionsProvider();
        return dispatcher.execute(command,source);
    }
    private static JsonObject cloudState() throws Exception {
        JsonObject out=new JsonObject(); Class<?> type=Class.forName("dev.yg.skyboxblock.client.SkyboxConfig");
        Object config=type.getMethod("get").invoke(null);
        for(String name:new String[]{"vanillaCloudHeight","vanillaCloudFadeStart","vanillaCloudFadeEnd","clouds"}) {
            try { out.add(name,new Gson().toJsonTree(type.getMethod(name).invoke(config))); }
            catch(NoSuchMethodException absent) { out.addProperty(name+"Available",false); }
        }
        Class<?> renderer=Class.forName("dev.yg.skyboxblock.client.SkyVanillaClouds");
        out.addProperty("renderedCloudDraws",((Number)renderer.getMethod("renderedFrames").invoke(null)).intValue());
        var field=renderer.getDeclaredField("shader"); field.setAccessible(true);
        if(field.get(null) instanceof net.minecraft.client.renderer.ShaderInstance shader) {
            int program=shader.getId(); out.addProperty("program",program);
            for(String name:new String[]{"CloudFogStart","CloudFogEnd","CloudOpacity"}) {
                int location=org.lwjgl.opengl.GL20.glGetUniformLocation(program,name);
                if(location>=0) out.addProperty(name,org.lwjgl.opengl.GL20.glGetUniformf(program,location));
            }
            int location=org.lwjgl.opengl.GL20.glGetUniformLocation(program,"ProjMat");
            if(location>=0) {
                var buffer=org.lwjgl.BufferUtils.createFloatBuffer(16); org.lwjgl.opengl.GL20.glGetUniformfv(program,location,buffer);
                float[] values=new float[16]; buffer.get(values); out.add("actualGpuCloudProjectionColumnMajor",new Gson().toJsonTree(values));
            }
        }
        Path file=owner.resolve("config/skyboxblock.properties");
        if(Files.exists(file)) {
            Properties properties=new Properties(); try(var stream=Files.newInputStream(file)) { properties.load(stream); }
            JsonObject saved=new JsonObject();
            for(String key:properties.stringPropertyNames())
                if(key.startsWith("environment.") || java.util.Set.of("vanillaCloudHeight","vanillaCloudFadeStart","vanillaCloudFadeEnd","clouds").contains(key))
                    saved.addProperty(key,properties.getProperty(key));
            out.add("persistedProperties",saved);
        }
        return out;
    }
    private static AutoCloseable glScope() throws ReflectiveOperationException {
        var constructor=Class.forName("dev.yg.skyboxblock.client.SkyGlState").getDeclaredConstructor(int.class);
        constructor.setAccessible(true); return (AutoCloseable)constructor.newInstance(6);
    }

    private static JsonObject distribution() {
        JsonObject out=new JsonObject(); if(frameCount==0) { out.addProperty("status","NO_MEASUREMENTS"); return out; }
        long[] sorted=Arrays.copyOf(intervals,frameCount); Arrays.sort(sorted); double sum=0; for(long value:sorted) sum+=value;
        out.addProperty("mean",sum/frameCount/1e6); out.addProperty("p50",sorted[(frameCount-1)/2]/1e6);
        out.addProperty("p95",sorted[(int)Math.floor((frameCount-1)*.95)]/1e6);
        out.addProperty("p99",sorted[(int)Math.floor((frameCount-1)*.99)]/1e6); out.addProperty("max",sorted[frameCount-1]/1e6);
        out.addProperty("effectiveFps",frameCount/(sum/1e9)); return out;
    }

    private static JsonObject memory() {
        JsonObject out=new JsonObject(); out.addProperty("nanoTime",System.nanoTime());
        Runtime rt=Runtime.getRuntime(); out.addProperty("heapUsedBytes",rt.totalMemory()-rt.freeMemory());
        out.addProperty("heapCommittedBytes",rt.totalMemory()); out.addProperty("heapMaxBytes",rt.maxMemory());
        out.addProperty("nonHeapUsedBytes",ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        long count=0,time=0; for(GarbageCollectorMXBean gc:ManagementFactory.getGarbageCollectorMXBeans()) { count+=Math.max(0,gc.getCollectionCount()); time+=Math.max(0,gc.getCollectionTime()); }
        out.addProperty("gcCount",count); out.addProperty("gcTimeMillis",time);
        out.addProperty("liveThreads",ManagementFactory.getThreadMXBean().getThreadCount());
        out.addProperty("loadedClasses",ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
        JsonArray pools=new JsonArray(); for(BufferPoolMXBean pool:ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            JsonObject p=new JsonObject(); p.addProperty("name",pool.getName()); p.addProperty("count",pool.getCount());
            p.addProperty("usedBytes",pool.getMemoryUsed()); p.addProperty("capacityBytes",pool.getTotalCapacity()); pools.add(p);
        } out.add("bufferPools",pools);
        if(ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            out.addProperty("processCpuNanoseconds",os.getProcessCpuTime()); out.addProperty("processCpuLoad",os.getProcessCpuLoad());
            out.addProperty("processCommittedVirtualBytes",os.getCommittedVirtualMemorySize());
        }
        return out;
    }

    private static JsonObject environment() {
        JsonObject out=new JsonObject(); out.addProperty("schemaVersion",1); out.addProperty("ownedGameDir",owner.toString());
        out.addProperty("probeOnly",true); out.addProperty("legacyRegressionEnabled",Boolean.getBoolean("skyboxblock.regression"));
        out.addProperty("javaVersion",System.getProperty("java.version")); out.addProperty("processors",Runtime.getRuntime().availableProcessors());
        JsonArray mods=new JsonArray(); FabricLoader.getInstance().getAllMods().stream().sorted(Comparator.comparing(m->m.getMetadata().getId())).forEach(mod->{
            JsonObject entry=new JsonObject(); entry.addProperty("id",mod.getMetadata().getId()); entry.addProperty("version",mod.getMetadata().getVersion().getFriendlyString()); mods.add(entry);
        }); out.add("actualMods",mods); out.add("plan",plan.deepCopy()); return out;
    }

    private static void preWorld(Minecraft mc) {
        if(mc.screen==null) return;
        for(var child:mc.screen.children()) if(child instanceof net.minecraft.client.gui.components.Button button
                && button.getMessage().getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                && text.getKey().equals("selectWorld.backupJoinSkipButton")) { button.onPress(); return; }
    }
    private static void fail(Minecraft mc,Throwable error) {
        if(finished) return; finished=true;
        LOG.error("[r30-probe] FAILED",error);
        try { JsonObject failure=new JsonObject(); failure.addProperty("complete",false); failure.addProperty("phase",current==null ? "startup" : name());
            failure.addProperty("error",error.toString()); failure.add("completedPhases",completed); write("run-failure.json",failure); if(samples!=null)samples.flush(); }
        catch(Exception logging) { LOG.error("[r30-probe] failure evidence write",logging); }
    }
    private static void write(String file,JsonObject data) throws IOException {
        Files.writeString(output.resolve(file),GSON.toJson(data)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    private static String name() { return str(current,"name","phase-"+phase); }
    private static String stem() { return String.format(Locale.ROOT,"%02d-%s",phase,name()); }
    static String str(JsonObject o,String key,String fallback) { return o.has(key) ? o.get(key).getAsString() : fallback; }
    static double num(JsonObject o,String key,double fallback) { return o.has(key) ? o.get(key).getAsDouble() : fallback; }
    static boolean bool(JsonObject o,String key,boolean fallback) { return o.has(key) ? o.get(key).getAsBoolean() : fallback; }
}
