package dev.yg.skyboxblock.client;

import com.google.gson.JsonObject;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import java.util.concurrent.CompletableFuture;

/** Copyright (c) 2026 Bing6512. Fixture precondition, never a production terrain refresh. */
final class FixtureStartup {
    private static final boolean LEGACY=Boolean.getBoolean("skyboxblock.regression")
            && Boolean.getBoolean("skyboxblock.regression.negativeFixtureStartup");
    private static CompletableFuture<Integer> serverChunks;
    private static int checks,stableTicks;
    private static boolean complete;
    private static JsonObject evidence;
    private FixtureStartup() {}

    static boolean ready(Minecraft mc) {
        if (complete) return true;
        if (LEGACY) {
            evidence=new JsonObject(); evidence.addProperty("legacyWithoutStartupPrecondition",true);
            complete=true; return true;
        }
        if (serverChunks==null) {
            serverChunks=new CompletableFuture<>(); var future=serverChunks; var key=mc.level.dimension();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level=mc.getSingleplayerServer().getLevel(key); int count=0;
                    for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) if(level.getChunkSource().getChunkNow(x,z)!=null) count++;
                    future.complete(count);
                } catch(Exception error) { future.completeExceptionally(error); }
            });
        }
        int client=0,compiled=0;
        for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) if(mc.level.getChunkSource().getChunkNow(x,z)!=null) client++;
        for(int x=-1;x<=0;x++) for(int y=8;y<=9;y++) if(mc.levelRenderer.isSectionCompiled(new BlockPos(x*16,y*16,0))) compiled++;
        Integer server=serverChunks.isDone() ? serverChunks.join() : null;
        boolean sodium=net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        // Native empty sections far above the spawn camera need not be compiled before the first edit.
        // Sodium initializes known-empty sections immediately, so its initial-build precondition is specific.
        boolean ready=client==4 && (!sodium || compiled==4) && server!=null && server==4 && mc.options.renderDistance().get()==12;
        stableTicks=ready ? stableTicks+1 : 0;
        evidence=new JsonObject(); evidence.addProperty("legacyWithoutStartupPrecondition",false);
        evidence.addProperty("clientExistingChunks",client); evidence.addProperty("compiledFixtureSections",compiled);
        evidence.addProperty("serverExistingChunks",server); evidence.addProperty("checks",++checks);
        evidence.addProperty("stableClientTicks",stableTicks); evidence.addProperty("renderDistance",mc.options.renderDistance().get());
        evidence.addProperty("compiledTargetSectionsRequired",sodium);
        evidence.addProperty("rendererPolicy",sodium ? "Sodium known-empty target sections initialized" : "native existing chunks; out-of-view empty target compilation is not required");
        evidence.addProperty("serverSampling","one successful existing-chunk snapshot on the server thread, not 20 consecutive server samples");
        evidence.addProperty("policy","before first edit: server chunk snapshot plus 20 stable client ticks under renderer-specific initialization; no forced load or renderer refresh");
        if(server!=null && server!=4) serverChunks=null;
        if(checks==1 || checks%40==0) SkyboxBlockMod.LOGGER.info("[skybox-regression] startup precondition {}",evidence);
        if(checks>600) throw new IllegalStateException("Fixture startup precondition did not become ready: "+evidence);
        if(stableTicks<20) return false;
        complete=true; SkyboxBlockMod.LOGGER.info("[skybox-regression] startup ready {}",evidence); return true;
    }
    static JsonObject evidence() { return evidence; }
}
