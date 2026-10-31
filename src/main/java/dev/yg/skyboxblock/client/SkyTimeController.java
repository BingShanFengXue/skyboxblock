package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyTimeClock;
import java.io.IOException;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;

/** Display-only clock reader; connected players share the server's persisted tick anchor. */
public final class SkyTimeController {
    private static final SkyTimeClock OFFLINE_CLOCK=new SkyTimeClock();
    private SkyTimeController() { }
    public static double currentTicks(ClientLevel level,float partial) {
        double automatic=0;
        if(level!=null) {
            boolean normal=level.effects().skyType()==DimensionSpecialEffects.SkyType.NORMAL;
            boolean local=SkyboxConfig.get().usesLocalTime(level.dimension().location().toString(),normal);
            float phase=level.getTimeOfDay(partial);
            automatic=local && Float.isFinite(phase) ? SkyTimeClock.ticksForPhase(phase)
                    : SkyTimeClock.normalize(Math.floorMod(level.getGameTime(),24000L)+(double)partial);
        }
        return sample(level,partial,automatic);
    }
    static double sample(ClientLevel level,float partial,double automatic) {
        long tick=level==null ? 0 : level.getGameTime();
        if(SkyboxServerState.enforced()) return SkyboxServerState.get().clockTicks(tick,partial,automatic);
        return OFFLINE_CLOCK.sample(SkyboxConfig.get().skyTime(),level,tick,partial,automatic);
    }
    public static String describe(ClientLevel level) {
        int ticks=(int)Math.floorMod(Math.round(currentTicks(level,0)),24000L);
        return "time = "+ticks+" / "+SkyboxConfig.get().skyTime().mode().id();
    }
    // Retained only for isolated legacy class-path tests. Gameplay mutations use server commands.
    private static void save(SkyTimeClock.Setting setting,ClientLevel level) throws IOException {
        SkyboxConfig.setSkyTime(setting); // Config rejects local writes while server authority is active.
        if(!SkyboxConfig.skyTimeFromJvm())
            OFFLINE_CLOCK.restart(SkyboxConfig.get().skyTime(),level,level==null ? 0 : level.getGameTime());
    }
    public static void set(int ticks,ClientLevel level) throws IOException {
        save(new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING,ticks),level);
    }
    public static void lock(Integer ticks,ClientLevel level) throws IOException {
        int fixed=ticks==null ? (int)Math.floorMod(Math.round(currentTicks(level,0)),24000L) : ticks;
        save(new SkyTimeClock.Setting(SkyTimeClock.Mode.LOCKED,fixed),level);
    }
    public static void unlock(ClientLevel level) throws IOException {
        if(SkyboxConfig.get().skyTime().mode()!=SkyTimeClock.Mode.LOCKED) return;
        save(new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING,(int)Math.floorMod(Math.round(currentTicks(level,0)),24000L)),level);
    }
    public static void reset() throws IOException { SkyboxConfig.resetSkyTime(); }
}