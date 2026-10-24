package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.ByteOrder;
import java.nio.file.*;

/** Opt-in stage readback in the separate owned probe; never bundled with production. */
public final class R34EdgeStages {
    private static long transaction=-1;
    private static JsonObject stages=new JsonObject();
    private R34EdgeStages() {}
    public static boolean required() { return Boolean.getBoolean("skyboxblock.r34.edgeStages") && R30Probe.ownerValidated(); }
    public static boolean complete(SkyWorldRenderer.Frame frame) {
        return !required() || transaction==frame.rootTransaction && stages.has("opaque") && stages.has("pre") && stages.has("post");
    }
    public static JsonObject snapshot() { return stages.deepCopy(); }
    public static void capture(String stage) {
        if(!required() || !R30Probe.edgeCaptureDue()) return;
        var frame=SkyWorldRenderer.lastRoot();
        if(frame==null || !frame.root || frame.shadow) return;
        if(transaction!=frame.rootTransaction) { transaction=frame.rootTransaction;stages=new JsonObject(); }
        // A native view may reach the guarded resolve fallback again; retain the FIRST real stage.
        if(stages.has(stage)) return;
        try {
            Path output=R30Probe.edgeOutput();String prefix=R30Probe.edgeStem()+"-tx-"+transaction+"-"+stage;
            JsonObject data=new JsonObject();data.addProperty("transaction",transaction);data.addProperty("stage",stage);
            data.addProperty("surfaceLayerUsed",frame.surfaceLayerUsed);data.addProperty("finalResolved",frame.finalResolved);
            int initial=GL11.glGetError();data.addProperty("initialGlError",initial);
            var constructor=Class.forName("dev.yg.skyboxblock.client.SkyGlState").getDeclaredConstructor(int.class);constructor.setAccessible(true);
            try(AutoCloseable ignored=(AutoCloseable)constructor.newInstance(6)) {
                String color=prefix+".png";
                try(NativeImage image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) { image.writeToFile(output.resolve(color)); }
                data.addProperty("color",color);
                if(frame.surfaceLayerUsed) {
                    var field=Class.forName("dev.yg.skyboxblock.client.SkySurfaceLayer").getDeclaredField("texture");field.setAccessible(true);int texture=field.getInt(null);
                    if(texture>0 && GL11.glIsTexture(texture)) {
                        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
                        int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
                        var pixels=BufferUtils.createByteBuffer(w*h*4).order(ByteOrder.LITTLE_ENDIAN);GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
                        String layer=prefix+"-layer.png";
                        try(NativeImage image=new NativeImage(w,h,false)) {
                            for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setPixelRGBA(x,h-1-y,pixels.getInt((y*w+x)*4));
                            image.writeToFile(output.resolve(layer));
                        }
                        data.addProperty("layer",layer);
                    }
                }
                data.addProperty("nativeBackgroundReady",frame.nativeBackgroundReady);
                data.addProperty("nativeBackgroundColorTexture",frame.nativeBackgroundColorTexture);
                data.addProperty("nativeDepthTexture",frame.distantDepthTexture);
                if(frame.nativeBackgroundReady) {
                    int background=frame.nativeBackgroundColorTexture;
                    if(background==Minecraft.getInstance().getMainRenderTarget().getColorTextureId()) throw new IllegalStateException("Backdrop is the composed MC target");
                    String file=prefix+"-native-background.png";
                    readTexture(background,output.resolve(file));data.addProperty("nativeBackground",file);
                }
            }
            data.addProperty("finalGlError",GL11.glGetError());stages.add(stage,data);
        } catch(Exception error) { throw new IllegalStateException("Owned R34 edge stage readback failed",error); }
    }
    private static void readTexture(int texture,Path file) throws Exception {
        if(texture<=0 || !GL11.glIsTexture(texture)) throw new IllegalStateException("Missing owned native backdrop");
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        if(w<=0 || h<=0 || (long)w*h>16_777_216L) throw new IllegalStateException("Unexpected backdrop extent");
        var pixels=BufferUtils.createByteBuffer(w*h*4).order(ByteOrder.LITTLE_ENDIAN);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
        try(NativeImage image=new NativeImage(w,h,false)) {
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) image.setPixelRGBA(x,h-1-y,pixels.getInt((y*w+x)*4));
            image.writeToFile(file);
        }
    }
}
