package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Pure transform helpers shared by the renderer and independent regression. */
public final class SkyRenderMath {
    /** MC 1.21.1 OverworldEffects uses 192.0F, independent of shaderpack/dimension overrides. */
    public static final float VANILLA_CLOUD_HEIGHT = 192F;
    private SkyRenderMath() {}

    public static double cloudRelativeY(double cameraY) {
        return cloudRelativeY(cameraY,VANILLA_CLOUD_HEIGHT);
    }

    public static double cloudRelativeY(double cameraY,float height) {
        return height - (float) cameraY + .33F;
    }

    /** Form the absolute grid endpoint before rounding: adjacent cubes then share identical bits. */
    public static float relativeGridVertex(int blockCoordinate, float corner, double cameraCoordinate) {
        return (float) ((double) blockCoordinate + corner - cameraCoordinate);
    }

    /** Vanilla Overworld's phase curve, driven by persisted server game ticks rather than dayTime. */
    public static float simulatedPhase(long gameTime, float partialTick) {
        double fraction=(Math.floorMod(gameTime,24000L)+(double)partialTick)/24000.0-.25;
        fraction-=Math.floor(fraction);
        return (float)(fraction*2+(.5-Math.cos(fraction*Math.PI)/2))/3F;
    }

    public static float displayPhase(boolean local,float nativePhase,long gameTime,float partialTick) {
        return local && Float.isFinite(nativePhase) ? nativePhase : simulatedPhase(gameTime,partialTick);
    }

    public static int simulatedMoonPhase(long gameTime) {
        return (int)Math.floorMod(Math.floorDiv(gameTime,24000L),8L);
    }

    public static Vector3f sunDirection(float timeOfDay) {
        float angle = (float) (timeOfDay * Math.PI * 2);
        return new Vector3f(-(float) Math.sin(angle), (float) Math.cos(angle), 0);
    }

    /**
     * A pack applies jitter after compressing NDC into its lower-left active raster.
     * The private sky fills that raster, so its projection uses jitter / raster scale.
     * Left multiplication also preserves nonstandard/oblique projection matrices.
     */
    public static Matrix4f jitteredSkyProjection(Matrix4f projection, float scaleX, float scaleY,
                                                 float jitterX, float jitterY, Matrix4f destination) {
        if (!(scaleX > 0) || !(scaleY > 0) || !Float.isFinite(scaleX) || !Float.isFinite(scaleY)
                || !Float.isFinite(jitterX) || !Float.isFinite(jitterY)) {
            throw new IllegalArgumentException("Non-finite or non-positive sky raster transform");
        }
        return destination.set(projection).translateLocal(jitterX / scaleX, jitterY / scaleY, 0);
    }

    /** Preserve both camera matrices: the shader subtracts two unprojected points, canceling
     * camera-origin translation whether vanilla embeds bob in projection or Iris puts it in view. */
    public static Matrix4f panoramaRayInverse(Matrix4f projection, Matrix4f view, Matrix4f destination) {
        return destination.set(projection).mul(view).invert();
    }

    public static Vector3f ray(Matrix4f inverseViewProjection, float x, float y) {
        Vector4f a = inverseViewProjection.transform(new Vector4f(x, y, -1, 1));
        Vector4f b = inverseViewProjection.transform(new Vector4f(x, y, 0, 1));
        a.div(a.w); b.div(b.w);
        return new Vector3f(b.x - a.x, b.y - a.y, b.z - a.z).normalize();
    }

    public static Vector4f relativeClipPlane(double cameraX, double cameraY, double cameraZ,
                                            double planeX, double planeY, double planeZ,
                                            double nx, double ny, double nz) {
        double offset = nx * (cameraX - planeX) + ny * (cameraY - planeY) + nz * (cameraZ - planeZ);
        return new Vector4f((float) nx, (float) ny, (float) nz, (float) offset);
    }
}
