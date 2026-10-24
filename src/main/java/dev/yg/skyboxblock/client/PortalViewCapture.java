package dev.yg.skyboxblock.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/** Explicit owned-fixture observer only. No production path captures world RGB. */
public final class PortalViewCapture {
    private static final ArrayList<SkyWorldRenderer.Frame> VIEWS=new ArrayList<>();
    private static final Gson JSON=new Gson();
    private static long transaction=-1;
    private PortalViewCapture() {}
    private static String label() { return System.getProperty("skyboxfixture.captureScene",""); }
    private static Path ownedOutput() {
        if (!Boolean.getBoolean("skyboxfixture.enabled") || !label().matches("[0-9]{2}-[a-z-]+")) return null;
        Minecraft mc=Minecraft.getInstance();
        var server=mc.getSingleplayerServer();
        if (server==null || !server.getWorldData().getLevelName().equals("Skybox render fixture")) return null;
        try {
            Path actual=mc.gameDirectory.toPath().toRealPath();
            String declared=System.getProperty("skyboxfixture.gameDir","");
            if (declared.isBlank() || !actual.equals(Path.of(declared).toRealPath())) return null;
            boolean fixture=false;
            for (Path at=actual;at!=null;at=at.getParent()) if (at.getFileName()!=null
                    && at.getFileName().toString().equals("portal-fixture") && at.getParent()!=null
                    && at.getParent().getFileName().toString().equals("build")) fixture=true;
            if (!fixture) return null;
            Path requested=Path.of(System.getProperty("skyboxfixture.output",actual.resolve("results").toString())).toAbsolutePath().normalize();
            if (!requested.startsWith(actual) || requested.equals(actual)) return null;
            Path parent=requested;
            while (!Files.exists(parent)) parent=parent.getParent();
            if (!parent.toRealPath().startsWith(actual)) return null;
            Files.createDirectories(requested);
            return requested.toRealPath().startsWith(actual) ? requested : null;
        } catch (IOException exception) { throw new RuntimeException(exception); }
    }
    static void beginView(SkyWorldRenderer.Frame frame) {
        if (ownedOutput()==null) { VIEWS.clear(); transaction=-1; return; }
        if (transaction!=frame.rootTransaction) { VIEWS.clear(); transaction=frame.rootTransaction; }
        if (!frame.shadow && VIEWS.size()<64) VIEWS.add(frame);
    }
    static void privateScene(SkyWorldRenderer.Frame frame,RenderTarget target,boolean display) {
        Path output=ownedOutput(); if (output==null || frame.shadow) return;
        String file=label()+"-view-"+frame.viewId+(display ? "-display.png" : "-world.png");
        capture(target,output.resolve(file));
        if (display) frame.referenceDisplay=file; else frame.referenceWorld=file;
    }
    static void preView(SkyWorldRenderer.Frame frame,String stage) {
        Path output=ownedOutput(); if (output==null || frame.shadow) return;
        frame.viewImageStage=stage;
        frame.preViewImage=label()+"-view-"+frame.viewId+"-pre.png";
        capture(Minecraft.getInstance().getMainRenderTarget(),output.resolve(frame.preViewImage));
    }
    static void postView(SkyWorldRenderer.Frame frame,String stage) {
        Path output=ownedOutput(); if (output==null || frame.shadow) return;
        frame.viewImageStage=stage;
        frame.postViewImage=label()+"-view-"+frame.viewId+"-post.png";
        capture(Minecraft.getInstance().getMainRenderTarget(),output.resolve(frame.postViewImage));
    }
    private static void capture(RenderTarget target,Path destination) {
        try (SkyGlState ignored=new SkyGlState(6); PackState pack=new PackState();
             NativeImage image=Screenshot.takeScreenshot(target)) {
            image.writeToFile(destination);
        } catch (IOException exception) { throw new RuntimeException(exception); }
    }
    /** Reflected by the independent harness only when its explicit capture transaction is armed. */
    public static JsonArray describe() {
        JsonArray result=new JsonArray();
        if (ownedOutput()==null) return result;
        for (var frame:VIEWS) {
            JsonObject data=new JsonObject();
            data.addProperty("viewId",frame.viewId); data.addProperty("rootTransaction",frame.rootTransaction);
            data.addProperty("root",frame.root); data.addProperty("portal",frame.portal); data.addProperty("shadow",frame.shadow);
            data.addProperty("dimension",frame.level.dimension().location().toString());
            data.add("camera",JSON.toJsonTree(new double[]{frame.camera.x,frame.camera.y,frame.camera.z}));
            data.add("projection",JSON.toJsonTree(frame.projection.get(new float[16])));
            data.add("view",JSON.toJsonTree(frame.view.get(new float[16])));
            data.add("rasterScale",JSON.toJsonTree(new float[]{frame.rasterScale.x,frame.rasterScale.y}));
            data.add("rasterJitter",JSON.toJsonTree(new float[]{frame.rasterJitter.x,frame.rasterJitter.y}));
            data.add("viewport",JSON.toJsonTree(frame.viewport)); data.add("targetViewport",JSON.toJsonTree(frame.targetViewport));
            data.addProperty("blocks",frame.blocks()); data.addProperty("vertices",frame.vertices());
            data.addProperty("drawn",frame.drawn); data.addProperty("irisFinalHook",frame.irisFinalHook);
            data.addProperty("finalResolved",frame.finalResolved); data.addProperty("finalResolveCount",frame.finalResolveCount);
            data.addProperty("referenceWorld",frame.referenceWorld); data.addProperty("referenceDisplay",frame.referenceDisplay);
            data.addProperty("preViewImage",frame.preViewImage); data.addProperty("postViewImage",frame.postViewImage);
            data.addProperty("imageStage",frame.viewImageStage);
            data.addProperty("itrpMaterialWrites",frame.itrpMaterialWrites); data.addProperty("itrpBackgroundInjected",frame.itrpBackgroundInjected);
            data.addProperty("itrpInjectionCount",frame.itrpInjectionCount); data.addProperty("bridgeFailure",frame.itrpBridgeFailure);
            result.add(data);
        }
        return result;
    }
    static void release() { VIEWS.clear(); transaction=-1; }
    private static final class PackState implements AutoCloseable {
        private final int buffer=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        private final int[] keys={GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_PIXELS,
                GL11.GL_PACK_SKIP_ROWS,GL12.GL_PACK_IMAGE_HEIGHT,GL12.GL_PACK_SKIP_IMAGES};
        private final int[] values=new int[keys.length];
        PackState() {
            for (int i=0;i<keys.length;i++) { values[i]=GL11.glGetInteger(keys[i]); GL11.glPixelStorei(keys[i],i==0 ? 1 : 0); }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
        }
        @Override public void close() {
            for (int i=0;i<keys.length;i++) com.mojang.blaze3d.platform.GlStateManager._pixelStore(keys[i],values[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,buffer);
        }
    }
}
