package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Random;

/** Regression for the new world-space path; the deleted CPU/NDC renderer is not exercised. */
public final class SkyGeometryRegression {
    private static int checks;
    private static void require(boolean pass, String text) { checks++; if (!pass) throw new AssertionError(text); }
    private static void near(float a,float b,float epsilon,String text) { require(Float.isFinite(a)&&Math.abs(a-b)<=epsilon,text+": "+a+" / "+b); }
    private static void panoramaRayRegression() {
        Random rng=new Random(0x523250414E4FL);
        int legacyDepthSplits=0;
        for(int i=0;i<10000;i++) {
            Matrix4f projection=new Matrix4f().perspective(.5F+rng.nextFloat()*1.6F,.7F+rng.nextFloat()*2.1F,.05F,2048);
            Matrix4f view=new Matrix4f().rotateX(rng.nextFloat()*2-1).rotateY(rng.nextFloat()*6.2F)
                    .rotateZ((rng.nextFloat()-.5F)*.15F).translateLocal((rng.nextFloat()-.5F)*.5F,
                            (rng.nextFloat()-.5F)*.5F,(rng.nextFloat()-.5F)*.5F);
            Matrix4f projectionBob=new Matrix4f();
            if((i&1)!=0) projectionBob.translate((rng.nextFloat()-.5F)*.2F,(rng.nextFloat()-.5F)*.2F,0)
                    .rotateX((rng.nextFloat()-.5F)*.05F).rotateZ((rng.nextFloat()-.5F)*.05F);
            Matrix4f effectiveView=new Matrix4f(projectionBob).mul(view);
            Matrix4f baseProjection=new Matrix4f(projection);
            projection.mul(projectionBob);
            Matrix4f oldP=new Matrix4f(projection),oldV=new Matrix4f(view);
            float x=rng.nextFloat()*1.8F-.9F,y=rng.nextFloat()*1.8F-.9F;
            // Analytic camera basis, not the panorama inverse or its homogeneous subtraction.
            Vector3f oracle=new Matrix4f(effectiveView).invert()
                    .transformDirection(new Vector3f(x/baseProjection.m00(),y/baseProjection.m11(),-1)).normalize();
            Matrix4f inverse=SkyRenderMath.panoramaRayInverse(projection,view,new Matrix4f());
            Vector4f h0=inverse.transform(new Vector4f(x,y,-1,1)); h0.div(h0.w);
            Vector4f h1=inverse.transform(new Vector4f(x,y,0,1)); h1.div(h1.w);
            Vector3f actual=new Vector3f(h1.x-h0.x,h1.y-h0.y,h1.z-h0.z).normalize();
            near(actual.x,oracle.x,4E-5F,"panorama ray x independent of translated face");
            near(actual.y,oracle.y,4E-5F,"panorama ray y independent of translated face");
            near(actual.z,oracle.z,4E-5F,"panorama ray z independent of translated face");
            near(actual.length(),1,1E-5F,"panorama unit direction");
            require(projection.equals(oldP)&&view.equals(oldV),"panorama helper never mutates vanilla geometry matrices");
            Vector3f origin=new Matrix4f(effectiveView).invert().transformPosition(new Vector3f());
            Vector3f oldNear=new Vector3f(oracle).mul(1.2F).add(origin).normalize();
            Vector3f oldFar=new Vector3f(oracle).mul(12F).add(origin).normalize();
            if(oldNear.distance(oldFar)>.001F) legacyDepthSplits++;
        }
        require(legacyDepthSplits>9000,"legacy panorama exposes same-material depth edges under camera bob");
        System.out.println("R32 legacy translated-face splits: "+legacyDepthSplits+" / 10000");
    }
    public static void main(String[] args) throws Exception {
        panoramaRayRegression();
        for(float[] face:SkyWorldRenderer.FACES) {
            Vector3f a=new Vector3f(face[0],face[1],face[2]);
            Vector3f b=new Vector3f(face[3],face[4],face[5]);
            Vector3f c=new Vector3f(face[6],face[7],face[8]);
            Vector3f n=b.sub(a,new Vector3f()).cross(c.sub(a,new Vector3f()));
            require(n.dot(new Vector3f(a).sub(.5F,.5F,.5F))>0,"outward cube winding");
        }
        Random random=new Random(0x5A5B0A);
        for(int i=0;i<10000;i++) {
            Matrix4f vp=new Matrix4f().perspective(.3F+random.nextFloat()*2.4F,.4F+random.nextFloat()*3,.05F,2048)
                    .rotateX(random.nextFloat()*6.2F).rotateY(random.nextFloat()*6.2F);
            float x=random.nextFloat()*1.8F-.9F,y=random.nextFloat()*1.8F-.9F;
            Vector3f ray=SkyRenderMath.ray(new Matrix4f(vp).invert(),x,y);
            Vector4f q=vp.transform(new Vector4f(new Vector3f(ray).mul(10),1));
            near(q.x/q.w,x,2E-4F,"ray projection x"); near(q.y/q.w,y,2E-4F,"ray projection y");
            near(ray.length(),1,1E-5F,"unit sky direction");
        }
        // Independent equality: full-target raster coordinates must equal private-sky coordinates.
        // Include non-square/odd windows, every ITRP FSR ratio and an oblique w row.
        for (int[] size : new int[][]{{1280,800},{1024,768},{1279,799}}) {
            for (float factor : new float[]{1F,.6667F,.5882F,.5F,.3333F}) {
                int activeWidth=(int)Math.floor(size[0]*factor),activeHeight=(int)Math.floor(size[1]*factor);
                float sx=(float)activeWidth/size[0],sy=(float)activeHeight/size[1];
                for (int i=0;i<400;i++) {
                    Matrix4f projection=new Matrix4f().perspective(1.2F,(float)size[0]/size[1],.05F,2048);
                    if ((i&1)!=0) projection.m03(.013F).m13(-.009F);
                    Matrix4f original=new Matrix4f(projection);
                    float jx=(random.nextFloat()*2-1)/size[0],jy=(random.nextFloat()*2-1)/size[1];
                    Matrix4f skyProjection=SkyRenderMath.jitteredSkyProjection(projection,sx,sy,jx,jy,new Matrix4f());
                    Vector4f point=new Vector4f(random.nextFloat()*4-2,random.nextFloat()*2-1,-2-random.nextFloat()*10,1);
                    Vector4f world=projection.transform(new Vector4f(point));
                    Vector4f sky=skyProjection.transform(new Vector4f(point));
                    float worldX=((world.x/world.w*sx+sx-1+jx)*.5F+.5F)*size[0];
                    float worldY=((world.y/world.w*sy+sy-1+jy)*.5F+.5F)*size[1];
                    near(worldX,(sky.x/sky.w*.5F+.5F)*activeWidth,.001F,"FSR sky/raster pixel x");
                    near(worldY,(sky.y/sky.w*.5F+.5F)*activeHeight,.001F,"FSR sky/raster pixel y");
                    near(world.z/world.w,sky.z/sky.w,1E-6F,"jitter preserves world depth");
                    require(projection.equals(original),"sky jitter never mutates world projection");
                }
            }
        }
        for (float invalid : new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY}) {
            boolean rejected=false;
            try { SkyRenderMath.jitteredSkyProjection(new Matrix4f(),invalid,1,0,0,new Matrix4f()); }
            catch (IllegalArgumentException expected) { rejected=true; }
            require(rejected,"invalid raster scale rejected before projection");
        }
        near(SkyRenderMath.VANILLA_CLOUD_HEIGHT,192F,0,"vanilla cloud layer locked to 192");
        for (int i=0;i<10000;i++) {
            float cameraY=-64+random.nextFloat()*448;
            double relative=SkyRenderMath.cloudRelativeY(cameraY);
            near((float)(relative+cameraY-.33F),192F,.00004F,"cloud world height invariant under camera altitude");
        }
        int legacyMismatches=0;
        for (int i=0;i<50000;i++) {
            int block=random.nextInt(59999998)-29999999;
            double camera=block+(random.nextDouble()-.5)*256;
            float a=SkyRenderMath.relativeGridVertex(block,1,camera);
            float b=SkyRenderMath.relativeGridVertex(block+1,0,camera);
            require(Float.floatToIntBits(a)==Float.floatToIntBits(b),"shared grid endpoint has identical bits");
            near(a,(float)((double)(block+1)-camera),0,"grid coordinate rounded exactly once");
            if (Float.floatToIntBits((float)(block-camera)+1F)!=Float.floatToIntBits((float)(block+1-camera))) legacyMismatches++;
        }
        require(legacyMismatches>100,"old separately rounded endpoints detected by independent negative control");
        System.out.println("Legacy shared-edge mismatches: "+legacyMismatches+" / 50000");
        near(SkyRenderMath.simulatedPhase(6000,0),0,0,"simulated noon");
        near(SkyRenderMath.simulatedPhase(18000,0),.5F,0,"simulated midnight");
        for (int i=0;i<30000;i++) {
            long tick=random.nextInt(2000000000)-1000000000L;
            float partial=random.nextFloat(),nativePhase=(random.nextFloat()-.5F)*20;
            float shown=SkyRenderMath.simulatedPhase(tick,partial);
            require(Float.isFinite(shown)&&shown>=0&&shown<=1,"finite simulated phase");
            near(SkyRenderMath.simulatedPhase(tick+192000L,partial),shown,0,"simulated phase has eight-day periodicity");
            require(SkyRenderMath.simulatedMoonPhase(tick+24000L)==(SkyRenderMath.simulatedMoonPhase(tick)+1)%8,"moon advances one phase per simulated day");
            near(SkyRenderMath.displayPhase(true,nativePhase,tick,partial),nativePhase,0,"local phase passthrough respects arbitrary offsets/day length/fixed phase");
            near(SkyRenderMath.displayPhase(false,nativePhase,tick,partial),shown,0,"fallback independent of native fixed phase");
        }
        for (int i=0;i<10000;i++) {
            long current=random.nextInt(2000000000),requested=random.nextInt(192000);
            long next=RenderRegression.forwardFixtureTime(current,requested);
            require(next>=current && next-current<24000,"fixture engine gameTime never rewinds");
            require(Math.floorMod(next,24000)==Math.floorMod(requested,24000),"fixture keeps requested phase while moving forward");
        }
        require(RenderRegression.forwardFixtureTime(18080,42000)==42000,"next fixture midnight advances moon without rolling back");
        near(SkyRenderMath.displayPhase(true,Float.NaN,18000,0),.5F,0,"invalid native phase falls back");
        Properties policy=new Properties();
        var defaults=SkyboxConfig.fromProperties(policy);
        require(defaults.usesLocalTime("fixture:fixed",true),"normal fixed-time sky uses actual native phase");
        require(!defaults.usesLocalTime("fixture:end",false),"nonstandard sky uses simulated time");
        require(!defaults.simulatedLocalWeather(),"fallback weather defaults to clear");
        policy.setProperty("localTimeDimensions","fixture:custom, fixture:both");
        policy.setProperty("simulatedTimeDimensions","fixture:normal,fixture:both");
        policy.setProperty("simulatedWeather","local");
        var overrides=SkyboxConfig.fromProperties(policy);
        require(overrides.usesLocalTime("fixture:custom",false),"explicit local override for custom sky mechanism");
        require(!overrides.usesLocalTime("fixture:normal",true),"explicit simulated override");
        require(!overrides.usesLocalTime("fixture:both",true),"simulated override wins explicit conflict");
        require(overrides.simulatedLocalWeather(),"fallback local weather is explicit opt-in");
        near(SkyRenderMath.sunDirection(0).y,1,1E-5F,"noon sun above");
        near(SkyRenderMath.sunDirection(.5F).y,-1,1E-5F,"midnight sun below");
        near(SkyRenderMath.sunDirection(.25F).x,-1,1E-5F,"sunset direction");
        for(int i=0;i<1000;i++) {
            float nearDepth=-1,farDepth=-1;
            float distance=.5F+i*.25F;
            Matrix4f p=new Matrix4f().perspective(1.2F,1.6F,.05F,2048);
            Vector4f a=p.transform(new Vector4f(0,0,-distance,1));
            Vector4f b=p.transform(new Vector4f(0,0,-distance-3,1));
            nearDepth=a.z/a.w; farDepth=b.z/b.w;
            require(nearDepth<farDepth,"near pillar must occlude farther sky for every tested camera distance");
        }
        Vector4f plane=SkyRenderMath.relativeClipPlane(1E7,140,3,1E7+2,140,3,1,0,0);
        near(plane.w,-2,0,"large-world subtraction before float");
        require(plane.dot(new Vector4f(3,0,0,1))>0,"positive portal halfspace");
        Properties file=new Properties(); file.setProperty("clouds","false");
        require(!SkyboxConfig.fromProperties(file).clouds(),"cloud file option");
        near(SkyboxConfig.fromProperties(new Properties()).vanillaCloudHeight(),192,0,"default cloud height preserved");
        for(float height:new float[]{-128,96,192,240,1024}) {
            file.setProperty("vanillaCloudHeight",Float.toString(height));
            near(SkyboxConfig.fromProperties(file).vanillaCloudHeight(),height,0,"independent file cloud height");
            near((float)SkyRenderMath.cloudRelativeY(140,height),height-140F+.33F,0,"custom height offset");
        }
        for(String bad:new String[]{"NaN","Infinity","-Infinity","bad","32769","-32769"}) {
            file.setProperty("vanillaCloudHeight",bad);
            near(SkyboxConfig.fromProperties(file).vanillaCloudHeight(),192,0,"invalid height uses default");
        }
        Properties cloudOptions=new Properties();
        near(SkyboxConfig.fromProperties(cloudOptions).vanillaCloudFadeStart(),128,0,"default cloud fade start");
        near(SkyboxConfig.fromProperties(cloudOptions).vanillaCloudFadeEnd(),384,0,"default cloud fade end");
        for(float distance:new float[]{16,32,128,384,400,512,768,1024,2048,4096}) {
            cloudOptions.setProperty("vanillaCloudFadeEnd",Float.toString(distance));
            cloudOptions.setProperty("vanillaCloudFadeStart",Float.toString(distance/3F));
            var config=SkyboxConfig.fromProperties(cloudOptions);
            near(config.vanillaCloudFadeEnd(),distance,0,"cloud distance property preserved");
            near(config.vanillaCloudFadeStart(),distance/3F,0,"cloud fade property preserved");
            int radius=SkyCloudRange.radiusFor(distance);
            require(radius==(distance<=384 ? 0 : (int)Math.ceil(distance/96.0)),"cloud mesh grows without changing cloud cell scale");
            try(var range=SkyCloudRange.build(distance)) {
                require(SkyCloudRange.minimumCell(-3)==(radius==0 ? -3 : -radius),"private cloud minimum");
                require(SkyCloudRange.maximumCell(4)==(radius==0 ? 4 : radius),"private cloud maximum");
                int outside=java.util.concurrent.CompletableFuture.supplyAsync(() -> SkyCloudRange.minimumCell(-3)).join();
                require(outside==-3,"ordinary cloud builder on another thread unaffected");
            }
            require(SkyCloudRange.minimumCell(-3)==-3 && SkyCloudRange.maximumCell(4)==4,"cloud scope restores original world range");
        }
        for(String bad:new String[]{"NaN","Infinity","-Infinity","bad","0","15.99","4097"})
            near(SkyboxConfig.parseCloudDistance(bad),384,0,"invalid cloud range defaults without nonfinite uniforms");
        for(String bad:new String[]{"NaN","Infinity","-Infinity","bad","-1","384","4096"})
            near(SkyboxConfig.parseCloudFadeStart(bad,384),128,0,"invalid/inverted cloud fade defaults to end/3");
        try(var outer=SkyCloudRange.build(768)) {
            require(SkyCloudRange.maximumCell(4)==8,"outer cloud range");
            try(var inner=SkyCloudRange.build(384)) { require(SkyCloudRange.maximumCell(4)==4,"nested default cloud range"); }
            require(SkyCloudRange.maximumCell(4)==8,"nested cloud range restores outer");
        }
        Matrix4f cloudBase=new Matrix4f().perspective(1.2F,1.6F,.05F,256),cloudBefore=new Matrix4f(cloudBase);
        Matrix4f cloudFar=SkyCloudRange.projection(cloudBase,768);
        require(cloudBase.equals(cloudBefore),"cloud range never mutates world projection");
        require(cloudFar!=cloudBase,"private cloud far clipping expands when necessary");
        near(cloudFar.m00(),cloudBase.m00(),0,"cloud screen X projection unchanged");
        near(cloudFar.m11(),cloudBase.m11(),0,"cloud screen Y projection unchanged");
        Vector4f cloudInside=cloudFar.transform(new Vector4f(0,0,-768,1));
        Vector4f cloudOutside=cloudFar.transform(new Vector4f(0,0,-800,1));
        require(cloudInside.z/cloudInside.w<1 && cloudOutside.z/cloudOutside.w>1,"cloud range fits private far clip with explicit padding");
        Matrix4f alreadyFar=new Matrix4f().perspective(1.2F,1.6F,.05F,2048);
        require(SkyCloudRange.projection(alreadyFar,384)==alreadyFar,"default cloud projection remains bit-identical");
        for(int phase=0;phase<8;phase++) {
            float u=(phase%4)/4F,v=(phase/4)/2F;
            float[] moon=SkyVanillaGeometry.celestial(20,-100,true,u,v,u+.25F,v+.5F);
            float[] actual={-20,-100,20,u+.25F,v+.5F,20,-100,20,u,v+.5F,20,-100,-20,u,v,
                    -20,-100,20,u+.25F,v+.5F,20,-100,-20,u,v,-20,-100,-20,u+.25F,v};
            for(int i=0;i<actual.length;i++) near(moon[i],actual[i],0,"vanilla moon vertex/UV phase "+phase);
        }
        require(SkyVanillaGeometry.disc(16).length==72,"native sky disc eight triangles");
        require(SkyVanillaGeometry.dawn(new float[]{1,.5F,.2F,.75F}).length==336,"native dawn sixteen triangles");
        String old=System.getProperty("skyboxblock.clouds");
        try { System.setProperty("skyboxblock.clouds","true"); require(SkyboxConfig.fromProperties(file).clouds(),"JVM option wins"); }
        finally { if(old==null)System.clearProperty("skyboxblock.clouds");else System.setProperty("skyboxblock.clouds",old); }
        var scopeField=SkyWorldRenderer.class.getDeclaredField("GAME_VIEWS"); scopeField.setAccessible(true);
        var scopes=(java.util.ArrayDeque<?>)scopeField.get(null);
        for (int i=0;i<1000;i++) {
            SkyWorldRenderer.beginFrame();
            require(scopes.size()==1,"root invocation scope starts clean");
            try {
                SkyWorldRenderer.beginFrame();
                require(scopes.size()==2,"nested invocation owns a distinct scope");
            } finally { SkyWorldRenderer.endFrame(false); }
            require(scopes.size()==1,"cancelled nested invocation restores parent scope");
            SkyWorldRenderer.endFrame(false);
            require(scopes.isEmpty(),"cancelled root invocation leaves no retained scope");
        }
        String report="PASS: "+checks+" assertions; world-space geometry, 10000 independent sky rays, 6000 FSR raster/jitter transforms, 1000 distance occlusion orders, 10000 fixed cloud-height invariants, 50000 shared-grid endpoints with old-rounding negative control, 30000 conditional sky-time/moon properties and explicit policy overrides, 10000 forward-only fixture clock phases, sun directions, portal plane and config precedence\n";
        System.out.print(report);
        if(args.length>0) { Path path=Path.of(args[0]); Files.createDirectories(path.toAbsolutePath().getParent()); Files.writeString(path,report,StandardCharsets.UTF_8); }
    }
}
