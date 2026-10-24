package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;

/** Private-canvas cloud range only: preserve vanilla cloud cells, UV scale and animation speed. */
public final class SkyCloudRange {
    private static final ThreadLocal<Integer> BUILD_RADIUS=ThreadLocal.withInitial(() -> 0);
    private SkyCloudRange() {}
    public static int radiusFor(float distance) {
        if(!Float.isFinite(distance) || distance<=SkyboxConfig.DEFAULT_CLOUD_FADE_END) return 0;
        return (int)Math.ceil(Math.min(distance,SkyboxConfig.MAX_CLOUD_DISTANCE)/96.0);
    }
    public static Scope build(float distance) {
        int previous=BUILD_RADIUS.get(); BUILD_RADIUS.set(radiusFor(distance)); return new Scope(previous);
    }
    public static int minimumCell(int original) { int radius=BUILD_RADIUS.get(); return radius==0 ? original : -radius; }
    public static int maximumCell(int original) { int radius=BUILD_RADIUS.get(); return radius==0 ? original : radius; }
    public static final class Scope implements AutoCloseable {
        private final int previous;
        private Scope(int previous) { this.previous=previous; }
        @Override public void close() { BUILD_RADIUS.set(previous); }
    }
    /** Extend only cloud depth clipping; keep view rays, scale, jitter and the world projection unchanged. */
    public static Matrix4f projection(Matrix4f source,float fadeEnd) {
        if(!Float.isFinite(fadeEnd) || Math.abs(source.m23()+1F)>1E-5F || Math.abs(source.m33())>1E-5F
                || Math.abs(source.m02())>1E-6F || Math.abs(source.m12())>1E-6F) return source;
        float a=source.m22(),b=source.m32();
        double near=b/(double)(a-1F),far=b/(double)(a+1F),wanted=fadeEnd+16.0;
        if(!Double.isFinite(near) || near<=0 || !Double.isFinite(far) || far<=near || far>=wanted) return source;
        Matrix4f result=new Matrix4f(source);
        result.m22((float)(-(wanted+near)/(wanted-near)));
        result.m32((float)(-2.0*wanted*near/(wanted-near)));
        return result;
    }
}
