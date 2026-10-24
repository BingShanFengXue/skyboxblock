package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteTicker;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;

/** Copyright (c) 2026 Bing6512. Explicit owned-process input and static-water fixture controls. */
public final class FixtureClientControl {
    private static final boolean REGRESSION=Boolean.getBoolean("skyboxblock.regression");
    private static final boolean WATER=REGRESSION && Boolean.getBoolean("skyboxblock.regression.freezeWaterTextures");
    private static long inputTicks,waterTicks,frozenSprites,observedWaterSprites,staticWaterSprites;
    private FixtureClientControl() {}

    static boolean owns(Minecraft mc) {
        if (!REGRESSION || mc==null || mc.gameDirectory==null) return false;
        String declared=System.getProperty("skyboxblock.regression.ownedGameDir","");
        if (declared.isBlank()) return false;
        try {
            Path supplied=Path.of(declared); if(!supplied.isAbsolute()) return false;
            Path owner=supplied.toRealPath(),actual=mc.gameDirectory.toPath().toRealPath();
            if (!owner.equals(actual)) return false;
            boolean child=false;
            for(Path parent=owner.getParent();parent!=null;parent=parent.getParent())
                if(parent.getFileName()!=null && parent.getFileName().toString().equals("render-regression")) { child=true;break; }
            if(!child) return false;
            var server=mc.getSingleplayerServer();
            if(server==null) return false;
            return "Skybox render fixture".equals(server.getWorldData().getLevelName())
                    && server.getWorldPath(LevelResource.ROOT).toRealPath().equals(owner.resolve("saves").resolve("fixture"));
        } catch(IOException|RuntimeException error) { return false; }
    }
    public static void beforeTick(Minecraft mc) {
        if(!owns(mc)) return;
        // Both queued clicks and held/toggled mappings must be cleared BEFORE handleKeybinds.
        KeyMapping.releaseAll(); KeyMapping.resetToggleKeys(); inputTicks++;
    }
    public static SpriteTicker wrapWater(SpriteContents contents,SpriteTicker original) {
        if(!WATER || !contents.name().getNamespace().equals("minecraft")) return original;
        String name=contents.name().getPath();
        if(!name.equals("block/water_still") && !name.equals("block/water_flow")) return original;
        observedWaterSprites++;
        if(original==null) { staticWaterSprites++; return null; }
        return new SpriteTicker() {
            private boolean frozen;
            @Override public void tickAndUpload(int x,int y) {
                if(!owns(Minecraft.getInstance())) { frozen=false; original.tickAndUpload(x,y); return; }
                if(!frozen) {
                    // Use the actual resource's first animation frame, never an invented water texture.
                    contents.uploadFirstFrame(x,y); frozen=true; frozenSprites++;
                    SkyboxBlockMod.LOGGER.info("[skybox-regression] owned water sprite first-frame freeze {}",contents.name());
                }
                waterTicks++;
            }
            @Override public void close() { original.close(); }
        };
    }
    public static long inputTicks() { return inputTicks; }
    public static long waterTicks() { return waterTicks; }
    public static long frozenSprites() { return frozenSprites; }
    public static long observedWaterSprites() { return observedWaterSprites; }
    public static long staticWaterSprites() { return staticWaterSprites; }
}
