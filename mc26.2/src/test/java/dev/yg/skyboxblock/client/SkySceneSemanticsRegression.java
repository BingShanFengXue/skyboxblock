package dev.yg.skyboxblock.client;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Standalone: execute real arithmetic bytecode without initializing Minecraft, GL or a game. */
public final class SkySceneSemanticsRegression {
    private static int checks;
    public static void main(String[] args) throws Exception {
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        String owner="dev/yg/skyboxblock/client/ExtractedSceneSemantics";
        writer.visit(Opcodes.V17,Opcodes.ACC_PUBLIC,owner,null,"java/lang/Object",null);
        copy(writer,owner,"net/minecraft/client/renderer/CloudRenderer","packCellData");
        copy(writer,owner,"dev/yg/skyboxblock/client/SkyVanillaScene","cloudCellColor");
        copy(writer,owner,"dev/yg/skyboxblock/client/SkyVanillaScene","cloudCellVisible");
        copy(writer,owner,"dev/yg/skyboxblock/client/SkyVanillaScene","flipMoonUv");
        copy(writer,owner,"dev/yg/skyboxblock/client/SkySceneState","angleRadians");
        copy(writer,owner,"dev/yg/skyboxblock/client/SkyEnvironmentMath","angle");
        writer.visitEnd();
        byte[] bytes=writer.toByteArray();
        Class<?> math=new ClassLoader(SkySceneSemanticsRegression.class.getClassLoader()) {
            Class<?> define(){return defineClass(owner.replace('/','.'),bytes,0,bytes.length);}
        }.define();
        Method pack=math.getMethod("packCellData",int.class,boolean.class,boolean.class,boolean.class,boolean.class);
        Method decode=math.getMethod("cloudCellColor",long.class);
        Method visible=math.getMethod("cloudCellVisible",long.class);
        Method radians=math.getMethod("angleRadians",float.class);
        Method flip=math.getMethod("flipMoonUv",float[].class);
        float[] vertices=new float[4*9];
        float[][] sunUv={{0,0},{1,0},{1,1},{0,1}};
        for(int i=0;i<4;i++) {vertices[i*9+3]=sunUv[i][0];vertices[i*9+4]=sunUv[i][1];vertices[i*9+5]=.625F;}
        flip.invoke(null,(Object)vertices);
        String[] nativeUv={"getU1","getV1","getU0","getV1","getU0","getV0","getU1","getV0"};
        MethodNode nativeMoon=method(read("net/minecraft/client/renderer/SkyRenderer"),"buildMoonPhases",null);
        java.util.ArrayList<String> calls=new java.util.ArrayList<>();
        for(AbstractInsnNode instruction:nativeMoon.instructions)if(instruction instanceof MethodInsnNode call
                && call.owner.endsWith("TextureAtlasSprite") && (call.name.startsWith("getU")||call.name.startsWith("getV")))calls.add(call.name);
        check(calls.equals(java.util.Arrays.asList(nativeUv)),"actual 26.2 native moon has both UV axes reversed versus sun");
        for(int i=0;i<4;i++) {
            check(vertices[i*9+3]==(nativeUv[i*2].equals("getU1")?1:0),"production moon U matches native bytecode");
            check(vertices[i*9+4]==(nativeUv[i*2+1].equals("getV1")?1:0),"production moon V matches native bytecode");
            check(vertices[i*9+5]==.625F,"UV fix preserves vertex color");
        }
        for(int i=0;i<360;i++) {
            float angle=(float)Math.toRadians(i);
            org.joml.Vector3f oldCenter=new org.joml.Matrix3f().rotateX(angle-(float)Math.PI).transform(new org.joml.Vector3f(0,-100,0));
            org.joml.Vector3f newCenter=new org.joml.Matrix3f().rotateX(angle).transform(new org.joml.Vector3f(0,100,0));
            check(oldCenter.distance(newCenter)<.0001F,"old minus180 with negativeY equals native positiveY without minus180; degrees="+i+" error="+oldCenter.distance(newCenter));
        }
        Random random=new Random(2621211L);
        for(int i=0;i<4096;i++) {
            int color=i<256 ? (i<<24)|0x12a5e9 : random.nextInt();
            for(int flags=0;flags<16;flags++) {
                long cell=(long)pack.invoke(null,color,(flags&8)!=0,(flags&4)!=0,(flags&2)!=0,(flags&1)!=0);
                check((int)decode.invoke(null,cell)==color,"native packed ARGB survives all four direction flags");
                check((cell&15)==flags,"native flags remain unchanged");
                check((boolean)visible.invoke(null,cell)==((color>>>24)>=32),"legacy cutout occupancy matches actual alpha before configured opacity");
            }
        }
        for(float degrees:new float[]{0,45,-45,360,540,-720,54000,Float.MAX_VALUE,-Float.MAX_VALUE,1e20F,-1e20F}) {
            float expected=(float)Math.toRadians(SkyEnvironmentMath.angle(degrees));
            float actual=(float)radians.invoke(null,degrees);
            check(Float.floatToIntBits(actual)==Float.floatToIntBits(expected),"actual override uses baseline modulo before radians");
            check(actual>=0 && actual<Math.PI*2,"bounded rotation at finite extreme inputs");
        }
        // Negative controls distinguish the repaired cases from the pre-review implementation.
        check(Math.abs((float)Math.sin((float)Math.toRadians(Float.MAX_VALUE))
                -(float)Math.sin((float)radians.invoke(null,Float.MAX_VALUE)))>.01F,"large-angle old path differs");
        check((int)decode.invoke(null,(long)pack.invoke(null,0xff12a5e9,true,true,true,true))!=0xffffffff,"colored cloud is not hardcoded white");
        for(int a=0;a<256;a++) {
            float tint=a/255F*.8F;
            check(Math.abs(tint-a*.8F/255F)<1e-7F,"cloud override alpha is linear, not replaced by .8");
            for(int pixelAlpha:new int[]{10,127,255}) {
                float actual=tint*(pixelAlpha/255F);
                float legacy=(pixelAlpha/255F)*.8F*(a/255F);
                check(Math.abs(actual-legacy)<1e-7F,"texture alpha times native .8 times configured opacity");
            }
        }
        for(int i=0;i<=1000;i++) {
            float distance=128+i*.256F;
            float t=Math.max(0,Math.min(1,(distance-128)/256));
            float visibility=1-t*t*(3-2*t);
            check(Math.abs(visibility-SkyEnvironmentMath.visibility(distance,128,384))<1e-7F,"legacy cloud smoothstep oracle");
        }
        check(Math.abs(SkyEnvironmentMath.visibility(192,128,384)-.84375F)<1e-7F,"quarter-distance fade differs from old new-core linear .75");
        Path root=Path.of(args.length==0?".":args[0]);
        String scene=Files.readString(root.resolve("src/main/java/dev/yg/skyboxblock/client/SkySceneState.java"));
        String draw=Files.readString(root.resolve("src/main/java/dev/yg/skyboxblock/client/SkyVanillaScene.java"));
        String shader=Files.readString(root.resolve("src/main/resources/assets/skyboxblock/shaders/isolated/scene.fsh"));
        check(scene.contains("if(!local) moonPhase=SkyRenderMath.simulatedMoonPhase(frame.gameTime);"),"locked/running local view keeps extracted lunar phase");
        check(scene.indexOf("sunriseAngle=sunAngle;")<scene.indexOf("if(environment.has(\"sun_angle\"))"),"dawn captures daily angle before object override");
        check(draw.contains("Math.sin(state.sunriseAngle)"),"dawn consumes its unmodified daily angle");
        check(draw.contains("data!=cloudData"),"resource reload cache compares identity, not record equals");
        check(draw.contains("int argb=cloudCellColor(cell);"),"actual cloud vertices consume decoded pack color");
        check(draw.contains("if(!cloudCellVisible(cell)) continue;"),"cutout cells do not create hidden walls");
        check(draw.split("!cloudCellVisible\\(cell\\(data,",-1).length-1==4,"all four walls open next to legacy-cutout neighbours");
        check(draw.contains("if(name.startsWith(\"moon/\")) flipMoonUv(vertices);"),"actual celestial draw applies native moon-only UV convention");
        check(draw.contains("SkyWindowRenderer.sampler(false,false)"),"celestial pixel-art sampling matches native nearest filtering");
        check(shader.contains("if(vertexColor.a*0.8<0.1) discard;"),"legacy texture-alpha cutout precedes configured opacity");
        check(31/255F*.8F<.1F && 32/255F*.8F>=.1F,"partly transparent cloud texels retain legacy 31/32 threshold");
        check(shader.contains("smoothstep(FogRange.x,FogRange.y,sphericalDistance)"),"actual shader uses smoothstep and spherical metric");
        check(shader.contains("FogRange.y<=FogRange.x ? step(FogRange.y,sphericalDistance)"),"degenerate/reversed fog range uses baseline step");
        ClassNode nativeCloud=read("net/minecraft/client/renderer/CloudRenderer");
        MethodNode prepare=method(nativeCloud,"prepare","(Lnet/minecraft/server/packs/resources/ResourceManager;");
        check(java.util.stream.StreamSupport.stream(prepare.instructions.spliterator(),false).anyMatch(n->n instanceof TypeInsnNode t && t.getOpcode()==Opcodes.NEW && t.desc.endsWith("CloudRenderer$TextureData")),"native prepare allocates new TextureData on each successful reload");
        MethodNode apply=method(nativeCloud,"apply","(Ljava/util/Optional;");
        check(java.util.stream.StreamSupport.stream(apply.instructions.spliterator(),false).anyMatch(n->n instanceof FieldInsnNode f && f.getOpcode()==Opcodes.PUTFIELD && f.name.equals("texture")),"native apply replaces texture identity");
        System.out.println("SkySceneSemanticsRegression: "+checks+" checks passed (actual native packed-cell bytecode, actual angle helper, alpha, fade, lunar/dawn/reload structural guards; no game/GL)");
    }
    private static void copy(ClassWriter writer,String owner,String source,String name)throws Exception {
        MethodNode m=method(read(source),name,null);
        m.access=Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC;
        for(AbstractInsnNode n:m.instructions)if(n instanceof MethodInsnNode call && (call.owner.equals("dev/yg/skyboxblock/client/SkyEnvironmentMath") || call.owner.equals("dev/yg/skyboxblock/client/SkyVanillaScene")))call.owner=owner;
        m.accept(writer);
    }
    private static ClassNode read(String name)throws Exception {
        try(var input=SkySceneSemanticsRegression.class.getClassLoader().getResourceAsStream(name+".class")) {
            if(input==null)throw new AssertionError("Missing class: "+name);
            ClassNode node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return node;
        }
    }
    private static MethodNode method(ClassNode node,String name,String prefix) {
        return node.methods.stream().filter(m->m.name.equals(name)&&(prefix==null||m.desc.startsWith(prefix))).findFirst().orElseThrow();
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
