package dev.yg.skyboxprobe;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.client.SkyVanillaClouds;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Owned opt-in GPU measurement. Never runs in a normal production client. */
public final class R36CloudDiagnostics {
    private static final boolean ENABLED=Boolean.getBoolean("skyboxblock.r36.cloudDiagnostics");
    private static TextureTarget black,white;
    private R36CloudDiagnostics() { }
    private static AutoCloseable scope() throws Exception {
        var constructor=Class.forName("dev.yg.skyboxblock.client.SkyGlState").getDeclaredConstructor(int.class);
        constructor.setAccessible(true);return (AutoCloseable)constructor.newInstance(6);
    }
    private static void installStencil(RenderTarget target) throws Exception {
        try {
            var method=Class.forName("dev.yg.skyboxblock.client.SkyCloudStencil").getDeclaredMethod("install",RenderTarget.class);
            method.setAccessible(true);method.invoke(null,target);
        } catch(ClassNotFoundException oldBaseline) { /* Actual R35 baseline has no deduplication. */ }
    }
    private static AutoCloseable rawScope() throws Exception {
        try {
            var method=Class.forName("dev.yg.skyboxblock.client.SkyCloudStencil").getDeclaredMethod("rawForDiagnostics");
            method.setAccessible(true);return (AutoCloseable)method.invoke(null);
        } catch(ClassNotFoundException oldBaseline) { return () -> { }; }
    }
    private static void ensure(int width,int height) throws Exception {
        if(black!=null && black.width==width && black.height==height) return;
        if(black!=null)black.destroyBuffers();if(white!=null)white.destroyBuffers();
        black=new TextureTarget(width,height,true,Minecraft.ON_OSX);
        white=new TextureTarget(width,height,true,Minecraft.ON_OSX);
        installStencil(black);installStencil(white);
    }
    private static NativeImage draw(SkyWorldRenderer.Frame frame,TextureTarget target,float background,Matrix4f projection) {
        target.setClearColor(background,background,background,1);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
        for(int i=0;i<6;i++)GL33.glBindSampler(i,0);
        SkyVanillaClouds.render(frame.renderer,frame.level,frame.camera,frame.view,projection,frame.partialTick,frame.sky);
        return Screenshot.takeScreenshot(target);
    }
    private static JsonObject pair(SkyWorldRenderer.Frame frame,Path directory,String stem,Matrix4f projection) throws Exception {
        try(NativeImage b=draw(frame,black,0,projection);NativeImage w=draw(frame,white,1,projection)) {
            double expected=.8*frame.sky.cloudOpacity,max=0;long covered=0,overdraw=0;
            long[] histogram=new long[256];int firstX=-1,firstY=-1;
            for(int y=0;y<b.getHeight();y++)for(int x=0;x<b.getWidth();x++) {
                int cb=b.getPixelRGBA(x,y),cw=w.getPixelRGBA(x,y);
                double difference=((cw&255)-(cb&255)+((cw>>>8)&255)-((cb>>>8)&255)+((cw>>>16)&255)-((cb>>>16)&255))/3.0;
                double alpha=Math.max(0,Math.min(1,1-difference/255.0));max=Math.max(max,alpha);
                histogram[(int)Math.round(alpha*255)]++;
                if(alpha>.025)covered++;
                if(alpha>expected+.025) { overdraw++;if(firstX<0){firstX=x;firstY=y;} }
            }
            String blackFile=stem+"-black.png",whiteFile=stem+"-white.png";
            b.writeToFile(directory.resolve(blackFile));w.writeToFile(directory.resolve(whiteFile));
            JsonObject data=new JsonObject();data.addProperty("black",blackFile);data.addProperty("white",whiteFile);
            data.addProperty("coveredPixels",covered);data.addProperty("overdrawPixels",overdraw);
            data.addProperty("singleBlendAlpha",expected);data.addProperty("maxMeasuredAlpha",max);
            data.addProperty("firstOverdrawX",firstX);data.addProperty("firstOverdrawY",firstY);
            data.add("alphaHistogram",new com.google.gson.Gson().toJsonTree(histogram));
            data.addProperty("formula","alpha=1-(whiteRGB-blackRGB)/255; same-frame actual cloud shader/mesh");
            white.bindWrite(true);
            data.addProperty("drawFramebuffer",GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
            data.addProperty("encoding",GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING));
            data.addProperty("depthBits",GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE));
            int stencilType=GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_STENCIL_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            data.addProperty("stencilBits",stencilType==GL11.GL_NONE ? 0 : GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,GL30.GL_STENCIL_ATTACHMENT,GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE));
            data.addProperty("samples",GL11.glGetInteger(GL13.GL_SAMPLES));
            return data;
        }
    }
    public static JsonObject capture(SkyWorldRenderer.Frame frame,Path directory,String stem) throws Exception {
        JsonObject result=new JsonObject();result.addProperty("enabled",ENABLED);
        if(!ENABLED || !R30Probe.ownerValidated() || frame.sky==null)return result;
        try(AutoCloseable ignored=scope()) {
            RenderTarget source=null;boolean display=false;
            Class<?> scene=Class.forName("dev.yg.skyboxblock.client.VanillaSkyScene");
            for(String fieldName:new String[]{"displayTarget","target"}) {
                var field=scene.getDeclaredField(fieldName);field.setAccessible(true);
                if(field.get(null) instanceof RenderTarget actual) { source=actual;display=fieldName.equals("displayTarget");break; }
            }
            if(source==null)throw new IllegalStateException("No actual private sky canvas for cloud oracle");
            ensure(source.width,source.height);
            Matrix4f projection=display ? frame.projection : frame.skyProjection;
            result.addProperty("gameTime",frame.level.getGameTime());result.addProperty("partialTick",frame.partialTick);
            result.addProperty("width",source.width);result.addProperty("height",source.height);
            result.addProperty("displayProjection",display);result.addProperty("cloudOpacity",frame.sky.cloudOpacity);
            result.add("camera",new com.google.gson.Gson().toJsonTree(new double[]{frame.camera.x,frame.camera.y,frame.camera.z}));
            result.add("measured",pair(frame,directory,stem+"-cloud",projection));
            try(AutoCloseable raw=rawScope()) { result.add("sameFrameRaw",pair(frame,directory,stem+"-cloud-raw",projection)); }
            int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int texture=Minecraft.getInstance().getTextureManager().getTexture(ResourceLocation.withDefaultNamespace("textures/environment/clouds.png")).getId();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            result.addProperty("textureMinFilter",GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER));
            result.addProperty("textureMagFilter",GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);GL13.glActiveTexture(active);
            Files.writeString(directory.resolve(stem+"-cloud.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n",StandardCharsets.UTF_8);
        }
        return result;
    }
    public static void release() throws Exception {
        if(black==null && white==null)return;
        try(AutoCloseable ignored=scope()) {
            if(black!=null)black.destroyBuffers();if(white!=null)white.destroyBuffers();black=white=null;
        }
    }
}