package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;

/** Immutable view snapshot. Shared settings affect only the private sky, never the actual dimension. */
public final class SkyDisplayState {
    private static final DimensionSpecialEffects OVERWORLD=new DimensionSpecialEffects.OverworldEffects();
    public final String timeSource,nativeSkyType,skyClockMode;
    public final long gameTime,dayTime;
    public final double displayTicks;
    public final float phase,nativePhase,rain,thunder,starBrightness;
    public final int moonPhase;
    public final boolean nativeFixedTime;
    public final Vec3 skyColor,fogColor,cloudColor;
    public final float[] sunrise;
    public final SkyEnvironment environment;
    public final float cloudOpacity;

    private SkyDisplayState(ClientLevel level,Vec3 camera,float partial) {
        SkyboxConfig config=SkyboxConfig.get();
        environment=config.environment();
        gameTime=level.getGameTime();dayTime=level.getDayTime();
        nativePhase=level.getTimeOfDay(partial);
        nativeSkyType=level.effects().skyType().name();
        nativeFixedTime=level.dimensionType().hasFixedTime();
        boolean normalAppearance=level.effects().skyType()==DimensionSpecialEffects.SkyType.NORMAL;
        boolean local=config.usesLocalTime(level.dimension().location().toString(),normalAppearance)
                && Float.isFinite(nativePhase);
        boolean customClock=config.skyTime().mode()!=SkyTimeClock.Mode.AUTO;
        skyClockMode=config.skyTime().mode().id();
        double autoTicks=local ? SkyTimeClock.ticksForPhase(nativePhase)
                : SkyTimeClock.normalize(Math.floorMod(gameTime,24000L)+(double)partial);
        displayTicks=SkyTimeController.sample(level,partial,autoTicks);
        timeSource=customClock ? "server-"+skyClockMode : (local ? "local-sky" : "simulated-world-ticks");
        long whole=(long)Math.floor(displayTicks);
        phase=customClock ? SkyRenderMath.simulatedPhase(whole,(float)(displayTicks-whole))
                : SkyRenderMath.displayPhase(local,nativePhase,gameTime,partial);
        moonPhase=environment.moonPhase(local ? Math.floorMod(level.getMoonPhase(),8)
                : SkyRenderMath.simulatedMoonPhase(gameTime));
        boolean localWeather=normalAppearance || config.simulatedLocalWeather();
        rain=localWeather ? level.getRainLevel(partial) : 0;
        thunder=localWeather ? level.getThunderLevel(partial) : 0;
        float daylight=Mth.clamp((float)Math.cos(phase*Math.PI*2)*2+.5F,0,1);
        Vec3 fog,dynamicSky,dynamicCloud;float dynamicStars;
        boolean nativeAppearance=local && normalAppearance && !customClock;
        if(nativeAppearance) {
            dynamicSky=level.getSkyColor(camera,partial);
            dynamicCloud=level.getCloudColor(partial);
            fog=Vec3.fromRGB24(level.getBiome(BlockPos.containing(camera)).value().getFogColor());
            dynamicStars=level.getStarBrightness(partial);
        } else {
            var palette=normalAppearance ? level.getBiome(BlockPos.containing(camera)).value()
                    : level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS).value();
            dynamicSky=weather(Vec3.fromRGB24(palette.getSkyColor()).scale(daylight),rain,.75F,thunder,.75F);
            fog=Vec3.fromRGB24(palette.getFogColor());
            float light=Mth.clamp(Mth.cos(phase*((float)Math.PI*2))*2+.5F,0,1);
            float grey=1-.4F*rain*.95F;
            dynamicCloud=weather(new Vec3(grey*(light*.9F+.1F),grey*(light*.9F+.1F),
                    grey*(light*.85F+.15F)),0,0,thunder,.95F);
            float stars=Mth.clamp(1-(Mth.cos(phase*((float)Math.PI*2))*2+.25F),0,1);
            dynamicStars=stars*stars*.5F;
        }
        Vec3 dynamicFog=nativeAppearance ? new Vec3(
                dev.yg.skyboxblock.mixin.FogColorAccess.skyboxblock$fogRed(),
                dev.yg.skyboxblock.mixin.FogColorAccess.skyboxblock$fogGreen(),
                dev.yg.skyboxblock.mixin.FogColorAccess.skyboxblock$fogBlue())
                : OVERWORLD.getBrightnessDependentFogColor(fog,daylight);
        skyColor=environment.has("sky_color") ? Vec3.fromRGB24(environment.color("sky_color",0)) : dynamicSky;
        fogColor=environment.has("fog_color") ? Vec3.fromRGB24(environment.color("fog_color",0)) : dynamicFog;
        cloudColor=environment.has("cloud_color") ? Vec3.fromRGB24(environment.color("cloud_color",0)) : dynamicCloud;
        cloudOpacity=environment.has("cloud_color") ? (environment.color("cloud_color",0)>>>24)/255F : 1F;
        starBrightness=environment.number("star_brightness",dynamicStars);
        float[] dawn=OVERWORLD.getSunriseColor(phase,partial);
        if(environment.has("sunrise_sunset_color")) {
            int argb=environment.color("sunrise_sunset_color",0);
            sunrise=new float[]{((argb>>>16)&255)/255F,((argb>>>8)&255)/255F,(argb&255)/255F,(argb>>>24)/255F};
        } else sunrise=dawn==null ? null : dawn.clone();
    }
    private static Vec3 weather(Vec3 color,float rain,float rainWeight,float thunder,float thunderWeight) {
        float r=(float)color.x,g=(float)color.y,b=(float)color.z;
        if(rain>0) {
            float grey=(r*.3F+g*.59F+b*.11F)*.6F,keep=1-rain*rainWeight;
            r=r*keep+grey*(1-keep);g=g*keep+grey*(1-keep);b=b*keep+grey*(1-keep);
        }
        if(thunder>0) {
            float grey=(r*.3F+g*.59F+b*.11F)*.2F,keep=1-thunder*thunderWeight;
            r=r*keep+grey*(1-keep);g=g*keep+grey*(1-keep);b=b*keep+grey*(1-keep);
        }
        return new Vec3(r,g,b);
    }
    public static SkyDisplayState sample(ClientLevel level,Vec3 camera,float partial) {
        return new SkyDisplayState(level,camera,partial);
    }
}