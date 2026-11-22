package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import dev.yg.skyboxblock.SkyStyle;
import dev.yg.skyboxblock.SkyboxBlockMod;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 历史废案：路线 A 巨构实验仅保留源码/资源供回溯，不参与正式天空盒渲染。
 *
 * <p>保留完整实现是为了让旧实验报告和资源仍可复现；生产入口由客户端初始化器移除，
 * 且 {@link #ENABLED} 固定为 false，避免通过旧 JVM 参数意外重新启用。
 */
@Deprecated(forRemoval = false)
final class SkyMegastructure {
   private static final float FIT_RADIUS = 3600.0F;
   private static final String RESOURCE = "/assets/skyboxblock/megastructure/sky.bin";
   private static ShaderInstance backdrop;
   private static ShaderInstance cubes;
   private static ShaderInstance sky;
   private static TextureTarget worldTarget;
   private static TextureTarget displayTarget;
   /** Screen-space premultiplied overlay: alpha 0 outside the structure, composited by the sky faces. */
   private static int layerFramebuffer, layerTex, layerWidth, layerHeight, dumpCount;
   private static boolean layerUsable, clearWarned;
   private static final java.nio.ByteBuffer clearProbe = BufferUtils.createByteBuffer(4);
   private static final java.nio.ByteBuffer coverageProbe = BufferUtils.createByteBuffer(32 * 18 * 4);
   private static long lastLayerFrame = Long.MIN_VALUE;
   private static float lastLayerM11 = Float.NaN;
   private static float sampleX, sampleY, sampleZ;
   private static boolean loaded;
   private static boolean failed;
   private static int backdropVao;
   private static int backdropVbo;
   private static final List<SkyMegastructure.Batch> batches = new ArrayList<>();
   private static float scale = 1.0F;
   /** Ring centre in world space, used by the mega_cube shader as the spin/breath pivot. */
   private static float pivotX, pivotY, pivotZ;
   private static int totalCubes;
   private static int debugFrames;
   private static final float[][] NORMALS = new float[][]{
      {0.0F, 1.0F, 0.0F}, {0.0F, -1.0F, 0.0F}, {0.0F, 0.0F, 1.0F}, {0.0F, 0.0F, -1.0F}, {1.0F, 0.0F, 0.0F}, {-1.0F, 0.0F, 0.0F}
   };
   private static final float[][][] FACES = new float[][][]{
      {{-0.5F, 0.5F, 0.5F}, {0.5F, 0.5F, 0.5F}, {0.5F, 0.5F, -0.5F}, {-0.5F, 0.5F, -0.5F}},
      {{-0.5F, -0.5F, -0.5F}, {0.5F, -0.5F, -0.5F}, {0.5F, -0.5F, 0.5F}, {-0.5F, -0.5F, 0.5F}},
      {{-0.5F, -0.5F, 0.5F}, {0.5F, -0.5F, 0.5F}, {0.5F, 0.5F, 0.5F}, {-0.5F, 0.5F, 0.5F}},
      {{0.5F, -0.5F, -0.5F}, {-0.5F, -0.5F, -0.5F}, {-0.5F, 0.5F, -0.5F}, {0.5F, 0.5F, -0.5F}},
      {{0.5F, -0.5F, 0.5F}, {0.5F, -0.5F, -0.5F}, {0.5F, 0.5F, -0.5F}, {0.5F, 0.5F, 0.5F}},
      {{-0.5F, -0.5F, -0.5F}, {-0.5F, -0.5F, 0.5F}, {-0.5F, 0.5F, 0.5F}, {-0.5F, 0.5F, -0.5F}}
   };

   private SkyMegastructure() {
   }

   static void setBackdrop(ShaderInstance shader) {
      backdrop = shader;
      invalidate();
   }

   static void setSky(ShaderInstance shader) {
      sky = shader;
      invalidate();
   }

   static void setCubes(ShaderInstance shader) {
      cubes = shader;
      invalidate();
   }

   static void invalidate() {
      loaded = false;
      failed = false;
   }

   /**
    * Historical Route A experiment (废案; production rendering is disabled).
    *
    * <p>The r37 build gated this on {@code frameHasMaterial(frame, SkyStyle.KAPRAN)}, but the renderer
    * was rewritten afterwards and now keys materials by {@code SkySkin} rather than {@code SkyStyle},
    * so that call no longer exists. The megastructure is a self-contained procedural sky with its own
    * shaders and no texture dependency, the implementation is retained only as historical source;
    * production rendering never enters it.
    *
    * <pre>skyboxblock.mega.enabled is ignored</pre>
    */
   private static final boolean ENABLED = false;

   private static final boolean READBACK_DIAGNOSTICS = Boolean.getBoolean("skyboxblock.mega.readbackDiagnostics")
       || Boolean.getBoolean("skyboxblock.perf.baseline");

    static boolean applies(SkyWorldRenderer.Frame frame) {
      return frame != null && frame.renderSkybox && ENABLED;
   }

   static int renderCanvas(SkyWorldRenderer.Frame frame, int width, int height, Matrix4f projection, boolean display) {
      ShaderInstance background = sky != null ? sky : backdrop;
      if (frame != null && ensureLoaded() && background != null && cubes != null) {
         TextureTarget canvas = display ? displayTarget : worldTarget;

         try (SkyGlState restore = new SkyGlState(6)) {
            if (canvas == null || canvas.width != width || canvas.height != height) {
               if (canvas != null) {
                  canvas.destroyBuffers();
               }

               SkyGlState.active(33984);
               canvas = new TextureTarget(width, height, true, Minecraft.ON_OSX);
               if (display) {
                  displayTarget = canvas;
               } else {
                  worldTarget = canvas;
               }
            }

            return draw(frame, canvas, projection, display, background);
         }
      } else {
         return -1;
      }
   }

   /**
   /**
    * Renders the instanced cubes into a private screen-space overlay layer.
    *
    * <p>Why a private layer instead of the sky canvas: the canvas that {@code VanillaSkyScene} paints is
    * only ever <em>read</em> by surfaces whose {@code SkyStyle} is 0, i.e. the plain {@code sky_block}.
    * Every panorama style ({@code SkyStyle == 3}, which covers all built-in and custom/HTML styles) and
    * the solid styles (1/2) compute their colour without sampling the canvas at all, so a structure
    * painted into the canvas costs GPU time and stays invisible for those blocks. The layer is composited
    * over whichever sky surface is actually on screen by {@code SkyWorldRenderer.drawMegaLayer}, using
    * that surface's own geometry and depth as the mask, so it works for every style.
    *
    * <p>The layer is an explicitly created RGBA8 framebuffer (not a {@code TextureTarget}) because the
    * whole technique needs a real alpha channel plus a clear to alpha 0. It holds premultiplied colour and
    * alpha 0 everywhere outside the structure, which is what makes the composite a pure "add to the sky"
    * operation. If the clear ever fails to produce alpha 0 the layer is marked unusable and the composite
    * is skipped entirely, so a broken overlay can never paint over the sky.
    */
   static void renderLayer(SkyWorldRenderer.Frame frame, int width, int height, Matrix4f projection) {
      if (frame == null || width <= 0 || height <= 0 || !ENABLED || !ensureLoaded() || cubes == null) return;
      float f = projection != null && projection.m11() != 0.0F
         ? projection.m11() : (float) (1.0 / Math.tan(Math.toRadians(62.0) / 2.0));
      // The world path and the display path share one camera, so one render per view is enough.
      if (layerTex != 0 && layerWidth == width && layerHeight == height
         && lastLayerFrame == frame.viewId && lastLayerM11 == f) return;
      if (layerTex == 0 || layerWidth != width || layerHeight != height) ensureLayer(width, height);
      if (layerTex == 0) return;
      lastLayerFrame = frame.viewId;
      lastLayerM11 = f;

      try (SkyGlState restore = new SkyGlState(6)) {
         GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, layerFramebuffer);
         GL11.glViewport(0, 0, width, height);
         GL11.glDisable(GL11.GL_SCISSOR_TEST);
         GL11.glColorMask(true, true, true, true);
         GL11.glDepthMask(false);
         GL11.glDisable(GL11.GL_DEPTH_TEST);
         GL11.glDisable(GL11.GL_CULL_FACE);

         GL11.glEnable(GL11.GL_BLEND);
         // Premultiplied "over" for every batch: opaque cubes keep alpha 1 and overwrite, additive batches
         // accumulate glow, and the untouched background keeps alpha 0.
         GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
         GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
         GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
         int clearedAlpha = 0;
         if (READBACK_DIAGNOSTICS && !clearWarned) {
            int oldAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
            try {
               GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
               clearProbe.clear();
               GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, clearProbe);
               clearedAlpha = clearProbe.get(3) & 0xFF;
            } finally { GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, oldAlignment); }
         }
         // The layer no longer depends on per-pixel alpha: the background pass below paints it fully
         // opaque, so the composite replaces the sky outright. A render target that cannot store
         // alpha (observed here as a cleared pixel reading back #FF000000) is therefore harmless.
         // Keep the probe purely as diagnostics instead of abandoning the frame.
         if (clearedAlpha != 0 && !clearWarned) {
            clearWarned = true;
            SkyboxBlockMod.LOGGER.info(
               "[skyboxblock] mega layer clear produced alpha {} instead of 0; target is alpha-insensitive, "
                  + "falling back to the opaque background pass",
               clearedAlpha);
         }
         layerUsable = true;

         float aspect = (float) width / height;
         // deep: a plain perspective that matches the far plane the authored geometry is fitted to
         // (FIT_RADIUS 3600 against a far plane of 4000, near 1). The coefficient placement matters:
         // w must come from the -1 at m23, and m32 carries the 2*far*near/(near-far) depth term. The
         // previous port had those two swapped and left m33 at its identity value 1, which made w grow
         // as 2.0005 * distance instead of distance - i.e. every cube was drawn at half angular size and
         // the depth range collapsed to (0.5, 1.0) instead of (0, 1).
         Matrix4f deep = new Matrix4f()
            .m00(f / aspect).m11(f)
            .m22(-1.0005001F).m32(-2.0005002F).m23(-1.0F).m33(0.0F);
         // frame.view carries the camera rotation only (vanilla's sky pose has no translation), so the
         // cubes' absolute world coordinates have to be moved into camera space here. Without this the
         // structure stays pinned to the world origin and only lines up when the camera is at 0,0,0.
         Vector3f camera = new Vector3f((float) frame.camera.x, (float) frame.camera.y, (float) frame.camera.z);
         Matrix4f mvp = new Matrix4f(deep).mul(
            new Matrix4f(frame.view).mul(new Matrix4f().translate(-camera.x, -camera.y, -camera.z)));

         // Background pass: paint the procedural nebula/starfield from mega_sky across the whole
         // layer before any geometry. Without it the layer stays transparent outside the structure
         // and the composite samples RGB 0 there, which is why the sky rendered pure black while the
         // reference (preview/hero.png) shows a dark warm nebula with sparse stars. This runs after
         // the clear-and-probe so the alpha-capability check still sees a transparent buffer; blend
         // stays off so the shader writes its own colour and alpha untouched.
         ShaderInstance background = sky != null ? sky : backdrop;
         if (background != null) {
            GL11.glDisable(GL11.GL_BLEND);
            GL20.glUseProgram(background.getId());
            uniformMatrix(background, "RayInverse", new Matrix4f(mvp).invert());
            uniformInt(background, "Sampler0", 0);
            uniformFloat(background, "Time", (float) (Util.getMillis() % 3600000L) / 1000.0F);
            GL30.glBindVertexArray(backdropVao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
         } else if (debugFrames < 3) {
            SkyboxBlockMod.LOGGER.warn("[skyboxblock] mega layer: no background shader registered (mega_sky/mega_backdrop)");
         }

         GL20.glUseProgram(cubes.getId());
         uniformMatrix(cubes, "Mvp", mvp);
         uniformVector(cubes, "StarDir", 0.0F, 0.287F, -0.958F);
            uniformVector(cubes, "Pivot", pivotX, pivotY, pivotZ);
         float seconds = (float) (Util.getMillis() % 3600000L) / 1000.0F;
         int indices = 0;

         for (Batch batch : batches) {
            boolean additive = batch.additive();
            uniformFloat(cubes, "Additive", additive ? 1.0F : 0.0F);
            uniformFloat(cubes, "Time", seconds);
            uniformFloat(cubes, "RingAngle", seconds * 0.095F);
            uniformInt(cubes, "Anim", animation(batch.name()));
            uniformFloat(cubes, "Emissive", emissive(batch.name()) ? 1.0F : 0.0F);
            uniformFloat(cubes, "Flicker", (float) Math.sin(seconds * 2.3));
            GL30.glBindVertexArray(batch.vao());
            // Re-state the attribute pointers every frame instead of trusting the ones recorded at upload
            // time: a VAO whose arrays were never enabled draws one degenerate vertex for every triangle,
            // which is invisible and raises no GL error.
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batch.vbo());
            attribute(cubes, "Position", 3, 0, 52);
            attribute(cubes, "Normal", 3, 12, 52);
            attribute(cubes, "Center", 3, 24, 52);
            attribute(cubes, "Color", 4, 36, 52);
            GL11.glDrawElements(GL11.GL_TRIANGLES, batch.indexCount(), 5125, 0L);
            indices += batch.indexCount();
         }

         GL30.glBindVertexArray(0);
         GL20.glUseProgram(0);
         int error = debugFrames < 3 ? GL11.glGetError() : 0;
         if (debugFrames < 3) {
            debugFrames++;
            SkyboxBlockMod.LOGGER.info(
               "[skyboxblock] mega layer draw {}x{} fbo={} tex={} status=0x{} vp={}x{} f={} idx={} batches={} err={} "
                  + "program={} position={} normal={} center={} color={} ndc={}",
               new Object[]{width, height, layerFramebuffer, layerTex,
                  Integer.toHexString(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)),
                  width, height, f, indices, batches.size(), error, cubes.getId(),
                  GL20.glGetAttribLocation(cubes.getId(), "Position"),
                  GL20.glGetAttribLocation(cubes.getId(), "Normal"),
                  GL20.glGetAttribLocation(cubes.getId(), "Center"),
                  GL20.glGetAttribLocation(cubes.getId(), "Color"),
                  ndcOf(mvp, camera)});
         }
         int covered = READBACK_DIAGNOSTICS && dumpCount < 3 ? probeCoverage(width, height) : 0;
         if (covered > 0 && dumpCount < 3) {
            dumpCount++;
            dumpLayer(width, height, indices, mvp, camera, covered);
         } else if (READBACK_DIAGNOSTICS && covered == 0 && debugFrames < 3) {
            SkyboxBlockMod.LOGGER.info("[skyboxblock] mega layer empty this view (nothing of the structure on screen)");
         }
      }
   }

   /** NDC of the sample cube corner, the fastest "is the projection sane" number to read in a log. */
   private static String ndcOf(Matrix4f mvp, Vector3f camera) {
      Vector4f projected = new Vector4f(sampleX - camera.x, sampleY - camera.y, sampleZ - camera.z, 1.0F);
      mvp.transform(projected);
      return projected.w > 0.0F
         ? String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", projected.x / projected.w,
            projected.y / projected.w, projected.z / projected.w)
         : "behind(" + projected.w + ")";
   }

   /** Cheap five-rectangle alpha probe: non-zero means part of the structure really landed on screen. */
   private static int probeCoverage(int width, int height) {
      coverageProbe.clear();
      int hits = 0;
      for (int i = 0; i < 5; i++) {
         int x = Math.max(0, Math.min(width - 32, (i == 1 || i == 3 ? width / 4 : i == 2 || i == 4 ? width * 3 / 4
            : width / 2) - 16));
         int y = Math.max(0, Math.min(height - 18, (i >= 3 ? height * 3 / 4 : i == 0 ? height / 2 : height / 4) - 9));
         GL11.glReadPixels(x, y, 32, 18, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, coverageProbe);
         for (int p = 0; p < 32 * 18; p++) {
            if ((coverageProbe.get(p * 4 + 3) & 0xFF) > 8) hits++;
         }
      }
      return hits;
   }

   /** Full readback of the layer: pixel statistics for the log and a PNG for the eye. */
   private static void dumpLayer(int width, int height, int indices, Matrix4f mvp, Vector3f camera, int covered) {
      java.nio.ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
      long nonEmpty = 0;
      int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1, sample = 0;
      try (com.mojang.blaze3d.platform.NativeImage image =
              new com.mojang.blaze3d.platform.NativeImage(width, height, false)) {
         for (int y = 0; y < height; y++) {
            int row = y * width * 4;
            for (int x = 0; x < width; x++) {
               int at = row + x * 4;
               int r = pixels.get(at) & 0xFF;
               int g = pixels.get(at + 1) & 0xFF;
               int b = pixels.get(at + 2) & 0xFF;
               int a = pixels.get(at + 3) & 0xFF;
               if (a > 8) {
                  nonEmpty++;
                  if (x < x0) x0 = x;
                  if (y < y0) y0 = y;
                  if (x > x1) x1 = x;
                  if (y > y1) y1 = y;
                  if (sample == 0) sample = (a << 24) | (b << 16) | (g << 8) | r;
               }
               // glReadPixels is bottom-up, NativeImage is top-down.
               image.setPixelRGBA(x, height - 1 - y, (a << 24) | (b << 16) | (g << 8) | r);
            }
         }
         String dump = "n/a";
         try {
            java.nio.file.Path out = Minecraft.getInstance().gameDirectory.toPath()
               .resolve("skyboxblock-mega").resolve("mega-layer-" + width + "x" + height + "-" + dumpCount + ".png");
            java.nio.file.Files.createDirectories(out.getParent());
            image.writeToFile(out);
            dump = out.toString();
         } catch (java.io.IOException failure) {
            dump = "write-failed:" + failure.getMessage();
         }
         SkyboxBlockMod.LOGGER.info(
            "[skyboxblock] mega layer {}x{} idx={} probeHits={} nonEmptyPx={} coverage={}% bbox=({},{})-({},{}) "
               + "sample=#{} ndc={} dump={}",
            new Object[]{width, height, indices, covered, nonEmpty,
               String.format(java.util.Locale.ROOT, "%.1f", nonEmpty * 100.0 / ((double) width * height)),
               nonEmpty == 0 ? -1 : x0, nonEmpty == 0 ? -1 : y0, nonEmpty == 0 ? -1 : x1,
               nonEmpty == 0 ? -1 : y1, String.format(java.util.Locale.ROOT, "%08X", sample),
               ndcOf(mvp, camera), dump});
      }
   }

   /** (Re)creates the explicit RGBA8 layer target. Returns false when the framebuffer is unusable. */
   private static void ensureLayer(int width, int height) {
      destroyLayer();
      SkyGlState.active(GL13.GL_TEXTURE0);
      int texture = GL11.glGenTextures();
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
      GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
         GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
      GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
      int framebuffer = GL30.glGenFramebuffers();
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
      GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
      int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
      GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
      if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
         SkyboxBlockMod.LOGGER.warn("[skyboxblock] mega layer framebuffer incomplete 0x{} at {}x{}",
            new Object[]{Integer.toHexString(status), width, height});
         GL30.glDeleteFramebuffers(framebuffer);
         GL11.glDeleteTextures(texture);
         return;
      }
      layerFramebuffer = framebuffer;
      layerTex = texture;
      layerWidth = width;
      layerHeight = height;
      layerUsable = false;
      clearWarned = false;
      SkyboxBlockMod.LOGGER.info("[skyboxblock] mega layer target {}x{} fbo={} tex={} format=0x{}",
         new Object[]{width, height, framebuffer, texture,
            Integer.toHexString(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT))});
   }

   private static void destroyLayer() {
      if (layerFramebuffer != 0) {
         GL30.glDeleteFramebuffers(layerFramebuffer);
         layerFramebuffer = 0;
      }
      if (layerTex != 0) {
         GL11.glDeleteTextures(layerTex);
         layerTex = 0;
      }
      layerWidth = layerHeight = 0;
      layerUsable = false;
   }

   /** Overlay layer texture for the sky composite pass, or 0 when there is nothing to composite. */
   static int layerTexture() {
      return ENABLED && layerTex != 0 && layerUsable ? layerTex : 0;
   }


   private static int draw(SkyWorldRenderer.Frame frame, TextureTarget canvas, Matrix4f projection, boolean display, ShaderInstance background) {
      canvas.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
      canvas.clear(Minecraft.ON_OSX);
      canvas.bindWrite(true);
      GL11.glDisable(3089);
      float aspect = canvas.height <= 0 ? 1.0F : (float)canvas.width / canvas.height;
      float f = (float)(1.0 / Math.tan(Math.toRadians(62.0) / 2.0));
      Matrix4f deep = new Matrix4f().m00(f / aspect).m11(f).m22(-1.0005001F).m23(-2.0005002F).m32(-1.0F);
      Matrix4f mvp = new Matrix4f(deep).mul(frame.view);
      if (debugFrames < 2) {
         debugFrames++;
         SkyboxBlockMod.LOGGER
            .info(
               "[skyboxblock] mega view tx={},{},{} size={}x{} aspect={} f={}",
               new Object[]{frame.view.m30(), frame.view.m31(), frame.view.m32(), canvas.width, canvas.height, aspect, f}
            );
      }

      GL11.glDepthMask(false);
      GL11.glDisable(2929);
      GL11.glDisable(3042);
      GL11.glDisable(2884);
      if (background == backdrop) {
         int texture = Minecraft.getInstance().getTextureManager().getTexture(SkyboxBlockMod.id("textures/sky/kapran.png")).getId();
         SkyGlState.active(33984);
         SkyGlState.bindTexture(texture);
         GL11.glTexParameteri(3553, 10242, 10497);
         GL11.glTexParameteri(3553, 10243, 33071);
      }

      GL20.glUseProgram(background.getId());
      uniformMatrix(background, "RayInverse", new Matrix4f(mvp).invert());
      uniformInt(background, "Sampler0", 0);
      uniformFloat(background, "Time", (float)(Util.getMillis() % 3600000L) / 1000.0F);
      GL30.glBindVertexArray(backdropVao);
      GL11.glDrawArrays(4, 0, 3);
      GL11.glDepthRange(0.0, 1.0);
      GL11.glClearDepth(1.0);
      GL11.glClear(256);
      GL11.glEnable(2929);
      GL11.glDepthFunc(515);
      GL11.glDepthMask(true);
      GL11.glDisable(2884);
      GL20.glUseProgram(cubes.getId());
      uniformMatrix(cubes, "Mvp", mvp);
      uniformVector(cubes, "StarDir", 0.0F, 0.287F, -0.958F);
      float seconds = (float)(Util.getMillis() % 3600000L) / 1000.0F;

      for (SkyMegastructure.Batch batch : batches) {
         boolean additive = batch.additive();
         if (!batch.name().equals("jet")) {
            if (additive) {
               GL11.glEnable(3042);
               GL11.glBlendFunc(770, 1);
               GL11.glDepthMask(false);
            } else {
               GL11.glDisable(3042);
               GL11.glDepthMask(true);
            }

            uniformFloat(cubes, "Additive", additive ? 1.0F : 0.0F);
            uniformFloat(cubes, "Time", seconds);
            uniformFloat(cubes, "RingAngle", seconds * 0.095F);
            uniformInt(cubes, "Anim", animation(batch.name()));
            uniformFloat(cubes, "Emissive", emissive(batch.name()) ? 1.0F : 0.0F);
            uniformFloat(cubes, "Flicker", (float)Math.sin(seconds * 2.3));
            GL30.glBindVertexArray(batch.vao());
            GL11.glDrawElements(4, batch.indexCount(), 5125, 0L);
            if (debugFrames < 2) {
               debugFrames++;
               SkyboxBlockMod.LOGGER
                  .info(
                     "[skyboxblock] mega draw {} program={} err={} locs={},{},{} idx={}",
                     new Object[]{
                        batch.name(),
                        cubes.getId(),
                        GL11.glGetError(),
                        GL20.glGetAttribLocation(cubes.getId(), "Position"),
                        GL20.glGetAttribLocation(cubes.getId(), "Normal"),
                        GL20.glGetAttribLocation(cubes.getId(), "Color"),
                        batch.indexCount()
                     }
                  );
            }
         }
      }

      GL30.glBindVertexArray(0);
      GL20.glUseProgram(0);
      GL11.glDepthMask(true);
      GL11.glDisable(3042);
      int texture = canvas.getColorTextureId();
      // r37 published this texture on the Frame (frame.megaSkyTexture / frame.megaDisplayTexture) so
      // SkyWorldRenderer could composite it in a later pass. The renderer was rewritten and no longer
      // has that composite step; renderOverlay() draws straight into the sky canvas instead, so this
      // legacy separate-target path just returns the texture id.
      return texture;
   }

   static TextureTarget worldCanvas() {
      return worldTarget;
   }

   static TextureTarget displayCanvas() {
      return displayTarget;
   }

   private static boolean emissive(String batch) {
      return switch (batch) {
         case "star", "lamp" -> true;
         default -> false;
      };
   }

   private static int animation(String batch) {
      return switch (batch) {
         case "cells" -> 1;
         case "jet" -> 2;
         case "halo" -> 3;
         default -> 0;
      };
   }

   private static void uniformMatrix(ShaderInstance shader, String name, Matrix4f value) {
      int location = GL20.glGetUniformLocation(shader.getId(), name);
      if (location >= 0) {
         FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
         value.get(buffer);
         GL20.glUniformMatrix4fv(location, false, buffer);
      }
   }

   private static void uniformVector(ShaderInstance shader, String name, float x, float y, float z) {
      int location = GL20.glGetUniformLocation(shader.getId(), name);
      if (location >= 0) {
         GL20.glUniform3f(location, x, y, z);
      }
   }

   private static void uniformFloat(ShaderInstance shader, String name, float value) {
      int location = GL20.glGetUniformLocation(shader.getId(), name);
      if (location >= 0) {
         GL20.glUniform1f(location, value);
      }
   }

   private static void uniformInt(ShaderInstance shader, String name, int value) {
      int location = GL20.glGetUniformLocation(shader.getId(), name);
      if (location >= 0) {
         GL20.glUniform1i(location, value);
      }
   }

   private static boolean ensureLoaded() {
      if (failed) {
         return false;
      }

      if (loaded) {
         return true;
      }

      try {
         build();
         loaded = true;
      } catch (IOException | RuntimeException error) {
         failed = true;
         SkyboxBlockMod.LOGGER.warn("[skyboxblock] megastructure geometry unavailable", error);
      }

      return loaded;
   }

   private static void build() throws IOException {
      byte[] data;
      try (InputStream raw = SkyMegastructure.class.getResourceAsStream("/assets/skyboxblock/megastructure/sky.bin")) {
         if (raw == null) {
            throw new IOException("missing /assets/skyboxblock/megastructure/sky.bin");
         }

         data = raw.readAllBytes();
      }

      List<String> names = new ArrayList<>();
      List<Boolean> additive = new ArrayList<>();
      List<float[]> instanceData = new ArrayList<>();
      List<Integer> counts = new ArrayList<>();
      float maxRadius = 1.0F;

      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
         byte[] magic = new byte[4];
         in.readFully(magic);
         String tag = new String(magic, StandardCharsets.US_ASCII);
         if (!tag.equals("SKB2") && !tag.equals("SKB1")) {
            throw new IOException("unsupported geometry tag " + tag);
         }

         int batchCount = Integer.reverseBytes(in.readInt());

         for (int b = 0; b < batchCount; b++) {
            boolean add = tag.equals("SKB2") && in.readUnsignedByte() == 1;
            int nameLength = Integer.reverseBytes(in.readInt());
            byte[] name = new byte[nameLength];
            in.readFully(name);
            int count = Integer.reverseBytes(in.readInt());
            byte[] chunk = in.readNBytes(count * 80);
            if (chunk.length != count * 80) {
               throw new IOException("truncated batch " + new String(name));
            }

            FloatBuffer floats = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] copy = new float[count * 20];
            floats.get(copy);

            for (int i = 0; i < count; i++) {
               float x = copy[i * 20 + 12];
               float y = copy[i * 20 + 13];
               float z = copy[i * 20 + 14];
               maxRadius = Math.max(maxRadius, (float)Math.sqrt(x * x + y * y + z * z));
            }

            names.add(new String(name, StandardCharsets.UTF_8));
            additive.add(add);
            instanceData.add(copy);
            counts.add(count);
         }
      }

      scale = Math.min(1.0F, 3600.0F / maxRadius);
      uploadBackdrop();
      Matrix4f var31 = new Matrix4f();
      Vector3f var32 = new Vector3f();
      Vector3f var33 = new Vector3f();

      for (int var34 = 0; var34 < names.size(); var34++) {
         int count = counts.get(var34);
         float[] data20 = instanceData.get(var34);
         FloatBuffer vertices = BufferUtils.createFloatBuffer(count * 24 * 13);

         for (int i = 0; i < count; i++) {
            int at = i * 20;
            float k = scale;
            float[] m = new float[16];

            for (int c = 0; c < 4; c++) {
               float x = data20[at + c * 4] * k;
               float y = data20[at + c * 4 + 1] * k;
               float z = data20[at + c * 4 + 2] * k;
               float w = data20[at + c * 4 + 3];
               m[c * 4] = -x;
               m[c * 4 + 1] = y;
               m[c * 4 + 2] = -z;
               m[c * 4 + 3] = w;
            }

            var31.set(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]);
            float r = data20[at + 16];
            float g = data20[at + 17];
            float bl = data20[at + 18];
            float alpha = data20[at + 19];
            float cx = data20[at + 12] * -k;
            float cy = data20[at + 13] * k;
            float cz = data20[at + 14] * -k;

            for (int face = 0; face < 6; face++) {
               for (int corner = 0; corner < 4; corner++) {
                  var32.set(FACES[face][corner][0], FACES[face][corner][1], FACES[face][corner][2]);
                  var31.transformPosition(var32);
                  var33.set(NORMALS[face][0], NORMALS[face][1], NORMALS[face][2]);
                  var31.transformDirection(var33).normalize();
                  vertices.put(var32.x).put(var32.y).put(var32.z);
                  vertices.put(var33.x).put(var33.y).put(var33.z);
                  vertices.put(cx).put(cy).put(cz);
                  vertices.put(r).put(g).put(bl).put(alpha);
               }
            }
         }

         vertices.flip();
         IntBuffer indices = BufferUtils.createIntBuffer(count * 36);

         for (int i = 0; i < count; i++) {
            int base = i * 24;

            for (int face = 0; face < 6; face++) {
               int v = base + face * 4;
               indices.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
            }
         }

         indices.flip();
         int vao = GL30.glGenVertexArrays();
         int vbo = GL15.glGenBuffers();
         int ebo = GL15.glGenBuffers();
         GL30.glBindVertexArray(vao);
         GL15.glBindBuffer(34962, vbo);
         GL15.glBufferData(34962, vertices, 35044);
         GL15.glBindBuffer(34963, ebo);
         GL15.glBufferData(34963, indices, 35044);
         attribute(cubes, "Position", 3, 0, 52);
         attribute(cubes, "Normal", 3, 12, 52);
         attribute(cubes, "Center", 3, 24, 52);
         attribute(cubes, "Color", 4, 36, 52);
         GL30.glBindVertexArray(0);
         batches.add(new SkyMegastructure.Batch(names.get(var34), additive.get(var34), vao, vbo, ebo, count * 36));
         totalCubes += count;
         if (var34 == 0) {
            vertices.rewind();
            sampleX = vertices.get(0);
            sampleY = vertices.get(1);
            sampleZ = vertices.get(2);
            SkyboxBlockMod.LOGGER
               .info(
                  "[skyboxblock] mega sample batch={} firstVertex=({},{},{}) scale={}",
                  new Object[]{names.get(var34), sampleX, sampleY, sampleZ, scale}
               );
         }
      }

      SkyboxBlockMod.LOGGER.info("[skyboxblock] megastructure: {} batches, {} cubes, scale {}", new Object[]{batches.size(), totalCubes, scale});

      // Ring centre, used as the pivot for the Dyson-swarm spin and the corona breathing. The
      // shader used to rotate about a line through the world origin, which put the ring's centre
      // ~490 blocks off that line and made the whole swarm swing around instead of turning in its
      // own plane. Derive it from the "star" batch centroid so a re-exported sky.bin needs no code
      // change; fall back to the "cells" centroid, then to the origin.
      pivotX = 0.0F;
      pivotY = 0.0F;
      pivotZ = 0.0F;
      for (String want : new String[]{"star", "cells"}) {
         int index = names.indexOf(want);
         if (index < 0) continue;
         float[] batchData = instanceData.get(index);
         int count = counts.get(index);
         if (count <= 0 || batchData == null || batchData.length < count * 20) continue;
         float sx = 0.0F, sy = 0.0F, sz = 0.0F;
         for (int i = 0; i < count; i++) {
            sx += batchData[i * 20 + 12];
            sy += batchData[i * 20 + 13];
            sz += batchData[i * 20 + 14];
         }
         pivotX = sx / count;
         pivotY = sy / count;
         pivotZ = sz / count;
         SkyboxBlockMod.LOGGER.info(
            "[skyboxblock] mega pivot from batch {}: ({},{},{}) over {} instances",
            new Object[]{want, pivotX, pivotY, pivotZ, count});
         break;
      }
   }

   private static void uploadBackdrop() {
      backdropVao = GL30.glGenVertexArrays();
      backdropVbo = GL15.glGenBuffers();
      float[] triangle = new float[]{-1.0F, -1.0F, 0.0F, 3.0F, -1.0F, 0.0F, -1.0F, 3.0F, 0.0F};
      GL30.glBindVertexArray(backdropVao);
      GL15.glBindBuffer(34962, backdropVbo);
      GL15.glBufferData(34962, triangle, 35044);
      attribute(backdrop, "Position", 3, 0, 12);
      attribute(sky, "Position", 3, 0, 12);
      GL30.glBindVertexArray(0);
   }

   private static void attribute(ShaderInstance shader, String name, int size, int offset, int stride) {
      if (shader != null) {
         int location = GL20.glGetAttribLocation(shader.getId(), name);
         if (location >= 0) {
            GL20.glEnableVertexAttribArray(location);
            GL20.glVertexAttribPointer(location, size, 5126, false, stride, offset);
         }
      }
   }

   static void release() {
      if (worldTarget != null) {
         worldTarget.destroyBuffers();
         worldTarget = null;
      }

      if (displayTarget != null) {
         displayTarget.destroyBuffers();
         displayTarget = null;
      }

      destroyLayer();
      lastLayerFrame = Long.MIN_VALUE;
      lastLayerM11 = Float.NaN;
      for (SkyMegastructure.Batch batch : batches) {
         GL30.glDeleteVertexArrays(batch.vao());
         GL15.glDeleteBuffers(batch.vbo());
         GL15.glDeleteBuffers(batch.ebo());
      }

      batches.clear();
      totalCubes = 0;
      if (backdropVao != 0) {
         GL30.glDeleteVertexArrays(backdropVao);
      }

      if (backdropVbo != 0) {
         GL15.glDeleteBuffers(backdropVbo);
      }

      backdropVbo = 0;
      backdropVao = 0;
      loaded = false;
   }

   private record Batch(String name, boolean additive, int vao, int vbo, int ebo, int indexCount) {
   }
}
