package dev.yg.skyboxblock.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.phys.Vec3;

/** One immutable display snapshot per view: Overworld appearance is independent of native sky type. */
public final class SkyDisplayState {
    private static final DimensionSpecialEffects OVERWORLD = new DimensionSpecialEffects.OverworldEffects();
    public final String timeSource, nativeSkyType;
    public final long gameTime, dayTime;
    public final float phase, nativePhase, rain, thunder, starBrightness;
    public final int moonPhase;
    public final boolean nativeFixedTime;
    public final Vec3 skyColor, fogColor, cloudColor;
    public final float[] sunrise;

    private SkyDisplayState(ClientLevel level, Vec3 camera, float partial) {
        gameTime=level.getGameTime(); dayTime=level.getDayTime();
        nativePhase=level.getTimeOfDay(partial);
        nativeSkyType=level.effects().skyType().name();
        nativeFixedTime=level.dimensionType().hasFixedTime();
        boolean normalAppearance=level.effects().skyType()==DimensionSpecialEffects.SkyType.NORMAL;
        boolean local=SkyboxConfig.get().usesLocalTime(level.dimension().location().toString(),normalAppearance)
                && Float.isFinite(nativePhase);
        timeSource=local ? "local-sky" : "simulated-world-ticks";
        phase=SkyRenderMath.displayPhase(local,nativePhase,gameTime,partial);
        moonPhase=local ? Math.floorMod(level.getMoonPhase(),8) : SkyRenderMath.simulatedMoonPhase(gameTime);
        boolean localWeather=normalAppearance || SkyboxConfig.get().simulatedLocalWeather();
        rain=localWeather ? level.getRainLevel(partial) : 0;
        thunder=localWeather ? level.getThunderLevel(partial) : 0;
        float daylight=Mth.clamp((float)Math.cos(phase*Math.PI*2)*2+.5F,0,1);
        Vec3 fog;
        if (local && normalAppearance) {
            // Preserve the actual normal-sky biome sampling, weather and native phase hooks.
            skyColor=level.getSkyColor(camera,partial);
            cloudColor=level.getCloudColor(partial);
            fog=Vec3.fromRGB24(level.getBiome(BlockPos.containing(camera)).value().getFogColor());
            starBrightness=level.getStarBrightness(partial);
        } else {
            // Never borrow End/Nether fog, biome sky color, cloud gating or native star rules.
            var palette=normalAppearance ? level.getBiome(BlockPos.containing(camera)).value()
                    : level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS).value();
            Vec3 base=Vec3.fromRGB24(palette.getSkyColor());
            skyColor=weather(base.scale(daylight),rain,.75F,thunder,.75F);
            fog=Vec3.fromRGB24(palette.getFogColor());
            float light=Mth.clamp(Mth.cos(phase*((float)Math.PI*2))*2+.5F,0,1);
            float grey=1-.4F*rain*.95F;
            cloudColor=weather(new Vec3(grey*(light*.9F+.1F),grey*(light*.9F+.1F),
                    grey*(light*.85F+.15F)),0,0,thunder,.95F);
            float stars=Mth.clamp(1-(Mth.cos(phase*((float)Math.PI*2))*2+.25F),0,1);
            starBrightness=stars*stars*.5F;
        }
        // FogRenderer includes the cubic biome sample, view-distance sky blend, directional
        // sunrise tint, weather and vision effects. A raw biome color times daylight does not.
        fogColor=local && normalAppearance ? new Vec3(
                dev.yg.skyboxblock.mixin.FogColorAccess.skyboxblock$fogRed(),
                dev.yg.skyboxblock.mixin.FogColorAccess.skyboxblock$fogGreen(),
                dev.yg.skyboxblock.mixin.FogColorAccess.skyboxblock$fogBlue())
                : OVERWORLD.getBrightnessDependentFogColor(fog,daylight);
        float[] dawn=OVERWORLD.getSunriseColor(phase,partial);
        sunrise=dawn==null ? null : dawn.clone(); // OverworldEffects reuses its own mutable array.
    }

    private static Vec3 weather(Vec3 color,float rain,float rainWeight,float thunder,float thunderWeight) {
        float r=(float)color.x,g=(float)color.y,b=(float)color.z;
        if (rain>0) {
            float grey=(r*.3F+g*.59F+b*.11F)*.6F, keep=1-rain*rainWeight;
            r=r*keep+grey*(1-keep); g=g*keep+grey*(1-keep); b=b*keep+grey*(1-keep);
        }
        if (thunder>0) {
            float grey=(r*.3F+g*.59F+b*.11F)*.2F, keep=1-thunder*thunderWeight;
            r=r*keep+grey*(1-keep); g=g*keep+grey*(1-keep); b=b*keep+grey*(1-keep);
        }
        return new Vec3(r,g,b);
    }

    public static SkyDisplayState sample(ClientLevel level,Vec3 camera,float partial) {
        return new SkyDisplayState(level,camera,partial);
    }
}
