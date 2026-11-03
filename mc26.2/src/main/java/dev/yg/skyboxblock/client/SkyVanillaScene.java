package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.mixin.SkyCloudDataAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.*;

/**
 * Independent vanilla scene: native extracted colors/angles/fog + native resource-pack cloud cells.
 * No SkyRenderer/CloudRenderer draw method, MC RenderPipeline, Iris phase, or world target is touched.
 * Sky disc, 16-segment dawn, 10842-seeded stars and celestial sizes are the native geometry.
 */
final class SkyVanillaScene implements AutoCloseable {
    private final SkyGlProgram program=new SkyGlProgram("scene");
    private int fbo,color,depth,width,height,sampler;
    private static final float[] UPPER=disc(16),LOWER=disc(-16),STARS=stars();
    private final Floats clouds=new Floats();
    private CloudRenderer.TextureData cloudData;
    private int cloudX=Integer.MIN_VALUE,cloudZ,cloudRadius;

    int render(SkySceneState state,Matrix4fc projection,Matrix4fc view,int w,int h,
               net.minecraft.world.phys.Vec3 camera) {
        ensure(w,h); GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo); GL11.glViewport(0,0,w,h);
        SkyGlScope.clean(); GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDepthMask(true);
        GL30.glClearBufferfv(GL11.GL_COLOR,0,new float[]{state.fog.x,state.fog.y,state.fog.z,1});
        GL30.glClearBufferfv(GL11.GL_DEPTH,0,new float[]{0}); GL11.glDepthMask(false);
        program.use(); program.vector4("FogColor",state.fog.x,state.fog.y,state.fog.z,state.fog.w);
        program.vector2("FogRange",0,state.skyFogEnd);
        Matrix4f mvp=new Matrix4f(projection).mul(view);
        setup(mvp,0,state.sky.x,state.sky.y,state.sky.z,1); program.draw(UPPER);
        if((state.sunrise>>>24)!=0) {
            GL11.glEnable(GL11.GL_BLEND); GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
            float flip=Math.sin(state.sunriseAngle)<0 ? (float)Math.PI : 0;
            Matrix4f dawnView=new Matrix4f(view).rotateX((float)Math.PI/2).rotateZ(flip).rotateZ((float)Math.PI/2);
            setup(new Matrix4f(projection).mul(dawnView),1,1,1,1,1); program.draw(dawn(state.sunrise));
        }
        GL11.glEnable(GL11.GL_BLEND); GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ZERO);
        celestial(state,projection,view,"sun",30,state.sunAngle,state.rainBrightness);
        String moon=net.minecraft.world.level.MoonPhase.values()[Math.floorMod(state.moonPhase,8)].getSerializedName();
        celestial(state,projection,view,"moon/"+moon,20,state.moonAngle,state.rainBrightness);
        float brightness=state.stars*(state.environment.has("star_brightness") ? 1 : state.rainBrightness);
        if(brightness>0) {
            setup(celestialMvp(projection,view,state.starAngle),1,brightness,brightness,brightness,brightness); program.draw(STARS);
        }
        GL11.glDisable(GL11.GL_BLEND);
        if(state.environment.has("has_sky_occluder") && state.environment.flag("has_sky_occluder",false)) {
            GL11.glEnable(GL11.GL_BLEND); GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);
            setup(mvp,1,state.fog.x,state.fog.y,state.fog.z,1); program.draw(occluder()); GL11.glDisable(GL11.GL_BLEND);
        } else if(!state.environment.has("has_sky_occluder") && state.darkDisc) {
            setup(new Matrix4f(projection).mul(new Matrix4f(view).translate(0,12,0)),1,0,0,0,1); program.draw(LOWER);
        }
        drawClouds(state,projection,view,camera);
        return color;
    }

    private void celestial(SkySceneState state,Matrix4fc p,Matrix4fc v,String name,float size,float angle,float alpha) {
        int texture=SkyWindowRenderer.texture(Identifier.withDefaultNamespace("textures/environment/celestial/"+name+".png"));
        setup(celestialMvp(p,v,angle),2,1,1,1,alpha); program.texture("Image",0,texture,sampler);
        float[] vertices=quad(-size,100,-size,size,100,-size,size,100,size,-size,100,size,1,1,1,1);
        // Native buildMoonPhases reverses both UV axes relative to buildCelestialQuad (the sun).
        if(name.startsWith("moon/")) flipMoonUv(vertices);
        program.draw(vertices);
    }
    private static Matrix4f celestialMvp(Matrix4fc p,Matrix4fc v,float angle) {
        return new Matrix4f(p).mul(new Matrix4f(v).rotateY(-(float)Math.PI/2).rotateX(angle));
    }
    private void setup(Matrix4fc matrix,int mode,float r,float g,float b,float a) {
        program.use();program.matrix("Mvp",matrix);program.integer("Mode",mode);program.vector4("Tint",r,g,b,a);
        program.vector4("PositionOffset",0,0,0,0);
    }
    private void drawClouds(SkySceneState state,Matrix4fc projection,Matrix4fc view,net.minecraft.world.phys.Vec3 camera) {
        var mc=Minecraft.getInstance(); var config=SkyboxConfig.get();
        if(!config.clouds()) return;
        CloudRenderer.TextureData data=((SkyCloudDataAccess)mc.levelRenderer.cloudRenderer()).skyboxblock$texture();
        if(data==null) return;
        double x=camera.x+(state.cloudTime%(data.width()*400L))*.03,z=camera.z+3.9600000381469727;
        int cellX=(int)Math.floor(x/12),cellZ=(int)Math.floor(z/12);
        float dx=(float)(x-cellX*12.0),dz=(float)(z-cellZ*12.0);
        float y=SkyEnvironmentMath.cloudHeight(state.environment,config.vanillaCloudHeight())+.33F-(float)camera.y;
        float end=SkyEnvironmentMath.cloudEnd(state.environment,config.vanillaCloudFadeEnd());
        int radius=(int)Math.ceil(end/12)+1;
        // Native-style cell cache: camera fractions, bob and cloud drift change only an offset uniform.
        if(data!=cloudData||cellX!=cloudX||cellZ!=cloudZ||radius!=cloudRadius) {
            clouds.clear();cloudData=data;cloudX=cellX;cloudZ=cellZ;cloudRadius=radius;
            for(int cz=-radius;cz<=radius;cz++) for(int cx=-radius;cx<=radius;cx++) {
                long cell=cell(data,cellX+cx,cellZ+cz); if(!cloudCellVisible(cell)) continue;
                // CloudRenderer.packCellData keeps the resource-pack ARGB above four face bits.
                int argb=cloudCellColor(cell);
                float r=((argb>>>16)&255)/255F,g=((argb>>>8)&255)/255F,b=(argb&255)/255F,alpha=(argb>>>24)/255F;
                float x0=cx*12,x1=x0+12,z0=cz*12,z1=z0+12;
                clouds.add(quad(x0,0,z1,x1,0,z1,x1,0,z0,x0,0,z0,r*.7F,g*.7F,b*.7F,alpha));
                clouds.add(quad(x0,4,z0,x1,4,z0,x1,4,z1,x0,4,z1,r,g,b,alpha));
                // Native occupancy accepts alpha>=10; legacy shader cutout accepts >=32.
                // A cut-out neighbour must expose the same wall as a fully empty neighbour.
                if((cell&8)!=0 || !cloudCellVisible(cell(data,cellX+cx,cellZ+cz-1))) clouds.add(quad(x1,0,z0,x0,0,z0,x0,4,z0,x1,4,z0,r*.8F,g*.8F,b*.8F,alpha));
                if((cell&4)!=0 || !cloudCellVisible(cell(data,cellX+cx+1,cellZ+cz))) clouds.add(quad(x1,0,z1,x1,0,z0,x1,4,z0,x1,4,z1,r*.9F,g*.9F,b*.9F,alpha));
                if((cell&2)!=0 || !cloudCellVisible(cell(data,cellX+cx,cellZ+cz+1))) clouds.add(quad(x0,0,z1,x1,0,z1,x1,4,z1,x0,4,z1,r*.8F,g*.8F,b*.8F,alpha));
                if((cell&1)!=0 || !cloudCellVisible(cell(data,cellX+cx-1,cellZ+cz))) clouds.add(quad(x0,0,z0,x0,0,z1,x0,4,z1,x0,4,z0,r*.9F,g*.9F,b*.9F,alpha));
            }
        }
        setup(new Matrix4f(projection).mul(view),3,state.cloud.x,state.cloud.y,state.cloud.z,state.cloud.w);
        program.vector4("PositionOffset",-dx,y,-dz,0);
        program.vector2("FogRange",SkyEnvironmentMath.cloudStart(state.environment,config.vanillaCloudFadeStart(),end),end);
        GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_GEQUAL);GL11.glDepthMask(true);
        // Native fancy clouds' depth prepass makes a closed cell alpha-blend only its nearest surface.
        GL11.glColorMask(false,false,false,false);program.draw(clouds.data,clouds.count);
        GL11.glColorMask(true,true,true,true); GL11.glDepthMask(false);GL11.glDepthFunc(GL11.GL_EQUAL);
        GL11.glEnable(GL11.GL_BLEND);GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
        program.draw(clouds.data,clouds.count); GL11.glDisable(GL11.GL_BLEND);GL11.glDisable(GL11.GL_DEPTH_TEST);
    }
    static void flipMoonUv(float[] vertices) {
        for(int at=3;at<vertices.length;at+=9) {vertices[at]=1-vertices[at];vertices[at+1]=1-vertices[at+1];}
    }
    static int cloudCellColor(long cell) { return (int)(cell>>4); }
    static boolean cloudCellVisible(long cell) { return (cloudCellColor(cell)>>>24)/255F*.8F>=.1F; }
    private static long cell(CloudRenderer.TextureData d,int x,int z) { return d.cells()[Math.floorMod(x,d.width())+Math.floorMod(z,d.height())*d.width()]; }
    private void ensure(int w,int h) {
        if(fbo!=0 && width==w && height==h) return;
        releaseTarget();width=w;height=h;fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);
        color=image(GL11.GL_RGBA8,w,h,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE);
        depth=image(GL30.GL_DEPTH_COMPONENT32F,w,h,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color,0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth,0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);SkyWindowRenderer.checkFramebuffer("private vanilla sky");
        // Native celestial sprites are pixel art and use nearest sampling, not the canvas/panorama filter.
        if(sampler==0) sampler=SkyWindowRenderer.sampler(false,false);
    }
    static int image(int internal,int w,int h,int format,int type) {
        int texture=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,internal,w,h,0,format,type,(java.nio.ByteBuffer)null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }
    private void releaseTarget() { if(fbo!=0) GL30.glDeleteFramebuffers(fbo);if(color!=0)GL11.glDeleteTextures(color);if(depth!=0)GL11.glDeleteTextures(depth);fbo=color=depth=0; }
    @Override public void close() {releaseTarget();if(sampler!=0)GL33.glDeleteSamplers(sampler);program.close();}

    static float[] quad(float x0,float y0,float z0,float x1,float y1,float z1,float x2,float y2,float z2,float x3,float y3,float z3,float r,float g,float b,float a) {
        Floats out=new Floats();float[][] p={{x0,y0,z0},{x1,y1,z1},{x2,y2,z2},{x3,y3,z3}};
        for(int i:new int[]{0,1,2,0,2,3}) out.vertex(p[i][0],p[i][1],p[i][2],i==1||i==2?1:0,i>=2?1:0,r,g,b,a);
        return out.array();
    }
    private static float[] disc(float y) {
        Floats out=new Floats();for(int i=0;i<8;i++) {
            out.vertex(0,y,0,0,0,1,1,1,1);
            for(int j=i;j<=i+1;j++) {double a=Math.toRadians(-180+j*45);out.vertex(Math.signum(y)*512*(float)Math.cos(a),y,512*(float)Math.sin(a),0,0,1,1,1,1);}
        }return out.array();
    }
    private static float[] dawn(int argb) {
        Vector4f c=SkySceneState.color(new Vector4f(),argb);Floats out=new Floats();
        for(int i=0;i<16;i++) {
            out.vertex(0,100,0,0,0,c.x,c.y,c.z,c.w);
            for(int j=i;j<=i+1;j++) {double a=j*Math.PI*2/16;float cos=(float)Math.cos(a);out.vertex((float)Math.sin(a)*120,cos*120,-cos*40*c.w,0,0,c.x,c.y,c.z,0);}
        }return out.array();
    }
    private static float[] stars() {
        Floats out=new Floats();RandomSource random=RandomSource.createThreadLocalInstance(10842L);
        for(int i=0;i<1500;i++) {
            float x=random.nextFloat()*2-1,y=random.nextFloat()*2-1,z=random.nextFloat()*2-1,size=.15F+random.nextFloat()*.1F;
            float length=x*x+y*y+z*z;if(length<=.010000001F||length>=1)continue;
            Vector3f center=new Vector3f(x,y,z).normalize(100);
            Matrix3f rotation=new Matrix3f().rotateTowards(new Vector3f(center).negate(),new Vector3f(0,1,0)).rotateZ(-(float)(random.nextDouble()*(float)Math.PI*2));
            Vector3f[] q=new Vector3f[4];float[][] corners={{size,-size},{size,size},{-size,size},{-size,-size}};
            for(int j=0;j<4;j++) q[j]=new Vector3f(corners[j][0],corners[j][1],0).mul(rotation).add(center);
            for(int j:new int[]{0,1,2,0,2,3}) out.vertex(q[j].x,q[j].y,q[j].z,0,0,1,1,1,1);
        }return out.array();
    }
    private static float[] occluder() {
        Floats out=new Floats();
        for(int band=0;band<16;band++)for(int slice=0;slice<32;slice++) {
            float[][] q=new float[4][4];
            for(int i=0;i<4;i++) {
                double e=Math.toRadians(-90+96F*(band+(i==1||i==2?1:0))/16),a=(slice+(i>=2?1:0))*Math.PI*2/32;
                float y=(float)Math.sin(e),edge=(float)Math.sin(Math.toRadians(6));
                q[i]=new float[]{64*(float)(Math.cos(e)*Math.cos(a)),64*y,64*(float)(Math.cos(e)*Math.sin(a)),1-Math.max(0,Math.min(1,(y+edge)/(2*edge)))};
            }
            for(int i:new int[]{0,1,2,0,2,3})out.vertex(q[i][0],q[i][1],q[i][2],0,0,1,1,1,q[i][3]);
        }return out.array();
    }
    static final class Floats {
        float[] data=new float[4096];int count;
        void clear(){count=0;}
        void add(float[] values){ensure(values.length);System.arraycopy(values,0,data,count,values.length);count+=values.length;}
        void vertex(float x,float y,float z,float u,float v,float r,float g,float b,float a){ensure(9);data[count++]=x;data[count++]=y;data[count++]=z;data[count++]=u;data[count++]=v;data[count++]=r;data[count++]=g;data[count++]=b;data[count++]=a;}
        private void ensure(int extra){if(count+extra>data.length)data=java.util.Arrays.copyOf(data,Math.max(data.length*2,count+extra));}
        float[] array(){return java.util.Arrays.copyOf(data,count);}
    }
}
