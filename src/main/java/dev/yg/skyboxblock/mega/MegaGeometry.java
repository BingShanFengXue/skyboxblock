package dev.yg.skyboxblock.mega;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class MegaGeometry {
   public static final float R = 512.0F;
   private static final float[] AXIS = norm(0.7F, -0.36F, -0.62F);
   private static final float STAR_R = 69.12F;
   private static final float RING_INNER = 143.36F;
   private static final float RING_OUTER = 266.24F;
   private static final float ENGINE_DIST = 317.44F;
   private static final float ABSORB_DIST = 289.28F;
   private static final float BODY_LEN = 53.76F;
   private static final float BODY_MAX = 37.888F;
   public static final float[] STAR_POS = scale(AXIS, 512.0F);
   public static final float[] ENGINE_POS = scale(AXIS, 317.44F);
   private static final float TAU = (float) (Math.PI * 2);

   private MegaGeometry() {
   }

   public static MegaGeometry.Mesh build(Random rng) {
      List<Float> out = new ArrayList<>(1058400);
      buildStar(out, rng);
      buildDyson(out, rng);
      buildEngine(out, rng);
      return new MegaGeometry.Mesh(out);
   }

   private static void buildStar(List<Float> out, Random rng) {
      int n = 820;
      float ga = (float)(Math.PI * (3.0 - Math.sqrt(5.0)));
      float cell = (float)(Math.sqrt(60036.77531936859 / n) * 1.16);

      for (int i = 0; i < n; i++) {
         float y = 1.0F - (float)i / (n - 1) * 2.0F;
         float rad = (float)Math.sqrt(Math.max(0.0F, 1.0F - y * y));
         float th = ga * i;
         float[] dir = new float[]{(float)Math.cos(th) * rad, y, (float)Math.sin(th) * rad};
         float t = clamp((1.0F - Math.abs(y)) * 0.85F + rng.nextFloat() * 0.3F, 0.0F, 1.0F);
         float[] col = mix(new float[]{1.0F, 0.58F, 0.18F}, new float[]{1.0F, 0.97F, 0.86F}, t);
         float s = cell * (0.95F + rng.nextFloat() * 0.12F);
         addBox(out, add(STAR_POS, scale(dir, 69.12F)), dir, s, s * 1.4F, s, col);
      }
   }

   private static void buildDyson(List<Float> out, Random rng) {
      float torusR = 204.79999F;
      float tubeR = 52.838398F;
      int nTh = 128;
      int nPh = 15;
      float[] e1 = new float[3];
      float[] e2 = new float[3];
      basis(AXIS, e1, e2);

      for (int i = 0; i < nTh; i++) {
         float th0 = (float)i / nTh * (float) (Math.PI * 2);

         for (int j = 0; j < nPh; j++) {
            if (!(rng.nextFloat() < 0.05F)) {
               float th = th0 + (rng.nextFloat() - 0.5F) * 0.006F;
               float ph = (float)j / nPh * (float) (Math.PI * 2) + i * 0.045F + (rng.nextFloat() - 0.5F) * 0.03F;
               float rr = tubeR * (0.96F + rng.nextFloat() * 0.08F);
               float[] radial = add(scale(e1, (float)Math.cos(th)), scale(e2, (float)Math.sin(th)));
               float[] p = add(STAR_POS, add(scale(radial, torusR + rr * (float)Math.cos(ph)), scale(AXIS, rr * (float)Math.sin(ph))));
               float[] inward = norm(sub(STAR_POS, p));
               float[] tang = norm(add(scale(e1, -((float)Math.sin(th))), scale(e2, (float)Math.cos(th))));
               float[] flat = norm(cross(tang, inward));
               float facing = dot(inward, scale(AXIS, -1.0F)) * 0.5F + 0.5F;
               float shade = clamp((0.55F + 0.75F * facing) * (0.88F + rng.nextFloat() * 0.24F), 0.18F, 1.6F);
               float[] col = mix(new float[]{0.3F, 0.36F, 0.48F}, new float[]{0.86F, 0.9F, 0.97F}, clamp(shade, 0.0F, 1.0F));
               float k = 0.85F + rng.nextFloat() * 0.3F;
               col = new float[]{col[0] * k, col[1] * k, col[2] * k};
               float w = 4.2F + rng.nextFloat() * 2.2F;
               addOriented(out, p, tang, inward, flat, w, 0.5F + rng.nextFloat() * 0.4F, w * 0.78F, col);
            }
         }
      }
   }

   private static void buildEngine(List<Float> out, Random rng) {
      float[] ax = AXIS;
      float[] e1 = new float[3];
      float[] e2 = new float[3];
      basis(ax, e1, e2);
      float bowlR = 76.8F;
      float bowlDepth = 38.4F;

      for (int layer = 0; layer < 5; layer++) {
         float lt = layer / 4.0F;
         float rr = bowlR * (1.0F - lt * 0.86F);
         float dist = 289.28F - bowlDepth * lt * 0.85F;
         int segs = Math.max(10, Math.round((float) (Math.PI * 2) * rr / 6.144F));
         float[] col = mix(new float[]{0.88F, 0.95F, 1.0F}, new float[]{0.56F, 0.82F, 1.0F}, lt);
         float k = 0.8F + rng.nextFloat() * 0.35F;
         float[] c = new float[]{col[0] * k, col[1] * k, col[2] * k};

         for (int i = 0; i < segs; i++) {
            float ang = (float)i / segs * (float) (Math.PI * 2) + lt * 0.08F;
            float[] radial = add(scale(e1, (float)Math.cos(ang)), scale(e2, (float)Math.sin(ang)));
            float[] pos = add(add(STAR_POS, scale(ax, dist)), scale(radial, rr));
            float[] tang = norm(cross(radial, ax));
            addOriented(out, pos, tang, ax, radial, (float) (Math.PI * 2) * rr / segs * 0.94F, 7.168F, 5.632F, c);
         }
      }

      addOriented(out, add(STAR_POS, scale(ax, 289.28F - bowlDepth * 0.9F)), e1, ax, e2, bowlR * 0.42F, 13.312F, bowlR * 0.42F, new float[]{0.88F, 0.95F, 1.0F});
      float bodyStart = 289.28F - bowlDepth * 0.95F;
      int spindle = 8;

      for (int k = 0; k < spindle; k++) {
         float t = (float)k / (spindle - 1);
         float dist = bodyStart + t * 53.76F;
         float rad = 37.888F * (0.34F + 0.66F * (float)Math.sin(Math.PI * Math.pow(t, 0.82)));
         float[] col = mix(new float[]{0.105F, 0.14F, 0.205F}, new float[]{0.4F, 0.52F, 0.66F}, clamp(0.25F + t * 0.8F, 0.0F, 1.0F));

         for (int i = 0; i < 18; i++) {
            float ang = i / 18.0F * (float) (Math.PI * 2);
            float[] radial = add(scale(e1, (float)Math.cos(ang)), scale(e2, (float)Math.sin(ang)));
            float[] pos = add(add(STAR_POS, scale(ax, dist)), scale(radial, rad));
            addOriented(
               out, pos, norm(cross(radial, ax)), ax, radial, (float) (Math.PI * 2) * rad / 18.0F * 0.92F, 53.76F / (spindle - 1) * 1.05F, rad * 0.3F, col
            );
         }
      }

      for (int k = 0; k < 4; k++) {
         float t = (k + 0.5F) / 4.0F;
         float dist = bodyStart + t * 53.76F;
         float rad = 37.888F * (0.34F + 0.66F * (float)Math.sin(Math.PI * Math.pow(t, 0.82)));
         addOriented(out, add(STAR_POS, scale(ax, dist)), e1, ax, e2, rad * 1.02F, 15.811764F, rad * 1.02F, new float[]{0.105F, 0.14F, 0.205F});
      }

      for (int k : new int[]{2, 4, 6}) {
         float t = (float)k / (spindle - 1);
         float dist = bodyStart + t * 53.76F;
         float rad = 37.888F * (0.34F + 0.66F * (float)Math.sin(Math.PI * Math.pow(t, 0.82)));

         for (int i = 0; i < 26; i++) {
            float ang = i / 26.0F * (float) (Math.PI * 2);
            float[] radial = add(scale(e1, (float)Math.cos(ang)), scale(e2, (float)Math.sin(ang)));
            float[] pos = add(add(STAR_POS, scale(ax, dist)), scale(radial, rad * 1.22F));
            addOriented(
               out,
               pos,
               norm(cross(radial, ax)),
               ax,
               radial,
               (float) (Math.PI * 2) * rad * 1.22F / 26.0F * 0.86F,
               3.584F,
               4.608F,
               new float[]{0.72F, 0.84F, 0.945F}
            );
         }
      }

      float wingBase = bodyStart + 22.579199F;

      for (int w = 0; w < 6; w++) {
         float ang = w / 6.0F * (float) (Math.PI * 2) + (float) (Math.PI / 6);
         float[] wingDir = add(scale(e1, (float)Math.cos(ang)), scale(e2, (float)Math.sin(ang)));
         float[] wingSide = norm(cross(wingDir, ax));

         for (int j = 0; j < 5; j++) {
            float t = j / 4.0F;
            float rr = 34.0992F + 26.112F * t;
            float dist = wingBase + t * 512.0F * 0.055F;
            float[] pos = add(add(STAR_POS, scale(ax, dist)), scale(wingDir, rr));
            float[] col = mix(new float[]{0.4F, 0.52F, 0.66F}, new float[]{0.72F, 0.84F, 0.945F}, t * 0.75F);
            addOriented(out, pos, wingSide, ax, wingDir, 51.2F * (1.0F - t * 0.42F), 28.16F * (1.0F - t * 0.3F), 4.096F, col);
         }
      }

      float retroDist = bodyStart + 18.2784F;

      for (int k = 0; k < 4; k++) {
         float ang = k / 4.0F * (float) (Math.PI * 2) + 0.45F;
         float[] outDir = add(scale(e1, (float)Math.cos(ang)), scale(e2, (float)Math.sin(ang)));
         float[] base = add(add(STAR_POS, scale(ax, retroDist)), scale(outDir, 44.70784F));
         addOriented(out, base, norm(cross(outDir, ax)), ax, outDir, 11.264F, 15.36F, 10.24F, new float[]{0.56F, 0.76F, 0.9F});
      }

      float nozzleDist = bodyStart + 53.76F + 15.36F;

      for (int i = 0; i < 30; i++) {
         float ang = i / 30.0F * (float) (Math.PI * 2);
         float[] radial = add(scale(e1, (float)Math.cos(ang)), scale(e2, (float)Math.sin(ang)));
         float[] pos = add(add(STAR_POS, scale(ax, nozzleDist + 6.144F)), scale(radial, 46.223362F));
         addOriented(
            out,
            pos,
            norm(cross(radial, ax)),
            ax,
            radial,
            8.906518F,
            8.192F,
            10.24F,
            mix(new float[]{0.265F, 0.315F, 0.47F}, new float[]{0.72F, 0.84F, 0.945F}, 0.35F)
         );
      }
   }

   private static void addBox(List<Float> out, float[] c, float[] up, float sx, float sy, float sz, float[] col) {
      float[] bx = norm(cross(new float[]{0.0F, 1.0F, 0.0F}, up));
      if (len(bx) < 1.0E-4F) {
         bx = new float[]{1.0F, 0.0F, 0.0F};
      }

      float[] bz = norm(cross(up, bx));
      addOriented(out, c, bx, up, bz, sx, sy, sz, col);
   }

   private static void addOriented(List<Float> out, float[] c, float[] bx, float[] by, float[] bz, float sx, float sy, float sz, float[] col) {
      float hx = sx * 0.5F;
      float hy = sy * 0.5F;
      float hz = sz * 0.5F;
      float[][] faces = new float[][]{
         {1.0F, 0.0F, 0.0F}, {-1.0F, 0.0F, 0.0F}, {0.0F, 1.0F, 0.0F}, {0.0F, -1.0F, 0.0F}, {0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, -1.0F}
      };

      for (float[] nrm : faces) {
         float shade;
         if (Math.abs(nrm[1]) > 0.62F) {
            shade = nrm[1] > 0.0F ? 1.0F : 0.45F;
         } else if (Math.abs(nrm[0]) > 0.62F) {
            shade = 0.78F;
         } else {
            shade = 0.62F;
         }

         float[][] quad = faceQuad(nrm, hx, hy, hz);
         int[][] tri = new int[][]{{0, 1, 2}, {0, 2, 3}};

         for (int[] t : tri) {
            for (int idx : t) {
               float[] local = quad[idx];
               float[] p = add(c, add(add(scale(bx, local[0]), scale(by, local[1])), scale(bz, local[2])));
               out.add(p[0]);
               out.add(p[1]);
               out.add(p[2]);
               out.add(col[0] * shade);
               out.add(col[1] * shade);
               out.add(col[2] * shade);
               out.add(1.0F);
            }
         }
      }
   }

   private static float[][] faceQuad(float[] n, float hx, float hy, float hz) {
      if (n[0] > 0.0F) {
         return new float[][]{{hx, -hy, -hz}, {hx, hy, -hz}, {hx, hy, hz}, {hx, -hy, hz}};
      } else if (n[0] < 0.0F) {
         return new float[][]{{-hx, -hy, hz}, {-hx, hy, hz}, {-hx, hy, -hz}, {-hx, -hy, -hz}};
      } else if (n[1] > 0.0F) {
         return new float[][]{{-hx, hy, -hz}, {-hx, hy, hz}, {hx, hy, hz}, {hx, hy, -hz}};
      } else if (n[1] < 0.0F) {
         return new float[][]{{-hx, -hy, hz}, {-hx, -hy, -hz}, {hx, -hy, -hz}, {hx, -hy, hz}};
      } else {
         return n[2] > 0.0F
            ? new float[][]{{-hx, -hy, hz}, {hx, -hy, hz}, {hx, hy, hz}, {-hx, hy, hz}}
            : new float[][]{{hx, -hy, -hz}, {-hx, -hy, -hz}, {-hx, hy, -hz}, {hx, hy, -hz}};
      }
   }

   static float[] add(float[] a, float[] b) {
      return new float[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
   }

   static float[] sub(float[] a, float[] b) {
      return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
   }

   static float[] scale(float[] a, float s) {
      return new float[]{a[0] * s, a[1] * s, a[2] * s};
   }

   static float dot(float[] a, float[] b) {
      return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
   }

   static float len(float[] a) {
      return (float)Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
   }

   static float[] cross(float[] a, float[] b) {
      return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
   }

   static float[] norm(float[] a) {
      float l = len(a);
      return l < 1.0E-6F ? new float[]{0.0F, 1.0F, 0.0F} : new float[]{a[0] / l, a[1] / l, a[2] / l};
   }

   static float[] norm(float x, float y, float z) {
      return norm(new float[]{x, y, z});
   }

   static void basis(float[] n, float[] t, float[] b) {
      float[] up = Math.abs(n[1]) > 0.94F ? new float[]{1.0F, 0.0F, 0.0F} : new float[]{0.0F, 1.0F, 0.0F};
      float[] u = norm(cross(up, n));
      System.arraycopy(u, 0, t, 0, 3);
      System.arraycopy(cross(n, u), 0, b, 0, 3);
   }

   static float[] mix(float[] a, float[] b, float t) {
      return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
   }

   static float clamp(float v, float lo, float hi) {
      return v < lo ? lo : Math.min(v, hi);
   }

   public static final class Mesh {
      public final float[] vertices;
      public final int vertexCount;

      Mesh(List<Float> list) {
         this.vertexCount = list.size() / 7;
         this.vertices = new float[list.size()];

         for (int i = 0; i < this.vertices.length; i++) {
            this.vertices[i] = list.get(i);
         }
      }
   }
}
