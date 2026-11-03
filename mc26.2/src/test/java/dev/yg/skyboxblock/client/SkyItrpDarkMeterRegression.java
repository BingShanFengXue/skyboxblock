package dev.yg.skyboxblock.client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Hidden, real GL3.3 regression for the production proxy raster and substitution. The compute here
 * is an instrumented atlas-sampling oracle, NOT a claim that Minecraft/the installed ITRP ran. */
public final class SkyItrpDarkMeterRegression {
    private static final int W=1024,H=512,UNIT=11,ACTIVE=13;
    private static int checks,gpuChecks,structuralChecks;
    private static final Path ROOT=Path.of("src/main");
    private SkyItrpDarkMeterRegression() { }
    public static void main(String[] args) throws Exception {
        structural();
        if(!GLFW.glfwInit())throw new AssertionError("GLFW init");
        GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT,GLFW.GLFW_TRUE);
        long context=GLFW.glfwCreateWindow(64,64,"ITRP dark meter offline GL3.3",0,0);
        if(context==0)throw new AssertionError("GL3.3 hidden context");
        GLFW.glfwMakeContextCurrent(context);var c=GL.createCapabilities();
        try {
            System.out.println("GL "+GL11.glGetString(GL11.GL_VERSION)+" / "+GL11.glGetString(GL11.GL_RENDERER)
                    +" core40="+c.OpenGL40+" core42="+c.OpenGL42+" core43="+c.OpenGL43
                    +" ARB_compute="+c.GL_ARB_compute_shader+" ARB_image="+c.GL_ARB_shader_image_load_store
                    +" dispatchPtr="+c.glDispatchCompute+" barrierPtr="+c.glMemoryBarrier+" bindImagePtr="+c.glBindImageTexture);
            check(c.OpenGL33 && !c.OpenGL40,"actual GL3.3, not a 4.x core substitute");
            check(ItrpDarkMeterProxy.capable(),"production capability gate accepts real ARB compute/image pointers");
            run();
            System.out.println("SkyItrpDarkMeterRegression: "+checks+" checks passed; "+gpuChecks+" GPU/contract, "+structuralChecks+" structural (no game/fixture launch)");
        } finally {ItrpDarkMeterProxy.release();GLFW.glfwDestroyWindow(context);GLFW.glfwTerminate();}
    }
    private static void run() {
        int compute=compute();int[] local=new int[3];GL20.glGetProgramiv(compute,GL43.GL_COMPUTE_WORK_GROUP_SIZE,local);
        check(Arrays.equals(local,new int[]{32,16,1}),"GL20 glGetProgramiv queries extension COMPUTE_WORK_GROUP_SIZE");
        int sampler=GL33.glGenSamplers();GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL33.glSamplerParameteri(sampler,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        int atlas=image(GL30.GL_RGBA16F,GL11.GL_RGBA,W,H,fill(W,H,new float[]{.01F,.01F,.01F,.37F}));
        int sky=image(GL11.GL_RGBA8,GL11.GL_RGBA,W,H,fill(W,H,new float[]{.75F,.75F,.75F,1}));
        float[] cover=new float[W*H*2],depthValues=new float[W*H];
        Arrays.fill(depthValues,.625F);
        for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
            cover[(y*W+x)*2]=.625F;cover[(y*W+x)*2+1]=x<512 ? 3 : x<768 ? 2 : x<896 ? 7 : 9;
            if(x>=256 && x<512)depthValues[y*W+x]=.9F;
        }
        int coverage=image(GL30.GL_RG32F,GL30.GL_RG,W,H,cover);
        int depth=image(GL30.GL_DEPTH_COMPONENT32F,GL11.GL_DEPTH_COMPONENT,W,H,depthValues);
        int exposure=image(GL30.GL_RG16F,GL30.GL_RG,512,513,fill(512,513,new float[]{12,.5F}));
        int scene=image(GL30.GL_RGBA16F,GL11.GL_RGBA,W,H,fill(W,H,new float[]{.2F,.3F,.4F,.5F}));
        int world=fbo(scene);
        float[] originalAtlas=read(atlas,GL11.GL_RGBA,W,H,4),originalScene=read(scene,GL11.GL_RGBA,W,H,4);
        float[] originalExposure=read(exposure,GL30.GL_RG,512,513,2);
        var view=view(sky,coverage,true,false,0,false,true,1,1,0,0);
        var input=new ItrpSceneBridge.MeterInputs(atlas,exposure,depth,W,H,UNIT,sampler,1F/W,1F/H);
        bindCompute(compute,atlas,exposure,sampler,world);
        int[] savedTextures=new int[6],savedSamplers=new int[6];
        for(int unit=0;unit<6;unit++){savedTextures[unit]=binding(unit);savedSamplers[unit]=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,unit);}
        Runnable restore=ItrpDarkMeterProxy.substitute(view,input);
        check(restore!=null,"production proxy activated");
        check(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==compute,"real compute remains current");
        check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE0+ACTIVE,"entry activeUnit restored before dispatch");
        check(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==world,"world draw framebuffer retained");
        int proxy=binding(UNIT);check(proxy!=atlas && proxy>0,"only colortex8 sampler sees owned atlas proxy");
        check(GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,UNIT)!=sampler,"meter uses private bilinear sampler");
        check(GL30.glGetIntegeri(GL42.GL_IMAGE_BINDING_NAME,3)==exposure,"original exposure image binding retained");
        check(Arrays.equals(originalExposure,read(exposure,GL30.GL_RG,512,513,2)),"proxy raster never writes exposure image");
        float[] pixels=read(proxy,GL11.GL_RGBA,W,H,4);
        close(pixel(pixels,493,2),new float[]{1.0621F,1.0621F,1.0621F,1},.006F,"dark black meters private physical sky, not black display");
        close(pixel(pixels,498,2),new float[]{.01F,.01F,.01F,1},.0001F,"opaque foreground blocks correction");
        close(pixel(pixels,502,2),new float[]{.01F,.01F,.01F,1},.0001F,"ordinary non-dark sky material unchanged");
        check(pixel(pixels,504,2)[0]>.9F && pixel(pixels,506,2)[0]>.9F,"galaxy and apocalypse dark material identities supported");
        try {dispatch();} finally {restore.run();}
        restored(atlas,sampler,compute,world,"normal compute finally");
        for(int unit=0;unit<6;unit++)check(binding(unit)==savedTextures[unit]
                && GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,unit)==savedSamplers[unit],"private raster unit "+unit+" texture and sampler restored");
        float[] afterExposure=read(exposure,GL30.GL_RG,512,513,2);
        check(afterExposure[0]>.1F && afterExposure[1]>0,"original instrumented compute still writes adapted exposure");
        check(Arrays.equals(Arrays.copyOfRange(originalExposure,2,originalExposure.length),Arrays.copyOfRange(afterExposure,2,afterExposure.length)),"compute output restricted to its own selected image texel");
        check(Arrays.equals(originalAtlas,read(atlas,GL11.GL_RGBA,W,H,4)),"real scene/bloom atlas RGB+alpha unmodified");
        check(Arrays.equals(originalScene,read(scene,GL11.GL_RGBA,W,H,4)),"ordinary world/scene RGB+alpha unmodified");
        restore.run();restored(atlas,sampler,compute,world,"restore idempotence");

        // Preseed the existing proxy, then prove the exact tile+2-texel scissor, no full atlas copy.
        upload(proxy,GL11.GL_RGBA,W,H,fill(W,H,new float[]{.7F,.6F,.5F,.4F}));
        resetExposure(exposure);bindCompute(compute,atlas,exposure,sampler,world);
        restore=ItrpDarkMeterProxy.substitute(view,input);
        try {
            float[] scoped=read(binding(UNIT),GL11.GL_RGBA,W,H,4);
            close(pixel(scoped,489,2),new float[]{.7F,.6F,.5F,.4F},.001F,"left of +2-texel scissor stays untouched");
            close(pixel(scoped,490,2),new float[]{.01F,.01F,.01F,1},.0001F,"left bilinear margin copies original atlas");
            close(pixel(scoped,509,2),new float[]{.01F,.01F,.01F,1},.0001F,"right bilinear margin copies original atlas");
            close(pixel(scoped,510,2),new float[]{.7F,.6F,.5F,.4F},.001F,"right of +2-texel scissor stays untouched");
            close(pixel(scoped,493,9),new float[]{.01F,.01F,.01F,1},.0001F,"top bilinear margin copies original atlas");
            close(pixel(scoped,493,10),new float[]{.7F,.6F,.5F,.4F},.001F,"outside top margin stays untouched");
            GL13.glActiveTexture(GL13.GL_TEXTURE0+17);throw new ExpectedDispatchFailure();
        } catch(ExpectedDispatchFailure expected) { /* Same finally semantics as the real mixin. */ }
        finally {restore.run();}
        restored(atlas,sampler,compute,world,"throwing compute finally, even if dispatch changed activeUnit");

        // The fixture-negative switch requires the explicit fixture owner property too.
        System.setProperty("skyboxblock.fixture.disableItrpDarkMeter","true");System.clearProperty("skyboxblock.fixture.control");
        check(!ItrpDarkMeterProxy.negativeControl(),"ordinary gameplay cannot disable meter via standalone negative flag");
        System.setProperty("skyboxblock.fixture.control","offline-owned-test");
        resetExposure(exposure);bindCompute(compute,atlas,exposure,sampler,world);
        check(ItrpDarkMeterProxy.negativeControl() && ItrpDarkMeterProxy.substitute(view,input)==null,"explicit fixture-only negative bypass retains real atlas");
        dispatch();float baseline=read(exposure,GL30.GL_RG,512,513,2)[0];
        check(baseline<.011F && afterExposure[0]>baseline*10,"same program negative control detects missing correction; auto-exposure still dispatches");
        System.out.println("Measured meter RGB="+Arrays.toString(pixel(pixels,493,2))+" foreground="+Arrays.toString(pixel(pixels,498,2))
                +" nonDark="+Arrays.toString(pixel(pixels,502,2))+" computeAverageOn="+afterExposure[0]+" computeAverageNegative="+baseline);
        System.clearProperty("skyboxblock.fixture.disableItrpDarkMeter");System.clearProperty("skyboxblock.fixture.control");

        for(var excluded:new SkyWindowRenderer.MeterView[]{view(sky,coverage,false,false,0,false,true,1,1,0,0),
                view(sky,coverage,true,true,0,false,true,1,1,0,0),view(sky,coverage,true,false,1,false,true,1,1,0,0),
                view(sky,coverage,true,false,0,true,true,1,1,0,0),view(sky,coverage,true,false,0,false,false,1,1,0,0)}) {
            bindCompute(compute,atlas,exposure,sampler,world);
            check(ItrpDarkMeterProxy.substitute(excluded,input)==null && binding(UNIT)==atlas,"non-root/portal/slot/shadow/non-dark contract does not bind proxy");
        }
        bindCompute(compute,atlas,exposure,sampler,world);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+UNIT);GL11.glBindTexture(GL11.GL_TEXTURE_2D,sky);GL13.glActiveTexture(GL13.GL_TEXTURE0+ACTIVE);
        check(ItrpDarkMeterProxy.substitute(view,input)==null && binding(UNIT)==sky,"stale/foreign atlas binding keeps generic texture rather than wrong substitution");
        bindCompute(compute,atlas,exposure,sampler,world);GL33.glBindSampler(UNIT,0);
        check(ItrpDarkMeterProxy.substitute(view,input)==null && binding(UNIT)==atlas,"sampler ownership change keeps generic atlas");
        // 18% floor and the finite 2048 cap from the real 1.21.1 meter definition.
        upload(sky,GL11.GL_RGBA,W,H,fill(W,H,new float[]{0,0,0,1}));
        resetExposure(exposure);bindCompute(compute,atlas,exposure,sampler,world);restore=ItrpDarkMeterProxy.substitute(view,input);
        try {close(pixel(read(binding(UNIT),GL11.GL_RGBA,W,H,4),493,2),new float[]{.36F,.36F,.36F,1},.0005F,"dark private physical sky uses neutral 18% meter-only floor");}
        finally {restore.run();}
        upload(exposure,GL30.GL_RG,512,513,fill(512,513,new float[]{12,0.0000001F}));
        bindCompute(compute,atlas,exposure,sampler,world);restore=ItrpDarkMeterProxy.substitute(view,input);
        try {close(pixel(read(binding(UNIT),GL11.GL_RGBA,W,H,4),493,2),new float[]{2048,2048,2048,1},1,"subnormal exposure stays finite at original 2048 cap");}
        finally {restore.run();}
        upload(exposure,GL30.GL_RG,512,513,fill(512,513,new float[]{12,0}));
        bindCompute(compute,atlas,exposure,sampler,world);restore=ItrpDarkMeterProxy.substitute(view,input);
        try {close(pixel(read(binding(UNIT),GL11.GL_RGBA,W,H,4),493,2),new float[]{.01F,.01F,.01F,1},.0001F,"invalid exposure uses original input rather than a zero/NaN patch");}
        finally {restore.run();}

        // Generated canvas is rasterized: scale/jitter mapping must match coverage and physical sky.
        float[] stripes=fill(W,H,new float[]{.2F,.2F,.2F,1});
        for(int y=0;y<H;y++)for(int x=0;x<W/2;x++)for(int i=0;i<3;i++)stripes[(y*W+x)*4+i]=.9F;
        upload(sky,GL11.GL_RGBA,W,H,stripes);upload(coverage,GL30.GL_RG,W,H,fill(W,H,new float[]{.625F,3}));
        upload(depth,GL11.GL_DEPTH_COMPONENT,W,H,fill(W,H,new float[]{.625F}));resetExposure(exposure);
        var raster=view(sky,coverage,true,false,0,false,true,.5F,.5F,.02F,.02F);
        bindCompute(compute,atlas,exposure,sampler,world);restore=ItrpDarkMeterProxy.substitute(raster,input);
        try {check(pixel(read(binding(UNIT),GL11.GL_RGBA,W,H,4),504,2)[0]>1.5F,"native q maps to generated raster canvas dq rather than wrong unscaled dark stripe");}
        finally {restore.run();}
        float[] copy=view.projection();copy[0]=99;
        check(view.projection()[0]==1,"meter snapshot matrix accessor is defensive, not mutable Frame state");
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"all production GL3.3 raster/barrier/sampler/compute operations have zero GL errors");
        ItrpDarkMeterProxy.release();check(!GL11.glIsTexture(proxy),"lifecycle releases the private proxy texture");
        GL20.glDeleteProgram(compute);GL33.glDeleteSamplers(sampler);
    }
    private static SkyWindowRenderer.MeterView view(int sky,int coverage,boolean root,boolean portal,int slot,boolean shadow,boolean dark,float sx,float sy,float jx,float jy) {
        float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        return new SkyWindowRenderer.MeterView(42,root,portal,slot,shadow,sky,coverage,identity,identity,sx,sy,jx,jy,W,H,new Object(),new Object(),new Object(),true,dark);
    }
    private static void bindCompute(int program,int atlas,int exposure,int sampler,int framebuffer) {
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+UNIT);GL11.glBindTexture(GL11.GL_TEXTURE_2D,atlas);GL33.glBindSampler(UNIT,sampler);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"colortex8"),UNIT);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+12);GL11.glBindTexture(GL11.GL_TEXTURE_2D,exposure);GL33.glBindSampler(12,sampler);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"pixelData2D"),12);
        GL20.glUniform2f(GL20.glGetUniformLocation(program,"pixelSize"),1F/W,1F/H);
        GL20.glUniform1i(GL20.glGetUniformLocation(program,"img_pixelData2D"),3);
        ARBShaderImageLoadStore.glBindImageTexture(3,exposure,0,false,0,GL15.GL_READ_WRITE,GL30.GL_RG16F);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer);GL11.glViewport(3,4,41,42);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+ACTIVE);
    }
    private static void dispatch() {ARBComputeShader.glDispatchCompute(1,1,1);ItrpDarkMeterProxy.barrier();}
    private static void restored(int atlas,int sampler,int compute,int world,String label) {
        check(binding(UNIT)==atlas && GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,UNIT)==sampler
                && GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE0+ACTIVE
                && GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==compute
                && GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)==world,label+" texture+sampler+activeUnit+program+FBO restored");
    }
    private static int binding(int unit) {int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);int result=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GL13.glActiveTexture(active);return result;}
    private static int compute() {
        String source="#version 330 core\n#extension GL_ARB_compute_shader : require\n#extension GL_ARB_shader_image_load_store : require\n"
                +"layout(local_size_x=32,local_size_y=16,local_size_z=1) in;\n"
                +"uniform sampler2D colortex8;uniform sampler2D pixelData2D;uniform vec2 pixelSize;layout(rg16f) uniform image2D img_pixelData2D;shared float meter[512];\n"
                +"void main(){uint i=gl_LocalInvocationIndex;vec2 q=(vec2(gl_GlobalInvocationID.xy)+.5)/vec2(32,16);vec2 uv=q/64.0+vec2(15.0/32.0+pixelSize.x*12.0,0);"
                +"meter[i]=dot(textureLod(colortex8,uv,0).rgb,vec3(.2126,.7152,.0722));barrier();if(i==0u){float avg=0;for(int j=0;j<512;j++)avg+=meter[j]/512.0;"
                +"avg+=texelFetch(pixelData2D,ivec2(0),0).g*.000001;imageStore(img_pixelData2D,ivec2(0),vec4(avg,1.0/max(avg,.00001),0,0));}}";
        int shader=GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(shader));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,shader);GL20.glLinkProgram(p);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(shader);return p;
    }
    private static void resetExposure(int id){upload(id,GL30.GL_RG,512,513,fill(512,513,new float[]{12,.5F}));}
    private static int image(int internal,int format,int w,int h,float[] data) {
        int id=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,internal,w,h,0,format,GL11.GL_FLOAT,buffer(data));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);return id;
    }
    private static void upload(int id,int format,int w,int h,float[] data) {int old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,format,GL11.GL_FLOAT,buffer(data));GL11.glBindTexture(GL11.GL_TEXTURE_2D,old);}
    private static float[] read(int id,int format,int w,int h,int channels) {
        int old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GL11.glBindTexture(GL11.GL_TEXTURE_2D,id);FloatBuffer data=org.lwjgl.BufferUtils.createFloatBuffer(w*h*channels);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,format,GL11.GL_FLOAT,data);float[] result=new float[w*h*channels];data.get(result);GL11.glBindTexture(GL11.GL_TEXTURE_2D,old);return result;
    }
    private static int fbo(int texture){int fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);return fbo;}
    private static float[] pixel(float[] values,int x,int y){return Arrays.copyOfRange(values,(y*W+x)*4,(y*W+x)*4+4);}
    private static float[] fill(int w,int h,float[] pixel){float[] result=new float[w*h*pixel.length];for(int i=0;i<w*h;i++)System.arraycopy(pixel,0,result,i*pixel.length,pixel.length);return result;}
    private static FloatBuffer buffer(float[] values){return org.lwjgl.BufferUtils.createFloatBuffer(values.length).put(values).flip();}
    private static void close(float[] actual,float[] expected,float tolerance,String label){check(actual.length==expected.length,label+" shape");for(int i=0;i<actual.length;i++)if(Math.abs(actual[i]-expected[i])>tolerance)throw new AssertionError(label+" actual="+Arrays.toString(actual)+" expected="+Arrays.toString(expected));}
    private static void check(boolean okay,String label){checks++;gpuChecks++;if(!okay)throw new AssertionError(label);}
    private static void structure(boolean okay,String label){checks++;structuralChecks++;if(!okay)throw new AssertionError(label);}
    private static void structural() throws Exception {
        String mixin=Files.readString(ROOT.resolve("java/dev/yg/skyboxblock/mixin/SkyIrisExposureComputeMixin.java"));
        String bridge=Files.readString(ROOT.resolve("java/dev/yg/skyboxblock/client/ItrpSceneBridge.java"));
        String proxy=Files.readString(ROOT.resolve("java/dev/yg/skyboxblock/client/ItrpDarkMeterProxy.java"));
        String renderer=Files.readString(ROOT.resolve("java/dev/yg/skyboxblock/client/SkyWindowRenderer.java"));
        String json=Files.readString(ROOT.resolve("resources/skyboxblock.mixins.json"));
        structure(mixin.contains("dispatch(FF)V") && mixin.contains("beforeCompute(this)") && mixin.contains("finally {if(restore!=null)restore.run();}"),"exact dispatch hook wraps original with finally");
        structure(json.contains("SkyIrisExposureComputeMixin"),"compute mixin registered");
        structure(bridge.contains("1.11.4+mc26.2") && bridge.contains(".equals(\"26.2\")") && bridge.contains("334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8"),"strict installed pack/Iris/Minecraft lock present");
        for(String anchor:new String[]{"meter-view-owner-mismatch","meter-compute-owner-missing-or-ambiguous","composite74","meter-local-size-mismatch","meter-exposure-image-owner-mismatch","meter-exposure-sampler-owner-mismatch","requireTarget(targets,8,atlas)","meter-input-alias"})structure(bridge.contains(anchor),"ownership guard source: "+anchor);
        structure(!proxy.contains("glBindImageTexture(") && !proxy.contains("glDispatchCompute(") && !proxy.contains("glCopyTexSubImage") && !proxy.contains("glBlitFramebuffer"),"proxy owns no exposure image writes, compute dispatch or world-color copying");
        structure(renderer.contains("ItrpDarkMeterProxy.release()") && renderer.contains("record MeterView("),"immutable accessor and lifecycle release wired");
    }
    private static final class ExpectedDispatchFailure extends RuntimeException { }
}
