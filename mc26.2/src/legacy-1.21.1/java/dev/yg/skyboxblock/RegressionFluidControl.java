package dev.yg.skyboxblock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** Copyright (c) 2026 Bing6512. Explicit owned-fixture scheduled-fluid control; never freezes world ticks. */
public final class RegressionFluidControl {
    private static final boolean ENABLED=Boolean.getBoolean("skyboxblock.regression")
            && Boolean.getBoolean("skyboxblock.regression.freezeFluidTicks")
            && !Boolean.getBoolean("skyboxblock.regression.negativeGlobalFluidFreeze");
    private static final AtomicLong SUPPRESSED=new AtomicLong();
    private RegressionFluidControl() {}
    public static boolean suppress(ServerLevel level,BlockPos pos,Fluid fluid) {
        if (!ENABLED || fluid!=Fluids.WATER && fluid!=Fluids.FLOWING_WATER || level==null || pos==null) return false;
        if (level.dimension()!=net.minecraft.world.level.Level.OVERWORLD
                || pos.getX() < -2 || pos.getX()>2 || pos.getY()<140 || pos.getY()>147 || pos.getZ()!=2) return false;
        String suite=System.getProperty("skyboxblock.regression.suite","");
        if (!suite.equals("all") && !suite.equals("transparency")) return false;
        String declared=System.getProperty("skyboxblock.regression.ownedGameDir","");
        if (declared.isBlank() || !"Skybox render fixture".equals(level.getServer().getWorldData().getLevelName())) return false;
        try {
            Path requested=Path.of(declared);
            if (!requested.isAbsolute()) return false;
            Path owner=requested.toRealPath();
            Path world=level.getServer().getWorldPath(LevelResource.ROOT).toRealPath();
            if (!world.equals(owner.resolve("saves").resolve("fixture"))) return false;
            boolean allowed=false;
            for(Path parent=owner.getParent();parent!=null;parent=parent.getParent())
                if(parent.getFileName()!=null && parent.getFileName().toString().equals("render-regression")) { allowed=true;break; }
            if (!allowed) return false;
        } catch(IOException|java.nio.file.InvalidPathException|SecurityException error) { return false; }
        if (!level.getFluidState(pos).is(fluid)) return false;
        if (SUPPRESSED.getAndIncrement()==0) SkyboxBlockMod.LOGGER.info("[skybox-regression] owned scheduled water ticks suppressed; world/chunk/network ticks remain live");
        return true;
    }
    public static long suppressedTicks() { return SUPPRESSED.get(); }
}
