package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.*;
import java.lang.reflect.Field;
import java.util.*;

/** Bounded owned-object lifetime ledger; excludes all world/Iris/DH borrowed objects and shader-manager programs. */
public final class R30GpuLifetime {
    private record Key(String kind,int id) {}
    private static final HashMap<Key,Long> live=new HashMap<>();
    private static final HashSet<Key> retired=new HashSet<>();
    private static final JsonArray events=new JsonArray();
    private static final IdentityHashMap<RenderTarget,int[]> targets=new IdentityHashMap<>();
    private static long created,deleted,generation,duplicateCreates,unknownDeletes,droppedEvents;
    private static int peak;
    private static Field targetField,displayField;
    private R30GpuLifetime() {}
    public static void created(String kind,int id) {
        if(!R30Probe.ownerValidated() || id<=0) return;
        Key key=new Key(kind,id);
        if(live.containsKey(key)) { duplicateCreates++; event("duplicate-create",key,live.get(key)); return; }
        long gen=++generation; live.put(key,gen); created++; peak=Math.max(peak,live.size()); event("create",key,gen);
    }
    public static void deleted(String kind,int id) {
        if(!R30Probe.ownerValidated() || id<=0) return;
        Key key=new Key(kind,id); Long gen=live.remove(key);
        if(gen==null) { unknownDeletes++; event("untracked-delete",key,-1); return; }
        deleted++; retired.add(key); event("delete",key,gen);
    }
    private static void event(String what,Key key,long gen) {
        if(events.size()>=50000) { droppedEvents++; return; }
        JsonObject e=new JsonObject(); e.addProperty("event",what); e.addProperty("kind",key.kind()); e.addProperty("id",key.id());
        e.addProperty("generation",gen); e.addProperty("nanoTime",System.nanoTime()); events.add(e);
    }
    /** Called at private canvas render/release RETURN. Only pointer comparisons when the target is unchanged. */
    public static void observeTargets() {
        if(!R30Probe.ownerValidated()) return;
        try {
            if(targetField==null) {
                Class<?> type=Class.forName("dev.yg.skyboxblock.client.VanillaSkyScene");
                targetField=type.getDeclaredField("target"); displayField=type.getDeclaredField("displayTarget");
                targetField.setAccessible(true); displayField.setAccessible(true);
            }
            Object a=targetField.get(null),b=displayField.get(null);
            ArrayList<RenderTarget> removed=null;
            for(var entry:targets.entrySet()) {
                RenderTarget target=entry.getKey();
                if(target==a || target==b) continue;
                // Minecraft invalidates these handles on destroyBuffers. A still-owned old
                // target is deliberately NOT balanced away: it remains an open allocation.
                if(target.frameBufferId<=0 && target.getColorTextureId()<=0 && target.getDepthTextureId()<=0) {
                    int[] ids=entry.getValue(); deleted("framebuffer",ids[0]); deleted("texture",ids[1]); deleted("texture",ids[2]);
                    if(removed==null) removed=new ArrayList<>(); removed.add(target);
                }
            }
            if(removed!=null) for(RenderTarget target:removed) targets.remove(target);
            if(a instanceof RenderTarget target && !targets.containsKey(target)) targetCreated(target);
            if(b instanceof RenderTarget target && !targets.containsKey(target)) targetCreated(target);
        } catch(ReflectiveOperationException error) { throw new IllegalStateException("Owned target lifetime observation failed",error); }
    }
    private static void targetCreated(RenderTarget target) {
        int[] ids={target.frameBufferId,target.getColorTextureId(),target.getDepthTextureId()}; targets.put(target,ids);
        created("framebuffer",ids[0]); created("texture",ids[1]); created("texture",ids[2]);
    }
    private static boolean exists(Key key) {
        return switch(key.kind()) { case "texture" -> GL11.glIsTexture(key.id()); case "framebuffer" -> GL30.glIsFramebuffer(key.id());
            case "buffer" -> GL15.glIsBuffer(key.id()); case "vertexArray" -> GL30.glIsVertexArray(key.id()); default -> false; };
    }
    public static JsonObject snapshot(boolean inspectRetired) {
        JsonObject out=new JsonObject(); out.addProperty("created",created); out.addProperty("deleted",deleted); out.addProperty("live",live.size());
        out.addProperty("peakObservedLive",peak); out.addProperty("duplicateCreates",duplicateCreates); out.addProperty("unknownDeletes",unknownDeletes);
        out.addProperty("droppedEvents",droppedEvents); out.addProperty("managedCanvasObjects",targets.size());
        out.addProperty("scope","Owned direct GL allocation/delete calls plus observed private TextureTarget ownership transitions; not total driver VRAM");
        out.addProperty("balancedCount",created-deleted==live.size());
        if(inspectRetired) {
            JsonArray unretired=new JsonArray(),invalidLive=new JsonArray();
            for(Key key:retired) if(!live.containsKey(key) && exists(key)) { JsonObject e=new JsonObject();e.addProperty("kind",key.kind());e.addProperty("id",key.id());unretired.add(e); }
            for(Key key:live.keySet()) if(!exists(key)) { JsonObject e=new JsonObject();e.addProperty("kind",key.kind());e.addProperty("id",key.id());invalidLive.add(e); }
            out.add("retiredNameStillLiveWithoutNewAllocation",unretired); out.add("trackedNameNoLongerLive",invalidLive); out.add("events",events.deepCopy());
        }
        return out;
    }
}
