package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.*;
import java.nio.file.*;

/** Optional hidden-window shader/GL transaction test; independent of Minecraft, Iris and Gradle. */
public final class SkyIsolatedGlRegression {
    private static int checks;
    public static void main(String[] args)throws Exception {
        if(!GLFW.glfwInit())throw new AssertionError("GLFW init");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,4);GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long handle=GLFW.glfwCreateWindow(64,64,"isolated sky shader regression",0,0);
        if(handle==0)throw new AssertionError("GL4.5 hidden context");GLFW.glfwMakeContextCurrent(handle);GL.createCapabilities();
        try {
            System.out.println("GL: "+GL11.glGetString(GL11.GL_VERSION)+" / "+GL11.glGetString(GL11.GL_RENDERER));
            Path root=Path.of("src/main/resources/assets/skyboxblock/shaders/isolated");
            int scene=program(root,"scene"),window=program(root,"window");
            check(scene>0&&window>0,"both actual production shader pairs compile and link");
            GL45.glClipControl(GL20.GL_LOWER_LEFT,GL45.GL_ZERO_TO_ONE);
            GL11.glViewport(0,0,64,64);GL11.glDisable(GL11.GL_DITHER);GL11.glDisable(GL11.GL_BLEND);
            int vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers();GL30.glBindVertexArray(vao);GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);
            float[] quad={-1,-1,.4F, 1,-1,.4F, 1,1,.4F, -1,-1,.4F, 1,1,.4F, -1,1,.4F};
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,quad,GL15.GL_STATIC_DRAW);GL20.glEnableVertexAttribArray(0);GL20.glVertexAttribPointer(0,3,GL11.GL_FLOAT,false,12,0L);
            GL20.glUseProgram(window);matrix(window,"Projection",new Matrix4f());matrix(window,"View",new Matrix4f());matrix(window,"RayInverse",new Matrix4f());
            GL20.glUniform4f(loc(window,"Viewport"),0,0,64,64);GL20.glUniform2f(loc(window,"RayDepth"),1,.5F);
            GL20.glUniform2f(loc(window,"DepthScale"),1,1);GL20.glUniform2f(loc(window,"DepthOffset"),0,0);
            GL20.glUniform1i(loc(window,"Clipping"),0);GL20.glUniform1i(loc(window,"DistantEnabled"),0);
            int owner=texture(1,GL30.GL_RG32F,GL30.GL_RG,new float[]{.4F,1}),depth=texture(2,GL30.GL_R32F,GL11.GL_RED,new float[]{.4F});
            int before=texture(4,GL30.GL_RGBA16F,GL11.GL_RGBA,new float[]{0,0,0,1}),after=texture(5,GL30.GL_RGBA16F,GL11.GL_RGBA,new float[]{0,0,0,1});
            uniform(window,"Coverage",1);uniform(window,"WorldDepth",2);uniform(window,"TransBefore",4);uniform(window,"TransAfter",5);
            uniform(window,"Pass",0);uniform(window,"Style",1);uniform(window,"Material",0);
            GL11.glEnable(GL11.GL_DEPTH_TEST);GL11.glDepthFunc(GL11.GL_GEQUAL);GL11.glDepthMask(true);GL11.glClearDepth(0);clear();
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(255,255,255,"unlit white opaque");
            uniform(window,"Style",2);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(0,0,0,"unlit black opaque");
            // Near surface .7 blocks .4 in native reversed depth.
            GL11.glClearDepth(.7);clear();uniform(window,"Style",1);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(51,77,102,"foreground reverse-Z depth wins");
            GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDepthMask(false);uniform(window,"Pass",2);uniform(window,"TransparentGuard",0);clear();
            GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(255,255,255,"final exact material+depth owner");
            set(owner,1,GL30.GL_RG,new float[]{.4F,2});clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(51,77,102,"other material not overwritten");
            set(owner,1,GL30.GL_RG,new float[]{.2F,1});set(depth,2,GL11.GL_RED,new float[]{.2F});clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(255,255,255,"same material restores across a different-depth face at temporal edges");
            set(owner,1,GL30.GL_RG,new float[]{.4F,1});set(depth,2,GL11.GL_RED,new float[]{.7F});clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(51,77,102,"final foreground not overwritten");
            set(depth,2,GL11.GL_RED,new float[]{.4F});uniform(window,"TransparentGuard",1);set(after,5,GL11.GL_RGBA,new float[]{.2F,.3F,.4F,1});clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(51,77,102,"no-depth-write transparency preserved");
            set(after,5,GL11.GL_RGBA,new float[]{0,0,0,1});clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);pixel(255,255,255,"unchanged transparent pixel restores sky");
            // The actual fragment program must ignore face depth even when bob lives in projection.
            uniform(window,"Pass",0);uniform(window,"Style",3);uniform(window,"TransparentGuard",0);
            int panorama=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0);GL11.glBindTexture(GL11.GL_TEXTURE_2D,panorama);
            float[] texels=new float[128*64*4];
            for(int y=0;y<64;y++)for(int x=0;x<128;x++) {
                int at=(x+y*128)*4;double angle=(x+.5)*Math.PI*2/128;
                texels[at]=(float)(.5+.4*Math.sin(angle));texels[at+1]=(float)(.5+.4*Math.cos(angle));texels[at+2]=(y+.5F)/64;texels[at+3]=1;
            }
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,128,64,0,GL11.GL_RGBA,GL11.GL_FLOAT,texels);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_LINEAR);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,GL11.GL_REPEAT);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,GL12.GL_CLAMP_TO_EDGE);uniform(window,"Image",0);
            Matrix4f p=new Matrix4f().setPerspective((float)Math.toRadians(73),1,100,.05F,true).translate(.16F,-.05F,.02F).rotateZ(.035F);
            Matrix4f view=new Matrix4f().rotateX(.15F).rotateY(-(float)Math.PI/2);
            Matrix4f inv=SkyWindowMath.rayInverse(p,view,new Matrix4f());matrix(window,"Projection",p);matrix(window,"View",view);matrix(window,"RayInverse",inv);
            float[] full=unproject(inv,-1,1,.2F);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,full,GL15.GL_STATIC_DRAW);clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,6);byte[] reference=pixels();
            float[] split=new float[36];System.arraycopy(unproject(inv,-1,0,.2F),0,split,0,18);System.arraycopy(unproject(inv,0,1,.7F),0,split,18,18);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER,split,GL15.GL_STATIC_DRAW);clear();GL11.glDrawArrays(GL11.GL_TRIANGLES,0,12);byte[] actual=pixels();
            int maxDifference=0;for(int i=0;i<actual.length;i++)maxDifference=Math.max(maxDifference,Math.abs((actual[i]&255)-(reference[i]&255)));
            check(maxDifference<=1,"actual GLSL panorama has no face-depth segmentation (max pixel delta="+maxDifference+")");
            org.joml.Vector3f ray=SkyWindowMath.ray(inv,32.5F/64,32.5F/64,true,new org.joml.Vector3f());float[] uv=SkyWindowMath.panorama(ray);
            int expectedR=Math.round((float)(.5+.4*Math.sin(uv[0]*Math.PI*2))*255),expectedG=Math.round((float)(.5+.4*Math.cos(uv[0]*Math.PI*2))*255),expectedB=Math.round(uv[1]*255);
            pixel(expectedR,expectedG,expectedB,"actual GLSL panorama agrees with independent CPU inverse-VP oracle");
            // State restoration is the key ITRP regression, not merely glGetError()==0.
            GL11.glViewport(3,4,41,42);GL11.glEnable(GL11.GL_BLEND);GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glDepthFunc(GL11.GL_GREATER);GL11.glDepthMask(false);GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            GL40.glBlendFuncSeparatei(0,GL11.GL_ONE,GL11.GL_ZERO,GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL30.glColorMaski(0,true,false,true,false);GL13.glActiveTexture(GL13.GL_TEXTURE4);
            int entryVao=GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),entryProgram=GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            try(SkyGlScope scope=new SkyGlScope()) {
                SkyGlScope.clean();GL20.glUseProgram(scene);GL30.glBindVertexArray(0);GL11.glViewport(0,0,9,10);
                GL11.glDepthFunc(GL11.GL_ALWAYS);GL11.glDepthMask(true);GL13.glActiveTexture(GL13.GL_TEXTURE1);
            }
            int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);
            check(java.util.Arrays.equals(viewport,new int[]{3,4,41,42}),"viewport restored");
            check(GL11.glIsEnabled(GL11.GL_BLEND)&&GL11.glIsEnabled(GL11.GL_CULL_FACE)&&GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0),"blend/cull/clip restored");
            check(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)==GL11.GL_GREATER&&!GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),"depth restored");
            check(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)==GL13.GL_TEXTURE4,"active texture restored");
            check(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)==entryVao&&GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)==entryProgram,"VAO/program restored");
            check(GL30.glGetIntegeri(GL14.GL_BLEND_SRC_RGB,0)==GL11.GL_ONE&&GL30.glGetIntegeri(GL14.GL_BLEND_DST_RGB,0)==GL11.GL_ZERO,"indexed MRT blend restored");
            check(GL11.glGetError()==GL11.GL_NO_ERROR,"zero GL errors");
            System.out.println("SkyIsolatedGlRegression: "+checks+" checks passed (actual GLSL, opaque depth, final material ownership, transparency, exact GL state)");
        } finally {GLFW.glfwDestroyWindow(handle);GLFW.glfwTerminate();}
    }
    private static int program(Path root,String name)throws Exception {
        int a=shader(GL20.GL_VERTEX_SHADER,Files.readString(root.resolve(name+".vsh"))),b=shader(GL20.GL_FRAGMENT_SHADER,Files.readString(root.resolve(name+".fsh")));
        int p=GL20.glCreateProgram();GL20.glAttachShader(p,a);GL20.glAttachShader(p,b);GL20.glLinkProgram(p);
        if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new AssertionError(name+": "+GL20.glGetProgramInfoLog(p));
        GL20.glDeleteShader(a);GL20.glDeleteShader(b);return p;
    }
    private static int shader(int kind,String source){int s=GL20.glCreateShader(kind);GL20.glShaderSource(s,source);GL20.glCompileShader(s);if(GL20.glGetShaderi(s,GL20.GL_COMPILE_STATUS)==0)throw new AssertionError(GL20.glGetShaderInfoLog(s));return s;}
    private static int loc(int p,String s){return GL20.glGetUniformLocation(p,s);}
    private static void uniform(int p,String s,int v){GL20.glUniform1i(loc(p,s),v);}
    private static void matrix(int p,String s,Matrix4f m){GL20.glUniformMatrix4fv(loc(p,s),false,m.get(new float[16]));}
    private static int texture(int unit,int format,int pixel,float[] values){int t=GL11.glGenTextures();GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,t);GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,format,1,1,0,pixel,GL11.GL_FLOAT,values);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);return t;}
    private static void set(int texture,int unit,int pixel,float[] values){GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,0,0,1,1,pixel,GL11.GL_FLOAT,values);}
    private static float[] unproject(Matrix4f inverse,float min,float max,float z) {
        float[] out=new float[18];float[][] xy={{min,-1},{max,-1},{max,1},{min,1}};int at=0;
        for(int i:new int[]{0,1,2,0,2,3}) {
            org.joml.Vector4f v=inverse.transform(new org.joml.Vector4f(xy[i][0],xy[i][1],z,1));
            out[at++]=v.x/v.w;out[at++]=v.y/v.w;out[at++]=v.z/v.w;
        }return out;
    }
    private static byte[] pixels(){java.nio.ByteBuffer data=org.lwjgl.BufferUtils.createByteBuffer(64*64*4);GL11.glReadPixels(0,0,64,64,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);byte[] out=new byte[data.remaining()];data.get(out);return out;}
    private static void clear(){GL11.glClearColor(.2F,.3F,.4F,1);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT|GL11.GL_DEPTH_BUFFER_BIT);}
    private static void pixel(int r,int g,int b,String name){java.nio.ByteBuffer data=org.lwjgl.BufferUtils.createByteBuffer(4);GL11.glReadPixels(32,32,1,1,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,data);check(Math.abs((data.get(0)&255)-r)<=1&&Math.abs((data.get(1)&255)-g)<=1&&Math.abs((data.get(2)&255)-b)<=1,name+" got="+(data.get(0)&255)+","+(data.get(1)&255)+","+(data.get(2)&255));}
    private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
}
