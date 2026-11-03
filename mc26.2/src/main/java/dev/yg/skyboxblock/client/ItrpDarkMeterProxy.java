package dev.yg.skyboxblock.client;

import dev.yg.skyboxblock.SkyboxBlockMod;
import org.lwjgl.opengl.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** ITRP's original exposure compute remains the sole exposure-image writer. This owns only a
 * tile-sized raster operation in a private atlas, briefly borrowed by its colortex8 sampler. */
public final class ItrpDarkMeterProxy {
    private static int framebuffer,texture,width,height,program,vao,linear,nearest;
    private static int activations,restorations;
    private static boolean warned,reportedCaps,negativeReported;
    private ItrpDarkMeterProxy() { }

    /** Exact Iris 1.11.4 dispatch(FF)V entry: use() has finished all sampler/image updates. */
    public static Runnable beforeCompute(Object compute) {
        var view=SkyWindowRenderer.activeRootMeterView();
        if(!eligible(view) || !ModCompat.irisActive())return null;
        if(negativeControl()) {
            if(!negativeReported){negativeReported=true;SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP dark meter: fixture negative control; original exposure compute retained");}
            return null;
        }
        if(!capable())return null;
        int current=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if(current==0 || GL20.glGetUniformLocation(current,"img_pixelData2D")<0
                || GL20.glGetUniformLocation(current,"pixelData2D")<0 || GL20.glGetUniformLocation(current,"colortex8")<0)return null;
        int[] size=new int[3];GL20.glGetProgramiv(current,GL43.GL_COMPUTE_WORK_GROUP_SIZE,size);
        if(size[0]!=32 || size[1]!=16 || size[2]!=1)return null;
        Runnable restore=null;
        try {
            ItrpSceneBridge.MeterInputs inputs;
            try(SkyGlScope ignored=new SkyGlScope()) {
                inputs=ItrpSceneBridge.meterInputs(view,compute,current);
            }
            if(inputs==null)return null;
            restore=substitute(view,inputs);
            if(restore!=null && activations++<3) {
                var c=GL.getCapabilities();
                SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP dark meter: composite74 sampler proxy active view={} atlas={} proxy={} exposure={} unit={} local=32x16x1 core42={} core43={} ARB_compute={} ARB_image={} dispatchPtr={} barrierPtr={}",
                        view.token(),inputs.atlas(),texture,inputs.exposure(),inputs.unit(),c.OpenGL42,c.OpenGL43,
                        c.GL_ARB_compute_shader,c.GL_ARB_shader_image_load_store,c.glDispatchCompute!=0,c.glMemoryBarrier!=0);
            }
            return restore;
        } catch(RuntimeException error) {
            if(restore!=null)restore.run();
            if(!warned){warned=true;SkyboxBlockMod.LOGGER.warn("[skyboxblock] ITRP dark meter unavailable; generic exposure input retained",error);}
            return null;
        }
    }
    static boolean eligible(SkyWindowRenderer.MeterView view) {
        return view!=null && view.root() && !view.portal() && view.slot()==0 && !view.shadow()
                && view.darkSky() && view.sceneInjected() && view.generatedSky()>0 && view.coverage()>0
                && view.width()>0 && view.height()>0 && Float.isFinite(view.scaleX()) && Float.isFinite(view.scaleY())
                && view.scaleX()>0 && view.scaleY()>0 && Float.isFinite(view.jitterX()) && Float.isFinite(view.jitterY());
    }
    static boolean negativeControl() {
        return !System.getProperty("skyboxblock.fixture.control", "").isBlank()
                && Boolean.getBoolean("skyboxblock.fixture.disableItrpDarkMeter");
    }
    /** Same-name ARB commands use the actual loaded pointers, not the context's maximum version. */
    static boolean capable() {
        var c=GL.getCapabilities();
        boolean okay=(c.OpenGL43 || c.GL_ARB_compute_shader) && (c.OpenGL42 || c.GL_ARB_shader_image_load_store)
                && c.glDispatchCompute!=0 && c.glMemoryBarrier!=0 && c.glBindImageTexture!=0 && c.glGetProgramiv!=0;
        if(!okay && !reportedCaps) {
            reportedCaps=true;
            SkyboxBlockMod.LOGGER.debug("[skyboxblock] ITRP dark meter: compute/image-load-store entry points missing; generic exposure retained");
        }
        return okay;
    }
    static void barrier() {
        var c=GL.getCapabilities();
        int bits=GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT|GL42.GL_TEXTURE_FETCH_BARRIER_BIT|GL42.GL_FRAMEBUFFER_BARRIER_BIT;
        if(c.OpenGL43 || c.GL_ARB_shader_storage_buffer_object)bits|=GL43.GL_SHADER_STORAGE_BARRIER_BIT;
        if(c.OpenGL42)GL42.glMemoryBarrier(bits);
        else ARBShaderImageLoadStore.glMemoryBarrier(bits);
    }

    /** Package-private seam exercises the actual production raster/substitution on a hidden GL3.3
     * context. Live callers must pass the owner-validated inputs above, never inferred texture IDs. */
    static Runnable substitute(SkyWindowRenderer.MeterView view,ItrpSceneBridge.MeterInputs input) {
        if(!eligible(view) || negativeControl() || !capable())return null;
        final int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0+input.unit());
        final int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        final int sampler=GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,input.unit());
        GL13.glActiveTexture(active);
        if(previous!=input.atlas() || sampler!=input.sampler())return null;
        // Iris's dispatch barrier has not executed yet: make the bloom atlas visible before sampling.
        barrier();
        try(SkyGlScope ignored=new SkyGlScope()) {renderProxy(view,input);}
        // Render scope restores the real compute, texture and sampler before our one-unit override.
        GL13.glActiveTexture(GL13.GL_TEXTURE0+input.unit());
        if(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)!=previous
                || GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,input.unit())!=sampler) {
            GL13.glActiveTexture(active);return null;
        }
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(input.unit(),linear);
        } catch(RuntimeException error) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);GL33.glBindSampler(input.unit(),sampler);throw error;
        } finally {GL13.glActiveTexture(active);}
        return new Runnable() {
            private boolean restored;
            @Override public void run() {
                if(restored)return;
                try {
                    GL13.glActiveTexture(GL13.GL_TEXTURE0+input.unit());
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);GL33.glBindSampler(input.unit(),sampler);
                    if(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)!=previous
                            || GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING,input.unit())!=sampler)
                        throw new IllegalStateException("ITRP dark meter sampler restore failed");
                    restored=true;
                } finally {GL13.glActiveTexture(active);}
                if(restorations++<3 && activations>0)SkyboxBlockMod.LOGGER.info("[skyboxblock] ITRP dark meter: original atlas texture+sampler+activeUnit restored after compute view={}",view.token());
            }
        };
    }
    private static void renderProxy(SkyWindowRenderer.MeterView view,ItrpSceneBridge.MeterInputs input) {
        programs();
        if(framebuffer==0)framebuffer=GL30.glGenFramebuffers();
        if(texture==0 || width!=input.width() || height!=input.height()) {
            if(texture!=0)GL11.glDeleteTextures(texture);
            texture=GL11.glGenTextures();width=input.width();height=input.height();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);
            int unpack=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL30.GL_RGBA16F,width,height,0,GL11.GL_RGBA,GL11.GL_FLOAT,(ByteBuffer)null);
            } finally {GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);}
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,texture,0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if(GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("ITRP dark meter framebuffer incomplete");
        GL11.glViewport(0,0,width,height);SkyGlScope.clean();GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);
        float offset=15F/32F+12F*input.pixelX();
        int left=Math.max(0,(int)Math.floor(offset*width)-2);
        int right=Math.min(width,(int)Math.ceil((offset+1F/64F)*width)+2);
        int top=Math.min(height,(int)Math.ceil(height/64F)+2);
        if(right<=left || top<=0)throw new IllegalStateException("ITRP dark meter tile outside atlas");
        GL11.glEnable(GL11.GL_SCISSOR_TEST);GL11.glScissor(left,0,right-left,top);
        GL20.glUseProgram(program);
        bind("MeterAtlas",0,input.atlas(),linear);bind("GeneratedSky",1,view.generatedSky(),linear);
        bind("SkyCoverage",2,view.coverage(),nearest);bind("WorldOpaqueDepth",3,input.opaqueDepth(),nearest);
        bind("ExposureData",4,input.exposure(),nearest);
        GL20.glUniform2f(location("TextureExtent"),width,height);GL20.glUniform2f(location("MeterOffset"),offset,0);
        GL20.glUniform4f(location("CoverageTransform"),view.scaleX(),view.scaleY(),view.jitterX()*.5F,view.jitterY()*.5F);
        GL30.glBindVertexArray(vao);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
    }
    private static int location(String name){return GL20.glGetUniformLocation(program,name);}
    private static void bind(String name,int unit,int nameId,int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,nameId);
        GL33.glBindSampler(unit,sampler);GL20.glUniform1i(location(name),unit);
    }
    private static void programs() {
        if(program==0) {
            int vertex=compile(GL20.GL_VERTEX_SHADER,"itrp_dark_meter.vsh"),fragment=0,candidate=0;
            try {
                fragment=compile(GL20.GL_FRAGMENT_SHADER,"itrp_dark_meter.fsh");candidate=GL20.glCreateProgram();
                GL20.glAttachShader(candidate,vertex);GL20.glAttachShader(candidate,fragment);GL20.glLinkProgram(candidate);
                if(GL20.glGetProgrami(candidate,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(candidate));
                program=candidate;vao=GL30.glGenVertexArrays();
            } catch(RuntimeException error){if(candidate!=0)GL20.glDeleteProgram(candidate);throw error;}
            finally {GL20.glDeleteShader(vertex);if(fragment!=0)GL20.glDeleteShader(fragment);}
        }
        if(linear==0)linear=sampler(GL11.GL_LINEAR);
        if(nearest==0)nearest=sampler(GL11.GL_NEAREST);
    }
    private static int sampler(int filter) {
        int id=GL33.glGenSamplers();GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_MIN_FILTER,filter);
        GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_MAG_FILTER,filter);
        GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(id,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(id,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);return id;
    }
    private static int compile(int type,String name) {
        int id=GL20.glCreateShader(type);
        try(var in=ItrpDarkMeterProxy.class.getResourceAsStream("/assets/skyboxblock/shaders/isolated/"+name)) {
            if(in==null)throw new IOException("missing isolated meter shader "+name);
            GL20.glShaderSource(id,new String(in.readAllBytes(),StandardCharsets.UTF_8));GL20.glCompileShader(id);
            if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(name+": "+GL20.glGetShaderInfoLog(id));
            return id;
        } catch(IOException|RuntimeException error){GL20.glDeleteShader(id);throw new IllegalStateException(name,error);}
    }
    public static void release() {
        if(texture!=0)GL11.glDeleteTextures(texture);if(framebuffer!=0)GL30.glDeleteFramebuffers(framebuffer);
        if(program!=0)GL20.glDeleteProgram(program);if(vao!=0)GL30.glDeleteVertexArrays(vao);
        if(linear!=0)GL33.glDeleteSamplers(linear);if(nearest!=0)GL33.glDeleteSamplers(nearest);
        framebuffer=texture=width=height=program=vao=linear=nearest=activations=restorations=0;
        warned=reportedCaps=negativeReported=false;
    }
}
