package dev.yg.skyboxblock.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.*;

/** Current-view DH 3.3.3 depth adapter. Uses real observed RenderParams, never guessed clip planes. */
public final class SkyDistantDepth {
    private static long token=-1;
    private static Object level;
    private static float a,b,c,d,ndcScale,ndcBias;
    private static double cameraX,cameraY,cameraZ;
    private static final float[] viewRotation=new float[9];
    private static boolean valid,warned,observed,configured;
    private SkyDistantDepth() { }
    /** Optional LodRenderer mixin observes the actual submitted projection without changing DH. */
    public static void capture(Object params) {
        valid=false;if(ModCompat.irisShadowPass())return;
        try {
            Object matrix=ModCompat.field(params,"dhProjectionMatrix");
            float v23=number(matrix,"m23"),v32=number(matrix,"m32");
            a=number(matrix,"m22");d=number(matrix,"m33");
            // DH's public matrix is row-major. Validate orientation instead of assuming a JOML layout.
            if(Math.abs(v32+1)<1e-4F){b=v23;c=v32;}
            else if(Math.abs(v23+1)<1e-4F){b=v32;c=v23;}
            else return;
            Object proxy=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy").get(null);
            if(proxy==null)return;
            String range=String.valueOf(proxy.getClass().getMethod("getDepthRange").invoke(proxy));
            ndcScale=range.equals("ZERO_TO_ONE")?1:2;ndcBias=range.equals("ZERO_TO_ONE")?0:-1;
            Object exact=ModCompat.field(params,"exactCameraPosition");
            cameraX=((Number)ModCompat.field(exact,"x")).doubleValue();
            cameraY=((Number)ModCompat.field(exact,"y")).doubleValue();
            cameraZ=((Number)ModCompat.field(exact,"z")).doubleValue();
            Object model=ModCompat.field(params,"mcModelViewMatrix");
            for(int row=0;row<3;row++)for(int col=0;col<3;col++)viewRotation[row*3+col]=number(model,"m"+row+col);
            token=SkyWindowRenderer.transaction();level=Minecraft.getInstance().level;
            valid=Float.isFinite(a)&&Float.isFinite(b)&&Float.isFinite(c)&&Float.isFinite(d);
            if(valid&&!observed){observed=true;dev.yg.skyboxblock.SkyboxBlockMod.LOGGER.info("[skyboxblock] observed DH 3.3.3 view projection: token={} range={} camera=({},{},{})",token,range,cameraX,cameraY,cameraZ);}
        }catch(ReflectiveOperationException|RuntimeException error){warn(error);}
    }
    private static float number(Object object,String name)throws ReflectiveOperationException{return ((Number)ModCompat.field(object,name)).floatValue();}
    static void configure(SkyGlProgram shader,long viewToken,Vec3 camera,Matrix4fc view,int width,int height,int sampler) {
        if(!ModCompat.DISTANT_HORIZONS||!valid||viewToken!=token||level!=Minecraft.getInstance().level)return;
        double dx=camera.x-cameraX,dy=camera.y-cameraY,dz=camera.z-cameraZ;
        if(dx*dx+dy*dy+dz*dz>1e-6)return;
        // A portal may share the same clock transaction but has its own camera rotation and depth.
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)if(Math.abs(view.get(col,row)-viewRotation[row*3+col])>1e-4F)return;
        try {
            Object proxy=Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed").getField("renderProxy").get(null);
            if(proxy==null)return;
            Object result=proxy.getClass().getMethod("getDhDepthTextureGlId").invoke(proxy);
            if(!Boolean.TRUE.equals(ModCompat.field(result,"success")))return;
            Object payload=ModCompat.field(result,"payload");if(!(payload instanceof Number n))return;
            int texture=n.intValue();if(texture<=0||!GL11.glIsTexture(texture))return;
            GL13.glActiveTexture(GL13.GL_TEXTURE3);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            int w=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),h=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
            if(w!=width||h!=height)return;
            shader.texture("DistantDepth",3,texture,sampler);shader.integer("DistantEnabled",1);
            shader.vector4("DistantProjection",a,b,c,d);shader.vector2("DistantNdc",ndcScale,ndcBias);
            if(!configured){configured=true;dev.yg.skyboxblock.SkyboxBlockMod.LOGGER.info("[skyboxblock] current-view DH depth configured: token={} texture={} size={}x{}",viewToken,texture,w,h);}
        }catch(ReflectiveOperationException|RuntimeException error){warn(error);}
    }
    private static void warn(Throwable error){if(!warned){warned=true;dev.yg.skyboxblock.SkyboxBlockMod.LOGGER.warn("[skyboxblock] DH 3.3.3 depth adapter: {}",error.toString());}}
}
