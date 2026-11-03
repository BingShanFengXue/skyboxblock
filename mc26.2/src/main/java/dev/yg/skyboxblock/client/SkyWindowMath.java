package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** CPU oracle for the production fragment shader's complete inverse-VP two-point construction. */
public final class SkyWindowMath {
    private SkyWindowMath() { }
    public static Matrix4f rasterProjection(Matrix4fc p,float sx,float sy,float jx,float jy,Matrix4f out) {
        return new Matrix4f().m00(sx).m11(sy).m30(sx-1+jx).m31(sy-1+jy).mul(p,out);
    }
    public static Matrix4f rayInverse(Matrix4fc projection,Matrix4fc view,Matrix4f out) {
        return out.set(projection).mul(view).invert();
    }
    public static Vector3f ray(Matrix4fc inverse,float u,float v,boolean zeroToOne,Vector3f out) {
        Vector4f a=inverse.transform(new Vector4f(u*2-1,v*2-1,1,1));
        Vector4f b=inverse.transform(new Vector4f(u*2-1,v*2-1,zeroToOne?.5F:0,1));
        return out.set(b.x/b.w-a.x/a.w,b.y/b.w-a.y/a.w,b.z/b.w-a.z/a.w).normalize();
    }
    /** Reference-mainline orientation: +X is u=.5; both sides of -X meet periodically. */
    public static float[] panorama(Vector3f ray) {
        float u=ray.x*ray.x+ray.z*ray.z<1e-12F?.5F:(float)(Math.atan2(ray.z,ray.x)/(Math.PI*2)+.5);
        return new float[]{u,(float)(Math.acos(Math.max(-1,Math.min(1,ray.y)))/Math.PI)};
    }
}
