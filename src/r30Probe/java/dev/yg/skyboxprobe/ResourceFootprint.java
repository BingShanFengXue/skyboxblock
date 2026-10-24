package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.*;
import java.lang.reflect.*;
import java.nio.Buffer;
import java.util.*;

/** Read-only inventory of known skybox-owned objects, not total driver VRAM or borrowed Iris/DH resources. */
final class ResourceFootprint {
    private static final String PREFIX="dev.yg.skyboxblock.client.";
    private ResourceFootprint() {}
    static JsonObject snapshot() throws ReflectiveOperationException {
        JsonObject result=new JsonObject(); JsonArray objects=new JsonArray(); Set<String> live=new HashSet<>();
        add(objects,live,"world.depthTexture","texture",number("SkyWorldRenderer",null,"depthTexture"));
        mesh(objects,live,"world.mesh",field("SkyWorldRenderer",null,"MESH"));
        mesh(objects,live,"sky.mesh",field("VanillaSkyScene",null,"MESH"));
        target(objects,live,"sky.worldTarget",field("VanillaSkyScene",null,"target"));
        target(objects,live,"sky.displayTarget",field("VanillaSkyScene",null,"displayTarget"));
        Object slots=field("SkyCoverage",null,"SLOTS");
        int count=0; for(Object slot:(Iterable<?>)slots) {
            add(objects,live,"coverage."+count+".fbo","framebuffer",number(null,slot,"framebuffer"));
            add(objects,live,"coverage."+count+".color","texture",number(null,slot,"color")); count++;
        }
        result.addProperty("coverageSlots",count);
        add(objects,live,"itrp.bridgeFbo","framebuffer",number("ItrpSkyBridge",null,"framebuffer"));
        Object clouds=field("SkyVanillaClouds",null,"privateBuffer");
        if(clouds!=null) {
            mesh(objects,live,"cloud.mesh",clouds);
            add(objects,live,"cloud.mesh.ebo","buffer",number(null,clouds,"ebo"));
        }
        try {
            add(objects,live,"edge-history.fbo","framebuffer",number("SkyTemporalEdgeMask",null,"framebuffer"));
            int[] edgeTextures=(int[])field("SkyTemporalEdgeMask",null,"textures");
            for(int i=0;i<edgeTextures.length;i++) add(objects,live,"edge-history.texture-"+i,"texture",edgeTextures[i]);
            result.addProperty("edgeHistoryClassPresent",true);
        } catch(ClassNotFoundException absent) { result.addProperty("edgeHistoryClassPresent",false); }
        result.add("objects",objects); result.addProperty("liveUniqueObjects",live.size());
        long bytes=0,cpu=0; for(JsonElement entry:objects) {
            JsonObject item=entry.getAsJsonObject(); if(item.has("bufferBytes")) bytes+=item.get("bufferBytes").getAsLong();
            if(item.has("cpuUploadCapacityBytes")) cpu+=item.get("cpuUploadCapacityBytes").getAsLong();
        }
        result.addProperty("observedGpuBufferBytes",bytes); result.addProperty("observedCpuUploadCapacityBytes",cpu);
        result.addProperty("scope","Known mod-owned textures/FBO/VAO/VBO/EBO; excludes resource-manager shader programs and all borrowed world/Iris/DH objects");
        result.addProperty("notTotalVram",true); return result;
    }
    private static void mesh(JsonArray out,Set<String> live,String label,Object mesh) throws ReflectiveOperationException {
        if(mesh==null) return;
        add(out,live,label+".vao","vertexArray",number(null,mesh,"vao"));
        add(out,live,label+".vbo","buffer",number(null,mesh,"vbo"));
        try {
            Object upload=field(null,mesh,"upload");
            if(upload instanceof Buffer buffer) {
                JsonObject item=new JsonObject(); item.addProperty("label",label+".upload");
                item.addProperty("kind","cpuUpload"); item.addProperty("cpuUploadCapacityBytes",(long)buffer.capacity()*Float.BYTES); out.add(item);
            }
        } catch(NoSuchFieldException absent) { /* SkyCloudMesh has no CPU upload field. */ }
    }
    private static void target(JsonArray out,Set<String> live,String label,Object object) {
        if(!(object instanceof RenderTarget target)) return;
        add(out,live,label+".fbo","framebuffer",target.frameBufferId);
        add(out,live,label+".color","texture",target.getColorTextureId());
        add(out,live,label+".depth","texture",target.getDepthTextureId());
        JsonObject size=new JsonObject(); size.addProperty("label",label+".dimensions");
        size.addProperty("width",target.width); size.addProperty("height",target.height); out.add(size);
    }
    private static void add(JsonArray out,Set<String> live,String label,String kind,int id) {
        if(id<=0) return;
        boolean exists=switch(kind) { case "texture" -> GL11.glIsTexture(id); case "framebuffer" -> GL30.glIsFramebuffer(id);
            case "buffer" -> GL15.glIsBuffer(id); case "vertexArray" -> GL30.glIsVertexArray(id); default -> false; };
        JsonObject item=new JsonObject(); item.addProperty("label",label); item.addProperty("kind",kind);
        item.addProperty("id",id); item.addProperty("live",exists);
        if(exists) live.add(kind+":"+id);
        if(exists && kind.equals("buffer")) {
            int previous=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,id); item.addProperty("bufferBytes",GL32.glGetBufferParameteri64(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_SIZE)); }
            finally { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,previous); }
        }
        out.add(item);
    }
    private static int number(String type,Object receiver,String name) throws ReflectiveOperationException { return ((Number)field(type,receiver,name)).intValue(); }
    private static Object field(String type,Object receiver,String name) throws ReflectiveOperationException {
        Class<?> cls=receiver!=null ? receiver.getClass() : Class.forName(PREFIX+type);
        for(Class<?> p=cls;p!=null;p=p.getSuperclass()) {
            try { Field field=p.getDeclaredField(name); field.setAccessible(true); return field.get(receiver); }
            catch(NoSuchFieldException absent) { }
        } throw new NoSuchFieldException(cls.getName()+"."+name);
    }
}
