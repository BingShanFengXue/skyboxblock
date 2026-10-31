package dev.yg.skyboxblock;

import java.util.Locale;

/** Independent client sky clock. Does not modify world time or depend on the rendering API. */
public final class SkyTimeClock {
    public static final int DAY=24000;
    public enum Mode {
        AUTO, RUNNING, LOCKED;
        public String id() { return name().toLowerCase(Locale.ROOT); }
        public static Mode parse(String text) {
            if(text==null) throw new IllegalArgumentException("时间模式缺失");
            return switch(text.trim().toLowerCase(Locale.ROOT)) {
                case "auto" -> AUTO;
                case "running" -> RUNNING;
                case "locked" -> LOCKED;
                default -> throw new IllegalArgumentException("时间模式应为 auto、running 或 locked");
            };
        }
    }
    public record Setting(Mode mode,int ticks) {
        public static final Setting AUTO=new Setting(Mode.AUTO,0);
        public Setting {
            if(mode==null || ticks<0 || ticks>=DAY) throw new IllegalArgumentException("时间刻数应为 0..23999");
        }
    }
    private Setting previous;
    private Object owner;
    private long lastGameTick;
    private int elapsed;

    /** A new world starts from the saved configured time; offline time never advances this clock. */
    public void restart(Setting setting,Object world,long gameTick) {
        previous=setting;owner=world;lastGameTick=gameTick;elapsed=0;
    }
    public double sample(Setting setting,Object world,long gameTick,float partial,double automaticTicks) {
        if(!setting.equals(previous) || owner!=world) restart(setting,world,gameTick);
        if(setting.mode()==Mode.AUTO) return normalize(automaticTicks);
        if(setting.mode()==Mode.LOCKED) return setting.ticks();
        // Ignore a source-clock rewind instead of jumping backwards or overflowing the day counter.
        if(gameTick>=lastGameTick) {
            long delta;
            try { delta=Math.subtractExact(gameTick,lastGameTick); }
            catch(ArithmeticException overflow) {
                delta=Math.floorMod(gameTick,DAY)-Math.floorMod(lastGameTick,DAY);
            }
            elapsed=(int)Math.floorMod(elapsed+Math.floorMod(delta,DAY),DAY);
        }
        lastGameTick=gameTick;
        float fraction=Float.isFinite(partial) ? Math.max(0F,Math.min(1F,partial)) : 0F;
        return normalize(setting.ticks()+elapsed+(double)fraction);
    }
    public static double normalize(double ticks) {
        if(!Double.isFinite(ticks)) throw new IllegalArgumentException("时间刻数必须有限");
        double value=ticks%DAY;
        return value<0 ? value+DAY : value;
    }
    /** Invert the native Overworld phase curve to capture the currently displayed auto time. */
    public static double ticksForPhase(float phase) {
        if(!Float.isFinite(phase)) throw new IllegalArgumentException("天空时间角度必须有限");
        double target=phase-Math.floor(phase),lo=0,hi=1;
        for(int i=0;i<48;i++) {
            double fraction=(lo+hi)*.5;
            double value=(fraction*2+(.5-Math.cos(fraction*Math.PI)/2))/3;
            if(value<target) lo=fraction; else hi=fraction;
        }
        return normalize(((lo+hi)*.5+.25)*DAY);
    }
}