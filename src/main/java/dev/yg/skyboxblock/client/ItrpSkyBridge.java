package dev.yg.skyboxblock.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Narrow adapter for one audited ITRP archive and Iris 1.8.1.
 * Writes only generated sky RGB and legal sky-owned material data; never reads world RGB.
 * All texture IDs are borrowed for the current owner/pass only, never retained across views or reloads.
 */
public final class ItrpSkyBridge {
    private static final String PACK_SHA256 = "334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8";
    private static final String IRIS_PREFIX = "net.irisshaders.iris.";
    private static final Set<String> REPORTED = new HashSet<>();
    // Pipeline identity -> its exact targets object, proved at a real composite40 callback.
    // No global GL-id/name map, and no texture IDs in this cache.
    private static final Map<Object, Object> PROVEN_PIPELINES = new WeakHashMap<>();
    private static ShaderInstance backgroundShader;
    private static Schema schema;
    private static boolean schemaFailed, injecting;
    private static int framebuffer;
    private static Object cachedPack;
    private static String cachedPackName, cachedPackHash;
    private static Path cachedPath;
    private static long cachedSize = -1, cachedModified = -1;
    private static Object cachedFileKey;

    private ItrpSkyBridge() {}

    public static void setShader(ShaderInstance instance) {
        backgroundShader = instance;
        invalidate();
    }

    /** No GL work: safe for reload invalidation; the temporary FBO is detached after every draw. */
    public static void invalidate() {
        cachedPack = null;
        cachedPackName = cachedPackHash = null;
        cachedPath = null;
        cachedSize = cachedModified = -1;
        cachedFileKey = null;
        PROVEN_PIPELINES.clear();
        REPORTED.clear();
        schema = null;
        schemaFailed = false;
    }

    /** Called by the renderer on the render thread; shaders themselves belong to Minecraft. */
    public static void release() {
        RenderSystem.assertOnRenderThread();
        if (framebuffer != 0) {
            try (SkyGlState ignored = new SkyGlState(6)) {
                GL30.glDeleteFramebuffers(framebuffer);
                framebuffer = 0;
            }
        }
        invalidate();
    }

    /**
     * Select shader OUTPUT locations, not assumed GLSL link order or global colortex numbers.
     * The caller owns the outer state scope and invokes the actual world mesh draw afterwards.
     */
    static void configureOpaque(SkyWorldRenderer.Frame frame, ShaderInstance shader, int[] originalDrawBuffers) {
        Uniform mode = shader.getUniform("PackMode");
        if (mode != null) mode.set(0);
        frame.itrpMaterialWrites = false;
        int program = shader.getId();
        int color = GL30.glGetFragDataLocation(program, "fragColor");
        int maxOutputs = GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS);
        int sourceColor = originalDrawBuffers.length == 0 ? GL11.GL_NONE : originalDrawBuffers[0];
        if (color < 0 || color >= maxOutputs || sourceColor == GL11.GL_NONE) {
            failure(frame, "world-color-output-unavailable", null, true);
            drawRouting(new int[]{GL11.GL_NONE});
            return;
        }
        int[] colorOnly = new int[color + 1];
        Arrays.fill(colorOnly, GL11.GL_NONE);
        colorOnly[color] = sourceColor;
        drawRouting(colorOnly);
        if (negativeAll()) { failure(frame, "negative-transparency-control", null, false); return; }
        if (!ModCompat.irisActive()) return;
        Schema api = schema(frame);
        if (api == null || !supportedPack(frame, api)) return;
        if (negativeMaterial()) { failure(frame, "negative-itrp-material-control", null, false); return; }
        if (mode == null || !shaderReady()) {
            failure(frame, "material-or-background-shader-unavailable", null, true);
            return;
        }
        int solid = GL30.glGetFragDataLocation(program, "SkySolidData");
        int normal = GL30.glGetFragDataLocation(program, "SkyNormalData");
        if (solid < 0 || normal < 0 || solid >= maxOutputs || normal >= maxOutputs
                || solid == color || normal == color || solid == normal) {
            failure(frame, "material-output-locations-unavailable", null, true);
            return;
        }
        try {
            Object pipeline = api.currentPipeline();
            Object targets = api.pipelineTargets.get(pipeline);
            if (PROVEN_PIPELINES.get(pipeline) != targets) {
                // First view is a deliberate probe; aux writes begin only after the real hook has proved dependencies.
                failure(frame, "awaiting-verified-composite40-owner", null, false);
                return;
            }
            try (SkyGlState ignored = new SkyGlState(6)) {
                if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == 0
                        || originalDrawBuffers.length < 3
                        || !validRaster(frame)) throw new BridgeException("opaque-mrt-or-raster-unavailable");
                int solidTexture = attachmentTexture(originalDrawBuffers[1]);
                int normalTexture = attachmentTexture(originalDrawBuffers[2]);
                int colorTexture = attachmentTexture(originalDrawBuffers[0]);
                if (colorTexture == 0 || solidTexture == 0 || normalTexture == 0 || solidTexture == normalTexture
                        || solidTexture == colorTexture || normalTexture == colorTexture)
                    throw new BridgeException("opaque-material-attachments-alias");
                requireTargetTexture(api, targets, 0, colorTexture);
                requireTargetTexture(api, targets, 1, solidTexture);
                requireTargetTexture(api, targets, 2, normalTexture);
                requireStorage(texture(solidTexture), frame, GL11.GL_RGBA16, "solid-data-storage-mismatch");
                requireStorage(texture(normalTexture), frame, GL11.GL_RGBA16, "solid-normal-storage-mismatch");
            }
            int[] outputs = new int[Math.max(color, Math.max(solid, normal)) + 1];
            Arrays.fill(outputs, GL11.GL_NONE);
            outputs[color] = originalDrawBuffers[0];
            outputs[solid] = originalDrawBuffers[1];
            outputs[normal] = originalDrawBuffers[2];
            drawRouting(outputs);
            mode.set(1);
            frame.itrpMaterialWrites = true;
            frame.itrpBridgeFailure = null;
        } catch (ReflectiveOperationException | BridgeException | RuntimeException exception) {
            mode.set(0);
            drawRouting(colorOnly);
            failure(frame, "opaque-material-validation-failed", exception, true);
        }
    }

    /** Entry point from the optional, no-locals CompositeRenderer before-renderQuad mixin. */
    public static void beforeCompositeQuad(Object owner) {
        if (injecting || negativeAll() || !ModCompat.irisActive()) return;
        SkyWorldRenderer.Frame frame = SkyWorldRenderer.activeGameFrame();
        if (frame == null || frame.shadow || !frame.drawn || frame.floatCount == 0
                || frame.finalResolved || frame.itrpBackgroundInjected) return;
        Schema api = schema(frame);
        if (api == null || !supportedPack(frame, api)) return;
        try {
            if (!api.rendererClass.isInstance(owner)) return;
            Object stage = api.rendererStage.get(owner);
            if (!(stage instanceof Enum<?> passStage) || !passStage.name().equals("COMPOSITE")) return;
            int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            Object pass = currentPass(api, owner, programId);
            if (pass == null || !"composite40".equals(api.passName.get(pass))) return;
            injecting = true;
            try (SkyGlState compositeState = new SkyGlState(6)) {
                Object pipeline = api.rendererPipeline.get(owner);
                Object targets = api.rendererTargets.get(owner);
                if (pipeline != api.currentPipeline() || api.pipelineTargets.get(pipeline) != targets)
                    throw new BridgeException("composite-owner-pipeline-mismatch");
                int[] drawTargets = (int[]) api.passDrawBuffers.get(pass);
                if (drawTargets.length != 1 || drawTargets[0] != 6)
                    throw new BridgeException("composite40-output-semantics-mismatch");
                if (!validRaster(frame) || !shaderReady()) throw new BridgeException("background-raster-or-shader-unavailable");
                int input = samplerTexture(programId, "colortex6");
                requireTargetTexture(api, targets, 6, input);
                TextureInfo inputStorage = texture(input);
                requireStorage(inputStorage, frame, GL30.GL_RGBA16F, "colortex6-storage-mismatch");
                int passOutput = attachmentTexture(GL30.GL_COLOR_ATTACHMENT0);
                if (passOutput == 0 || passOutput == input) throw new BridgeException("composite40-input-output-alias");
                requireTargetTexture(api, targets, 6, passOutput);
                Object depthObject = api.noTranslucents.invoke(targets);
                int opaqueDepth = ((Number) api.depthTextureId.invoke(depthObject)).intValue();
                TextureInfo depthStorage = texture(opaqueDepth);
                // texture() has just bound this exact, owner-verified DepthTexture on unit zero.
                // Iris DEPTH may allocate/copy with unsized GL_DEPTH_COMPONENT; ask GL for effective storage bits.
                int depthBits = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL14.GL_TEXTURE_DEPTH_SIZE);
                requireStorage(depthStorage, frame, 0, "opaque-depth-storage-mismatch");
                String depthDescription = "format=0x" + Integer.toHexString(depthStorage.internalFormat)
                        + " depthBits=" + depthBits;
                if (!depthFormat(depthStorage.internalFormat) || depthBits <= 0)
                    throw new BridgeException("opaque-depth-format-mismatch " + depthDescription);
                if (depthBits < 24)
                    throw new BridgeException("opaque-depth-precision-insufficient " + depthDescription
                            + " minimumBits=24; strict coverage tolerance unchanged");
                if (REPORTED.add("validated-opaque-depth-" + depthStorage.internalFormat + "-" + depthBits))
                    SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP opaque depth validated: {} minimumBits=24", depthDescription);
                if (frame.ownSkyCoverageTexture == 0) throw new BridgeException("own-sky-coverage-unavailable");
                requireStorage(texture(frame.ownSkyCoverageTexture), frame, GL30.GL_RG32F, "own-sky-coverage-storage-mismatch");
                if (input == opaqueDepth || input == frame.ownSkyCoverageTexture)
                    throw new BridgeException("background-texture-alias");
                frame.itrpSceneInputTexture = input;
                frame.itrpOpaqueDepthTexture = opaqueDepth;
                PROVEN_PIPELINES.put(pipeline, targets);
                if (negativeBackground()) { failure(frame, "negative-itrp-background-control", null, false); return; }
                if (!frame.itrpMaterialWrites && !negativeMaterial()) {
                    failure(frame, "awaiting-this-view-neutral-material", null, false);
                    return;
                }
                int exposureTexture=api.exposureTexture(pipeline);
                TextureInfo exposureStorage=texture(exposureTexture);
                if(exposureStorage.width!=512 || exposureStorage.height!=513 || exposureStorage.internalFormat!=GL30.GL_RG16F)
                    throw new BridgeException("exposure-image-storage-mismatch");
                if(exposureTexture==input || exposureTexture==opaqueDepth || exposureTexture==frame.ownSkyCoverageTexture)
                    throw new BridgeException("exposure-image-alias");
                frame.itrpExposureTexture=exposureTexture;
                int skyTexture = VanillaSkyScene.render(frame, frame.viewport[2], frame.viewport[3]);
                if (skyTexture <= 0 || skyTexture == input) throw new BridgeException("generated-sky-unavailable-or-alias");
                compositeState.clearSamplers();
                writeBackground(frame, input, opaqueDepth, skyTexture, exposureTexture, inputStorage);
                frame.itrpBackgroundInjected = true;
                frame.itrpInjectionCount++;
                if (!negativeMaterial()) frame.itrpBridgeFailure = null;
            } finally { injecting = false; }
        } catch (ReflectiveOperationException | BridgeException | RuntimeException exception) {
            injecting = false;
            PROVEN_PIPELINES.clear();
            failure(frame, "composite-background-validation-failed", exception, true);
        }
    }

    private static void writeBackground(SkyWorldRenderer.Frame frame, int input, int opaqueDepth,
                                        int ownSky, int exposureTexture, TextureInfo storage) throws BridgeException {
        // compositeState in the caller owns the foreign framebuffer, shader, Iris state and quad VAO.
        if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, input, 0);
        try {
            int outputLocation = GL30.glGetFragDataLocation(backgroundShader.getId(), "fragColor");
            if (outputLocation < 0 || outputLocation >= GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS))
                throw new BridgeException("background-fragment-output-unavailable");
            int[] outputs = new int[outputLocation + 1];
            Arrays.fill(outputs, GL11.GL_NONE);
            outputs[outputLocation] = GL30.GL_COLOR_ATTACHMENT0;
            GL20.glDrawBuffers(outputs);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new BridgeException("background-framebuffer-incomplete");
            GlStateManager._viewport(0, 0, storage.width, storage.height);
            GL11.glViewport(0, 0, storage.width, storage.height);
            GL11.glDepthRange(frame.depthRange[0], frame.depthRange[1]);
            SkyGlState.opaqueState();
            // The private color-only target has no foreign stencil attachment. Its mask is our own provenance.
            RenderSystem.disableDepthTest(); GL11.glDisable(GL11.GL_DEPTH_TEST);
            RenderSystem.depthMask(false); GL11.glDepthMask(false);
            backgroundShader.setSampler("Sampler0", ownSky);
            backgroundShader.setSampler("WorldOpaqueDepth", opaqueDepth);
            backgroundShader.setSampler("SkyCoverage", frame.ownSkyCoverageTexture);
            backgroundShader.setSampler("ExposureData", exposureTexture);
            backgroundShader.getUniform("DistantDepthEnabled").set(0);
            backgroundShader.setSampler("DistantDepth",opaqueDepth);
            frame.distantHdrReady=ModCompat.DISTANT_HORIZONS && DistantSkyDepth.configure(backgroundShader,frame);
            SkyWorldRenderer.geometryUniforms(backgroundShader, frame, frame.viewport, false);
            backgroundShader.getUniform("TextureExtent").set((float) storage.width, (float) storage.height);
            backgroundShader.getUniform("Gain").set(backgroundGain());
            // No clear, no destination sampling, no world-color copy, no changes to Iris's own attachments or flips.
            SkyWorldRenderer.drawMaterialFaces(frame, backgroundShader, ownSky);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
        }
    }

    /** Read-only validation of the exact ITRP exposure compute's borrowed inputs. */
    static SkyExposureMeter.Inputs exposureMeterInputs(SkyWorldRenderer.Frame frame,int program) {
        try(SkyGlState ignored=new SkyGlState(6)) {
            Schema api=schema(frame);
            if(api==null || !supportedPack(frame,api)) return null;
            Object pipeline=api.currentPipeline(),targets=api.pipelineTargets.get(pipeline);
            if(PROVEN_PIPELINES.get(pipeline)!=targets || !frame.itrpBackgroundInjected) return null;
            int exposure=api.exposureTexture(pipeline);
            if(samplerTexture(program,"pixelData2D")!=exposure) return null;
            int image=GL20.glGetUniformLocation(program,"img_pixelData2D");
            if(image<0 || GL30.glGetIntegeri(org.lwjgl.opengl.GL42.GL_IMAGE_BINDING_NAME,
                    GL20.glGetUniformi(program,image))!=exposure) return null;
            TextureInfo e=texture(exposure);
            if(e.width!=512 || e.height!=513 || e.internalFormat!=GL30.GL_RG16F) return null;
            int atlas=samplerTexture(program,"colortex8"); requireTargetTexture(api,targets,8,atlas);
            TextureInfo a=texture(atlas);
            if(a.width<=0 || a.height<=0 || (a.internalFormat!=GL30.GL_RGBA16F && a.internalFormat!=GL11.GL_RGBA16)) return null;
            Object depthObject=api.noTranslucents.invoke(targets);
            int depth=((Number)api.depthTextureId.invoke(depthObject)).intValue();
            requireStorage(texture(depth),frame,0,"meter-opaque-depth-size");
            if(frame.ownSkyCoverageTexture==0 || frame.ownSkyColorTexture<=0 || atlas==exposure || atlas==depth) return null;
            float[] pixel=new float[2];int p=GL20.glGetUniformLocation(program,"pixelSize");
            if(p<0) return null; GL20.glGetUniformfv(program,p,pixel);
            if(!(pixel[0]>0 && pixel[1]>0)) return null;
            int unit=GL20.glGetUniformi(program,GL20.glGetUniformLocation(program,"colortex8"));
            return new SkyExposureMeter.Inputs(atlas,exposure,depth,a.width,a.height,unit,pixel[0],pixel[1]);
        } catch(ReflectiveOperationException | BridgeException | RuntimeException error) {
            if(REPORTED.add("dark-sky-meter-inputs")) SkyboxBlockMod.LOGGER.warn("[skyboxblock] dark-sky meter input validation unavailable",error);
            return null;
        }
    }

    /** Regression-only scalar readback; never enabled by normal gameplay and never writes a borrowed image. */
    static com.google.gson.JsonObject exposureDiagnostics(SkyWorldRenderer.Frame frame) {
        if (!Boolean.getBoolean("skyboxblock.regression") || !ModCompat.irisActive()) return null;
        var data = new com.google.gson.JsonObject();
        try (SkyGlState ignored = new SkyGlState(6)) {
            Schema api = schema(frame);
            if (api == null || !supportedPack(frame, api)) return null;
            Object pipeline = api.currentPipeline();
            int texture = api.exposureTexture(pipeline);
            TextureInfo storage = texture(texture);
            if (storage.width != 512 || storage.height != 513 || storage.internalFormat != GL30.GL_RG16F)
                throw new BridgeException("diagnostic-exposure-storage-mismatch");
            if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            try {
                GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                    throw new BridgeException("diagnostic-exposure-framebuffer-incomplete");
                var values = org.lwjgl.BufferUtils.createFloatBuffer(2);
                GL11.glReadPixels(0, 0, 1, 1, GL30.GL_RG, GL11.GL_FLOAT, values);
                data.addProperty("averageLuminance", values.get(0));
                data.addProperty("exposureMultiplier", values.get(1));
                data.addProperty("ownerVerified", true);
                data.addProperty("texture", texture);
                data.addProperty("width", storage.width);
                data.addProperty("height", storage.height);
                data.addProperty("source", "owner-verified-pixelData2D-RG16F-texel-0-0");
            } finally {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            }
        } catch (ReflectiveOperationException | BridgeException | RuntimeException exception) {
            data.addProperty("error", exception.toString());
        }
        return data;
    }

    private static Object currentPass(Schema api, Object owner, int currentProgram) throws ReflectiveOperationException, BridgeException {
        if (currentProgram == 0) return null;
        Object result = null;
        for (Object candidate : (Iterable<?>) api.rendererPasses.get(owner)) {
            // Read from the fixed Pass declaration: ComputeOnlyPass inherits these fields.
            Object program = api.passProgram.get(candidate);
            if (program == null || ((Number) api.programId.invoke(program)).intValue() != currentProgram) continue;
            if (result != null && result != candidate) throw new BridgeException("ambiguous-current-composite-program");
            result = candidate;
        }
        return result;
    }

    private static int samplerTexture(int program, String name) throws BridgeException {
        int location = GL20.glGetUniformLocation(program, name);
        if (location < 0) throw new BridgeException("inactive-input-sampler-" + name);
        int unit = GL20.glGetUniformi(program, location);
        if (unit < 0 || unit >= GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS))
            throw new BridgeException("invalid-input-sampler-unit");
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            // Query only; leave both texture binding and Minecraft's cached active unit unchanged.
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (texture == 0) throw new BridgeException("unbound-input-sampler-" + name);
            return texture;
        } finally { GL13.glActiveTexture(active); }
    }

    private static int attachmentTexture(int attachment) {
        if (attachment < GL30.GL_COLOR_ATTACHMENT0 || attachment > GL30.GL_COLOR_ATTACHMENT0 + 31) return 0;
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        return type == GL11.GL_TEXTURE ? GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) : 0;
    }

    private static TextureInfo texture(int id) throws BridgeException {
        if (id <= 0 || !GL11.glIsTexture(id)) throw new BridgeException("invalid-borrowed-texture");
        // Every caller is already within a six-unit SkyGlState scope; this touches unit zero only.
        SkyGlState.active(GL13.GL_TEXTURE0); SkyGlState.bindTexture(id);
        return new TextureInfo(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT));
    }

    private static void requireTargetTexture(Schema api, Object targets, int index, int texture)
            throws ReflectiveOperationException, BridgeException {
        Object target = api.targetAt.invoke(targets, index);
        if (target == null) throw new BridgeException("required-render-target-missing-" + index);
        int main = ((Number) api.mainTexture.invoke(target)).intValue();
        int alt = ((Number) api.altTexture.invoke(target)).intValue();
        if (texture <= 0 || (texture != main && texture != alt)) throw new BridgeException("unexpected-render-target-texture-" + index);
    }

    private static void requireStorage(TextureInfo storage, SkyWorldRenderer.Frame frame, int format, String reason)
            throws BridgeException {
        if (storage.width != frame.targetViewport[2] || storage.height != frame.targetViewport[3]
                || storage.width <= 0 || storage.height <= 0 || (format != 0 && storage.internalFormat != format))
            throw new BridgeException(reason);
    }

    private static boolean validRaster(SkyWorldRenderer.Frame frame) {
        return frame.targetViewport.length == 4 && frame.viewport.length == 4
                && frame.targetViewport[0] == 0 && frame.targetViewport[1] == 0
                && frame.targetViewport[2] > 0 && frame.targetViewport[3] > 0
                && frame.viewport[0] >= 0 && frame.viewport[1] >= 0
                && frame.viewport[2] > 0 && frame.viewport[3] > 0
                && frame.viewport[0] + frame.viewport[2] <= frame.targetViewport[2]
                && frame.viewport[1] + frame.viewport[3] <= frame.targetViewport[3]
                && Float.isFinite(frame.rasterScale.x) && Float.isFinite(frame.rasterScale.y)
                && frame.rasterScale.x > 0 && frame.rasterScale.y > 0;
    }

    private static boolean depthFormat(int format) {
        // Audited Iris 1.8.1 DepthBufferFormat.DEPTH returns 6402, including its first depthtex1 copy.
        // Acceptance also requires effective GL_TEXTURE_DEPTH_SIZE >= 24 at the call site.
        return format == GL11.GL_DEPTH_COMPONENT
                || format == 0x81A5 || format == 0x81A6 || format == 0x81A7 // DEPTH_COMPONENT16/24/32
                || format == GL30.GL_DEPTH_COMPONENT32F || format == GL30.GL_DEPTH24_STENCIL8
                || format == GL30.GL_DEPTH32F_STENCIL8;
    }

    private static void drawRouting(int[] outputs) { GL20.glDrawBuffers(outputs); }

    private static boolean shaderReady() {
        if (backgroundShader == null) return false;
        for (String uniform : new String[]{"WorldProjection", "WorldView", "SkyViewport", "ClipPlane", "ClippingEnabled",
                "RasterScale", "RasterJitter", "TextureExtent", "Gain"})
            if (backgroundShader.getUniform(uniform) == null) return false;
        return true;
    }

    private static boolean supportedPack(SkyWorldRenderer.Frame frame, Schema api) {
        String version = FabricLoader.getInstance().getModContainer("iris")
                .map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse("");
        if (!(version.equals("1.8.1") || version.startsWith("1.8.1+"))) {
            failure(frame, "unsupported-iris-version", null, false); return false;
        }
        try {
            Object maybePack = api.currentPack.invoke(null);
            if (!(maybePack instanceof Optional<?> current) || current.isEmpty()) return false;
            Object pack = current.get();
            String name = (String) api.currentPackName.invoke(null);
            Path directory = FabricLoader.getInstance().getGameDir().resolve("shaderpacks").toAbsolutePath().normalize();
            Path file = directory.resolve(name).normalize();
            if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
                failure(frame, "unsupported-nonarchive-shaderpack", null, false); return false;
            }
            Path real = file.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
            boolean cached = cachedPack == pack && name.equals(cachedPackName) && real.equals(cachedPath)
                    && cachedSize == attributes.size() && cachedModified == attributes.lastModifiedTime().toMillis()
                    && java.util.Objects.equals(cachedFileKey, attributes.fileKey());
            if (!cached) {
                PROVEN_PIPELINES.clear();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream stream = Files.newInputStream(real)) {
                    byte[] chunk = new byte[65536];
                    int count;
                    while ((count = stream.read(chunk)) != -1) digest.update(chunk, 0, count);
                }
                cachedPack = pack; cachedPackName = name; cachedPath = real;
                cachedSize = attributes.size(); cachedModified = attributes.lastModifiedTime().toMillis();
                cachedFileKey = attributes.fileKey(); cachedPackHash = HexFormat.of().formatHex(digest.digest());
            }
            if (!PACK_SHA256.equals(cachedPackHash)) {
                failure(frame, "unsupported-shaderpack-hash", null, false); return false;
            }
            return true;
        } catch (Exception exception) {
            failure(frame, "shaderpack-fingerprint-unavailable", exception, true); return false;
        }
    }

    private static Schema schema(SkyWorldRenderer.Frame frame) {
        if (schema != null) return schema;
        if (schemaFailed) {
            failure(frame, "iris-reflection-schema-unavailable", null, false);
            return null;
        }
        try { return schema = new Schema(); }
        catch (ReflectiveOperationException | RuntimeException exception) {
            schemaFailed = true;
            failure(frame, "iris-reflection-schema-unavailable", exception, true);
            return null;
        }
    }

    private static void failure(SkyWorldRenderer.Frame frame, String reason, Throwable throwable, boolean warning) {
        Throwable cause = throwable;
        while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null)
            cause = cause.getCause();
        String detail = cause == null ? "" : ": " + cause;
        if (frame != null) frame.itrpBridgeFailure = reason + detail;
        if (REPORTED.add(reason)) {
            if (warning) SkyboxBlockMod.LOGGER.warn("[skyboxblock] ITRP sky bridge: {}{}", reason, detail);
            else SkyboxBlockMod.LOGGER.debug("[skyboxblock] ITRP sky bridge inactive: {}", reason);
        }
    }

    private static boolean regressionFlag(String key) {
        return Boolean.getBoolean("skyboxblock.regression") && Boolean.getBoolean("skyboxblock.regression." + key);
    }
    private static boolean negativeAll() { return regressionFlag("negativeTransparency"); }
    private static boolean negativeMaterial() { return regressionFlag("negativeItriPMaterial"); }
    private static boolean negativeBackground() { return regressionFlag("negativeItriPBackground"); }

    private static float backgroundGain() {
        try {
            float value = Float.parseFloat(System.getProperty("skyboxblock.itrpBackgroundGain", "1"));
            return Float.isFinite(value) && value > 0 ? value : 1F;
        } catch (NumberFormatException ignored) { return 1F; }
    }

    private record TextureInfo(int width, int height, int internalFormat) {}
    private static final class BridgeException extends Exception {
        BridgeException(String message) { super(message); }
    }

    /** Iris-owned strings only. No reflected Minecraft method names or private MC fields. */
    private static final class Schema {
        final Class<?> rendererClass, pipelineClass;
        final Field rendererPasses, rendererTargets, rendererPipeline, rendererStage;
        final Field passName, passProgram, passDrawBuffers, pipelineTargets,pipelineImages;
        final Method imageSamplerName,imageId;
        final Method currentPack, currentPackName, pipelineManager, pipelineNullable;
        final Method programId, targetAt, mainTexture, altTexture, noTranslucents, depthTextureId;
        Schema() throws ReflectiveOperationException {
            Class<?> iris = Class.forName(IRIS_PREFIX + "Iris");
            currentPack = iris.getMethod("getCurrentPack");
            currentPackName = iris.getMethod("getCurrentPackName");
            pipelineManager = iris.getMethod("getPipelineManager");
            pipelineNullable = Class.forName(IRIS_PREFIX + "pipeline.PipelineManager").getMethod("getPipelineNullable");
            rendererClass = Class.forName(IRIS_PREFIX + "pipeline.CompositeRenderer");
            rendererPasses = field(rendererClass, "passes");
            rendererTargets = field(rendererClass, "renderTargets");
            rendererPipeline = field(rendererClass, "pipeline");
            rendererStage = field(rendererClass, "compositePass");
            Class<?> pass = Class.forName(IRIS_PREFIX + "pipeline.CompositeRenderer$Pass");
            passName = field(pass, "name"); passProgram = field(pass, "program"); passDrawBuffers = field(pass, "drawBuffers");
            programId = Class.forName(IRIS_PREFIX + "gl.program.Program").getMethod("getProgramId");
            pipelineClass = Class.forName(IRIS_PREFIX + "pipeline.IrisRenderingPipeline");
            pipelineTargets = field(pipelineClass, "renderTargets");
            pipelineImages=field(pipelineClass,"customImages");
            Class<?> image=Class.forName(IRIS_PREFIX+"gl.image.GlImage");
            imageSamplerName=image.getMethod("getSamplerName"); imageId=image.getMethod("getId");
            Class<?> targets = Class.forName(IRIS_PREFIX + "targets.RenderTargets");
            targetAt = targets.getMethod("get", int.class);
            noTranslucents = targets.getMethod("getDepthTextureNoTranslucents");
            Class<?> target = Class.forName(IRIS_PREFIX + "targets.RenderTarget");
            mainTexture = target.getMethod("getMainTexture"); altTexture = target.getMethod("getAltTexture");
            depthTextureId = Class.forName(IRIS_PREFIX + "targets.DepthTexture").getMethod("getTextureId");
        }
        Object currentPipeline() throws ReflectiveOperationException, BridgeException {
            Object manager = pipelineManager.invoke(null);
            Object pipeline = pipelineNullable.invoke(manager);
            if (pipeline == null || !pipelineClass.isInstance(pipeline)) throw new BridgeException("iris-world-pipeline-unavailable");
            return pipeline;
        }
        int exposureTexture(Object pipeline) throws ReflectiveOperationException,BridgeException {
            int result=0,count=0;
            for(Object image:(Iterable<?>)pipelineImages.get(pipeline)) {
                if("pixelData2D".equals(imageSamplerName.invoke(image))) {
                    result=((Number)imageId.invoke(image)).intValue();count++;
                }
            }
            if(count!=1 || result<=0) throw new BridgeException("owner-exposure-image-unavailable-or-ambiguous");
            return result;
        }
        private static Field field(Class<?> type, String name) throws NoSuchFieldException {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
    }
}
