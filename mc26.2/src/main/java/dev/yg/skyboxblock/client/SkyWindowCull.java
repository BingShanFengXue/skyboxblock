package dev.yg.skyboxblock.client;

import org.joml.Matrix4fc;

/** Conservative side-plane cull for a camera-relative cube; deliberately ignores near/far planes. */
public final class SkyWindowCull {
    private static final double CLIP_EPSILON = 1.0e-4;
    private SkyWindowCull() { }

    public static boolean outsideSidePlanes(int blockX, int blockY, int blockZ,
                                            double cameraX, double cameraY, double cameraZ,
                                            Matrix4fc clipFromCamera) {
        if (clipFromCamera == null || !Double.isFinite(cameraX) || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ)) return false;
        boolean left = true, right = true, bottom = true, top = true;
        for (int corner = 0; corner < 8; corner++) {
            double x = blockX + ((corner & 1) == 0 ? 0D : 1D) - cameraX;
            double y = blockY + ((corner & 2) == 0 ? 0D : 1D) - cameraY;
            double z = blockZ + ((corner & 4) == 0 ? 0D : 1D) - cameraZ;
            double cx = clipFromCamera.m00() * x + clipFromCamera.m10() * y
                    + clipFromCamera.m20() * z + clipFromCamera.m30();
            double cy = clipFromCamera.m01() * x + clipFromCamera.m11() * y
                    + clipFromCamera.m21() * z + clipFromCamera.m31();
            double cw = clipFromCamera.m03() * x + clipFromCamera.m13() * y
                    + clipFromCamera.m23() * z + clipFromCamera.m33();
            if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cw) || cw <= 1.0e-6)
                return false;
            double epsilon = CLIP_EPSILON * Math.max(1D, Math.abs(cw));
            left &= cx < -cw - epsilon;
            right &= cx > cw + epsilon;
            bottom &= cy < -cw - epsilon;
            top &= cy > cw + epsilon;
            if (!left && !right && !bottom && !top) return false;
        }
        return left || right || bottom || top;
    }

    /** Portal/mirror or otherwise uncertain matrix paths explicitly fail open. */
    public static boolean visible(boolean allowSideCull, int blockX, int blockY, int blockZ,
                                  double cameraX, double cameraY, double cameraZ, Matrix4fc clipFromCamera) {
        return !allowSideCull || !outsideSidePlanes(blockX, blockY, blockZ,
                cameraX, cameraY, cameraZ, clipFromCamera);
    }
}
