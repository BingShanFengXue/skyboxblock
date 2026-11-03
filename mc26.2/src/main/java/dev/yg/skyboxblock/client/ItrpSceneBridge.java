package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.opengl.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;

/**
 * Audited ITRP 0.8.31 / Iris 1.11.4 scene-input adapter. No guessed global colortex IDs.
 * The pack does its solid lighting in composite25, not deferred: its glass in composite50
 * samples composite40's colortex6 input. A post-deferred colortex0 write cannot repair that.
 * Every borrowed GL name is revalidated against the current view's actual pipeline/targets.
 */
final class ItrpSceneBridge {
    static final String PACK_SHA256="334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8";
    record MaterialTargets(Object pipeline,Object targets,Object composite,int solid,int normal) { }
    record SceneInput(int color,int opaqueDepth,int exposure) { }
    private record Storage(int width,int height,int format) { }
    private static final Set<String> REPORTED=new HashSet<>();
    private static Object cachedPack;
    private static Path cachedPath;
    private static Object cachedFileKey;
    private static long cachedSize=-1,cachedModified=-1;
    private static String cachedHash;
    private ItrpSceneBridge() { }

    /** Caller owns SkyGlScope; reads the foreign FBO but mutates only texture-unit zero bindings. */
    static MaterialTargets opaque(int source,int color,int depth,int width,int height) {
        if(!ModCompat.irisActive() || source<=0 || !supportedPack())return null;
        try {
            Object pipeline=ModCompat.pipeline(),targets=ModCompat.field(pipeline,"renderTargets");
            Object composite=ModCompat.field(pipeline,"compositeRenderer");
            require(ModCompat.field(composite,"pipeline")==pipeline && ModCompat.field(composite,"renderTargets")==targets,"opaque-owner-mismatch");
            require("COMPOSITE".equals(String.valueOf(ModCompat.field(composite,"compositePass"))),"composite-stage-mismatch");
            // Verify the compiled program plan before enabling auxiliary writes, including first view.
            validatePlan(composite);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,source);
            int[] textures=new int[3];
            for(int i=0;i<3;i++) {
                int attachment=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0+i);
                require(attachment>=GL30.GL_COLOR_ATTACHMENT0 && attachment<GL30.GL_COLOR_ATTACHMENT0+32,"opaque-mrt-routing-mismatch");
                textures[i]=ModCompat.attachment(source,attachment);
                requireTarget(targets,i,textures[i]);
            }
            require(textures[0]==color && color!=textures[1] && color!=textures[2] && textures[1]!=textures[2],"opaque-mrt-alias");
            require(ModCompat.attachment(source,GL30.GL_DEPTH_ATTACHMENT)==depth,"opaque-depth-owner-mismatch");
            requireStorage(storage(textures[1]),width,height,GL11.GL_RGBA16,"solid-data-storage-mismatch");
            requireStorage(storage(textures[2]),width,height,GL11.GL_RGBA16,"solid-normal-storage-mismatch");
            if(REPORTED.add("material-ready"))SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP scene bridge: audited 0.8.31 material MRT and composite25/40/50 plan validated");
            return new MaterialTargets(pipeline,targets,composite,textures[1],textures[2]);
        } catch(ReflectiveOperationException|RuntimeException error) {failure("opaque-validation",error);return null;}
    }

    /** Called after actual Program.use/customUniforms.push and immediately before Iris's indexed quad. */
    static SceneInput sceneInput(Object owner,MaterialTargets material,int width,int height,int coverage,int generatedSky) {
        if(material==null || owner!=material.composite())return null;
        try {
            Object pipeline=ModCompat.pipeline(),targets=ModCompat.field(owner,"renderTargets");
            require(pipeline==material.pipeline() && targets==material.targets() && ModCompat.field(owner,"pipeline")==pipeline,"scene-owner-mismatch");
            require("COMPOSITE".equals(String.valueOf(ModCompat.field(owner,"compositePass"))),"scene-stage-mismatch");
            int program=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            Object current=null;
            for(Object pass:(Iterable<?>)ModCompat.field(owner,"passes")) {
                Object p=ModCompat.field(pass,"program");
                if(p!=null && programId(p)==program) {
                    require(current==null,"ambiguous-current-program");current=pass;
                }
            }
            if(current==null || !"composite40".equals(ModCompat.field(current,"name")))return null;
            require(Arrays.equals((int[])ModCompat.field(current,"drawBuffers"),new int[]{6}),"scene-pass-target-mismatch");
            require(GL30.glGetFragDataLocation(program,"framebuffer_mainOutput")==0,"scene-fragment-layout-mismatch");
            Object passFbo=ModCompat.field(current,"framebuffer");
            int expected=((Number)passFbo.getClass().getMethod("getId").invoke(passFbo)).intValue();
            int actual=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            require(actual==expected && actual>0,"scene-framebuffer-owner-mismatch");
            int input=samplerTexture(program,"colortex6");requireTarget(targets,6,input);
            int attachment=GL11.glGetInteger(GL20.GL_DRAW_BUFFER0),output=ModCompat.attachment(actual,attachment);
            requireTarget(targets,6,output);require(input!=output,"scene-read-write-alias");
            requireStorage(storage(input),width,height,GL30.GL_RGBA16F,"scene-input-storage-mismatch");
            requireStorage(storage(output),width,height,GL30.GL_RGBA16F,"scene-output-storage-mismatch");
            // 26.2 changed this accessor from Iris DepthTexture to MC GpuTexture; use the actual GlTexture.
            Object opaque=targets.getClass().getMethod("getDepthTextureNoTranslucents").invoke(targets);
            require(opaque instanceof com.mojang.blaze3d.textures.GpuTexture,"scene-depth-type-mismatch");
            int depth=SkyWindowRenderer.gl((com.mojang.blaze3d.textures.GpuTexture)opaque);
            Storage depthStorage=storage(depth);requireStorage(depthStorage,width,height,0,"scene-depth-size-mismatch");
            int bits=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL14.GL_TEXTURE_DEPTH_SIZE);
            require(depthFormat(depthStorage.format()) && bits>=24,"scene-depth-format-or-precision-mismatch");
            requireStorage(storage(coverage),width,height,GL30.GL_RG32F,"scene-provenance-storage-mismatch");
            int exposure=exposureTexture(pipeline);
            requireStorage(storage(exposure),512,513,GL30.GL_RG16F,"scene-exposure-storage-mismatch");
            require(input!=depth && input!=coverage && input!=generatedSky && input!=exposure && exposure!=depth && exposure!=coverage,"scene-source-alias");
            if(REPORTED.add("scene-ready"))SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP scene bridge: composite40 actual colortex6 RGBA16F input, opaque depth {} bits and pixelData2D verified",bits);
            return new SceneInput(input,depth,exposure);
        } catch(ReflectiveOperationException|RuntimeException error) {failure("scene-validation",error);return null;}
    }

    record MeterInputs(int atlas,int exposure,int opaqueDepth,int width,int height,int unit,int sampler,float pixelX,float pixelY) { }

    /** Read-only exposure input ownership. Caller owns SkyGlScope; no attachment/image is written. */
    static MeterInputs meterInputs(SkyWindowRenderer.MeterView view,Object compute,int program) {
        if(view==null || !view.root() || view.portal() || view.shadow() || !view.darkSky()
                || !view.sceneInjected() || !ModCompat.irisActive() || !supportedPack())return null;
        try {
            require(FabricLoader.getInstance().getModContainer("iris").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("")
                    .equals("1.11.4+mc26.2"),"meter-iris-version-mismatch");
            require(FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("")
                    .equals("26.2"),"meter-minecraft-version-mismatch");
            Object pipeline=ModCompat.pipeline(),targets=ModCompat.field(pipeline,"renderTargets");
            Object composite=ModCompat.field(pipeline,"compositeRenderer");
            require(pipeline==view.pipeline() && targets==view.targets() && composite==view.composite(),"meter-view-owner-mismatch");
            require(ModCompat.field(composite,"pipeline")==pipeline && ModCompat.field(composite,"renderTargets")==targets
                    && "COMPOSITE".equals(String.valueOf(ModCompat.field(composite,"compositePass"))),"meter-composite-owner-mismatch");
            require(compute!=null && compute.getClass().getName().equals("net.irisshaders.iris.gl.program.ComputeProgram")
                    && programId(compute)==program && program>0 && GL20.glIsProgram(program),"meter-compute-program-mismatch");
            int memberships=0;
            for(Object pass:(Iterable<?>)ModCompat.field(composite,"passes")) {
                Object[] computes=(Object[])ModCompat.field(pass,"computes");
                if(computes==null)continue;
                for(Object member:computes)if(member==compute) {
                    require("composite74".equals(ModCompat.field(pass,"name")),"meter-compute-pass-mismatch");memberships++;
                }
            }
            require(memberships==1,"meter-compute-owner-missing-or-ambiguous");
            require(ModCompat.field(compute,"indirectPointer")==null,"meter-indirect-dispatch-mismatch");
            Object groups=ModCompat.field(compute,"absoluteWorkGroups");
            require(groups instanceof org.joml.Vector3ic g && g.x()==1 && g.y()==1 && g.z()==1,"meter-workgroup-count-mismatch");
            int[] local=new int[3];GL20.glGetProgramiv(program,GL43.GL_COMPUTE_WORK_GROUP_SIZE,local);
            require(Arrays.equals(local,new int[]{32,16,1}),"meter-local-size-mismatch");
            int exposure=exposureTexture(pipeline);
            require(samplerTexture(program,"pixelData2D")==exposure,"meter-exposure-sampler-owner-mismatch");
            int image=GL20.glGetUniformLocation(program,"img_pixelData2D");require(image>=0,"meter-exposure-image-missing");
            int imageUnit=GL20.glGetUniformi(program,image);
            require(imageUnit>=0 && imageUnit<GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS),"meter-image-unit-mismatch");
            require(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,imageUnit)==exposure
                    && GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LEVEL,imageUnit)==0
                    // Iris 1.11.4 ImageBinding.update always passes layered=true, including 2D.
                    // The validated underlying TextureType remains TEXTURE_2D; GL ignores layering there.
                    && GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYERED,imageUnit)==1
                    && GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_LAYER,imageUnit)==0
                    && GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_FORMAT,imageUnit)==GL30.GL_RG16F,"meter-exposure-image-owner-mismatch");
            requireStorage(storage(exposure),512,513,GL30.GL_RG16F,"meter-exposure-storage-mismatch");
            int atlas=samplerTexture(program,"colortex8");requireTarget(targets,8,atlas);
            Storage a=storage(atlas);
            require(a.width()>0 && a.height()>0 && (a.format()==GL30.GL_RGBA16F || a.format()==GL11.GL_RGBA16),"meter-atlas-storage-mismatch");
            Object opaque=targets.getClass().getMethod("getDepthTextureNoTranslucents").invoke(targets);
            require(opaque instanceof com.mojang.blaze3d.textures.GpuTexture,"meter-depth-type-mismatch");
            int depth=SkyWindowRenderer.gl((com.mojang.blaze3d.textures.GpuTexture)opaque);
            Storage d=storage(depth);requireStorage(d,view.width(),view.height(),0,"meter-depth-size-mismatch");
            require(depthFormat(d.format()),"meter-depth-format-mismatch");
            requireStorage(storage(view.coverage()),view.width(),view.height(),GL30.GL_RG32F,"meter-coverage-storage-mismatch");
            requireStorage(storage(view.generatedSky()),view.width(),view.height(),GL11.GL_RGBA8,"meter-generated-sky-storage-mismatch");
            int[] names={atlas,exposure,depth,view.coverage(),view.generatedSky()};
            for(int i=0;i<names.length;i++)for(int j=i+1;j<names.length;j++)require(names[i]!=names[j],"meter-input-alias");
            int pixel=GL20.glGetUniformLocation(program,"pixelSize");require(pixel>=0,"meter-pixel-size-missing");
            float[] size=new float[2];GL20.glGetUniformfv(program,pixel,size);
            require(Float.isFinite(size[0]) && Float.isFinite(size[1]) && size[0]>0 && size[1]>0
                    && size[0]<1 && size[1]<1,"meter-pixel-size-invalid");
            int unit=GL20.glGetUniformi(program,GL20.glGetUniformLocation(program,"colortex8"));
            int sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,unit);
            return new MeterInputs(atlas,exposure,depth,a.width(),a.height(),unit,sampler,size[0],size[1]);
        } catch(ReflectiveOperationException|RuntimeException error) {failure("meter-validation",error);return null;}
    }

    private static void validatePlan(Object composite) throws ReflectiveOperationException {
        boolean solid=false,water=false,glass=false;
        for(Object pass:(Iterable<?>)ModCompat.field(composite,"passes")) {
            String name=String.valueOf(ModCompat.field(pass,"name"));
            if(!name.equals("composite25") && !name.equals("composite40") && !name.equals("composite50"))continue;
            Object p=ModCompat.field(pass,"program");require(p!=null,"required-composite-program-missing");
            int id=programId(p);require(id>0 && GL20.glIsProgram(id),"required-composite-program-invalid");
            int[] draw=(int[])ModCompat.field(pass,"drawBuffers");
            if(name.equals("composite25") || name.equals("composite40")) {
                require(GL30.glGetFragDataLocation(id,"framebuffer_mainOutput")==0,"composite-fragment-output-mismatch");
                require(Arrays.equals(draw,new int[]{6}),"solid-or-water-scene-target-mismatch");
                if(name.equals("composite25"))solid=true;
                else {require(GL20.glGetUniformLocation(id,"colortex6")>=0,"water-scene-sampler-missing");water=true;}
            } else {
                require(GL20.glGetUniformLocation(id,"colortex6")>=0,"glass-scene-sampler-missing");
                // Normal 0.8.31 stores its result to colorimg9; DLSSRR instead writes MRT 6.
                boolean imageLayout=Arrays.equals(draw,new int[]{0}) && GL20.glGetUniformLocation(id,"colorimg9")>=0;
                boolean rrLayout=Arrays.equals(draw,new int[]{6}) && GL30.glGetFragDataLocation(id,"framebuffer_mainOutput")==0;
                require(imageLayout || rrLayout,"glass-scene-layout-mismatch");glass=true;
            }
        }
        require(solid && water && glass,"required-composite-plan-missing");
    }

    private static int programId(Object program) throws ReflectiveOperationException {
        return ((Number)program.getClass().getMethod("getProgramId").invoke(program)).intValue();
    }
    private static int exposureTexture(Object pipeline) throws ReflectiveOperationException {
        int result=0,count=0;
        for(Object image:(Iterable<?>)ModCompat.field(pipeline,"customImages")) {
            if("pixelData2D".equals(image.getClass().getMethod("getSamplerName").invoke(image))) {
                Object target=ModCompat.field(image,"target");
                require(((Number)target.getClass().getMethod("getGlType").invoke(target)).intValue()==GL11.GL_TEXTURE_2D,
                        "exposure-image-is-not-texture2D");
                result=((Number)image.getClass().getMethod("getId").invoke(image)).intValue();count++;
            }
        }
        require(count==1 && result>0,"exposure-owner-missing-or-ambiguous");return result;
    }
    private static int samplerTexture(int program,String name) {
        int location=GL20.glGetUniformLocation(program,name);require(location>=0,"inactive-scene-sampler");
        int unit=GL20.glGetUniformi(program,location);
        require(unit>=0 && unit<GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS),"invalid-scene-sampler-unit");
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);int texture=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);require(texture>0,"unbound-scene-sampler");return texture;}
        finally {GL13.glActiveTexture(active);}
    }
    private static void requireTarget(Object targets,int index,int texture) throws ReflectiveOperationException {
        Object target=targets.getClass().getMethod("get",int.class).invoke(targets,index);require(target!=null,"missing-render-target-"+index);
        int main=((Number)target.getClass().getMethod("getMainTexture").invoke(target)).intValue();
        int alt=((Number)target.getClass().getMethod("getAltTexture").invoke(target)).intValue();
        require(texture>0 && (texture==main || texture==alt),"unexpected-target-texture-"+index);
    }
    private static Storage storage(int id) {
        require(id>0 && GL11.glIsTexture(id),"invalid-borrowed-texture");
        GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        return new Storage(GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_INTERNAL_FORMAT));
    }
    private static void requireStorage(Storage s,int w,int h,int format,String reason) {
        require(w>0 && h>0 && s.width()==w && s.height()==h && (format==0 || s.format()==format),reason);
    }
    private static boolean depthFormat(int format) {
        return format==GL11.GL_DEPTH_COMPONENT || format==0x81A5 || format==0x81A6 || format==0x81A7
                || format==GL30.GL_DEPTH_COMPONENT32F || format==GL30.GL_DEPTH24_STENCIL8 || format==GL30.GL_DEPTH32F_STENCIL8;
    }
    private static void require(boolean okay,String reason) {if(!okay)throw new IllegalStateException(reason);}
    private static void failure(String reason,Throwable error) {
        String key=reason+":"+error;
        if(REPORTED.add(key))SkyboxBlockMod.LOGGER.warn("[skyboxblock] ITRP scene bridge {}: {} (generic sky path retained)",reason,error.toString());
    }
    private static boolean supportedPack() {
        // Explicit owned-fixture negative control; ordinary clients do not acquire a compatibility toggle.
        if(!System.getProperty("skyboxblock.fixture.control", "").isBlank()
                && Boolean.getBoolean("skyboxblock.fixture.disableItrpSceneBridge"))return false;
        String version=FabricLoader.getInstance().getModContainer("iris").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        if(!(version.equals("1.11.4") || version.startsWith("1.11.4+")))return false;
        try {
            Class<?> iris=Class.forName("net.irisshaders.iris.Iris");
            Object value=iris.getMethod("getCurrentPack").invoke(null);
            if(!(value instanceof Optional<?> optional) || optional.isEmpty())return false;
            String name=(String)iris.getMethod("getCurrentPackName").invoke(null);
            Path directory=FabricLoader.getInstance().getGameDir().resolve("shaderpacks").toAbsolutePath().normalize();
            Path path=directory.resolve(name).normalize();if(!path.startsWith(directory) || !Files.isRegularFile(path))return false;
            Path real=path.toRealPath();BasicFileAttributes a=Files.readAttributes(real,BasicFileAttributes.class);
            if(cachedPack!=optional.get() || !real.equals(cachedPath) || a.size()!=cachedSize || a.lastModifiedTime().toMillis()!=cachedModified
                    || !java.util.Objects.equals(a.fileKey(),cachedFileKey)) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(InputStream stream=Files.newInputStream(real)) {byte[] bytes=new byte[65536];int n;while((n=stream.read(bytes))!=-1)digest.update(bytes,0,n);}
                cachedPack=optional.get();cachedPath=real;cachedSize=a.size();cachedModified=a.lastModifiedTime().toMillis();cachedFileKey=a.fileKey();cachedHash=HexFormat.of().formatHex(digest.digest());
            }
            return PACK_SHA256.equals(cachedHash);
        } catch(Exception error) {failure("pack-fingerprint",error);return false;}
    }
    static void invalidate() {cachedPack=null;cachedPath=null;cachedFileKey=null;cachedSize=cachedModified=-1;cachedHash=null;REPORTED.clear();}
}
