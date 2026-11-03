package dev.yg.skyboxblock.client;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Offline real-GPU test: production MRT/HDR GLSL and the audited scene-input sampling contract.
 * Does not launch Minecraft, load a mod, send a fixture request or replace live A/B acceptance. */
public final class SkyItrpSceneInputRegression {
    private static final int W=64,H=64;
    private static int checks,vao;
    private static final Path SHADERS=Path.of("src/main/resources/assets/skyboxblock/shaders/isolated");
    public static void main(String[] args) throws Exception {
        if(!GLFW.glfwInit())throw new AssertionError("glfw init");
        GLFW.glfwDefaultWindowHints();GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED,GLFW.GLFW_FALSE);GLFW.glfwWindowHint(GLFW.GLFW_FOCUS_ON_SHOW,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT,GLFW.GLFW_TRUE);
        long context=GLFW.glfwCreateWindow(W,H,"offline ITRP scene input regression",0,0);
        if(context==0)throw new AssertionError("GL3.3 context");
        GLFW.glfwMakeContextCurrent(context);GL.createCapabilities();
        try {
            System.out.println("GL: "+GL11.glGetString(GL11.GL_VERSION)+" / "+GL11.glGetString(GL11.GL_RENDERER));
            run();System.out.println("SkyItrpSceneInputRegression: "+checks+" checks passed (production MRT/HDR GLSL, refraction input, foreground/material ownership, GL3.3)");
        } finally {GLFW.glfwDestroyWindow(context);GLFW.glfwTerminate();}
    }
    private static void run() throws Exception {
        int window=program(Files.readString(SHADERS.resolve("window.vsh")),Files.readString(SHADERS.resolve("window.fsh")));
        int hdr=program(Files.readString(SHADERS.resolve("itrp_background.vsh")),Files.readString(SHADERS.resolve("itrp_background.fsh")));
        check(GL30.glGetFragDataLocation(window,"SkySolidData")==1 && GL30.glGetFragDataLocation(window,"SkyNormalData")==2,"production auxiliary locations explicit");
        mesh();
        int nearest=GL33.glGenSamplers();GL33.glSamplerParameteri(nearest,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL33.glSamplerParameteri(nearest,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL33.glSamplerParameteri(nearest,GL14.GL_TEXTURE_COMPARE_MODE,GL11.GL_NONE);
        int color=image(GL30.GL_RGBA16F,GL11.GL_RGBA,4,W,H,new float[]{.1F,.2F,.3F,1});
        int solid=image(GL11.GL_RGBA16,GL11.GL_RGBA,4,W,H,new float[]{.4F,.4F,.4F,.4F});
        int normal=image(GL11.GL_RGBA16,GL11.GL_RGBA,4,W,H,new float[]{.4F,.4F,.4F,.4F});
        int depth=image(GL30.GL_DEPTH_COMPONENT32F,GL11.GL_DEPTH_COMPONENT,1,W,H,new float[]{0});
        int coverage=image(GL30.GL_RG32F,GL30.GL_RG,2,W,H,new float[]{0,0});
        int sky=image(GL30.GL_RGBA16F,GL11.GL_RGBA,4,W,H,new float[]{.25F,.5F,.75F,1});
        int exposure=image(GL30.GL_RG16F,GL30.GL_RG,2,512,513,new float[]{12,.5F});
        int scene=image(GL30.GL_RGBA16F,GL11.GL_RGBA,4,W,H,new float[]{.05F,.1F,.3F,0});
        int glass=image(GL30.GL_RGBA16F,GL11.GL_RGBA,4,W,H,new float[]{0,0,0,0});
        int worldFbo=fbo(color,depth);attach(GL30.GL_COLOR_ATTACHMENT1,solid);attach(GL30.GL_COLOR_ATTACHMENT2,normal);
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0,GL30.GL_COLOR_ATTACHMENT1,GL30.GL_COLOR_ATTACHMENT2});complete();
        GL11.glViewport(0,0,W,H);SkyGlScope.clean();GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_GEQUAL);GL11.glDepthMask(true);
        uniforms(window);integer(window,"Style",1);integer(window,"Material",0);integer(window,"Pass",0);integer(window,"ItrpMaterial",1);
        draw();
        close(pixel(worldFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{1,1,1,1},.001F,"opaque generated white RGB");
        close(pixel(worldFbo,GL30.GL_COLOR_ATTACHMENT1,8,8),new float[]{0,0,65281F/65535F,255F/65535F},2F/65535F,"audited legal neutral material replaces stale world data");
        close(pixel(worldFbo,GL30.GL_COLOR_ATTACHMENT2,8,8),new float[]{.5F,.5F,.5F,.5F},2F/65535F,"octahedral surface normals initialized");
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,worldFbo);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);integer(window,"ItrpMaterial",0);integer(window,"Style",2);draw();
        close(pixel(worldFbo,GL30.GL_COLOR_ATTACHMENT1,8,8),new float[]{0,0,65281F/65535F,255F/65535F},2F/65535F,"generic draw routing never touches auxiliary data");
        int coverageFbo=fbo(coverage,depth);GL11.glDepthFunc(GL11.GL_EQUAL);GL11.glDepthMask(false);integer(window,"Pass",1);draw();
        close(pixel(coverageFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{.625F,1,0,1},.000001F,"hardware equality records native reversed surface ownership");
        // Opaque pre-translucent copy: a normal foreground rectangle must remain untouched by HDR injection.
        float[] opaque=new float[W*H];Arrays.fill(opaque,.625F);
        for(int y=20;y<44;y++)for(int x=24;x<40;x++)opaque[y*W+x]=.9F;
        int opaqueDepth=image(GL30.GL_DEPTH_COMPONENT32F,GL11.GL_DEPTH_COMPONENT,1,W,H,new float[]{.625F});
        upload(opaqueDepth,GL11.GL_DEPTH_COMPONENT,W,H,opaque);
        int sceneFbo=fbo(scene,0);
        GL20.glUseProgram(hdr);uniforms(hdr);integer(hdr,"Style",1);integer(hdr,"Material",0);
        bind(hdr,"Image",0,sky,nearest);bind(hdr,"Coverage",1,coverage,nearest);bind(hdr,"WorldOpaqueDepth",2,opaqueDepth,nearest);bind(hdr,"ExposureData",4,exposure,nearest);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,sceneFbo);SkyGlScope.clean();GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);draw();
        close(pixel(sceneFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{2,2,2,0},.002F,"white is injected into actual HDR scene input before refraction");
        close(pixel(sceneFbo,GL30.GL_COLOR_ATTACHMENT0,32,32),new float[]{.05F,.1F,.3F,0},.0003F,"normal foreground opaque depth protects scene RGB");
        integer(hdr,"Style",2);integer(hdr,"Material",7);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,sceneFbo);draw();
        close(pixel(sceneFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{2,2,2,0},.002F,"wrong sky material cannot overwrite nearest owner");
        integer(hdr,"Material",0);draw();
        close(pixel(sceneFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{0,0,0,0},.00001F,"black background reaches HDR scene input");
        integer(hdr,"Style",0);draw();
        close(pixel(sceneFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{(float)Math.pow(.25,2.2)*2,(float)Math.pow(.5,2.2)*2,(float)Math.pow(.75,2.2)*2,0},.002F,"native generated sky decodes to HDR with actual exposure multiplier");
        integer(hdr,"Style",1);draw();
        // Minimal audited GlassRefraction sampling contract, not a final-screen glass repaint.
        // ITRP Translucent_FS lines 159/163 read this scene input with a displaced coordinate.
        int glassProgram=program("#version 330 core\nlayout(location=0) in vec3 Position;void main(){gl_Position=vec4(Position.xy,0,1);}",
                "#version 330 core\nuniform sampler2D colortex6;out vec4 fragColor;void main(){vec2 uv=gl_FragCoord.xy/64.0+vec2(0.03125,0);fragColor=vec4(textureLod(colortex6,uv,0).rgb*vec3(.8,.2,.2)*.5,1);}");
        int glassFbo=fbo(glass,0);GL20.glUseProgram(glassProgram);bind(glassProgram,"colortex6",0,scene,nearest);draw();
        close(pixel(glassFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{.8F,.2F,.2F,1},.002F,"refracted red glass samples generated white rather than blue/green world");
        // Negative control: absent injection must really reproduce stale-blue background through glass.
        upload(scene,GL11.GL_RGBA,W,H,fill(W,H,new float[]{.05F,.1F,.3F,0}));GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,glassFbo);draw();
        close(pixel(glassFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{.02F,.01F,.03F,1},.001F,"negative no-scene-input control detects stale world background");
        // Keep the original final transparent guard: composed glass is left verbatim, never painted white.
        int before=image(GL30.GL_RGBA16F,GL11.GL_RGBA,4,W,H,new float[]{0,0,0,0});
        int after=image(GL30.GL_RGBA16F,GL11.GL_RGBA,4,W,H,new float[]{.4F,.1F,.1F,1});
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,glassFbo);uniforms(window);integer(window,"Pass",2);integer(window,"Style",1);integer(window,"Material",0);integer(window,"TransparentGuard",1);
        bind(window,"Image",0,sky,nearest);bind(window,"Coverage",1,coverage,nearest);bind(window,"WorldDepth",2,opaqueDepth,nearest);bind(window,"TransBefore",4,before,nearest);bind(window,"TransAfter",5,after,nearest);draw();
        close(pixel(glassFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{.02F,.01F,.03F,1},.001F,"final transparent guard preserved; no final-screen glass overpainting");
        // Cover the DH adapter's unit reservation: exposure is unit FOUR, DH remains unit THREE.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,sceneFbo);uniforms(hdr);integer(hdr,"Style",1);integer(hdr,"Material",0);
        bind(hdr,"Image",0,sky,nearest);bind(hdr,"Coverage",1,coverage,nearest);bind(hdr,"WorldOpaqueDepth",2,opaqueDepth,nearest);bind(hdr,"ExposureData",4,exposure,nearest);
        int dh=image(GL30.GL_R32F,GL11.GL_RED,1,W,H,new float[]{0});bind(hdr,"DistantDepth",3,dh,nearest);draw();
        close(pixel(sceneFbo,GL30.GL_COLOR_ATTACHMENT0,8,8),new float[]{2,2,2,0},.002F,"DH texture unit three does not replace exposure unit four");
        check(GL11.glGetError()==GL11.GL_NO_ERROR,"all GL3.3 entry points and framebuffer operations error-free");
        GL20.glDeleteProgram(window);GL20.glDeleteProgram(hdr);GL20.glDeleteProgram(glassProgram);
    }
    private static void uniforms(int program) {
        GL20.glUseProgram(program);float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        for(String name:new String[]{"Projection","View","RayInverse"})GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program,name),false,identity);
        GL20.glUniform2f(GL20.glGetUniformLocation(program,"RayDepth"),1,0);
        GL20.glUniform4f(GL20.glGetUniformLocation(program,"Viewport"),0,0,W,H);
        GL20.glUniform2f(GL20.glGetUniformLocation(program,"DepthScale"),1,1);
        GL20.glUniform2f(GL20.glGetUniformLocation(program,"DepthOffset"),0,0);
        integer(program,"Clipping",0);integer(program,"DistantEnabled",0);integer(program,"ItrpMaterial",0);
    }
    private static void integer(int p,String name,int value) {GL20.glUniform1i(GL20.glGetUniformLocation(p,name),value);}
    private static void bind(int p,String name,int unit,int texture,int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(unit,sampler);integer(p,name,unit);
    }
    private static int image(int internal,int format,int components,int w,int h,float[] pixel) {
        int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int result=GL11.glGenTextures();GL11.glBindTexture(GL11.GL_TEXTURE_2D,result);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,internal,w,h,0,format,GL11.GL_FLOAT,buffer(fill(w,h,pixel)));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL12.GL_CLAMP_TO_EDGE);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);GL11.glBindTexture(GL11.GL_TEXTURE_2D,previous);return result;
    }
    private static float[] fill(int w,int h,float[] pixel) {float[] result=new float[w*h*pixel.length];for(int i=0;i<w*h;i++)System.arraycopy(pixel,0,result,i*pixel.length,pixel.length);return result;}
    private static FloatBuffer buffer(float[] data) {return org.lwjgl.BufferUtils.createFloatBuffer(data.length).put(data).flip();}
    private static void upload(int texture,int format,int w,int h,float[] values) {GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,w,h,format,GL11.GL_FLOAT,buffer(values));}
    private static int fbo(int color,int depth) {
        int fbo=GL30.glGenFramebuffers();GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);attach(GL30.GL_COLOR_ATTACHMENT0,color);if(depth!=0)attach(GL30.GL_DEPTH_ATTACHMENT,depth);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);complete();return fbo;
    }
    private static void attach(int attachment,int texture) {GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,attachment,GL11.GL_TEXTURE_2D,texture,0);}
    private static void complete() {if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("incomplete FBO");}
    private static float[] pixel(int fbo,int attachment,int x,int y) {
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,fbo);GL11.glReadBuffer(attachment);FloatBuffer out=org.lwjgl.BufferUtils.createFloatBuffer(4);GL11.glReadPixels(x,y,1,1,GL11.GL_RGBA,GL11.GL_FLOAT,out);float[] result=new float[4];out.get(result);return result;
    }
    private static void mesh() {
        vao=GL30.glGenVertexArrays();GL30.glBindVertexArray(vao);int vbo=GL15.glGenBuffers();GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER,new float[]{-1,-1,.25F,1,-1,.25F,1,1,.25F,-1,-1,.25F,1,1,.25F,-1,1,.25F},GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,12,0);GL20.glEnableVertexAttribArray(0);
    }
    private static void draw() {GL30.glBindVertexArray(vao);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);}
    private static int program(String vertex,String fragment) {
        int vs=shader(GL20.GL_VERTEX_SHADER,vertex),fs=shader(GL20.GL_FRAGMENT_SHADER,fragment),p=GL20.glCreateProgram();GL20.glAttachShader(p,vs);GL20.glAttachShader(p,fs);GL20.glLinkProgram(p);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new AssertionError(GL20.glGetProgramInfoLog(p));GL20.glDeleteShader(vs);GL20.glDeleteShader(fs);return p;
    }
    private static int shader(int type,String source) {int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(shader));return shader;}
    private static void close(float[] actual,float[] expected,float tolerance,String label) {checks++;for(int i=0;i<4;i++)if(Math.abs(actual[i]-expected[i])>tolerance)throw new AssertionError(label+" actual="+Arrays.toString(actual)+" expected="+Arrays.toString(expected));}
    private static void check(boolean value,String label) {checks++;if(!value)throw new AssertionError(label);}
}
