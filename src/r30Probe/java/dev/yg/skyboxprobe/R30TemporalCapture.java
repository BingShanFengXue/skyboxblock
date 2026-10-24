package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded owned diagnostic capture. PNG compression stays off the render thread whenever the queue has room. */
public final class R30TemporalCapture {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicInteger callerRuns=new AtomicInteger();
    private static final ThreadPoolExecutor WRITER=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),task->{
        Thread thread=new Thread(task,"skybox-r30-frame-writer"); thread.setDaemon(true); return thread;
    },(task,pool)->{ callerRuns.incrementAndGet(); task.run(); });
    private static final ArrayList<Future<?>> pending=new ArrayList<>();
    private static int captured;
    private R30TemporalCapture() {}
    public static void begin() { if(!pending.isEmpty()) throw new IllegalStateException("Previous temporal writes not drained");captured=0;callerRuns.set(0); }
    public static int captured() { return captured; }
    public static void capture(SkyWorldRenderer.Frame frame,Path output,String stem) throws Exception {
        if(!R30Probe.ownerValidated()) return;
        Path directory=output.resolve("temporal");Files.createDirectories(directory);
        int index=captured++;String file=stem+String.format(Locale.ROOT,"-frame-%04d",index);
        long before=System.nanoTime(); NativeImage image;
        var constructor=Class.forName("dev.yg.skyboxblock.client.SkyGlState").getDeclaredConstructor(int.class);constructor.setAccessible(true);
        try(AutoCloseable ignored=(AutoCloseable)constructor.newInstance(6)) { image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget()); }
        JsonObject metadata=new JsonObject();metadata.addProperty("index",index);metadata.addProperty("rootTransaction",frame.rootTransaction);
        metadata.addProperty("captureStartNanoTime",before);metadata.addProperty("readbackFinishedNanoTime",System.nanoTime());
        metadata.addProperty("width",image.getWidth());metadata.addProperty("height",image.getHeight());
        metadata.addProperty("skyBlocks",frame.blocks());metadata.addProperty("skyVertices",frame.vertices());
        metadata.addProperty("rasterScaleX",frame.rasterScale.x);metadata.addProperty("rasterScaleY",frame.rasterScale.y);
        metadata.addProperty("rasterJitterX",frame.rasterJitter.x);metadata.addProperty("rasterJitterY",frame.rasterJitter.y);
        metadata.addProperty("jitterBitsX",String.format(Locale.ROOT,"%08x",Float.floatToRawIntBits(frame.rasterJitter.x)));
        metadata.addProperty("jitterBitsY",String.format(Locale.ROOT,"%08x",Float.floatToRawIntBits(frame.rasterJitter.y)));
        metadata.add("camera",GSON.toJsonTree(new double[]{frame.camera.x,frame.camera.y,frame.camera.z}));
        metadata.add("projectionColumnMajor",GSON.toJsonTree(frame.projection.get(new float[16])));
        metadata.add("viewColumnMajor",GSON.toJsonTree(frame.view.get(new float[16])));
        metadata.addProperty("irisFinalHook",frame.irisFinalHook);metadata.addProperty("finalResolveCount",frame.finalResolveCount);
        metadata.addProperty("image",file+".png");
        for(String fieldName:new String[]{"distantDepthEnabled","distantDepthTexture","distantOpaqueReady","distantHdrReady","edgeHistoryUsed","edgeHistoryFrames","geometrySignature"}) {
            try { metadata.add(fieldName,GSON.toJsonTree(frame.getClass().getField(fieldName).get(frame))); }
            catch(NoSuchFieldException absent) { metadata.addProperty(fieldName+"Available",false); }
        }
        pending.add(WRITER.submit(()->{
            try(image) {
                image.writeToFile(directory.resolve(file+".png"));
                metadata.addProperty("encodedNanoTime",System.nanoTime());
                Files.writeString(directory.resolve(file+".json"),GSON.toJson(metadata)+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            } catch(Exception error) { throw new CompletionException(error); }
        }));
    }
    public static JsonObject finish() throws Exception {
        for(Future<?> task:pending)task.get();pending.clear();
        JsonObject out=new JsonObject();out.addProperty("capturedFrames",captured);out.addProperty("queueCapacity",8);
        out.addProperty("encoderThreads",2);out.addProperty("renderThreadBackpressureCount",callerRuns.get());
        out.addProperty("notPerformanceBenchmark",true);
        out.addProperty("overhead","Synchronous GPU color readback, bounded asynchronous PNG compression; frame pacing and jitter bits are recorded, no unprofiled FPS claim");return out;
    }
    public static void close() throws Exception {
        finish();WRITER.shutdown();if(!WRITER.awaitTermination(60,TimeUnit.SECONDS))throw new IllegalStateException("Temporal writer did not stop");
    }
}
