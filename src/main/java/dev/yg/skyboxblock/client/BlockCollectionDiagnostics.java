package dev.yg.skyboxblock.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.yg.skyboxblock.SkyBlockEntity;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Copyright (c) 2026 Bing6512. Read-only, explicitly enabled collection diagnostics. */
final class BlockCollectionDiagnostics {
    private static final boolean ENABLED=Boolean.getBoolean("skyboxblock.regression")
            && Boolean.getBoolean("skyboxblock.regression.collectionDiagnostics");
    private static final ArrayDeque<JsonObject> HISTORY=new ArrayDeque<>();
    private static Trace trace;
    private static String scene;
    private static CompletableFuture<JsonObject> serverProbe;

    private static final class Trace {
        final SkyWorldRenderer.Frame frame;
        final JsonArray attempts=new JsonArray();
        int afterOpaque;
        JsonObject opaque;
        Trace(SkyWorldRenderer.Frame frame) { this.frame=frame; }
    }
    private BlockCollectionDiagnostics() {}

    static void startScene(String name) {
        if (!ENABLED) return;
        scene=name; serverProbe=null; HISTORY.clear();
    }
    static void beginView(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || scene==null || !frame.root || frame.shadow) return;
        trace=new Trace(frame);
    }
    static void collect(SkyWorldRenderer.Frame frame,SkyBlockEntity block) {
        if (!ENABLED || trace==null || trace.frame!=frame) return;
        var data=position(block.getBlockPos());
        data.addProperty("afterOpaque",frame.drawn);
        data.addProperty("sameLevel",block.getLevel()==frame.level);
        data.addProperty("isRemoved",block.isRemoved());
        data.addProperty("cachedSkyBlock",block.getBlockState().is(SkyboxBlockMod.SKY_BLOCK));
        var pos=block.getBlockPos();
        var chunk=frame.level==null ? null : frame.level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);
        data.addProperty("currentChunkAvailable",chunk!=null);
        data.addProperty("currentBlock",chunk==null ? null : BuiltInRegistries.BLOCK.getKey(chunk.getBlockState(pos).getBlock()).toString());
        var raw=chunk==null ? null : chunk.getBlockEntities().get(pos);
        data.addProperty("incomingIdentity",identity(block)); data.addProperty("incomingLevelIdentity",identity(block.getLevel()));
        data.addProperty("rawMapSameEntity",raw==block); data.addProperty("rawIdentity",identity(raw));
        data.addProperty("rawRemoved",raw==null ? null : raw.isRemoved()); data.addProperty("rawLevelIdentity",raw==null ? null : identity(raw.getLevel()));
        boolean live=raw instanceof SkyBlockEntity && !raw.isRemoved() && raw.getLevel()==frame.level && raw.getBlockPos().equals(pos)
                && chunk.getBlockState(pos).is(SkyboxBlockMod.SKY_BLOCK);
        data.addProperty("wouldResolveToLiveEntity",live);
        String reason=frame.drawn ? "after-opaque" : block.getLevel()!=frame.level ? "different-level" : block.isRemoved() ? "incoming-removed"
                : chunk==null ? "chunk-unavailable" : !chunk.getBlockState(pos).is(SkyboxBlockMod.SKY_BLOCK) ? "not-current-sky"
                : raw!=block ? "raw-identity-different" : "strict-eligible";
        data.addProperty("strictReason",reason);
        data.addProperty("alreadyCollected",frame.collected(block.getBlockPos().asLong()));
        trace.attempts.add(data);
        if (frame.drawn) trace.afterOpaque++;
    }
    static void opaque(SkyWorldRenderer.Frame frame) {
        if (!ENABLED || trace==null || trace.frame!=frame) return;
        trace.opaque=snapshot(frame);
        HISTORY.addLast(trace.opaque.deepCopy());
        while(HISTORY.size()>16) HISTORY.removeFirst();
    }

    /** Waits only for one read-only server sample; never waits for a desired count or rebuilds terrain. */
    static boolean serverSampleReady(Minecraft mc) {
        if (!ENABLED) return true;
        if (serverProbe==null) {
            serverProbe=new CompletableFuture<>(); var future=serverProbe; var key=mc.level.dimension();
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level=mc.getSingleplayerServer().getLevel(key); var data=fixture(level);
                    data.addProperty("dimension",level.dimension().location().toString());
                    data.addProperty("gameTime",level.getGameTime()); future.complete(data);
                } catch(Exception error) { future.completeExceptionally(error); }
            });
            return false;
        }
        return serverProbe.isDone();
    }
    static JsonObject evidence(SkyWorldRenderer.Frame frame) {
        if (!ENABLED) return null;
        var data=new JsonObject(); data.addProperty("readOnlyDiagnostics",true); data.addProperty("scene",scene);
        data.addProperty("samplingPolicy","no force reload; no geometry-count readiness gate; one asynchronous server read");
        if (serverProbe!=null && serverProbe.isDone()) data.add("server",serverProbe.join());
        data.add("atEnd",snapshot(frame));
        if (trace!=null && trace.frame==frame) {
            data.add("atOpaque",trace.opaque); data.add("collectAttempts",trace.attempts.deepCopy());
            data.addProperty("collectAttemptsAfterOpaque",trace.afterOpaque);
        }
        var history=new JsonArray(); for(var entry:HISTORY) history.add(entry.deepCopy());
        data.add("recentOpaqueFrames",history); return data;
    }
    private static JsonObject snapshot(SkyWorldRenderer.Frame frame) {
        var data=new JsonObject(); data.addProperty("viewId",frame.viewId);
        data.addProperty("collectedBlocks",frame.blocks()); data.addProperty("vertices",frame.vertices());
        var collected=new JsonArray();
        for(long packed:frame.collectedPositions()) collected.add(position(BlockPos.of(packed)));
        data.add("collectedPositions",collected);
        data.addProperty("rendererLevelIdentity",identity(frame.level));
        data.addProperty("minecraftLevelIdentity",identity(Minecraft.getInstance().level));
        data.addProperty("rendererIsMinecraftLevel",frame.level==Minecraft.getInstance().level);
        data.add("client",fixture(frame.level));
        data.add("sodium",sodium()); return data;
    }
    private static JsonObject fixture(BlockGetter level) {
        var data=new JsonObject(); var rows=new JsonArray(); int sky=0,entities=0;
        var chunks=new java.util.HashMap<Long,net.minecraft.world.level.chunk.LevelChunk>();
        var availability=new JsonArray();
        for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) {
            net.minecraft.world.level.chunk.LevelChunk existing=null;
            if(level instanceof net.minecraft.world.level.Level world) existing=world.getChunkSource().getChunkNow(x,z);
            chunks.put(net.minecraft.world.level.ChunkPos.asLong(x,z),existing);
            var chunk=new JsonObject(); chunk.addProperty("x",x); chunk.addProperty("z",z); chunk.addProperty("alreadyLoaded",existing!=null); availability.add(chunk);
        }
        for(int y=140;y<=144;y++) {
            var row=new JsonObject(); row.addProperty("y",y); var cells=new JsonArray(); int rowSky=0,rowEntities=0;
            for(int x=-3;x<=3;x++) {
                var pos=new BlockPos(x,y,3); var chunk=chunks.get(net.minecraft.world.level.ChunkPos.asLong(x>>4,3>>4));
                var state=chunk==null ? null : chunk.getBlockState(pos);
                // The raw map never lazily creates a missing block entity or queues a terrain refresh.
                var entity=chunk==null ? null : chunk.getBlockEntities().get(pos);
                var cell=position(pos); cell.addProperty("chunkAvailable",chunk!=null);
                cell.addProperty("block",state==null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                if (state!=null && state.is(SkyboxBlockMod.SKY_BLOCK)) { sky++; rowSky++; }
                if (entity instanceof SkyBlockEntity) { entities++; rowEntities++; }
                cell.addProperty("entity",entity==null ? null : entity.getClass().getName()); cells.add(cell);
            }
            row.addProperty("skyBlocks",rowSky); row.addProperty("skyBlockEntities",rowEntities); row.add("cells",cells); rows.add(row);
        }
        int backing=0,floor=0;
        for(int x=-8;x<=8;x++) for(int y=140;y<=146;y++) {
            var state=fixtureState(chunks,new BlockPos(x,y,7)); if(state!=null && state.is(Blocks.SANDSTONE)) backing++;
        }
        for(int x=-8;x<=8;x++) for(int z=-14;z<=8;z++) {
            var state=fixtureState(chunks,new BlockPos(x,139,z)); if(state!=null && state.is(Blocks.BEDROCK)) floor++;
        }
        int occluderSandstone=0; var occluderRows=new JsonArray();
        for(int y=140;y<=145;y++) {
            var state=fixtureState(chunks,new BlockPos(0,y,0)); var row=new JsonObject(); row.addProperty("y",y);
            row.addProperty("block",state==null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            if(state!=null && state.is(Blocks.SANDSTONE)) occluderSandstone++;
            occluderRows.add(row);
        }
        int highSky=0,highEntities=0,highBacking=0,highFloor=0; var high=new JsonArray();
        for(int x=-5;x<=5;x++) for(int y=181;y<=190;y++) {
            var pos=new BlockPos(x,y,2); var chunk=chunks.get(net.minecraft.world.level.ChunkPos.asLong(x>>4,0));
            var state=chunk==null ? null : chunk.getBlockState(pos); var entity=chunk==null ? null : chunk.getBlockEntities().get(pos);
            if(state!=null && state.is(SkyboxBlockMod.SKY_BLOCK)) highSky++;
            if(entity instanceof SkyBlockEntity) highEntities++;
            if(entity!=null || state!=null && state.is(SkyboxBlockMod.SKY_BLOCK)) {
                var item=position(pos); item.addProperty("block",state==null ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                item.addProperty("entityIdentity",identity(entity));item.addProperty("removed",entity==null ? null : entity.isRemoved());
                item.addProperty("entityLevelIdentity",entity==null ? null : identity(entity.getLevel())); high.add(item);
            }
        }
        for(int x=-6;x<=6;x++) for(int y=180;y<=191;y++) { var s=fixtureState(chunks,new BlockPos(x,y,4));if(s!=null && s.is(Blocks.SANDSTONE)) highBacking++; }
        for(int x=-6;x<=6;x++) for(int z=-8;z<=4;z++) { var s=fixtureState(chunks,new BlockPos(x,179,z));if(s!=null && s.is(Blocks.BEDROCK)) highFloor++; }
        data.addProperty("highSkyBlocks",highSky);data.addProperty("highSkyEntities",highEntities);data.addProperty("highBacking",highBacking);data.addProperty("highFloor",highFloor);data.add("highSkyInstances",high);
        data.addProperty("occluderSandstone",occluderSandstone); data.add("occluderRows",occluderRows);
        data.addProperty("wallSkyBlocks",sky); data.addProperty("wallSkyBlockEntities",entities);
        data.addProperty("backingSandstoneBlocks",backing); data.addProperty("floorBedrockBlocks",floor); data.add("rows",rows);
        data.add("existingChunks",availability); data.addProperty("blockEntityReadPolicy","LevelChunk raw getBlockEntities map; no lazy creation");
        return data;
    }
    private static net.minecraft.world.level.block.state.BlockState fixtureState(java.util.Map<Long,net.minecraft.world.level.chunk.LevelChunk> chunks,BlockPos pos) {
        var chunk=chunks.get(net.minecraft.world.level.ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4));
        return chunk==null ? null : chunk.getBlockState(pos);
    }
    private static String identity(Object value) { return value==null ? null : value.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(value)); }
    private static JsonObject position(BlockPos pos) {
        var data=new JsonObject(); data.addProperty("x",pos.getX()); data.addProperty("y",pos.getY()); data.addProperty("z",pos.getZ()); return data;
    }
    private static Object call(Object target,String name,Class<?>[] types,Object... arguments) throws ReflectiveOperationException {
        var method=target.getClass().getMethod(name,types); method.setAccessible(true); return method.invoke(target,arguments);
    }
    private static Object call(Object target,String name) throws ReflectiveOperationException { return call(target,name,new Class<?>[0]); }
    private static String sectionKey(Object section) throws ReflectiveOperationException {
        return call(section,"getChunkX")+","+call(section,"getChunkY")+","+call(section,"getChunkZ");
    }
    private static JsonArray entities(Object array) {
        var data=new JsonArray(); if(array==null) return data;
        for(int i=0;i<Array.getLength(array);i++) if(Array.get(array,i) instanceof BlockEntity entity) {
            var item=position(entity.getBlockPos()); item.addProperty("type",entity.getClass().getName());
            item.addProperty("identity",identity(entity)); item.addProperty("removed",entity.isRemoved());
            item.addProperty("levelIdentity",identity(entity.getLevel()));item.addProperty("cachedBlock",BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString());data.add(item);
        }
        return data;
    }
    private static void listSections(Object renderLists,String iteratorName,boolean geometry,Set<String> sections) throws ReflectiveOperationException {
        var iterator=(Iterator<?>)call(renderLists,"iterator",new Class<?>[]{boolean.class},false);
        while(iterator.hasNext()) {
            Object list=iterator.next(),region=call(list,"getRegion");
            Object entries=geometry ? call(list,iteratorName,new Class<?>[]{boolean.class},false) : call(list,iteratorName);
            if(entries==null) continue;
            while((Boolean)call(entries,"hasNext")) {
                int index=((Number)call(entries,"nextByteAsInt")).intValue();
                Object section=call(region,"getSection",new Class<?>[]{int.class},index);
                if(section!=null) sections.add(sectionKey(section));
            }
        }
    }
    private static JsonObject sodium() {
        var data=new JsonObject();
        try {
            var type=Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            Object renderer=type.getMethod("instanceNullable").invoke(null);
            data.addProperty("present",renderer!=null); if(renderer==null) return data;
            data.addProperty("terrainComplete",(Boolean)call(renderer,"isTerrainRenderComplete"));
            data.addProperty("debug",String.valueOf(call(renderer,"getChunksDebugString")));
            var field=type.getDeclaredField("renderSectionManager"); field.setAccessible(true); Object manager=field.get(renderer);
            var updated=manager.getClass().getDeclaredField("lastUpdatedFrame"); updated.setAccessible(true);
            data.addProperty("managerLastUpdatedFrame",updated.getInt(manager));
            data.addProperty("visibilitySemantics","cached lastVisibleFrame equals managerLastUpdatedFrame; actual render-list membership recorded separately");
            data.addProperty("uploadFrameSemantics","Sodium lastUploadFrame stores the uploaded output submission generation, not wall-clock upload time");
            Object lists=call(manager,"getRenderLists"); Set<String> entityList=new HashSet<>(),geometryList=new HashSet<>();
            listSections(lists,"sectionsWithEntitiesIterator",false,entityList);
            listSections(lists,"sectionsWithGeometryIterator",true,geometryList);
            var sections=new JsonArray();
            var getSection=manager.getClass().getDeclaredMethod("getRenderSection",int.class,int.class,int.class); getSection.setAccessible(true);
            for(int x=-1;x<=0;x++) for(int y:new int[]{8,9,11}) {
                var item=new JsonObject(); item.addProperty("x",x); item.addProperty("y",y); item.addProperty("z",0);
                String key=x+","+y+",0"; Object section=getSection.invoke(manager,x,y,0);
                item.addProperty("exists",section!=null); item.addProperty("inEntitiesRenderList",entityList.contains(key));
                item.addProperty("inGeometryRenderList",geometryList.contains(key));
                item.addProperty("visible",(Boolean)call(manager,"isSectionVisible",new Class<?>[]{int.class,int.class,int.class},x,y,0));
                item.addProperty("boxVisible",(Boolean)call(renderer,"isBoxVisible",new Class<?>[]{double.class,double.class,double.class,double.class,double.class,double.class},
                        (double)(x*16),(double)(y*16),0D,(double)((x+1)*16),(double)((y+1)*16),16D));
                if(section!=null) {
                    item.addProperty("built",(Boolean)call(section,"isBuilt")); item.addProperty("flags",((Number)call(section,"getFlags")).intValue());
                    item.addProperty("lastVisibleFrame",((Number)call(section,"getLastVisibleFrame")).intValue());
                    item.addProperty("lastUploadFrame",((Number)call(section,"getLastUploadFrame")).intValue());
                    item.addProperty("lastSubmittedFrame",((Number)call(section,"getLastSubmittedFrame")).intValue());
                    item.addProperty("pendingUpdate",String.valueOf(call(section,"getPendingUpdate")));
                    item.addProperty("taskPending",call(section,"getTaskCancellationToken")!=null);
                    item.add("culledBlockEntities",entities(call(section,"getCulledBlockEntities")));
                    item.add("globalBlockEntities",entities(call(section,"getGlobalBlockEntities")));
                }
                sections.add(item);
            }
            data.add("sections",sections);
        } catch(ClassNotFoundException absent) { data.addProperty("present",false); }
        catch(ReflectiveOperationException|RuntimeException error) {
            data.addProperty("inspectionError",error.getClass().getName()+": "+error.getMessage());
        }
        return data;
    }
}
