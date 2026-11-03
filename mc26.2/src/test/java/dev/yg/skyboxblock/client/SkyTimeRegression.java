package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import dev.yg.skyboxblock.SkyboxSettings;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.Set;

/** Real shared-clock implementation regression; no Minecraft window or world mutation. */
public final class SkyTimeRegression {
    private static int checks;
    private static void check(boolean ok,String text) { checks++;if(!ok)throw new AssertionError(text); }
    private static void near(double a,double b,double eps,String text) { check(Math.abs(a-b)<=eps,text+": "+a+" != "+b); }
    private static SkyboxSettings settings(SkyTimeClock.Mode mode,int tick,long anchor) {
        return new SkyboxSettings(SkyEnvironment.EMPTY,true,192,128,384,false,Set.of(),Set.of(),
                new SkyTimeClock.Setting(mode,tick),anchor,1);
    }
    public static void main(String[] args) throws Exception {
        double worst=0;
        for(int tick=0;tick<24000;tick++) {
            float phase=SkyRenderMath.simulatedPhase(tick,0);
            double restored=SkyTimeClock.ticksForPhase(phase);
            double error=Math.abs(SkyTimeClock.normalize(restored-tick+12000)-12000);
            worst=Math.max(worst,error);check(error<.01,"native phase inverse captures current tick");
            near(settings(SkyTimeClock.Mode.LOCKED,tick,123).clockTicks(99999,.9F,6000),tick,0,"locked ignores world advancement");
        }
        var running=settings(SkyTimeClock.Mode.RUNNING,23999,100);
        near(running.clockTicks(100,0,6000),23999,0,"running starts at saved value");
        near(running.clockTicks(101,.5F,6000),.5,0,"running wraps and interpolates");
        near(running.clockTicks(99,0,6000),23999,0,"anchor ahead does not wrap backwards");
        near(SkyboxSettings.DEFAULT.clockTicks(100,.5F,7000.25),7000.25,0,"auto uses native source unchanged");
        Random random=new Random(36);
        for(int i=0;i<10000;i++) {
            long anchor=random.nextLong(Long.MAX_VALUE/2),now=anchor+random.nextLong(Long.MAX_VALUE/2);
            int base=random.nextInt(24000);
            var a=settings(SkyTimeClock.Mode.RUNNING,base,anchor);
            var b=settings(SkyTimeClock.Mode.RUNNING,base,anchor);
            long delta=BigInteger.valueOf(now).subtract(BigInteger.valueOf(anchor)).mod(BigInteger.valueOf(24000)).longValueExact();
            double expected=(base+delta+.5)%24000;
            near(a.clockTicks(now,.5F,0),expected,0,"persisted anchor exact large tick arithmetic");
            near(a.clockTicks(now,.5F,0),b.clockTicks(now,.5F,23000),0,"independent readers share server phase");
        }
        SkyTimeClock local=new SkyTimeClock();Object world=new Object();
        var state=new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING,1000);
        near(local.sample(state,world,100,0,0),1000,0,"offline test clock initial");
        near(local.sample(state,world,110,0,0),1010,0,"offline test clock advances");
        near(local.sample(state,world,105,0,0),1010,0,"rewind does not jump");
        near(local.sample(new SkyTimeClock.Setting(SkyTimeClock.Mode.RUNNING,1000),world,106,.5F,0),1011.5,0,"equal reloaded settings keep anchor");
        for(int invalid:new int[]{-1,24000,Integer.MIN_VALUE,Integer.MAX_VALUE}) {
            try { new SkyTimeClock.Setting(SkyTimeClock.Mode.LOCKED,invalid);throw new AssertionError("invalid clock accepted"); }
            catch(IllegalArgumentException expected) {checks++;}
        }
        String report="PASS: "+checks+" sky time assertions; worst inverse phase error="+worst+" ticks\n";
        System.out.print(report);
        if(args.length>0) {Path path=Path.of(args[0]);Files.createDirectories(path.getParent());Files.writeString(path,report);}
    }
}