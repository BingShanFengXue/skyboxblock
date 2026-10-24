package dev.yg.skyboxprobe;

import com.google.gson.*;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.yg.skyboxblock.client.ModCompat;
import org.lwjgl.opengl.*;
import java.lang.reflect.Method;
import java.util.*;

/** Sparse before/after checks of sky private transactions. Disabled for performance runs. */
public final class R30StateAudit {
    private static final boolean REQUESTED=Boolean.getBoolean("skyboxblock.r30.stateAudit");
    private static final Map<String,JsonObject> before=new HashMap<>();
    private static final JsonArray failures=new JsonArray();
    private static long calls,checked,failed;
    private static final Map<String,Long> stageCalls=new HashMap<>();
    private R30StateAudit() {}
    public static void before(String stage) {
        if(!REQUESTED || !R30Probe.ownedActive()) return;
        calls++;
        if(stageCalls.merge(stage,1L,Long::sum)%240!=0) { before.remove(stage); return; }
        before.put(stage,snapshot());
    }
    public static void after(String stage) {
        if(!REQUESTED || !R30Probe.ownedActive()) return;
        JsonObject previous=before.remove(stage); if(previous==null) return;
        JsonObject current=snapshot(); checked++;
        if(!previous.equals(current)) {
            failed++;
            if(failures.size()<64) {
                JsonObject failure=new JsonObject(); failure.addProperty("stage",stage); failure.addProperty("check",checked);
                failure.add("before",previous); failure.add("after",current); failures.add(failure);
            }
        }
    }
    public static JsonObject summary() {
        JsonObject out=new JsonObject(); out.addProperty("enabled",REQUESTED); out.addProperty("calls",calls);
        out.addProperty("sampledTransactions",checked); out.addProperty("failedTransactions",failed); out.add("failures",failures.deepCopy());
        out.addProperty("meaning","One every 240 instrumented calls; exact GL and Iris logical before/after comparisons, not all frames"); return out;
    }
    private static JsonObject snapshot() {
        JsonObject out=new JsonObject();
        int[] enums={GL30.GL_DRAW_FRAMEBUFFER_BINDING,GL30.GL_READ_FRAMEBUFFER_BINDING,GL20.GL_CURRENT_PROGRAM,
                GL30.GL_VERTEX_ARRAY_BINDING,GL15.GL_ARRAY_BUFFER_BINDING,GL11.GL_DEPTH_FUNC,GL11.GL_FRONT_FACE,
                GL11.GL_CULL_FACE_MODE,GL14.GL_BLEND_SRC_RGB,GL14.GL_BLEND_DST_RGB,GL14.GL_BLEND_SRC_ALPHA,GL14.GL_BLEND_DST_ALPHA};
        for(int value:enums) out.addProperty("gl-"+Integer.toHexString(value),GL11.glGetInteger(value));
        for(int value:new int[]{GL11.GL_DEPTH_TEST,GL11.GL_BLEND,GL11.GL_CULL_FACE,GL11.GL_SCISSOR_TEST,GL30.GL_CLIP_DISTANCE0})
            out.addProperty("enabled-"+Integer.toHexString(value),GL11.glIsEnabled(value));
        out.addProperty("depthWrite",GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        int[] viewport=new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport); out.add("viewport",new Gson().toJsonTree(viewport));
        double[] range=new double[2]; GL11.glGetDoublev(GL11.GL_DEPTH_RANGE,range); out.add("depthRange",new Gson().toJsonTree(range));
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE); out.addProperty("activeTexture",active);
        JsonArray textures=new JsonArray();
        for(int i=0;i<6;i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0+i); JsonObject unit=new JsonObject();
            unit.addProperty("texture2D",GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            unit.addProperty("sampler",GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,i)); textures.add(unit);
        } GL13.glActiveTexture(active); out.add("textures",textures);
        var shader=RenderSystem.getShader(); out.addProperty("renderSystemShader",shader==null ? -1 : shader.getId());
        out.add("shaderColor",new Gson().toJsonTree(RenderSystem.getShaderColor().clone()));
        out.addProperty("fogStart",RenderSystem.getShaderFogStart()); out.addProperty("fogEnd",RenderSystem.getShaderFogEnd());
        out.addProperty("fogShape",RenderSystem.getShaderFogShape().getIndex());
        if(ModCompat.irisActive()) for(String method:new String[]{"irisMainBound","irisOverridesShaders"}) {
            try { Method getter=ModCompat.class.getDeclaredMethod(method); getter.setAccessible(true); Object value=getter.invoke(null); out.add(method,new Gson().toJsonTree(value)); }
            catch(Exception error) { out.addProperty(method+"Unavailable",error.toString()); }
        }
        return out;
    }
}
