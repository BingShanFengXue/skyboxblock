package dev.yg.skyboxblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Copyright (c) 2026 Bing6512. Opt-in owned-fixture depth/LOD observation; no rendering or freeze controls. */
public final class FixtureDistantTerrain {
    private static final int SCHEMA_VERSION=1;
    private static final long MAX_DEPTH_PIXELS=16_777_216L;
    private static final int MAX_SECTIONS=20_000;
    private FixtureDistantTerrain() {}

    /**
     * Call once per selected capture, at the genuine root Iris final/pre-resolve stage (including no-sky views).
     * Disabled: returns null before any client, GL, reflection or filesystem access.
     * Enabled but unobservable: returns explicit skipped/unavailable evidence; never fabricates empty geometry.
     */
    public static JsonObject capture(SkyWorldRenderer.Frame frame,String scene,Path output) {
        if (!Boolean.getBoolean("skyboxblock.regression")
                || !Boolean.getBoolean("skyboxblock.regression.distantTerrainDiagnostics")) return null;
        JsonObject result=new JsonObject();
        result.addProperty("schemaVersion",SCHEMA_VERSION);
        result.addProperty("readOnlyDiagnostic",true);
        result.addProperty("scene",scene);
        result.addProperty("sampleStageContract","caller must invoke at root Iris final/pre-resolve, not private sky render");
        result.addProperty("status","unavailable");
        try {
            Minecraft mc=Minecraft.getInstance();
            String rejected=guard(mc,frame,scene,output);
            if (rejected!=null) { result.addProperty("status","skipped"); result.addProperty("reason",rejected); return result; }
            RenderSystem.assertOnRenderThread();
            Path owner=mc.gameDirectory.toPath().toRealPath();
            Path directory=ownedOutput(owner,output).resolve("distant-terrain-diagnostics");
            Files.createDirectories(directory);
            directory=directory.toRealPath();
            if (!directory.startsWith(owner)) throw new IOException("Diagnostic directory escaped owned fixture");
            String stem=scene.replaceAll("[^A-Za-z0-9._-]","_")+"-view-"+frame.viewId;
            result.addProperty("viewId",frame.viewId);
            result.addProperty("rootTransaction",frame.rootTransaction);
            result.addProperty("dimension",frame.level.dimension().location().toString());
            result.addProperty("clientLevelIdentity",identity(frame.level));
            result.addProperty("root",frame.root); result.addProperty("shadow",frame.shadow);
            result.addProperty("portal",frame.portal); result.addProperty("irisFinalHook",frame.irisFinalHook);
            result.addProperty("skyBlocks",frame.blocks()); result.addProperty("skyVertices",frame.vertices());
            result.add("physicalTargetViewport",ints(frame.targetViewport));
            result.add("activeViewport",ints(frame.viewport));
            result.add("rasterScale",numbers(frame.rasterScale.x,frame.rasterScale.y));
            result.add("rasterJitter",numbers(frame.rasterJitter.x,frame.rasterJitter.y));
            result.addProperty("irisVersion",version("iris")); result.addProperty("dhVersion",version("distanthorizons"));
            result.addProperty("nanoTimeStart",System.nanoTime());
            result.add("dhState",dhState(frame));
            try {
                Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
                Object optional=call(manager,"getPipeline");
                if (!(optional instanceof Optional<?> pipelineOptional) || pipelineOptional.isEmpty())
                    throw new IllegalStateException("No current Iris pipeline");
                Object pipeline=pipelineOptional.get();
                Object compat=call(pipeline,"getDHCompat");
                result.addProperty("pipelineIdentity",identity(pipeline));
                result.addProperty("dhCompatIdentity",identity(compat));
                JsonObject depth=new JsonObject();
                // Read the same live suppliers used by IrisSamplers; do not cache IDs between captures.
                try (SkyGlState gl=new SkyGlState(1); PixelPackState pack=new PixelPackState()) {
                    result.add("samplingGlViewport",ints(gl.viewport));
                    result.addProperty("samplingReadFramebuffer",gl.readFramebuffer);
                    result.addProperty("samplingDrawFramebuffer",gl.drawFramebuffer);
                    result.addProperty("samplingReadBuffer",gl.readBuffer);
                    depth.add("all",depth(frame,directory,stem,"all",compat,"getDepthTex"));
                    depth.add("opaque",depth(frame,directory,stem,"opaque",compat,"getDepthTexNoTranslucent"));
                }
                result.add("depthTextures",depth);
                Object after=call(manager,"getPipeline");
                boolean samePipeline=after instanceof Optional<?> p && p.orElse(null)==pipeline;
                result.addProperty("samePipelineAfterSampling",samePipeline);
                boolean all=depth.getAsJsonObject("all").get("status").getAsString().equals("sampled");
                boolean opaque=depth.getAsJsonObject("opaque").get("status").getAsString().equals("sampled");
                result.addProperty("status",samePipeline && all && opaque ? "sampled" : all || opaque ? "partial" : "unavailable");
            } catch (Exception|LinkageError error) { result.add("irisDepthUnavailable",failure(error)); }
            result.addProperty("nanoTimeEnd",System.nanoTime());
            result.addProperty("snapshotSemantics","read-only observations, not an atomic LOD snapshot or GPU readiness barrier");
            result.addProperty("diagnosticJson",directory.getFileName()+"/"+stem+".json");
            try { Files.writeString(directory.resolve(stem+".json"),result.toString(),StandardCharsets.UTF_8); }
            catch (IOException error) { result.add("metadataWriteUnavailable",failure(error)); }
        } catch (Exception|LinkageError error) { result.add("unavailable",failure(error)); }
        return result;
    }

    private static String guard(Minecraft mc,SkyWorldRenderer.Frame frame,String scene,Path output) throws IOException {
        if (mc==null || mc.gameDirectory==null || mc.level==null || frame==null) return "client-or-frame-unavailable";
        if (!frame.root || frame.shadow || frame.portal || frame.level!=mc.level) return "not-current-root-nonshadow-level";
        if (mc.getSingleplayerServer()==null || mc.getSingleplayerServer().getWorldData()==null
                || !"Skybox render fixture".equals(mc.getSingleplayerServer().getWorldData().getLevelName())) return "not-owned-fixture-world";
        String declared=System.getProperty("skyboxblock.regression.ownedGameDir","");
        if (declared.isBlank() || !Path.of(declared).isAbsolute()) return "missing-absolute-ownedGameDir";
        Path owner=Path.of(declared).toRealPath(),actual=mc.gameDirectory.toPath().toRealPath();
        if (!owner.equals(actual)) return "ownedGameDir-does-not-match-client";
        boolean child=false;
        for (Path parent=actual.getParent();parent!=null;parent=parent.getParent())
            if (parent.getFileName()!=null && "render-regression".equals(parent.getFileName().toString())) { child=true; break; }
        if (!child) return "not-render-regression-child";
        if (scene==null || scene.isBlank() || output==null) return "missing-scene-or-output";
        ownedOutput(owner,output);
        if (ModCompat.irisShadowPass() || ModCompat.portalRendering()) return "live-shadow-or-portal-pass";
        if (!ModCompat.irisActive()) return "iris-not-active";
        return null;
    }

    /** Resolve existing ancestors first so an output symlink does not escape the owned game directory. */
    private static Path ownedOutput(Path owner,Path output) throws IOException {
        Path requested=output.toAbsolutePath().normalize(),ancestor=requested;
        while (ancestor!=null && !Files.exists(ancestor)) ancestor=ancestor.getParent();
        if (ancestor==null) throw new IOException("Output has no existing ancestor");
        Path real=ancestor.toRealPath().resolve(ancestor.relativize(requested)).normalize();
        if (!real.startsWith(owner)) throw new IOException("Output is outside owned fixture");
        return real;
    }

    /** SkyGlState covers framebuffer/read-buffer/texture/cache restoration; pack state and PBO are additional. */
    private static final class PixelPackState implements AutoCloseable {
        private final int buffer=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        private final int[] keys={GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_PIXELS,
                GL11.GL_PACK_SKIP_ROWS,GL11.GL_PACK_SWAP_BYTES,GL11.GL_PACK_LSB_FIRST,
                GL12.GL_PACK_IMAGE_HEIGHT,GL12.GL_PACK_SKIP_IMAGES};
        private final int[] values=new int[keys.length];
        PixelPackState() {
            for (int i=0;i<keys.length;i++) values[i]=GL11.glGetInteger(keys[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);
            for (int key:keys) GL11.glPixelStorei(key,key==GL11.GL_PACK_ALIGNMENT ? 4 : 0);
        }
        @Override public void close() {
            for (int i=0;i<keys.length;i++) GL11.glPixelStorei(keys[i],values[i]);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,buffer);
        }
    }

    private static JsonObject depth(SkyWorldRenderer.Frame frame,Path directory,String stem,String role,
                                   Object compat,String getter) {
        JsonObject data=new JsonObject(); data.addProperty("supplier",getter);
        data.addProperty("status","unavailable");
        ByteBuffer pixels=null;
        try {
            int texture=((Number)call(compat,getter)).intValue(); data.addProperty("textureId",texture);
            if (texture<=0 || !GL11.glIsTexture(texture)) throw new IllegalStateException("Supplier has no live GL texture");
            SkyGlState.active(GL13.GL_TEXTURE0); SkyGlState.bindTexture(texture);
            int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH);
            int height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            int format=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT);
            int depthBits=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL14.GL_TEXTURE_DEPTH_SIZE);
            int stencilBits=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL30.GL_TEXTURE_STENCIL_SIZE);
            data.addProperty("width",width); data.addProperty("height",height);
            data.addProperty("internalFormat",String.format("0x%04x",format));
            data.addProperty("depthBits",depthBits); data.addProperty("stencilBits",stencilBits);
            long count=(long)width*height;
            if (width<=0 || height<=0 || count>MAX_DEPTH_PIXELS || depthBits<=0)
                throw new IllegalStateException("Unsupported size or non-depth texture");
            // Keep diagnostics conservative instead of risking a packed depth/stencil readback mismatch.
            if (stencilBits!=0) throw new IllegalStateException("Packed depth/stencil readback is not implemented");
            pixels=MemoryUtil.memAlloc(Math.toIntExact(count*4)).order(ByteOrder.nativeOrder());
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,pixels);
            byte[] raw=new byte[Math.toIntExact(count*4)];
            for (int i=0;i<count;i++) {
                int bits=Float.floatToRawIntBits(pixels.getFloat(i*4)),o=i*4;
                raw[o]=(byte)bits; raw[o+1]=(byte)(bits>>>8); raw[o+2]=(byte)(bits>>>16); raw[o+3]=(byte)(bits>>>24);
            }
            String prefix=stem+"-dh-depth-"+role;
            Path rawFile=directory.resolve(prefix+".f32le"); Files.write(rawFile,raw);
            data.addProperty("rawFile",directory.getFileName()+"/"+rawFile.getFileName());
            data.addProperty("rawSha256",sha(raw)); data.addProperty("rawBytes",raw.length);
            data.addProperty("rawFormat","IEEE754 float32 little-endian, x fastest, rows bottom-to-top (OpenGL)");
            data.addProperty("readback","glGetTexImage depth-component float; no color readback; no glGetError consumption");
            data.add("physical",statistics(pixels,width,height,0,0,width,height));
            Path fullMask=directory.resolve(prefix+"-physical-mask.png");
            mask(pixels,width,0,0,width,height,fullMask);
            data.addProperty("physicalMask",directory.getFileName()+"/"+fullMask.getFileName());
            data.addProperty("maskSemantics","white iff finite depth < 1; matches ITRP LOD sky-exclusion test, not inferred visible geometry");
            int[] target=frame.targetViewport,active=frame.viewport;
            int x=active[0]-target[0],y=active[1]-target[1],w=active[2],h=active[3];
            boolean mapping=width==target[2] && height==target[3] && x>=0 && y>=0 && w>0 && h>0
                    && (long)x+w<=width && (long)y+h<=height;
            data.addProperty("activeCropMappingVerified",mapping);
            if (mapping) {
                data.add("activeCropInTexture",ints(new int[]{x,y,w,h}));
                data.add("active",statistics(pixels,width,height,x,y,w,h));
                Path activeMask=directory.resolve(prefix+"-active-mask.png"); mask(pixels,width,x,y,w,h,activeMask);
                data.addProperty("activeMask",directory.getFileName()+"/"+activeMask.getFileName());
                data.addProperty("pngRowOrder","top-to-bottom; source texture y is recorded per curve row");
            } else data.addProperty("activeCropUnavailable","Texture dimensions do not prove one-to-one physical viewport mapping; no resampling guessed");
            data.addProperty("status","sampled");
        } catch (Exception|LinkageError error) { data.add("unavailable",failure(error)); }
        finally { if (pixels!=null) MemoryUtil.memFree(pixels); }
        return data;
    }

    private static JsonObject statistics(ByteBuffer pixels,int textureWidth,int textureHeight,int x0,int y0,int width,int height) {
        JsonObject result=new JsonObject(); JsonArray rows=new JsonArray();
        long finite=0,less=0,zero=0,one=0,outside=0; double sum=0,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        MessageDigest digest=digest(); ByteBuffer word=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        for (int imageY=0;imageY<height;imageY++) {
            int y=y0+height-1-imageY; long rowFinite=0,rowLess=0; double rowSum=0,rowMin=Double.POSITIVE_INFINITY,rowMax=Double.NEGATIVE_INFINITY;
            for (int x=x0;x<x0+width;x++) {
                float d=pixels.getFloat((y*textureWidth+x)*4);
                word.clear(); word.putInt(Float.floatToRawIntBits(d)); digest.update(word.array());
                if (!Float.isFinite(d)) continue;
                rowFinite++; rowSum+=d; rowMin=Math.min(rowMin,d); rowMax=Math.max(rowMax,d);
                if (d<1) rowLess++; if (d==0) zero++; if (d==1) one++; if (d<0 || d>1) outside++;
            }
            JsonObject row=new JsonObject(); row.addProperty("imageY",imageY); row.addProperty("textureY",y);
            row.addProperty("finiteCount",rowFinite); row.addProperty("depthLessThanOneCount",rowLess);
            nullable(row,"min",rowFinite==0 ? null : rowMin); nullable(row,"max",rowFinite==0 ? null : rowMax);
            nullable(row,"mean",rowFinite==0 ? null : rowSum/rowFinite); rows.add(row);
            finite+=rowFinite; less+=rowLess; sum+=rowSum; min=Math.min(min,rowMin); max=Math.max(max,rowMax);
        }
        long count=(long)width*height; result.addProperty("pixelCount",count); result.addProperty("finiteCount",finite);
        result.addProperty("nonfiniteCount",count-finite); result.addProperty("depthLessThanOneCount",less);
        result.addProperty("zeroCount",zero); result.addProperty("oneCount",one); result.addProperty("outOfUnitRangeCount",outside);
        nullable(result,"min",finite==0 ? null : min); nullable(result,"max",finite==0 ? null : max);
        nullable(result,"mean",finite==0 ? null : sum/finite);
        result.addProperty("topDownFloat32LeSha256",HexFormat.of().formatHex(digest.digest()));
        result.addProperty("textureWidth",textureWidth); result.addProperty("textureHeight",textureHeight);
        result.add("rowsTopDown",rows); return result;
    }

    private static void mask(ByteBuffer pixels,int textureWidth,int x0,int y0,int width,int height,Path file) throws IOException {
        try (NativeImage image=new NativeImage(width,height,false)) {
            for (int imageY=0;imageY<height;imageY++) for (int x=0;x<width;x++) {
                float d=pixels.getFloat(((y0+height-1-imageY)*textureWidth+x0+x)*4);
                image.setPixelRGBA(x,imageY,Float.isFinite(d) && d<1 ? 0xFFFFFFFF : 0xFF000000);
            }
            image.writeToFile(file);
        }
    }

    /** Read only existing wrapper map entries; getWrapper() would create/mark-access a wrapper. */
    private static JsonObject dhState(SkyWorldRenderer.Frame frame) {
        JsonObject data=new JsonObject(); data.addProperty("status","unavailable");
        try {
            Class<?> api=Class.forName("com.seibel.distanthorizons.api.DhApi");
            data.addProperty("apiVersion",api.getMethod("getApiMajorVersion").invoke(null)+"."
                    +api.getMethod("getApiMinorVersion").invoke(null)+"."+api.getMethod("getApiPatchVersion").invoke(null));
            Class<?> delayed=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object proxy=delayed.getField("worldProxy").get(null);
            if (proxy!=null) data.addProperty("worldReadOnly",(Boolean)call(proxy,"getReadOnly"));
            Object renderProxy=delayed.getField("renderProxy").get(null);
            if (renderProxy!=null) {
                data.addProperty("renderingApi",String.valueOf(call(renderProxy,"getRenderingApi")));
                data.addProperty("depthDirection",String.valueOf(call(renderProxy,"getDepthDirection")));
            }
            Class<?> wrapperType=Class.forName("com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper");
            Object mapObject=field(wrapperType,null,"LEVEL_WRAPPER_REF_BY_CLIENT_LEVEL");
            if (!(mapObject instanceof Map<?,?> map)) throw new IllegalStateException("Existing DH wrapper map unavailable");
            Object ref; synchronized (map) { ref=map.get(frame.level); }
            Object wrapper=ref instanceof Reference<?> reference ? reference.get() : null;
            if (wrapper==null) throw new IllegalStateException("Current ClientLevel has no existing DH wrapper; no wrapper created");
            if (call(wrapper,"getWrappedMcObject")!=frame.level) throw new IllegalStateException("DH wrapper is for another ClientLevel");
            data.addProperty("wrapperIdentity",identity(wrapper)); data.addProperty("dhIdentifier",String.valueOf(call(wrapper,"getDhIdentifier")));
            Object level=call(wrapper,"getDhLevel");
            if (level==null) throw new IllegalStateException("Existing DH wrapper has no DH level");
            data.addProperty("dhLevelIdentity",identity(level)); data.addProperty("rendering",(Boolean)call(level,"isRendering"));
            Object clientside=field(level.getClass(),level,"clientside");
            Object stateRef=field(clientside.getClass(),clientside,"ClientRenderStateRef");
            Object state=stateRef instanceof AtomicReference<?> atomic ? atomic.get() : null;
            if (state==null) throw new IllegalStateException("DH client render state unavailable");
            Object tree=field(state.getClass(),state,"quadtree"),handler=call(level,"getRenderBufferHandler");
            if (handler==null) throw new IllegalStateException("DH render buffer handler unavailable");
            data.addProperty("renderStateIdentity",identity(state)); data.addProperty("quadTreeIdentity",identity(tree));
            ArrayList<Object> enabled=new ArrayList<>();
            tree.getClass().getMethod("populateListWithEnabledRenderSections",ArrayList.class).invoke(tree,enabled);
            if (enabled.size()>MAX_SECTIONS) throw new IllegalStateException("DH section observation exceeds diagnostic bound");
            ArrayList<JsonObject> sections=new ArrayList<>(); boolean stable=true;
            for (Object section:enabled) {
                JsonObject item=new JsonObject(); item.addProperty("pos",((Number)field(section.getClass(),section,"pos")).longValue());
                Object before=field(section.getClass(),section,"renderBufferContainer");
                item.addProperty("renderDataDirty",(Boolean)field(section.getClass(),section,"renderDataDirty"));
                item.addProperty("gpuUploadInProgress",(Boolean)call(section,"gpuUploadInProgress"));
                item.addProperty("gpuUploadCompleteFlag",(Boolean)call(section,"gpuUploadComplete"));
                item.addProperty("renderingEnabled",(Boolean)call(section,"getRenderingEnabled"));
                item.add("container",container(before));
                boolean same=before==field(section.getClass(),section,"renderBufferContainer");
                item.addProperty("sameContainerDuringObservation",same); stable&=same; sections.add(item);
            }
            sections.sort(Comparator.comparingLong(item -> item.get("pos").getAsLong()));
            JsonArray enabledJson=new JsonArray(); for (JsonObject section:sections) enabledJson.add(section);
            data.add("enabledSections",enabledJson); data.addProperty("enabledSectionCount",sections.size());
            data.addProperty("enabledFingerprintSha256",sha(enabledJson.toString().getBytes(StandardCharsets.UTF_8)));
            Object actual=call(handler,"getColumnRenderBuffers");
            ArrayList<Object> observedBuffers=new ArrayList<>();
            if (actual!=null && actual.getClass().isArray()) {
                int length=Array.getLength(actual);
                if(length>MAX_SECTIONS) throw new IllegalStateException("DH render-list observation exceeds diagnostic bound");
                for(int i=0;i<length;i++) observedBuffers.add(Array.get(actual,i));
            } else if(actual instanceof Iterable<?> iterable) {
                for(Object buffer:iterable) {
                    if(observedBuffers.size()>=MAX_SECTIONS) throw new IllegalStateException("DH render-list observation exceeds diagnostic bound");
                    observedBuffers.add(buffer);
                }
            } else throw new IllegalStateException("DH last-built render list is neither an array nor iterable");
            ArrayList<JsonObject> buffers=new ArrayList<>();
            for (Object buffer:observedBuffers) if(buffer!=null) buffers.add(container(buffer));
            buffers.sort(Comparator.comparingLong(item -> item.get("pos").getAsLong()));
            JsonArray actualJson=new JsonArray(); for (JsonObject buffer:buffers) actualJson.add(buffer);
            data.add("lastBuiltRenderBuffers",actualJson); data.addProperty("lastBuiltRenderBufferCount",buffers.size());
            data.addProperty("lastBuiltFingerprintSha256",sha(actualJson.toString().getBytes(StandardCharsets.UTF_8)));
            data.addProperty("renderListProvenance","handler's last-built list; no per-draw/pass provenance inferred");
            data.addProperty("sameContainersDuringObservation",stable);
            data.addProperty("gpuFlagSemantics","gpuUploadCompleteFlag is container != null && !renderDataDirty, not a GL fence");
            data.addProperty("status","sampled");
        } catch (Exception|LinkageError error) { data.add("unavailable",failure(error)); }
        return data;
    }

    private static JsonObject container(Object buffer) throws ReflectiveOperationException {
        JsonObject item=new JsonObject(); item.addProperty("available",buffer!=null);
        if (buffer==null) return item;
        item.addProperty("identity",identity(buffer)); item.addProperty("pos",((Number)field(buffer.getClass(),buffer,"pos")).longValue());
        item.addProperty("buffersUploaded",(Boolean)field(buffer.getClass(),buffer,"buffersUploaded"));
        for (String name:new String[]{"vboOpaqueWrappers","vboTransparentWrappers"}) {
            Object array=field(buffer.getClass(),buffer,name); JsonArray entries=new JsonArray(); long vertices=0;
            if (array!=null) for (int i=0;i<Array.getLength(array);i++) {
                Object vbo=Array.get(array,i); JsonObject entry=new JsonObject(); entry.addProperty("slot",i);
                entry.addProperty("available",vbo!=null);
                if (vbo!=null) { int count=((Number)call(vbo,"getVertexCount")).intValue(); vertices+=count;
                    entry.addProperty("identity",identity(vbo)); entry.addProperty("vertexCount",count); }
                entries.add(entry);
            }
            item.add(name,entries); item.addProperty(name+"VertexCount",vertices);
        }
        return item;
    }

    private static Object call(Object object,String name) throws ReflectiveOperationException {
        if (object==null) throw new IllegalStateException("Null receiver for "+name);
        Method method=object.getClass().getMethod(name); method.setAccessible(true); return method.invoke(object);
    }
    private static Object field(Class<?> type,Object receiver,String name) throws ReflectiveOperationException {
        for (Class<?> current=type;current!=null;current=current.getSuperclass()) {
            try { Field field=current.getDeclaredField(name); field.setAccessible(true); return field.get(receiver); }
            catch (NoSuchFieldException absent) { /* Search the superclass without mutating any object. */ }
        }
        throw new NoSuchFieldException(type.getName()+"."+name);
    }
    private static String version(String id) { return FabricLoader.getInstance().getModContainer(id)
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("not-loaded"); }
    private static String identity(Object object) { return object==null ? null : object.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(object)); }
    private static JsonObject failure(Throwable error) {
        while (error instanceof InvocationTargetException invocation && invocation.getCause()!=null) error=invocation.getCause();
        JsonObject data=new JsonObject(); data.addProperty("status","unavailable");
        data.addProperty("type",error.getClass().getName()); data.addProperty("message",error.getMessage()); return data;
    }
    private static JsonArray ints(int[] values) { JsonArray result=new JsonArray(); for (int value:values) result.add(value); return result; }
    private static JsonArray numbers(float... values) { JsonArray result=new JsonArray(); for (float value:values) result.add(value); return result; }
    private static void nullable(JsonObject data,String name,Double value) { data.addProperty(name,value); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
}
