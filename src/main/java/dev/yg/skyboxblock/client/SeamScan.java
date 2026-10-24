package dev.yg.skyboxblock.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Explicit fixture-only render-frame scan. Never installed as a production readback path. */
public final class SeamScan {
    private static final boolean REQUESTED = Boolean.getBoolean("skyboxblock.regression")
            && "seamscan".equals(System.getProperty("skyboxblock.regression.suite"));
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final float[] SCREEN = {-1,-1,0, 3,-1,0, -1,3,0};
    private static final SkyMesh SCREEN_MESH = new SkyMesh();
    private static final double[][] WALL = {{-10,140,6},{11,151,7}};
    private static final double[][] ROOF = {{-10,151,-5},{11,152,7}};
    private static final List<Stage> STAGES = new ArrayList<>();
    private static final JsonArray RESULTS = new JsonArray();
    private static ShaderInstance shader;
    private static TextureTarget target;
    private static java.nio.ByteBuffer textureRead;
    private static Path gameDir, output;
    private static boolean registered, rejected, finished, calibrated, stageComplete, saveNext;
    private static int tickCount, settleTicks, stageTicks, finishTicks, stageIndex = -1, totalFrames, stageTotalFrames;
    private static int measuredFrames, driftFrames, stableWait, rawMax, legacyMax, mismatchMax, sourceMax, stateFailures;
    private static int[] minimumMask, maximumError;
    private static boolean[] interior;
    private static final Set<String> JITTERS = new HashSet<>();
    private static JsonArray jitterValues;
    private static JsonObject firstMeta;
    private static Capture previous;
    private static int savedAnomalies;
    private static double eyeX, eyeY, eyeZ, baseEyeY;
    private static float yaw = -7, pitch = -12;
    private static String terminalFailure;

    private record Stage(String shape, int phase, int width, int height, boolean near) {
        String name() { return String.format(Locale.ROOT, "%s-%s-%dx%d-p%02d", shape,
                near ? "near" : "normal", width, height, phase); }
        double[][][] bounds() { return shape.equals("fold") ? new double[][][]{WALL, ROOF} : new double[][][]{WALL}; }
    }
    private record Capture(NativeImage mask, NativeImage sky, NativeImage actual, JsonObject meta)
            implements AutoCloseable {
        @Override public void close() { mask.close(); sky.close(); actual.close(); }
    }

    private SeamScan() {}
    public static boolean isActive() { return REQUESTED && registered && !rejected && !finished; }
    public static boolean enabled() { return REQUESTED; }
    public static void setShader(ShaderInstance value) { shader = value; }

    public static void register() {
        if (!REQUESTED || registered) return;
        registered = true;
        for (int[] size : new int[][]{{1280,800},{1786,1053}})
            for (String shape : List.of("flat", "fold"))
                for (int phase = 0; phase < 16; phase++)
                    STAGES.add(new Stage(shape, phase, size[0], size[1], false));
        if (Boolean.getBoolean("skyboxblock.regression.seamscanNear"))
            for (String shape : List.of("flat", "fold")) for (int phase = 0; phase < 16; phase++)
                STAGES.add(new Stage(shape, phase, 1280,800,true));
        ClientTickEvents.END_CLIENT_TICK.register(SeamScan::tick);
    }

    /** Guard before every command, readback, camera write and optional fixture exit. */
    private static boolean ownsFixture(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        return !rejected && mc.player != null && mc.level != null && server != null
                && "Skybox render fixture".equals(server.getWorldData().getLevelName())
                && gameDir != null && mc.gameDirectory.toPath().toAbsolutePath().normalize().equals(gameDir);
    }
    private static boolean initializeGuard(Minecraft mc) {
        try {
            Path real = mc.gameDirectory.toPath().toRealPath();
            Path ancestor = real;
            while (ancestor != null && !(ancestor.getFileName() != null
                    && ancestor.getFileName().toString().equals("render-regression")
                    && ancestor.getParent() != null && ancestor.getParent().getFileName().toString().equals("build")))
                ancestor = ancestor.getParent();
            if (ancestor == null) throw new IOException("gameDir outside build/render-regression");
            // Require the actual supplied path to be canonical, not a symlink alias to another instance.
            gameDir = real;
            if (!ownsFixture(mc)) throw new IOException("not the named isolated fixture world");
            Path requested = Path.of(System.getProperty("skyboxblock.regression.output",
                    gameDir.resolve("results").toString())).toAbsolutePath().normalize();
            if (!requested.startsWith(gameDir)) throw new IOException("output outside fixture gameDir");
            Path existing=requested;
            while(existing!=null&&!Files.exists(existing))existing=existing.getParent();
            if(existing==null||!existing.toRealPath().startsWith(gameDir))throw new IOException("output ancestor resolves outside fixture");
            Files.createDirectories(requested);
            if (!requested.toRealPath().startsWith(gameDir)) throw new IOException("output resolves outside fixture");
            output = requested.resolve("seamscan");
            if (Files.exists(output)) throw new IOException("seamscan output already exists; use a fresh fixture label");
            Files.createDirectory(output);
            return true;
        } catch (IOException error) {
            rejected = true;
            SkyboxBlockMod.LOGGER.error("[skybox-seamscan] GUARD FAILED; no commands/exit: {}", error.toString());
            return false;
        }
    }

    private static void tick(Minecraft mc) {
        if (!REQUESTED || rejected || mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
        if (gameDir == null && !initializeGuard(mc)) return;
        if (!ownsFixture(mc)) { rejected = true; return; }
        if (finished) {
            if (++finishTicks == 30 && Boolean.getBoolean("skyboxblock.regression.exit") && ownsFixture(mc)) mc.stop();
            return;
        }
        mc.options.pauseOnLostFocus = false;
        if (mc.screen != null) mc.setScreen(null);
        if (++tickCount < 60) return;
        if (stageIndex < 0) {
            System.setProperty("skyboxblock.regression.freezeClouds", "true");
            mc.options.hideGui = true;
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            command(mc,"gamemode spectator");
            command(mc,"gamerule doDaylightCycle false");
            command(mc,"gamerule doWeatherCycle false");
            command(mc,"weather clear");
            command(mc,"time set day");
            nextStage(mc);
        } else if (stageComplete) {
            if (stageIndex + 1 == STAGES.size()) { finish(mc, null); return; }
            nextStage(mc);
        }
        if (++stageTicks > 800) { finish(mc,"stage watchdog: no usable render frames"); return; }
        if (settleTicks > 0) settleTicks--;
        lockCamera();
    }

    /** Main calls this at GameRenderer.renderLevel HEAD, before camera matrices are formed. */
    public static void lockCamera() {
        Minecraft mc = Minecraft.getInstance();
        if (!isActive() || stageIndex < 0 || !ownsFixture(mc)) return;
        var p = mc.player;
        double feet = eyeY - p.getEyeHeight();
        p.setPos(eyeX, feet, eyeZ);
        p.xo = eyeX; p.yo = feet; p.zo = eyeZ;
        p.setDeltaMovement(Vec3.ZERO);
        p.setYRot(yaw); p.yRotO = yaw; p.yHeadRot = yaw; p.yHeadRotO = yaw;
        p.setXRot(pitch); p.xRotO = pitch;
    }
    private static void command(Minecraft mc, String text) {
        if (!ownsFixture(mc)) throw new IllegalStateException("fixture ownership lost before command");
        mc.player.connection.sendCommand(text);
    }
    private static void teleport(Minecraft mc) {
        command(mc,String.format(Locale.ROOT,"tp @s %.9f %.9f %.9f %.7f %.7f",
                eyeX, eyeY-mc.player.getEyeHeight(), eyeZ, yaw, pitch));
        lockCamera();
    }
    private static void nextStage(Minecraft mc) {
        closePrevious();
        Stage old = stageIndex >= 0 ? STAGES.get(stageIndex) : null;
        Stage s = STAGES.get(++stageIndex);
        boolean changedShape = old == null || !old.shape.equals(s.shape);
        if (changedShape) {
            command(mc,"fill -12 138 -7 12 154 9 minecraft:air");
            command(mc,"fill -11 139 8 11 153 8 minecraft:red_concrete");
            command(mc,"fill -10 140 6 10 150 6 skyboxblock:sky_block");
            if (s.shape.equals("fold")) {
                command(mc,"fill -10 153 -5 10 153 7 minecraft:magenta_concrete");
                command(mc,"fill -10 151 -5 10 151 6 skyboxblock:sky_block");
            }
        }
        if (old == null || old.width != s.width || old.height != s.height)
            GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), s.width, s.height);
        eyeX = .123457; baseEyeY = s.near ? 149.123457 : 146.234568;
        eyeY = baseEyeY; eyeZ = s.near ? 4.125731 : -2.789123;
        calibrated = false; stageComplete = false; saveNext = false;
        settleTicks = changedShape ? 30 : 5; stageTicks = 0; stageTotalFrames = 0;
        measuredFrames = driftFrames = stableWait = rawMax = legacyMax = mismatchMax = sourceMax = savedAnomalies = stateFailures = 0;
        minimumMask = maximumError = null; interior = null; firstMeta = null;
        JITTERS.clear(); jitterValues = new JsonArray();
        teleport(mc);
        SkyboxBlockMod.LOGGER.info("[skybox-seamscan] stage {}/{} {}",stageIndex+1,STAGES.size(),s.name());
    }

    private static double clip(float[] m, int row, double x, double y, double z) {
        return m[row]*x + m[4+row]*y + m[8+row]*z + m[12+row];
    }
    /** Solve an exact 1/16 display-pixel Y shift at one shared grid edge using actual P*V. */
    private static double phaseOffset(SkyWorldRenderer.Frame f, int height, int phase) {
        float[] m = new Matrix4f(f.projection).mul(f.view).get(new float[16]);
        double x = -eyeX, y = 148-baseEyeY, z = 6-eyeZ;
        double cy = clip(m,1,x,y,z), cw = clip(m,3,x,y,z);
        double wanted = cy/cw - 2.0*(phase/16.0)/height;
        double dy = -m[5], dw = -m[7];
        double delta = (wanted*cw-cy)/(dy-wanted*dw);
        if (!Double.isFinite(delta) || Math.abs(delta) > .2) throw new IllegalStateException("bad phase calibration");
        return delta;
    }

    /** Called after the real final resolve, only for root views. Does not change final output. */
    public static void inspect(SkyWorldRenderer.Frame frame, int skyTexture, int worldDepthTexture,
                               int depthWidth, int depthHeight) {
        Minecraft mc = Minecraft.getInstance();
        if (!isActive() || stageIndex < 0 || stageComplete || !ownsFixture(mc)
                || frame.portal || frame.shadow) return;
        totalFrames++; stageTotalFrames++;
        if (settleTicks > 0) return;
        Stage s = STAGES.get(stageIndex);
        int w = mc.getMainRenderTarget().width, h = mc.getMainRenderTarget().height;
        if (measuredFrames >= 180) { completeStage(mc,"render-frame cap before 23 distinct jitter states"); return; }
        measuredFrames++;
        if (w != s.width || h != s.height || shader == null || worldDepthTexture <= 0) {
            driftFrames++; return;
        }
        if (!calibrated) {
            eyeY = baseEyeY + phaseOffset(frame,h,s.phase);
            calibrated = true; stableWait = 4;
            teleport(mc); return;
        }
        if (stableWait-- > 0 || frame.camera.distanceTo(new Vec3(eyeX,eyeY,eyeZ)) > .00005) {
            driftFrames++; return;
        }
        String jitterKey = Integer.toHexString(Float.floatToRawIntBits(frame.rasterJitter.x)) + ":"
                + Integer.toHexString(Float.floatToRawIntBits(frame.rasterJitter.y));
        boolean newJitter = JITTERS.add(jitterKey);
        if (!newJitter && !saveNext) return;
        if (newJitter) {
            JsonObject jitter = new JsonObject();
            jitter.addProperty("key",jitterKey);
            jitter.add("vector",JSON.toJsonTree(new float[]{frame.rasterJitter.x,frame.rasterJitter.y}));
            jitter.addProperty("projectionHash",Arrays.hashCode(frame.projection.get(new float[16])));
            jitter.addProperty("viewHash",Arrays.hashCode(frame.view.get(new float[16])));
            jitter.add("camera",JSON.toJsonTree(new double[]{frame.camera.x,frame.camera.y,frame.camera.z}));
            jitterValues.add(jitter);
        }
        int initialError = GL11.glGetError();
        Boolean mainBefore = ModCompat.irisMainBound(), overrideBefore = ModCompat.irisOverridesShaders();
        Capture capture;
        try (SkyGlState scope = new SkyGlState(6)) {
            NativeImage actual = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            NativeImage sky = readTexture(skyTexture,w,h);
            if (target == null || target.width != w || target.height != h) {
                if (target != null) target.destroyBuffers();
                target = new TextureTarget(w,h,true,Minecraft.ON_OSX);
            }
            target.setClearColor(0,0,0,1); target.clear(Minecraft.ON_OSX); target.bindWrite(true);
            SkyGlState.opaqueState(); scope.clearSamplers();
            RenderSystem.disableDepthTest(); GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthMask(false); GL11.glDepthMask(false);
            GL11.glDepthRange(0,1);
            GL20.glDrawBuffers(new int[]{org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0});
            shader.setSampler("WorldDepth",worldDepthTexture);
            shader.getUniform("SkyViewport").set(0F,0F,(float)w,(float)h);
            shader.getUniform("DepthUvTransform").set(frame.rasterScale.x,frame.rasterScale.y,
                    (float)(frame.viewport[0]-frame.targetViewport[0])/depthWidth+frame.rasterJitter.x*.5F,
                    (float)(frame.viewport[1]-frame.targetViewport[1])/depthHeight+frame.rasterJitter.y*.5F);
            shader.getUniform("RasterScale").set(1F,1F);
            shader.getUniform("RasterJitter").set(0F,0F);
            shader.getUniform("ClippingEnabled").set(0);
            shader.getUniform("ClipPlane").set(0F,0F,0F,1F);
            shader.getUniform("WorldProjection").set(new Matrix4f());
            shader.getUniform("WorldView").set(new Matrix4f());
            shader.getUniform("Mode").set(0);
            RenderSystem.disableCull(); GL11.glDisable(GL11.GL_CULL_FACE);
            SCREEN_MESH.draw(shader,SCREEN,SCREEN.length,false);
            shader.getUniform("WorldProjection").set(frame.projection);
            shader.getUniform("WorldView").set(frame.view);
            shader.getUniform("Mode").set(1);
            RenderSystem.enableCull(); GL11.glEnable(GL11.GL_CULL_FACE);
            RenderSystem.enableBlend(); GL11.glEnable(GL11.GL_BLEND);
            com.mojang.blaze3d.platform.GlStateManager._blendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE);
            GL14.glBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_MAX,GL14.GL_MAX);
            SkyWorldRenderer.drawDiagnosticFaces(frame,shader);
            NativeImage mask = Screenshot.takeScreenshot(target);
            JsonObject meta = metadata(frame,s,w,h,worldDepthTexture,depthWidth,depthHeight);
            capture = new Capture(mask,sky,actual,meta);
        }
        capture.meta.addProperty("initialGlError",initialError);
        capture.meta.addProperty("finalGlError",GL11.glGetError());
        capture.meta.add("irisMainBoundBefore",JSON.toJsonTree(mainBefore));
        capture.meta.add("irisMainBoundAfter",JSON.toJsonTree(ModCompat.irisMainBound()));
        capture.meta.add("irisOverridesBefore",JSON.toJsonTree(overrideBefore));
        capture.meta.add("irisOverridesAfter",JSON.toJsonTree(ModCompat.irisOverridesShaders()));
        if (initialError != 0 || capture.meta.get("finalGlError").getAsInt() != 0
                || !java.util.Objects.equals(mainBefore,ModCompat.irisMainBound())
                || !java.util.Objects.equals(overrideBefore,ModCompat.irisOverridesShaders())) stateFailures++;
        if (interior == null) {
            interior = erode(expected(frame,w,h,s.bounds()),w,h,3);
            minimumMask = new int[w*h]; Arrays.fill(minimumMask,0xFF00FFFF);
            maximumError = new int[w*h]; Arrays.fill(maximumError,0xFF000000);
            firstMeta = capture.meta.deepCopy();
        }
        int[] counts = analyze(capture,w,h);
        rawMax = Math.max(rawMax,counts[0]); legacyMax = Math.max(legacyMax,counts[1]);
        mismatchMax = Math.max(mismatchMax,counts[2]); sourceMax = Math.max(sourceMax,counts[3]);
        boolean anomaly = counts[0]+counts[1]+counts[2]+counts[3] > 0 || stateFailures > 0;
        try {
            if (saveNext) { save(capture,"neighbor-after"); saveNext = false; }
            if (anomaly && savedAnomalies == 0) {
                if (previous != null) save(previous,"neighbor-before");
                save(capture,"first-anomaly"); savedAnomalies++; saveNext = true;
            }
        } catch (IOException error) { capture.close(); finish(mc,"capture IO: "+error); return; }
        closePrevious(); previous = capture;
        if (JITTERS.size() >= 23 && !saveNext) completeStage(mc,null);
    }

    private static NativeImage readTexture(int texture, int width, int height) {
        SkyGlState.active(GL13.GL_TEXTURE0); SkyGlState.bindTexture(texture);
        if(textureRead==null||textureRead.capacity()<width*height*4)textureRead=BufferUtils.createByteBuffer(width*height*4);
        var pixels = textureRead.clear().order(ByteOrder.LITTLE_ENDIAN);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        NativeImage image = new NativeImage(width,height,false);
        for (int y=0;y<height;y++) for (int x=0;x<width;x++)
            image.setPixelRGBA(x,height-1-y,pixels.getInt((y*width+x)*4));
        return image;
    }
    private static JsonObject metadata(SkyWorldRenderer.Frame f, Stage s, int w, int h, int depth, int dw, int dh) {
        JsonObject m = new JsonObject();
        m.addProperty("stage",stageIndex); m.addProperty("name",s.name()); m.addProperty("shape",s.shape);
        m.addProperty("phase",s.phase); m.addProperty("requestedPixelPhase",s.phase/16.0);
        m.addProperty("near",s.near); m.addProperty("width",w); m.addProperty("height",h);
        m.addProperty("frame",totalFrames); m.addProperty("stageRenderFrame",measuredFrames);
        m.addProperty("subpixelBits",GL11.glGetInteger(GL11.GL_SUBPIXEL_BITS));
        m.addProperty("negativeSeams",Boolean.getBoolean("skyboxblock.regression.negativeSeams"));
        m.addProperty("spectator",Minecraft.getInstance().player.isSpectator());
        m.addProperty("hideGui",Minecraft.getInstance().options.hideGui);
        m.addProperty("worldName","Skybox render fixture");
        m.addProperty("gameDir",gameDir.toString());
        m.addProperty("worldDepthTexture",depth); m.addProperty("depthWidth",dw); m.addProperty("depthHeight",dh);
        m.add("bounds",JSON.toJsonTree(s.bounds()));
        m.add("camera",JSON.toJsonTree(new double[]{f.camera.x,f.camera.y,f.camera.z}));
        m.add("baseCamera",JSON.toJsonTree(new double[]{eyeX,baseEyeY,eyeZ}));
        m.add("gridReference",JSON.toJsonTree(new double[]{0,148,6}));
        m.add("projection",JSON.toJsonTree(f.projection.get(new float[16])));
        m.add("view",JSON.toJsonTree(f.view.get(new float[16])));
        m.add("inverseDisplayViewProjection",JSON.toJsonTree(new Matrix4f(f.projection).mul(f.view).invert().get(new float[16])));
        m.add("rasterScale",JSON.toJsonTree(new float[]{f.rasterScale.x,f.rasterScale.y}));
        m.add("rasterJitter",JSON.toJsonTree(new float[]{f.rasterJitter.x,f.rasterJitter.y}));
        m.add("targetViewport",JSON.toJsonTree(f.targetViewport)); m.add("activeViewport",JSON.toJsonTree(f.viewport));
        return m;
    }
    private static int maxDifference(int a, int b) {
        return Math.max(Math.abs((a&255)-(b&255)),Math.max(Math.abs((a>>>8&255)-(b>>>8&255)),Math.abs((a>>>16&255)-(b>>>16&255))));
    }
    private static int[] analyze(Capture c, int w, int h) {
        int raw=0, legacy=0, mismatch=0, dark=0, samples=0;
        JsonArray locations = new JsonArray();
        for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
            int i=y*w+x, mask=c.mask.getPixelRGBA(x,y);
            int old=minimumMask[i];
            minimumMask[i]=0xFF000000|Math.min(old&255,mask&255)
                    |Math.min(old>>>8&255,mask>>>8&255)<<8|Math.max(old>>>16&255,mask>>>16&255)<<16;
            int a=c.actual.getPixelRGBA(x,y), b=c.sky.getPixelRGBA(x,y), e=maximumError[i];
            maximumError[i]=0xFF000000|Math.max(e&255,Math.abs((a&255)-(b&255)))
                    |Math.max(e>>>8&255,Math.abs((a>>>8&255)-(b>>>8&255)))<<8
                    |Math.max(e>>>16&255,Math.abs((a>>>16&255)-(b>>>16&255)))<<16;
            if (!interior[i]) continue;
            samples++;
            boolean hole=(mask&255)<128, reject=!hole&&(mask>>>8&255)<128;
            boolean different=maxDifference(a,b)>2;
            boolean sourceDark=false;
            if (y>0&&y+1<h) {
                int above=c.sky.getPixelRGBA(x,y-1), below=c.sky.getPixelRGBA(x,y+1);
                int average=((above&255)+(below&255))/2|(((above>>>8&255)+(below>>>8&255))/2)<<8
                        |(((above>>>16&255)+(below>>>16&255))/2)<<16;
                sourceDark=maxDifference(above,below)<=8&&maxDifference(b,average)>=24
                        &&(b>>>16&255)<(average>>>16&255)-20;
            }
            if(hole)raw++; if(reject)legacy++; if(different)mismatch++; if(sourceDark)dark++;
            if ((hole||reject||different||sourceDark)&&locations.size()<24) {
                JsonObject point=new JsonObject(); point.addProperty("x",x); point.addProperty("y",y);
                point.addProperty("rawHole",hole); point.addProperty("legacyDepthReject",reject);
                point.addProperty("finalMismatch",different); point.addProperty("sourceDarkLine",sourceDark);
                locations.add(point);
            }
        }
        c.meta.addProperty("sampledPixels",samples); c.meta.addProperty("rawHole",raw);
        c.meta.addProperty("legacyDepthReject",legacy); c.meta.addProperty("finalMismatch",mismatch);
        c.meta.addProperty("sourceDarkLine",dark); c.meta.add("anomalyCoordinates",locations);
        return new int[]{raw,legacy,mismatch,dark};
    }
    private static boolean[] expected(SkyWorldRenderer.Frame f,int w,int h,double[][][] boxes) {
        float[] inverse=new Matrix4f(f.projection).mul(f.view).invert().get(new float[16]);
        boolean[] result=new boolean[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            double nx=2*(x+.5)/w-1,ny=1-2*(y+.5)/h;
            double aw=clip(inverse,3,nx,ny,-1),bw=clip(inverse,3,nx,ny,0);
            double dx=clip(inverse,0,nx,ny,0)/bw-clip(inverse,0,nx,ny,-1)/aw;
            double dy=clip(inverse,1,nx,ny,0)/bw-clip(inverse,1,nx,ny,-1)/aw;
            double dz=clip(inverse,2,nx,ny,0)/bw-clip(inverse,2,nx,ny,-1)/aw;
            for(double[][] box:boxes)if(hit(f.camera.x,f.camera.y,f.camera.z,dx,dy,dz,box)){result[y*w+x]=true;break;}
        }
        return result;
    }
    private static boolean hit(double ox,double oy,double oz,double dx,double dy,double dz,double[][] b){
        double enter=0,leave=Double.POSITIVE_INFINITY;
        for(int k=0;k<3;k++){
            double o=k==0?ox:k==1?oy:oz,d=k==0?dx:k==1?dy:dz;
            if(Math.abs(d)<1e-15){if(o<b[0][k]||o>b[1][k])return false;continue;}
            double a=(b[0][k]-o)/d,z=(b[1][k]-o)/d;
            enter=Math.max(enter,Math.min(a,z));leave=Math.min(leave,Math.max(a,z));
        }
        return leave>=enter&&leave>0;
    }
    private static boolean[] erode(boolean[] input,int w,int h,int times){
        boolean[] result=input;
        for(int n=0;n<times;n++){
            boolean[] next=new boolean[w*h];
            for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
                int i=y*w+x;next[i]=result[i]&&result[i-1]&&result[i+1]&&result[i-w]&&result[i+w];
            }
            result=next;
        }
        return result;
    }
    private static void save(Capture c,String kind)throws IOException{
        String prefix=String.format(Locale.ROOT,"s%03d-f%06d-%s",stageIndex,c.meta.get("frame").getAsInt(),kind);
        c.mask.writeToFile(output.resolve(prefix+"-mask.png"));
        c.sky.writeToFile(output.resolve(prefix+"-source.png"));
        c.actual.writeToFile(output.resolve(prefix+"-final.png"));
        JsonObject meta=c.meta.deepCopy();meta.addProperty("capturePrefix",prefix);
        writeJson(output.resolve(prefix+".json"),meta);
    }
    private static void writeJson(Path path,JsonObject object)throws IOException{
        Files.writeString(path,JSON.toJson(object)+"\n",java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
    private static void completeStage(Minecraft mc,String failure){
        if(stageComplete)return;
        Stage s=STAGES.get(stageIndex);
        JsonObject summary=firstMeta==null?new JsonObject():firstMeta.deepCopy();
        summary.addProperty("stage",stageIndex);summary.addProperty("name",s.name());
        summary.addProperty("shape",s.shape);summary.addProperty("phase",s.phase);
        summary.addProperty("near",s.near);summary.addProperty("width",s.width);summary.addProperty("height",s.height);
        summary.addProperty("renderFrames",measuredFrames);summary.addProperty("driftFrames",driftFrames);
        summary.addProperty("totalStageRenderFrames",stageTotalFrames);
        summary.addProperty("warmupRenderFrames",stageTotalFrames-measuredFrames);
        summary.addProperty("stateFailures",stateFailures);
        summary.addProperty("jitterCount",JITTERS.size());summary.add("jitterStates",jitterValues);
        summary.addProperty("complete",JITTERS.size()>=23&&failure==null&&firstMeta!=null);
        summary.addProperty("maxRawHole",rawMax);summary.addProperty("maxLegacyDepthReject",legacyMax);
        summary.addProperty("maxFinalMismatch",mismatchMax);summary.addProperty("maxSourceDarkLine",sourceMax);
        if(failure!=null)summary.addProperty("failure",failure);
        String prefix=String.format(Locale.ROOT,"stage-%03d",stageIndex);
        summary.addProperty("aggregatePrefix",prefix);
        try{
            if(minimumMask!=null){
                try(NativeImage mask=new NativeImage(s.width,s.height,false);NativeImage errors=new NativeImage(s.width,s.height,false)){
                    for(int y=0;y<s.height;y++)for(int x=0;x<s.width;x++){
                        int i=y*s.width+x;mask.setPixelRGBA(x,y,minimumMask[i]);errors.setPixelRGBA(x,y,maximumError[i]);
                    }
                    mask.writeToFile(output.resolve(prefix+"-mask-min.png"));
                    errors.writeToFile(output.resolve(prefix+"-final-error-max.png"));
                }
            }
            writeJson(output.resolve(prefix+".json"),summary);
            RESULTS.add(summary);stageComplete=true;closePrevious();
        }catch(IOException error){finish(mc,"summary IO: "+error);}
    }
    private static void closePrevious(){if(previous!=null){previous.close();previous=null;}}
    private static void finish(Minecraft mc,String failure){
        if(finished)return;
        finished=true; // Prevent recursive IO-error completion from re-entering finish().
        terminalFailure=failure;
        if(stageIndex>=0&&!stageComplete)completeStage(mc,failure==null?"incomplete stage":failure);
        finished=true;closePrevious();
        int complete=0;long raw=0,legacy=0,mismatch=0;
        for(var entry:RESULTS){var s=entry.getAsJsonObject();if(s.get("complete").getAsBoolean())complete++;
            raw+=s.get("maxRawHole").getAsLong();legacy+=s.get("maxLegacyDepthReject").getAsLong();mismatch+=s.get("maxFinalMismatch").getAsLong();}
        JsonObject summary=new JsonObject();summary.addProperty("schemaVersion",1);
        summary.addProperty("finished",true);summary.addProperty("plannedStages",STAGES.size());
        summary.addProperty("completedStages",complete);summary.addProperty("totalRenderFrames",totalFrames);
        summary.addProperty("phaseComplete",complete==STAGES.size());summary.addProperty("negativeSeams",Boolean.getBoolean("skyboxblock.regression.negativeSeams"));
        summary.addProperty("rawHoleStageMaxSum",raw);summary.addProperty("legacyDepthRejectStageMaxSum",legacy);
        summary.addProperty("finalMismatchStageMaxSum",mismatch);summary.add("stages",RESULTS);
        if(terminalFailure!=null)summary.addProperty("failure",terminalFailure);
        try{writeJson(output.resolve("seamscan-summary.json"),summary);}catch(IOException error){SkyboxBlockMod.LOGGER.error("[skybox-seamscan] report failed",error);}
        try(SkyGlState state=new SkyGlState(6)){if(target!=null)target.destroyBuffers();target=null;SCREEN_MESH.close();}
        SkyboxBlockMod.LOGGER.info("[skybox-seamscan] FINISHED stages={}/{} frames={} phaseComplete={} rawHole={} legacyDepthReject={} finalMismatch={} failure={}",
                complete,STAGES.size(),totalFrames,complete==STAGES.size(),raw,legacy,mismatch,terminalFailure);
    }
}
