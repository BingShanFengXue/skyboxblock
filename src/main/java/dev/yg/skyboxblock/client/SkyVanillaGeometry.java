package dev.yg.skyboxblock.client;

import net.minecraft.util.Mth;

/** Minecraft 1.21.1 sky-disc and 16-segment dawn geometry, without native world-buffer hooks. */
final class SkyVanillaGeometry {
    private SkyVanillaGeometry() { }
    static float[] disc(float y) {
        float[] data=new float[8*3*3]; int at=0;
        for (int i=0;i<8;i++) {
            data[at++]=0; data[at++]=y; data[at++]=0;
            for (int j=i;j<=i+1;j++) {
                float angle=(-180+j*45)*((float)Math.PI/180F);
                data[at++]=Math.signum(y)*512F*Mth.cos(angle);
                data[at++]=y;
                data[at++]=512F*Mth.sin(angle);
            }
        }
        return data;
    }
    static float[] dawn(float[] color) {
        float[] data=new float[16*3*7]; int at=0;
        for(int i=0;i<16;i++) {
            at=vertex(data,at,0,100,0,color,color[3]);
            for(int j=i;j<=i+1;j++) {
                float a=j*((float)Math.PI*2)/16F, c=Mth.cos(a);
                at=vertex(data,at,Mth.sin(a)*120F,c*120F,-c*40F*color[3],color,0);
            }
        }
        return data;
    }
    private static int vertex(float[] out,int at,float x,float y,float z,float[] c,float alpha) {
        out[at++]=x; out[at++]=y; out[at++]=z;
        // POSITION_COLOR stores normalized unsigned bytes in the original renderer.
        out[at++]=(int)(c[0]*255F)/255F; out[at++]=(int)(c[1]*255F)/255F;
        out[at++]=(int)(c[2]*255F)/255F; out[at++]=(int)(alpha*255F)/255F;
        return at;
    }
    static float[] celestial(float size,float y,boolean moon,float u0,float v0,float u1,float v1) {
        float z0=moon ? size : -size,z1=-z0;
        if(moon) { float t=u0;u0=u1;u1=t;t=v0;v0=v1;v1=t; }
        return new float[]{-size,y,z0,u0,v0, size,y,z0,u1,v0, size,y,z1,u1,v1,
                -size,y,z0,u0,v0, size,y,z1,u1,v1, -size,y,z1,u0,v1};
    }
}
