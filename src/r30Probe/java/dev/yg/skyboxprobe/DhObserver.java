package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.*;
import com.mojang.blaze3d.platform.NativeImage;
import dev.yg.skyboxblock.client.FixtureDistantTerrain;
import dev.yg.skyboxblock.client.SkyWorldRenderer;
import dev.yg.skyboxblock.client.ModCompat;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** DH 3.3.3 API observation only. Never cancels a render event, freezes a world or disables LOD updates. */
final class DhObserver {
    private static long beforeCount,afterCount;
    private static final HashMap<String,Long> passes=new HashMap<>();
    static void register() {
        DhApi.events.bind(DhApiBeforeRenderEvent.class,new DhApiBeforeRenderEvent() {
            @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                beforeCount++; DhApiRenderParam p=event.value;
                passes.merge(String.valueOf(p.renderPass),1L,Long::sum);
                // Observe the event reference on this render thread; copy matrices only at phase snapshots.
                // No per-frame matrix allocation and no edits to the borrowed parameter.
                last=p;
            }
        });
        DhApi.events.bind(DhApiAfterRenderEvent.class,new DhApiAfterRenderEvent() {
            @Override public void afterRender(DhApiEventParam<Void> event) { afterCount++; }
        });
    }
    private static DhApiRenderParam last;
    static JsonObject counters() {
        JsonObject result=new JsonObject(); result.addProperty("beforeRenderEvents",beforeCount); result.addProperty("afterRenderEvents",afterCount);
        result.add("passes",new Gson().toJsonTree(passes));
        if(DhApi.Delayed.renderProxy!=null) {
            result.addProperty("renderingApi",String.valueOf(DhApi.Delayed.renderProxy.getRenderingApi()));
            result.addProperty("depthRange",String.valueOf(DhApi.Delayed.renderProxy.getDepthRange()));
            result.addProperty("depthDirection",String.valueOf(DhApi.Delayed.renderProxy.getDepthDirection()));
            result.addProperty("nativeRenderer",DhApi.Delayed.renderProxy.isNativeRenderer());
        } return result;
    }
    static JsonObject snapshot(SkyWorldRenderer.Frame frame,Path output,String stem) throws Exception {
        JsonObject result=counters();
        result.addProperty("apiVersion",DhApi.getApiMajorVersion()+"."+DhApi.getApiMinorVersion()+"."+DhApi.getApiPatchVersion());
        result.addProperty("dhVersion",DhApi.getModVersion()); result.addProperty("readOnly",true);
        if(last!=null) {
            JsonObject p=new JsonObject(); p.addProperty("renderPass",String.valueOf(last.renderPass));
            p.addProperty("nearClipPlane",last.nearClipPlane); p.addProperty("farClipPlane",last.farClipPlane);
            p.addProperty("worldYOffset",last.worldYOffset);
            p.add("dhProjectionValues",new Gson().toJsonTree(last.dhProjectionMatrix.getValuesAsArray()));
            p.add("mcProjectionValues",new Gson().toJsonTree(last.mcProjectionMatrix.getValuesAsArray()));
            p.addProperty("matrixOrder","Original DH API getValuesAsArray order, no guessed transposition"); result.add("lastRenderParameter",p);
        }
        try {
            Method method=FixtureDistantTerrain.class.getDeclaredMethod("dhState",SkyWorldRenderer.Frame.class); method.setAccessible(true);
            result.add("lodState",(JsonObject)method.invoke(null,frame));
        } catch(Exception error) { result.addProperty("lodStateError",error.toString()); }
        result.add("renderList",renderList(frame));
        JsonArray depth=new JsonArray();
        if(ModCompat.irisActive()) {
            Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            Object pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
            Object compat=pipeline.getClass().getMethod("getDHCompat").invoke(pipeline);
            Object projection=compat.getClass().getMethod("getProjection").invoke(null);
            if(projection instanceof org.joml.Matrix4fc matrix) result.add("irisDhProjectionColumnMajor",new Gson().toJsonTree(matrix.get(new float[16])));
            for(String getter:new String[]{"getDepthTex","getDepthTexNoTranslucent"}) {
                try { int texture=((Number)compat.getClass().getMethod(getter).invoke(compat)).intValue(); depth.add(readDepth(texture,output,stem+"-"+getter)); }
                catch(Exception error) { JsonObject failed=new JsonObject(); failed.addProperty("source",getter); failed.addProperty("error",error.toString()); depth.add(failed); }
            }
        } else if(DhApi.Delayed.renderProxy!=null) {
            var query=DhApi.Delayed.renderProxy.getDhDepthTextureGlId();
            if(query.success && query.payload!=null) depth.add(readDepth(query.payload,output,stem+"-native-dh-depth"));
            else { JsonObject unavailable=new JsonObject(); unavailable.addProperty("status","UNAVAILABLE"); unavailable.addProperty("message",query.message); depth.add(unavailable); }
        }
        result.add("depth",depth); return result;
    }
    private static JsonObject renderList(SkyWorldRenderer.Frame frame) {
        JsonObject result=new JsonObject(); result.addProperty("status","UNAVAILABLE");
        try {
            Class<?> type=Class.forName("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper");
            Object raw=field(type,null,"LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL");
            if(!(raw instanceof Map<?,?> map)) throw new IllegalStateException("No existing wrapper map");
            Object ref; synchronized(map) { ref=map.get(frame.level); }
            Object wrapper=ref instanceof java.lang.ref.Reference<?> reference ? reference.get() : null;
            if(wrapper==null) throw new IllegalStateException("No existing current-level wrapper");
            Object level=wrapper.getClass().getMethod("getDhLevel").invoke(wrapper);
            Object handler=level.getClass().getMethod("getRenderBufferHandler").invoke(level);
            Object buffers=handler.getClass().getMethod("getColumnRenderBuffers").invoke(handler);
            result.addProperty("containerClass",buffers.getClass().getName());
            // Pinned 3.3.3 uses SortedArraySet, which is deliberately NOT Iterable.
            Method size=buffers.getClass().getMethod("size"),get=buffers.getClass().getMethod("get",int.class);
            int count=((Number)size.invoke(buffers)).intValue();
            if(count<0 || count>20000) throw new IllegalStateException("Render-list diagnostic bound");
            Method describe=FixtureDistantTerrain.class.getDeclaredMethod("container",Object.class); describe.setAccessible(true);
            ArrayList<JsonObject> entries=new ArrayList<>();
            for(int i=0;i<count;i++) { Object buffer=get.invoke(buffers,i); if(buffer!=null) entries.add((JsonObject)describe.invoke(null,buffer)); }
            entries.sort(Comparator.comparingLong(item->item.get("pos").getAsLong()));
            JsonArray array=new JsonArray(); for(JsonObject entry:entries) array.add(entry);
            result.add("buffers",array); result.addProperty("count",count);
            result.addProperty("sameSizeAfterObservation",count==((Number)size.invoke(buffers)).intValue());
            result.addProperty("fingerprintSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(array.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            for(String key:new String[]{"visibleBufferCount","culledBufferCount","shadowVisibleBufferCount","shadowCulledBufferCount"})
                result.addProperty(key,((Number)field(handler.getClass(),handler,key)).intValue());
            result.addProperty("status","SAMPLED");
            result.addProperty("semantics","Read-only last-built list and last pass counters, not an atomic snapshot or per-buffer GPU fence");
        } catch(Exception error) { result.addProperty("error",error.toString()); }
        return result;
    }
    private static Object field(Class<?> type,Object receiver,String name) throws ReflectiveOperationException {
        for(Class<?> p=type;p!=null;p=p.getSuperclass()) {
            try { Field f=p.getDeclaredField(name); f.setAccessible(true); return f.get(receiver); }
            catch(NoSuchFieldException absent) { }
        } throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static JsonObject readDepth(int texture,Path output,String stem) throws Exception {
        JsonObject out=new JsonObject(); out.addProperty("textureId",texture);
        if(texture<=0 || !GL11.glIsTexture(texture)) { out.addProperty("status","NO_LIVE_TEXTURE"); return out; }
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int binding=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),pbo=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int[] keys={GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_PIXELS,GL11.GL_PACK_SKIP_ROWS,
                GL11.GL_PACK_SWAP_BYTES,GL11.GL_PACK_LSB_FIRST,GL12.GL_PACK_IMAGE_HEIGHT,GL12.GL_PACK_SKIP_IMAGES};
        int[] values=new int[keys.length]; for(int i=0;i<keys.length;i++) values[i]=GL11.glGetInteger(keys[i]);
        ByteBuffer pixels=null;
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            int bits=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL14.GL_TEXTURE_DEPTH_SIZE);
            int stencil=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL30.GL_TEXTURE_STENCIL_SIZE);
            out.addProperty("width",w); out.addProperty("height",h); out.addProperty("depthBits",bits); out.addProperty("stencilBits",stencil);
            if(w<=0 || h<=0 || (long)w*h>16_777_216 || bits<=0 || stencil!=0) { out.addProperty("status","UNSUPPORTED_TEXTURE_LAYOUT"); return out; }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
            for(int key:keys) GL11.glPixelStorei(key,key==GL11.GL_PACK_ALIGNMENT ? 4 : 0);
            pixels=MemoryUtil.memAlloc(w*h*4).order(ByteOrder.nativeOrder());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,pixels);
            byte[] raw=new byte[w*h*4]; long zero=0,one=0,interior=0,invalid=0; float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
            try(NativeImage mask=new NativeImage(w,h,false)) {
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    int i=y*w+x; float value=pixels.getFloat(i*4); int word=Float.floatToRawIntBits(value);
                    raw[i*4]=(byte)word; raw[i*4+1]=(byte)(word>>>8); raw[i*4+2]=(byte)(word>>>16); raw[i*4+3]=(byte)(word>>>24);
                    if(!Float.isFinite(value) || value<0 || value>1) invalid++;
                    else { min=Math.min(min,value); max=Math.max(max,value); if(value==0)zero++; else if(value==1)one++; else interior++; }
                    mask.setPixelRGBA(x,h-1-y,Float.isFinite(value) && value>0 && value<1 ? 0xFFFFFFFF : 0xFF000000);
                }
                mask.writeToFile(output.resolve(stem+"-interior-mask.png"));
            }
            Path rawFile=output.resolve(stem+".f32le"); Files.write(rawFile,raw,StandardOpenOption.CREATE_NEW);
            out.addProperty("status","SAMPLED"); out.addProperty("zeroCount",zero); out.addProperty("oneCount",one);
            out.addProperty("interiorDepthCount",interior); out.addProperty("invalidCount",invalid);
            if(Float.isFinite(min)) out.addProperty("min",min); if(Float.isFinite(max)) out.addProperty("max",max);
            out.addProperty("rawFile",rawFile.getFileName().toString()); out.addProperty("rawBytes",raw.length);
            out.addProperty("rawSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
            out.addProperty("rawFormat","IEEE754 float32 little-endian, x fastest, rows bottom-to-top");
            out.addProperty("maskMeaning","Strictly interior finite 0<depth<1; direction/clear value must be interpreted using actual renderer API, not assumed");
        } finally {
            if(pixels!=null) MemoryUtil.memFree(pixels);
            for(int i=0;i<keys.length;i++) GL11.glPixelStorei(keys[i],values[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,pbo); GL11.glBindTexture(GL11.GL_TEXTURE_2D,binding); GL13.glActiveTexture(active);
        }
        return out;
    }
}
