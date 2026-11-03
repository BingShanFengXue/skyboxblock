package dev.yg.skyboxblock.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.objectweb.asm.*;

/** Standalone, Minecraft-free math + actual 26.2 class-file hook verification. No Gradle/game needed. */
public final class SkyWindowCoreRegression {
    private static int checks;
    public static void main(String[] args)throws Exception {
        for(boolean z01:new boolean[]{true,false})for(int i=0;i<100;i++) {
            float u=(i%10+.5F)/10,v=(i/10+.5F)/10;
            Matrix4f p=new Matrix4f().setPerspective((float)Math.toRadians(73),1.7F,2000,.05F,z01);
            Matrix4f rotation=new Matrix4f().rotateX(.18F).rotateY(-.43F);
            Matrix4f bob=new Matrix4f().translate(.13F,-.07F,.02F).rotateZ(.03F);
            Matrix4f pp=new Matrix4f(p).mul(bob),vv=new Matrix4f(bob).mul(rotation);
            Matrix4f inverseA=SkyWindowMath.rayInverse(pp,rotation,new Matrix4f());
            Matrix4f inverseB=SkyWindowMath.rayInverse(p,vv,new Matrix4f());
            Vector3f a=SkyWindowMath.ray(inverseA,u,v,z01,new Vector3f());
            Vector3f b=SkyWindowMath.ray(inverseB,u,v,z01,new Vector3f());
            check(a.distance(b)<2e-5F,"bob in projection or view must produce the same ray");
            Vector4f q0=inverseA.transform(new Vector4f(u*2-1,v*2-1,.8F,1));q0.div(q0.w);
            Vector4f q1=inverseA.transform(new Vector4f(u*2-1,v*2-1,.2F,1));q1.div(q1.w);
            Vector3f oracle=new Vector3f(q1.x-q0.x,q1.y-q0.y,q1.z-q0.z).normalize();
            check(a.distance(oracle)<2e-5F,"ray independent of selected face depth");
            Matrix4f raster=SkyWindowMath.rasterProjection(pp,.75F,.8F,.0004F,-.0007F,new Matrix4f());
            Vector4f clip=pp.transform(new Vector4f(2,1,-10,1)),shift=raster.transform(new Vector4f(2,1,-10,1));
            check(Math.abs(shift.x/shift.w-(clip.x/clip.w*.75F-.25F+.0004F))<1e-5F,"shader-side raster transform");
            check(Math.abs(shift.y/shift.w-(clip.y/clip.w*.8F-.2F-.0007F))<1e-5F,"shader-side raster transform Y");
        }
        check(Math.abs(SkyWindowMath.panorama(new Vector3f(1,0,0))[0]-.5F)<1e-6F,"mainline panorama orientation");
        float left=SkyWindowMath.panorama(new Vector3f(-1,0,-1e-6F).normalize())[0];
        float right=SkyWindowMath.panorama(new Vector3f(-1,0,1e-6F).normalize())[0];
        check(Math.min(Math.abs(left-right),1-Math.abs(left-right))<1e-5F,"periodic longitude seam");
        check(SkyWindowMath.panorama(new Vector3f(0,1,0))[1]==0,"north pole");
        check(SkyWindowMath.panorama(new Vector3f(0,-1,0))[1]==1,"south pole");
        hook("net/minecraft/client/renderer/LevelRenderer","lambda$addMainPass$0","net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame","executeSolid","()V",1);
        hook("net/minecraft/client/renderer/LevelRenderer","lambda$addMainPass$0","net/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame","executeTranslucent","()V",1);
        hook("net/minecraft/client/renderer/GameRenderer","renderLevel","net/minecraft/client/renderer/ProjectionMatrixBuffer","getBuffer","(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",1);
        final boolean[] reload={false};readClass("net/minecraft/client/renderer/texture/TextureManager",new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){if(name.equals("lambda$reload$2"))reload[0]=true;return null;}});
        check(reload[0],"26.2 native resource apply lambda exists");
        final int[] distance={-1};
        readClass("dev/yg/skyboxblock/client/SkyBlockEntityRenderer",new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if(!name.equals("getViewDistance")||!desc.equals("()I"))return null;
                return new MethodVisitor(Opcodes.ASM9){@Override public void visitIntInsn(int opcode,int operand){distance[0]=operand;}};
            }
        });
        check(distance[0]==256,"1.21.1 sky-window visibility distance is retained, not engine default 64");
        for(double camera:new double[]{-20000000.13,-1000000.03,-.2,.123456,1000000.999,20000000.6}) {
            int base=(int)Math.floor(camera);
            for(int offset=-31;offset<=31;offset++) {
                float edgeLeft=SkyRenderMath.relativeGridVertex(base+offset,1,camera);
                float edgeRight=SkyRenderMath.relativeGridVertex(base+offset+1,0,camera);
                check(Float.floatToIntBits(edgeLeft)==Float.floatToIntBits(edgeRight),"shared cube grid vertices have identical bits at large/fractional coordinates");
            }
        }
        check(Float.floatToIntBits((float)(-1-.2)+1)!=Float.floatToIntBits(SkyRenderMath.relativeGridVertex(-1,1,.2)),"negative control: per-block float pose addition rounds a shared edge twice");
        final int[] exactVertices={0};
        readClass("dev/yg/skyboxblock/client/SkyWindowRenderer",new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if(!name.equals("face"))return null;
                return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){if(owner.endsWith("SkyRenderMath")&&method.equals("relativeGridVertex"))exactVertices[0]++;}};
            }
        });
        check(exactVertices[0]==3,"production face collection uses original double-before-float XYZ grid conversion");
        System.out.println("SkyWindowCoreRegression: "+checks+" checks passed (inverse VP, bob, raster, panorama, exact 26.2 hooks)");
    }
    private static void hook(String owner,String method,String calledOwner,String called,String desc,int expected)throws Exception {
        int[] hits={0};readClass(owner,new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int access,String name,String d,String signature,String[] exceptions){if(!name.equals(method))return null;return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int opcode,String o,String n,String d,boolean iface){if(o.equals(calledOwner)&&n.equals(called)&&d.equals(desc))hits[0]++;}};}});
        check(hits[0]==expected,owner+"."+method+" exact injection count "+hits[0]);
    }
    private static void readClass(String name,ClassVisitor visitor)throws Exception {try(var in=SkyWindowCoreRegression.class.getClassLoader().getResourceAsStream(name+".class")){if(in==null)throw new AssertionError(name);new ClassReader(in).accept(visitor,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);}}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
