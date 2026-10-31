package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Executable tests against the actual render helpers, not a translated Python formula. */
public final class SkyEnvironmentRenderRegression {
    private static int assertions;
    private static void check(boolean condition,String message) {
        assertions++; if(!condition) throw new AssertionError(message);
    }
    private static void near(double a,double b,double epsilon,String message) {
        check(Math.abs(a-b)<=epsilon,message+": "+a+" != "+b);
    }
    private static SkyEnvironment env(String key,String value) {
        return SkyEnvironment.fromEntries(Map.of("environment.visual/"+key,value));
    }
    public static void main(String[] args) throws Exception {
        SkyEnvironment empty=SkyEnvironment.EMPTY;
        near(SkyEnvironmentMath.fogStart(empty,0),0,0,"auto fog start");
        near(SkyEnvironmentMath.fogEnd(empty,128),128,0,"auto fog end");
        near(SkyEnvironmentMath.cloudStart(empty,128,384),128,0,"auto cloud start");
        near(SkyEnvironmentMath.cloudEnd(empty,384),384,0,"auto cloud end");
        check(!SkyEnvironmentMath.hasFog(empty),"auto no fog override");
        check(SkyEnvironmentMath.hasFog(env("fog_end_distance","1")),"fog override detection");
        near(SkyEnvironmentMath.fogEnd(env("fog_end_distance","1"),128),96,0,"vanilla fog end minimum");
        near(SkyEnvironmentMath.fogEnd(env("sky_fog_end_distance","2048"),128),2048,0,"sky fog independent range");
        var both=SkyEnvironment.fromEntries(Map.of("environment.visual/fog_end_distance","96",
                "environment.visual/sky_fog_end_distance","2048"));
        near(SkyEnvironmentMath.fogEnd(both,128),2048,0,"explicit sky fog wins rather than min");
        near(SkyEnvironmentMath.cloudEnd(env("fog_end_distance","96"),384),384,0,"cloud end independent");
        near(SkyEnvironmentMath.cloudStart(env("cloud_fog_end_distance","768"),128,768),256,0,"cloud start end/3");
        near(SkyEnvironmentMath.cloudStart(env("fog_start_distance","-64"),128,768),-64,0,"negative cloud fog start");
        near(SkyEnvironmentMath.visibility(50,100,10),0,0,"reversed fog edges step");
        near(SkyEnvironmentMath.visibility(5,100,10),1,0,"reversed fog edges before end");
        near(SkyEnvironmentMath.visibility(100,100,100),0,0,"equal fog edges no NaN");
        float previous=1;
        for(int i=0;i<=1000;i++) {
            float visible=SkyEnvironmentMath.visibility(i/10F,-16,96);
            check(Float.isFinite(visible) && visible>=0 && visible<=1,"finite bounded fog");
            check(visible<=previous,"fog monotonic"); previous=visible;
        }
        check(Float.floatToIntBits(SkyEnvironmentMath.cloudHeight(env("cloud_height","192.33"),1))
                ==Float.floatToIntBits(192F),"192.33 environment converts bitwise to nominal192");
        Random random=new Random(35);
        for(int i=0;i<10000;i++) {
            double camera=random.nextDouble()*2048-1024;
            near(SkyRenderMath.cloudRelativeY(camera,SkyEnvironmentMath.cloudHeight(empty,192)),
                    SkyRenderMath.cloudRelativeY(camera,192),0,"auto cloud relative Y unchanged");
            near(SkyRenderMath.cloudRelativeY(camera,SkyEnvironmentMath.cloudHeight(env("cloud_height","192.33"),1)),
                    SkyRenderMath.cloudRelativeY(camera,192),0,"explicit default cloud height exact");
            float angle=Float.intBitsToFloat(random.nextInt());
            if(!Float.isFinite(angle)) continue;
            float normalized=SkyEnvironmentMath.angle(angle);
            check(Float.isFinite(normalized) && normalized>=0 && normalized<360,"normalized finite angle");
        }
        for(int degrees=-720;degrees<=720;degrees++) {
            float radians=(float)Math.toRadians(SkyEnvironmentMath.angle(degrees));
            Vector3f sun=new Matrix4f().rotateY((float)-Math.PI/2).rotateX(radians).transformPosition(new Vector3f(0,100,0));
            Vector3f moon=new Matrix4f().rotateY((float)-Math.PI/2)
                    .rotateX((float)Math.toRadians(SkyEnvironmentMath.angle(degrees)-180))
                    .transformPosition(new Vector3f(0,-100,0));
            near(sun.distance(moon),0,0.0001,"independent moon angle celestial origin");
        }
        float[] dome=SkyVanillaGeometry.occluder(.2F,.4F,.6F);
        check(dome.length>0 && dome.length%(6*7)==0,"occluder complete triangles");
        for(int at=0;at<dome.length;at+=7) {
            double length=Math.sqrt(dome[at]*dome[at]+dome[at+1]*dome[at+1]+dome[at+2]*dome[at+2]);
            near(length,64,0.00002,"occluder sphere radius");
            check(dome[at+6]>=0 && dome[at+6]<=1,"occluder alpha bounded");
            if(dome[at+1]<-7) near(dome[at+6],1,0,"below-horizon opaque");
            if(dome[at+1]>6.6) near(dome[at+6],0,.004,"upper edge transparent");
        }
        String report="PASS: "+assertions+" render-environment assertions\n";
        System.out.print(report);
        if(args.length>0) { Path path=Path.of(args[0]);Files.createDirectories(path.getParent());Files.writeString(path,report); }
    }
}