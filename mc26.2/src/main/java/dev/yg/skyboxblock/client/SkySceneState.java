package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;
import dev.yg.skyboxblock.SkyTimeClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector4f;

/** Private immutable-by-convention snapshot; no environment/world clock or native render state writes. */
final class SkySceneState {
    private static final java.util.WeakHashMap<ClientLevel,SkyTimeClock> CLOCKS=new java.util.WeakHashMap<>();
    final SkyEnvironment environment;
    final Vector4f sky=new Vector4f(),fog=new Vector4f(),cloud=new Vector4f();
    float sunAngle,moonAngle,starAngle,sunriseAngle,stars,rainBrightness,skyFogEnd;
    int sunrise,moonPhase;
    boolean darkDisc;
    double cloudTime;

    SkySceneState(LevelRenderState frame,float partial) {
        Minecraft mc=Minecraft.getInstance(); ClientLevel level=mc.level;
        var config=SkyboxConfig.get(); environment=config.environment();
        var nativeSky=frame.skyRenderState;
        boolean normal=nativeSky.skybox==DimensionType.Skybox.OVERWORLD;
        boolean local=level!=null && config.usesLocalTime(level.dimension().identifier().toString(),normal);
        boolean simulated=!normal || !local || config.skyTime().mode()!=SkyTimeClock.Mode.AUTO;
        sunAngle=nativeSky.sunAngle; moonAngle=nativeSky.moonAngle; starAngle=nativeSky.starAngle;
        stars=nativeSky.starBrightness; rainBrightness=nativeSky.rainBrightness;
        sunrise=nativeSky.sunriseAndSunsetColor;
        moonPhase=nativeSky.moonPhase==null ? 0 : nativeSky.moonPhase.index(); darkDisc=nativeSky.shouldRenderDarkDisc;
        color(sky,nativeSky.skyColor); color(cloud,frame.cloudColor);
        fog.set(frame.cameraRenderState.fogData.color); skyFogEnd=frame.cameraRenderState.fogData.skyEnd;
        cloudTime=frame.gameTime+partial;
        if(simulated) {
            float localPhase=normal ? nativeSky.sunAngle/(float)(Math.PI*2)
                    : level.environmentAttributes().getDimensionValue(net.minecraft.world.attribute.EnvironmentAttributes.SUN_ANGLE)/360F;
            double automatic=local ? SkyTimeClock.ticksForPhase(localPhase)
                    : Math.floorMod(frame.gameTime,24000L)+(double)partial;
            double ticks=SkyboxServerState.enforced()
                    ? SkyboxServerState.get().clockTicks(frame.gameTime,partial,automatic)
                    : CLOCKS.computeIfAbsent(level,key -> new SkyTimeClock()).sample(config.skyTime(),CLOCKS,frame.gameTime,partial,automatic);
            long whole=(long)Math.floor(ticks);
            float phase=SkyRenderMath.simulatedPhase(whole,(float)(ticks-whole));
            sunAngle=phase*(float)(Math.PI*2); moonAngle=sunAngle+(float)Math.PI; starAngle=sunAngle;
            float daylight=clamp((float)Math.cos(sunAngle)*2+.5F);
            float rain=level!=null && (normal || config.simulatedLocalWeather()) ? level.getRainLevel(partial) : 0;
            float thunder=level!=null && (normal || config.simulatedLocalWeather()) ? level.getThunderLevel(partial) : 0;
            // The reference uses the local biome, or registered Plains outside a normal sky.
            // Read the actual 26.2 data-driven palette; there is no screenshot-tuned haze constant.
            var palette=normal ? level.getBiome(frame.cameraRenderState.blockPos).value()
                    : level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value();
            var attributes=palette.getAttributes();
            var dimensionAttributes=normal ? level.dimensionType().attributes()
                    : level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                    .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value().attributes();
            var skyAttribute=net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR;
            var fogAttribute=net.minecraft.world.attribute.EnvironmentAttributes.FOG_COLOR;
            color(sky,attributes.applyModifier(skyAttribute,dimensionAttributes.applyModifier(skyAttribute,skyAttribute.defaultValue())));sky.w=1;
            sky.x*=daylight;sky.y*=daylight;sky.z*=daylight;weather(sky,rain,.75F,thunder,.75F);
            color(fog,attributes.applyModifier(fogAttribute,dimensionAttributes.applyModifier(fogAttribute,fogAttribute.defaultValue())));fog.w=1;
            fog.x*=daylight*.94F+.06F;fog.y*=daylight*.94F+.06F;fog.z*=daylight*.91F+.09F;
            weather(fog,rain,.5F,thunder,.5F);
            float grey=1-.4F*rain*.95F;
            var cloudAttribute=net.minecraft.world.attribute.EnvironmentAttributes.CLOUD_COLOR;
            color(cloud,dimensionAttributes.applyModifier(cloudAttribute,cloudAttribute.defaultValue()));
            cloud.x*=grey*(daylight*.9F+.1F);cloud.y*=grey*(daylight*.9F+.1F);cloud.z*=grey*(daylight*.85F+.15F);
            weather(cloud,0,0,thunder,.95F);
            rainBrightness=1-rain;
            float star=clamp(1-((float)Math.cos(sunAngle)*2+.25F)); stars=star*star*.5F;
            // A custom display clock changes the daily phase, not the local world's lunar day.
            if(!local) moonPhase=SkyRenderMath.simulatedMoonPhase(frame.gameTime);
            float c=(float)Math.cos(sunAngle);
            if(c>=-.4F && c<=.4F) {
                float t=c/.4F*.5F+.5F;
                float alpha=1-(1-(float)Math.sin(t*Math.PI))*.99F; alpha*=alpha;
                sunrise=argb(t*.3F+.7F,t*t*.7F+.2F,.2F,alpha);
            } else sunrise=0;
            darkDisc=normal && nativeSky.shouldRenderDarkDisc;
        }
        // Like the 1.21.1 reference, an object-angle override must not move the dawn band.
        sunriseAngle=sunAngle;
        if(environment.has("sky_color")) color(sky,environment.color("sky_color",0));
        if(environment.has("fog_color")) color(fog,environment.color("fog_color",0));
        if(environment.has("cloud_color")) {color(cloud,environment.color("cloud_color",0));cloud.w*=.8F;}
        if(environment.has("sunrise_sunset_color")) sunrise=environment.color("sunrise_sunset_color",0);
        if(environment.has("sun_angle")) sunAngle=angleRadians(environment.number("sun_angle",0));
        if(environment.has("moon_angle")) moonAngle=angleRadians(environment.number("moon_angle",0));
        if(environment.has("star_angle")) starAngle=angleRadians(environment.number("star_angle",0));
        if(environment.has("star_brightness")) stars=environment.number("star_brightness",stars);
        moonPhase=environment.moonPhase(moonPhase);
        skyFogEnd=SkyEnvironmentMath.fogEnd(environment,skyFogEnd);
    }
    static float angleRadians(float degrees) { return (float)Math.toRadians(SkyEnvironmentMath.angle(degrees)); }
    static Vector4f color(Vector4f out,int argb) {
        return out.set(((argb>>>16)&255)/255F,((argb>>>8)&255)/255F,(argb&255)/255F,(argb>>>24)/255F);
    }
    private static int argb(float r,float g,float b,float a) {
        return ((int)(clamp(a)*255)<<24)|((int)(clamp(r)*255)<<16)|((int)(clamp(g)*255)<<8)|(int)(clamp(b)*255);
    }
    private static float clamp(float v) { return Math.max(0,Math.min(1,v)); }
    private static void weather(Vector4f c,float rain,float rw,float thunder,float tw) {
        if(rain>0) { float grey=(c.x*.3F+c.y*.59F+c.z*.11F)*.6F,keep=1-rain*rw;
            c.x=c.x*keep+grey*(1-keep);c.y=c.y*keep+grey*(1-keep);c.z=c.z*keep+grey*(1-keep); }
        if(thunder>0) { float grey=(c.x*.3F+c.y*.59F+c.z*.11F)*.2F,keep=1-thunder*tw;
            c.x=c.x*keep+grey*(1-keep);c.y=c.y*keep+grey*(1-keep);c.z=c.z*keep+grey*(1-keep); }
    }
}
