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
        final boolean[] distanceFromConfig={false};
        readClass("dev/yg/skyboxblock/client/SkyBlockEntityRenderer",new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if(!name.equals("getViewDistance")||!desc.equals("()I"))return null;
                return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){
                        if(owner.equals("dev/yg/skyboxblock/client/SkyboxConfig")&&method.equals("skyboxRenderDistance"))distanceFromConfig[0]=true;
                    }
                };
            }
        });
        check(distanceFromConfig[0],"sky-window visibility distance comes from the configurable local limit, not engine default 64");
        check(SkyboxConfig.DEFAULT_SKYBOX_RENDER_DISTANCE==256,"default sky-window distance stays 256 blocks");
        check(SkyboxConfig.MAX_SKYBOX_RENDER_DISTANCE==4096,"sky-window distance can be raised to 4096 blocks");
        check(SkyboxConfig.parseSkyboxRenderDistance("4096")==4096,"configured distance accepts the upper bound");
        check(SkyboxConfig.parseSkyboxRenderDistance("15")==256,"configured distance rejects values below the lower bound");
        check(SkyboxConfig.parseSkyboxRenderDistance("4097")==256,"configured distance rejects values above the upper bound");
        worldTargetValidation();
        lateTransparencySnapshotValidation();
        lateTransparencyHookOrder();
        suppressSupplementHook();
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
        checks += SkyBlockSpatialIndexRegression.run();
        System.out.println("SkyWindowCoreRegression: "+checks+" checks passed (inverse VP, bob, raster, panorama, exact 26.2 hooks, fail-soft framebuffer targets, partitioned fallback collection)");
    }
    private static void worldTargetValidation() {
        check(SkyWindowRenderer.worldTargetStatus(false,-1,11,12,1920,1080)
                        ==SkyWindowRenderer.WorldTargetStatus.READY_NATIVE,
                "without Iris, a valid native world target remains enabled");
        check(SkyWindowRenderer.worldTargetStatus(true,3,11,12,1920,1080)
                        ==SkyWindowRenderer.WorldTargetStatus.READY_IRIS,
                "valid Iris framebuffer and attachments are accepted");
        check(SkyWindowRenderer.worldTargetStatus(true,0,0,0,0,0)
                        ==SkyWindowRenderer.WorldTargetStatus.MISSING_FRAMEBUFFER,
                "missing Iris framebuffer is classified as fail-soft");
        check(SkyWindowRenderer.worldTargetStatus(true,3,0,12,1920,1080)
                        ==SkyWindowRenderer.WorldTargetStatus.MISSING_ATTACHMENT,
                "missing opaque color attachment is classified as fail-soft");
        check(SkyWindowRenderer.worldTargetStatus(true,3,11,0,1920,1080)
                        ==SkyWindowRenderer.WorldTargetStatus.MISSING_ATTACHMENT,
                "missing opaque depth attachment is classified as fail-soft");
        check(SkyWindowRenderer.worldTargetStatus(true,3,11,12,0,1080)
                        ==SkyWindowRenderer.WorldTargetStatus.INVALID_DIMENSIONS,
                "zero-sized opaque target is classified as fail-soft");
        check(SkyWindowRenderer.worldTargetStatus(false,-1,11,12,1920,-1)
                        ==SkyWindowRenderer.WorldTargetStatus.INVALID_DIMENSIONS,
                "negative native target dimensions are rejected");
    }
    private static void lateTransparencySnapshotValidation() {
        check(SkyWindowRenderer.lateTransparencySnapshotEligible(true,false,false,7,1920,1080,1920,1080),
                "native/DH completed root color with exact view extent may refresh the after-transparency sample");
        check(SkyWindowRenderer.lateTransparencySnapshotEligible(true,false,false,8,1280,720,1280,720),
                "Iris final-stage color with exact view extent passes the same eligibility contract");
        check(!SkyWindowRenderer.lateTransparencySnapshotEligible(false,false,false,7,1920,1080,1920,1080),
                "portal/non-root views never reuse a root transparency snapshot");
        check(!SkyWindowRenderer.lateTransparencySnapshotEligible(true,true,false,7,1920,1080,1920,1080),
                "clipped portal views are excluded from late root-target capture");
        check(!SkyWindowRenderer.lateTransparencySnapshotEligible(true,false,true,7,1920,1080,1920,1080),
                "shadow views are excluded from late transparency capture");
        check(!SkyWindowRenderer.lateTransparencySnapshotEligible(true,false,false,0,1920,1080,1920,1080),
                "missing color texture leaves the earlier proven sample untouched");
        check(!SkyWindowRenderer.lateTransparencySnapshotEligible(true,false,false,7,1280,720,1920,1080),
                "different target dimensions are never copied into the current view sample");
        check(!SkyWindowRenderer.lateTransparencySnapshotEligible(true,false,false,7,0,1080,1920,1080),
                "invalid source extent is rejected without changing world-depth provenance");
    }
    private static void lateTransparencyHookOrder() throws Exception {
        final int[] viewOrder={0,0};
        final int[] viewInstruction={0};
        readClass("dev/yg/skyboxblock/mixin/SkyLevelRenderMixin",new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if(!name.equals("skyboxblock$view"))return null;
                return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){
                        int instruction=++viewInstruction[0];
                        if(owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")&&method.equals("call"))viewOrder[0]=instruction;
                        if(owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer")&&method.equals("endLevel"))viewOrder[1]=instruction;
                    }
                };
            }
        });
        check(viewOrder[0]>0&&viewOrder[1]>viewOrder[0],
                "native endLevel late snapshot runs only after the wrapped full LevelRenderer framegraph returns");

        final boolean[] irisCaptureBeforeDepth={false},colorCopy={false},depthUntouched={true};
        readClass("dev/yg/skyboxblock/client/SkyWindowRenderer",new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if(name.equals("beforeIrisFinal"))return new MethodVisitor(Opcodes.ASM9){
                    int captureAt=-1,depthAt=-1,index;
                    @Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){
                        index++;
                        if(owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer")&&method.equals("captureLateTransparency"))captureAt=index;
                        if(owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer")&&method.equals("copyDepth"))depthAt=index;
                    }
                    @Override public void visitEnd(){irisCaptureBeforeDepth[0]=captureAt>0&&depthAt>captureAt;}
                };
                if(name.equals("captureLateTransparency"))return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){
                        if(owner.equals("org/lwjgl/opengl/GL11")&&method.equals("glCopyTexSubImage2D"))colorCopy[0]=true;
                    }
                    @Override public void visitFieldInsn(int op,String owner,String field,String d){
                        if(op==Opcodes.PUTFIELD&&owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer$Frame")
                                &&(field.equals("worldDepth")||field.equals("depthCopied")||field.equals("itrpOpaqueDepth")))depthUntouched[0]=false;
                    }
                };
                return null;
            }
        });
        check(irisCaptureBeforeDepth[0],"Iris final-stage snapshot is taken before the existing current-depth copy");
        check(colorCopy[0],"late snapshot copies color only");
        check(depthUntouched[0],"late snapshot does not alter world depth or material provenance");
    }
    private static void suppressSupplementHook()throws Exception {
        final boolean[] loadedIndex={false},liveOwnership={false},distanceConfig={false},collect={false},beforeDrawn={false};
        readClass("dev/yg/skyboxblock/client/SkyWindowRenderer",new ClassVisitor(Opcodes.ASM9){
            @Override public MethodVisitor visitMethod(int access,String name,String desc,String sig,String[] ex){
                if(name.equals("collectSkippedSkyBlocks")) return new MethodVisitor(Opcodes.ASM9){
                    @Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){
                        if(owner.equals("dev/yg/skyboxblock/SkyBlockEntity")&&method.equals("loadedNear"))loadedIndex[0]=true;
                        if(owner.equals("net/minecraft/client/multiplayer/ClientLevel")&&method.equals("getBlockEntity"))liveOwnership[0]=true;
                        if(owner.equals("dev/yg/skyboxblock/client/SkyboxConfig")&&method.equals("skyboxRenderDistance"))distanceConfig[0]=true;
                        if(owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer")&&method.equals("collect"))collect[0]=true;
                    }
                };
                if(name.equals("drawOpaque")) {
                    final boolean[] supplementCalled={false};
                    return new MethodVisitor(Opcodes.ASM9){
                        @Override public void visitMethodInsn(int op,String owner,String method,String d,boolean iface){
                            if(owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer")&&method.equals("collectSkippedSkyBlocks"))supplementCalled[0]=true;
                        }
                        @Override public void visitFieldInsn(int op,String owner,String field,String d){
                            if(op==Opcodes.PUTFIELD&&owner.equals("dev/yg/skyboxblock/client/SkyWindowRenderer$Frame")
                                    &&field.equals("drawn")&&supplementCalled[0])beforeDrawn[0]=true;
                        }
                    };
                }
                return null;
            }
        });
        check(loadedIndex[0],"supplement queries occupied sky-window chunk buckets near this camera instead of walking every loaded window");
        check(liveOwnership[0],"supplement rejects indexed entities not owned by the current client level");
        check(distanceConfig[0],"supplement applies the configurable local distance limit");
        check(collect[0]&&beforeDrawn[0],"supplement feeds the regular collector before the frame closes collection");
    }
    private static void hook(String owner,String method,String calledOwner,String called,String desc,int expected)throws Exception {
        int[] hits={0};readClass(owner,new ClassVisitor(Opcodes.ASM9){@Override public MethodVisitor visitMethod(int access,String name,String d,String signature,String[] exceptions){if(!name.equals(method))return null;return new MethodVisitor(Opcodes.ASM9){@Override public void visitMethodInsn(int opcode,String o,String n,String d,boolean iface){if(o.equals(calledOwner)&&n.equals(called)&&d.equals(desc))hits[0]++;}};}});
        check(hits[0]==expected,owner+"."+method+" exact injection count "+hits[0]);
    }
    private static void readClass(String name,ClassVisitor visitor)throws Exception {try(var in=SkyWindowCoreRegression.class.getClassLoader().getResourceAsStream(name+".class")){if(in==null)throw new AssertionError(name);new ClassReader(in).accept(visitor,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);}}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
